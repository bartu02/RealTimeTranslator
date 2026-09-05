package com.example.realtimetranslator

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.realtimetranslator.camera.FrameConverter
import com.example.realtimetranslator.camera.uprightBitmap
import com.example.realtimetranslator.camera.uprightHeight
import com.example.realtimetranslator.camera.uprightWidth
import com.example.realtimetranslator.core.TextHeuristics
import com.example.realtimetranslator.core.ViewportMapper
import com.example.realtimetranslator.core.awaitResult
import com.example.realtimetranslator.model.OverlayBlock
import com.example.realtimetranslator.model.OverlayLine
import com.example.realtimetranslator.model.OverlayState
import com.example.realtimetranslator.model.RecognizedBlock
import com.example.realtimetranslator.ocr.BlockExtractor
import com.example.realtimetranslator.ocr.BlockMatcher
import com.example.realtimetranslator.render.StyleEstimator
import com.example.realtimetranslator.tracking.RegionTracker
import com.example.realtimetranslator.tracking.Transform
import com.example.realtimetranslator.translate.TranslationRepository
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the whole camera-to-overlay pipeline.
 *
 * Two ideas shape it. Recognition rate and overlay rate are separate: every
 * frame is tracked, which is cheap and keeps boxes glued to the scene, while
 * only every [RECOGNITION_INTERVAL_MS] does a frame go through recognition and
 * translation, which is expensive.
 *
 * And a recognizer pass amends the overlay rather than replacing it. Readings
 * are matched against the blocks already on screen, so text that has not changed
 * keeps the translation and the colours it already had. Rebuilding the list
 * every pass is what made the overlay visibly rewrite itself several times a
 * second even when nothing had moved.
 */
class TranslatorViewModel : ViewModel() {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val translations = TranslationRepository()
    private val tracker = RegionTracker()
    private val frames = FrameConverter()

    private val _overlay = MutableStateFlow(OverlayState())
    val overlay: StateFlow<OverlayState> = _overlay.asStateFlow()

    /** Published by the UI, read by the camera thread. */
    @Volatile
    private var viewport: Viewport? = null

    private val recognitionInFlight = AtomicBoolean(false)
    private val nextBlockId = AtomicLong(1)

    /**
     * Motion accumulated since the frame currently being recognized was captured,
     * so results can be nudged forward to where the text is now rather than
     * snapping back to where it was when that frame was read.
     */
    private val motionSinceCapture = AtomicReference(Transform.IDENTITY)

    @Volatile
    private var lastRecognitionAt = 0L

    /**
     * Readings seen exactly once so far.
     *
     * Text has to be read twice in a row before it reaches the screen. While the
     * camera moves the recognizer produces a great deal of one-off garbage, and
     * requiring a second opinion costs a genuine reading only one extra pass.
     */
    @Volatile
    private var pending: List<RecognizedBlock> = emptyList()

    init {
        viewModelScope.launch {
            val ready = translations.prepare()
            _overlay.update { it.copy(isModelReady = ready) }
            if (!ready) Log.w(TAG, "Offline translation model is unavailable")
        }
    }

    /** Called by the UI whenever the preview is measured or the selection moves. */
    fun updateViewport(width: Float, height: Float, selection: RectF) {
        viewport = Viewport(width, height, RectF(selection))
    }

    /** Runs on the single camera analysis thread. */
    fun onFrame(image: ImageProxy) {
        try {
            val imageWidth = image.uprightWidth
            val imageHeight = image.uprightHeight
            if (imageWidth <= 0 || imageHeight <= 0) return

            trackMotion(image)
            maybeRecognize(image, imageWidth, imageHeight)
        } catch (throwable: Throwable) {
            Log.e(TAG, "Frame analysis failed", throwable)
        } finally {
            image.close()
        }
    }

    /**
     * Moves the existing overlay to follow the scene. Cheap enough to run on
     * every frame: it only touches the luminance plane, at low resolution.
     */
    private fun trackMotion(image: ImageProxy) {
        if (!TranslatorApp.openCvAvailable) return

        val hasWork = _overlay.value.blocks.isNotEmpty() || recognitionInFlight.get()
        if (!hasWork) {
            // Nothing to move and nothing in flight, so drop the reference frame
            // and let the next pass start clean rather than comparing against a
            // frame from before the camera was pointed somewhere else.
            tracker.reset()
            return
        }

        // The converter owns this Mat and reuses it, so it is not released here.
        val frame = frames.grayFrame(image, TRACKING_LONG_SIDE) ?: return
        val transform = tracker.track(frame.mat)?.rescale(frame.scaleFromUpright) ?: return

        if (recognitionInFlight.get()) {
            motionSinceCapture.updateAndGet { accumulated -> accumulated.then(transform) }
        }

        _overlay.update { state ->
            if (state.blocks.isEmpty()) {
                state
            } else {
                state.copy(
                    blocks = state.blocks.map { block ->
                        block.copy(
                            box = transform.map(block.box),
                            area = transform.map(block.area),
                            lines = block.lines.map { it.copy(box = transform.map(it.box)) }
                        )
                    }
                )
            }
        }
    }

    private fun maybeRecognize(image: ImageProxy, imageWidth: Int, imageHeight: Int) {
        if (recognitionInFlight.get()) return
        if (SystemClock.uptimeMillis() - lastRecognitionAt < RECOGNITION_INTERVAL_MS) return

        val currentViewport = viewport ?: return
        val bitmap = image.uprightBitmap() ?: return

        lastRecognitionAt = SystemClock.uptimeMillis()
        motionSinceCapture.set(Transform.IDENTITY)
        recognitionInFlight.set(true)

        viewModelScope.launch(Dispatchers.Default) {
            try {
                recognizeAndTranslate(bitmap, currentViewport, imageWidth, imageHeight)
            } catch (throwable: Throwable) {
                Log.e(TAG, "Recognition pass failed", throwable)
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
                recognitionInFlight.set(false)
            }
        }
    }

    private suspend fun recognizeAndTranslate(
        frame: Bitmap,
        viewport: Viewport,
        imageWidth: Int,
        imageHeight: Int
    ) {
        val mapper = ViewportMapper(viewport.width, viewport.height, imageWidth, imageHeight)
        val selection = mapper.viewToImage(viewport.selection)

        // Recognise a margin beyond the selection, then keep only the blocks
        // centred inside it. Cropping exactly to the selection sliced words in
        // half at its edges, and half a word translates badly: "Naturliches"
        // arrived at the translator as "irliches".
        val region = RectF(selection).apply {
            inset(-width() * CROP_MARGIN, -height() * CROP_MARGIN)
        }
        val crop = cropTo(frame, region) ?: return

        val readings = try {
            val result = recognizer.process(InputImage.fromBitmap(crop.bitmap, 0)).awaitResult()
            val extracted = BlockExtractor.extract(result, crop.left, crop.top)
            val meaningful = extracted.filter { TextHeuristics.isPotentiallyMeaningful(it.text) }
            val selected = meaningful.filter {
                selection.contains(it.box.centerX(), it.box.centerY())
            }
            if (DIAGNOSTICS) {
                Log.d(
                    TAG,
                    "read=${extracted.size} meaningful=${meaningful.size} " +
                        "inSelection=${selected.size} image=${imageWidth}x$imageHeight " +
                        "selection=$selection " +
                        "firstBox=${meaningful.firstOrNull()?.box}"
                )
            }
            selected
        } finally {
            // cropTo hands back the source itself when the region covers the
            // whole frame, and that one is still needed for colour sampling.
            if (crop.bitmap !== frame && !crop.bitmap.isRecycled) crop.bitmap.recycle()
        }

        val catchUp = motionSinceCapture.getAndSet(Transform.IDENTITY)
        val existing = _overlay.value.blocks

        val awaitingConfirmation = pending
        val claimed = HashSet<Long>()
        val unchanged = ArrayList<OverlayBlock>(readings.size)
        val changed = ArrayList<RecognizedBlock>()
        val reusableIds = ArrayList<Long?>()
        val stillPending = ArrayList<RecognizedBlock>()

        for (reading in readings) {
            val match = BlockMatcher.bestMatch(existing.filter { it.id !in claimed }, reading)
            if (match != null) claimed += match.id

            val confirmed = confirms(awaitingConfirmation, reading)

            when {
                match != null && BlockMatcher.readsTheSame(match.sourceText, reading.text) -> {
                    // Same words in the same place: keep the translation, and
                    // refresh only what this frame can say about geometry and
                    // colour.
                    unchanged += refresh(match, reading, frame, catchUp)
                }

                confirmed -> {
                    // A second pass agrees, so this is real text rather than a
                    // misread. It reuses the matched block's identity when there
                    // is one, so the overlay updates in place.
                    changed += reading
                    reusableIds += match?.id
                }

                match != null -> {
                    // Something is there, but it reads differently and only once
                    // so far. Keep showing the translation that is already
                    // correct and wait for the next pass to agree.
                    unchanged += refresh(match, reading, frame, catchUp)
                    stillPending += reading
                }

                else -> {
                    // First sighting. While the camera moves the recognizer
                    // produces a lot of one-off garbage, and showing it the
                    // moment it appears is what made the overlay noisy.
                    stillPending += reading
                }
            }
        }

        pending = stillPending

        // Only text that actually changed reaches the translator, and the cache
        // absorbs most of what does.
        val translated = translations.translateAll(changed.map { it.text })

        val refreshed = changed.mapIndexed { index, reading ->
            val styled = styleLines(frame, reading, catchUp)
            OverlayBlock(
                id = reusableIds[index] ?: nextBlockId.getAndIncrement(),
                box = catchUp.map(reading.box),
                area = catchUp.map(reading.area),
                lines = styled.lines,
                sourceText = reading.text,
                translatedText = translated[reading.text] ?: reading.text,
                ink = styled.ink,
                missCount = 0
            )
        }

        // Blocks this pass failed to find are kept for a few more passes. The
        // recognizer drops a block for a frame or two constantly, and removing
        // them immediately makes the overlay blink.
        val missing = existing
            .asSequence()
            .filter { it.id !in claimed }
            .map { it.copy(missCount = it.missCount + 1) }
            .filter { it.missCount <= MAX_MISSED_PASSES }
            .toList()

        if (DIAGNOSTICS) {
            Log.d(
                TAG,
                "publish kept=${unchanged.size} new=${refreshed.size} " +
                    "missing=${missing.size} pending=${stillPending.size}"
            )
        }

        _overlay.update {
            it.copy(
                blocks = unchanged + refreshed + missing,
                imageWidth = imageWidth,
                imageHeight = imageHeight
            )
        }
    }

    /** Whether an earlier pass saw the same words in the same place. */
    private fun confirms(awaiting: List<RecognizedBlock>, reading: RecognizedBlock): Boolean {
        val candidate = BlockMatcher.bestPending(awaiting, reading) ?: return false
        return BlockMatcher.readsTheSame(candidate.text, reading.text)
    }

    /** Keeps a block's translation and identity, taking everything else from this frame. */
    private fun refresh(
        existing: OverlayBlock,
        reading: RecognizedBlock,
        frame: Bitmap,
        catchUp: Transform
    ): OverlayBlock {
        val styled = styleLines(frame, reading, catchUp)
        return existing.copy(
            box = catchUp.map(reading.box),
            area = catchUp.map(reading.area),
            lines = styled.lines,
            ink = styled.ink,
            missCount = 0
        )
    }

    /**
     * Samples the surface colour behind every line of a reading, and takes the
     * ink from the largest line as the colour to write the translation in.
     *
     * Colours are read where the text was in the captured frame, but the boxes
     * are moved forward by [catchUp] to where the scene has drifted since.
     */
    private fun styleLines(
        frame: Bitmap,
        reading: RecognizedBlock,
        catchUp: Transform
    ): StyledLines {
        val lines = ArrayList<OverlayLine>(reading.lines.size)
        var ink = Color.BLACK
        var largest = -1f

        for (line in reading.lines) {
            val style = StyleEstimator.estimate(frame, line.bounds())
            if (line.area > largest) {
                largest = line.area
                ink = style.ink
            }
            lines += OverlayLine(box = catchUp.map(line), paper = style.paper)
        }

        return StyledLines(lines, ink)
    }

    override fun onCleared() {
        recognizer.close()
        translations.close()
        tracker.release()
        frames.release()
        super.onCleared()
    }

    private fun cropTo(source: Bitmap, region: RectF): Crop? {
        val left = region.left.toInt().coerceIn(0, source.width - 1)
        val top = region.top.toInt().coerceIn(0, source.height - 1)
        val right = region.right.toInt().coerceIn(left + 1, source.width)
        val bottom = region.bottom.toInt().coerceIn(top + 1, source.height)

        val width = right - left
        val height = bottom - top
        if (width < MIN_CROP_SIZE || height < MIN_CROP_SIZE) return null

        return try {
            Crop(
                Bitmap.createBitmap(source, left, top, width, height),
                left.toFloat(),
                top.toFloat()
            )
        } catch (throwable: Throwable) {
            Log.e(TAG, "Could not crop to the selected region", throwable)
            null
        }
    }

    private class Crop(val bitmap: Bitmap, val left: Float, val top: Float)

    private class StyledLines(val lines: List<OverlayLine>, val ink: Int)

    private data class Viewport(val width: Float, val height: Float, val selection: RectF)

    private companion object {
        const val TAG = "TranslatorViewModel"

        /** Temporary pipeline tracing while the overlay is being diagnosed. */
        const val DIAGNOSTICS = true

        /** How often a frame is sent through recognition and translation. */
        const val RECOGNITION_INTERVAL_MS = 250L

        /** Long side of the grayscale image used for motion tracking. */
        const val TRACKING_LONG_SIDE = 320

        /** How many consecutive misses a block survives before it is dropped. */
        const val MAX_MISSED_PASSES = 4

        /**
         * How far recognition reaches beyond the selection, as a fraction of it,
         * so words straddling the edge are read whole rather than sliced.
         */
        const val CROP_MARGIN = 0.3f

        const val MIN_CROP_SIZE = 16
    }
}
