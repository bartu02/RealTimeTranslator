package com.example.realtimetranslator

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.speech.tts.TextToSpeech
import android.util.Log
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview as CameraXPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.realtimetranslator.ui.theme.RealTimeTranslatorTheme
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.pow


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RealTimeTranslatorTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) {
                    CameraPermissionWrapper(modifier = Modifier.padding(it))
                }
            }
        }
    }
}

fun isPotentiallyMeaningful(t: String): Boolean {
    if (t.length < 3) return false
    if (!t.contains(Regex("[aeiouAEIOUäöüÄÖÜ]"))) return false
    if (t.matches(Regex("\\d+"))) return false
    if (t.contains(Regex("[~`@#%^*_+=<>]"))) return false
    if (t.contains(" ") || t.length >= 6) return true
    return false
}

@Composable
fun CameraPermissionWrapper(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasPermission = granted
        }
    )

    LaunchedEffect(key1 = true) {
        if (!hasPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    if (hasPermission) {
        CameraPreviewView(modifier = modifier)
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(text = "Please grant camera permission to use the translator.")
        }
    }
}

data class TextBlockData(val box: RectF, val text: String, val sourceImageWidth: Int, val sourceImageHeight: Int)

enum class LensMode { INSTANT, HIGHLIGHT, TAP, COPY, PRONUNCIATION }

@Composable
fun ModePicker(modifier: Modifier = Modifier, mode: LensMode, onSelect: (LensMode) -> Unit) {
    val modes = listOf(LensMode.INSTANT, LensMode.HIGHLIGHT, LensMode.TAP, LensMode.COPY, LensMode.PRONUNCIATION)
    Row(
        modifier = modifier
            .padding(12.dp)
            .background(Color.Black.copy(alpha = 0.35f), shape = RoundedCornerShape(24.dp))
            .padding(8.dp)
            .horizontalScroll(rememberScrollState())
    ) {
        modes.forEach { m ->
            val selected = m == mode
            Box(modifier = Modifier
                .padding(4.dp)
                .clickable { onSelect(m) }
                .background(if (selected) Color.White.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = when (m) {
                        LensMode.INSTANT -> "Instant"
                        LensMode.HIGHLIGHT -> "Highlight"
                        LensMode.TAP -> "Tap"
                        LensMode.COPY -> "Copy"
                        LensMode.PRONUNCIATION -> "Pronounce"
                    },
                    color = Color.White,
                    fontSize = 14.sp
                )
            }
        }
    }
}

private fun calculateTextSize(paint: Paint, lines: List<String>, maxWidth: Float, maxHeight: Float): Float {
    var textSize = maxHeight / lines.size * 0.8f
    paint.textSize = textSize

    lines.forEach { line ->
        while (paint.measureText(line) > maxWidth && textSize > 6f) {
            textSize -= 1f
            paint.textSize = textSize
        }
    }
    return textSize
}

private fun blurBitmap(context: Context, bitmap: Bitmap, radius: Float = 15f): Bitmap {
    if (radius == 0f) return bitmap
    val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    val rs = RenderScript.create(context)
    val input = Allocation.createFromBitmap(rs, bitmap)
    val outputAlloc = Allocation.createTyped(rs, input.type)
    val script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
    script.setRadius(radius.coerceIn(0f, 25f))
    script.setInput(input)
    script.forEach(outputAlloc)
    outputAlloc.copyTo(output)
    rs.destroy()
    return output
}

fun blurRegion(
    context: Context,
    src: Bitmap,
    rect: RectF,
    radius: Float = 15f
): Bitmap {
    val left = rect.left.toInt().coerceIn(0, src.width - 1)
    val top = rect.top.toInt().coerceIn(0, src.height - 1)
    val width = rect.width().toInt().coerceAtLeast(1)
        .coerceAtMost(src.width - left)
    val height = rect.height().toInt().coerceAtLeast(1)
        .coerceAtMost(src.height - top)

    val cropped = Bitmap.createBitmap(src, left, top, width, height)
    return blurBitmap(context, cropped, radius)
}

private fun Bitmap.rotate(degrees: Float): Bitmap {
    val matrix = Matrix()
    matrix.postRotate(degrees)
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

private fun ImageProxy.toBitmap(): Bitmap? {
    val yBuffer = planes[0].buffer
    val vuBuffer = planes[2].buffer

    val ySize = yBuffer.remaining()
    val vuSize = vuBuffer.remaining()

    val nv21 = ByteArray(ySize + vuSize)

    yBuffer.get(nv21, 0, ySize)
    vuBuffer.get(nv21, ySize, vuSize)

    val yuvImage = YuvImage(nv21, ImageFormat.NV21, this.width, this.height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 100, out)
    val imageBytes = out.toByteArray()
    return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
}

@OptIn(ExperimentalGetImage::class)
@Composable
fun CameraPreviewView(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    val options = remember {
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.GERMAN)
            .setTargetLanguage(TranslateLanguage.ENGLISH)
            .build()
    }
    val germanToEnglishTranslator = remember { Translation.getClient(options) }
    var modelReady by remember { mutableStateOf(false) }

    var translatedBlocks by remember { mutableStateOf<Map<TextBlockData, String>>(emptyMap()) }
    var singleTranslatedText by remember { mutableStateOf("Point at text to translate") }
    var viewSize by remember { mutableStateOf(ComposeSize.Zero) }
    var latestBitmap by remember { mutableStateOf<Bitmap?>(null) }

    var currentMode by remember { mutableStateOf(LensMode.INSTANT) }

    val tts = remember {
        var ttsInstance: TextToSpeech? = null
        val listener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsInstance?.language = Locale.ENGLISH
            }
        }
        ttsInstance = TextToSpeech(context, listener)
        ttsInstance
    }
    val clipboardManager = LocalClipboardManager.current
    DisposableEffect(Unit) {
        onDispose { tts?.shutdown() }
    }

    var lastAnalyzedTimestamp by remember { mutableStateOf(0L) }

    DisposableEffect(Unit) {
        val conditions = DownloadConditions.Builder().build()
        germanToEnglishTranslator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener { modelReady = true; Log.d("Translation", "Model downloaded.") }
            .addOnFailureListener { e -> Log.e("Translation", "Model download failed: $e") }
        onDispose { germanToEnglishTranslator.close() }
    }

    Box(modifier = modifier.onGloballyPositioned { viewSize = it.size.toSize() }) {
        val previewView = remember {
            PreviewView(context).apply { this.scaleType = PreviewView.ScaleType.FIT_CENTER }
        }
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // UI Overlay
        Column(modifier = Modifier.fillMaxSize()) {
            ModePicker(modifier = Modifier.align(Alignment.CenterHorizontally), mode = currentMode) { selected ->
                currentMode = selected
            }

            Spacer(modifier = Modifier.weight(1f))

            if(currentMode != LensMode.HIGHLIGHT) {
                 Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = singleTranslatedText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.7f))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        color = Color.White,
                        fontSize = 20.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 24.sp
                    )
                }
            }
        }

        if (currentMode != LensMode.HIGHLIGHT) {
             Text("+", modifier = Modifier.align(Alignment.Center), color = Color.White, fontSize = 32.sp)
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            if (currentMode == LensMode.HIGHLIGHT && translatedBlocks.isNotEmpty() && latestBitmap != null) {

                fun transformRect(box: RectF, imageWidth: Int, imageHeight: Int): RectF {
                    val viewAspectRatio = viewSize.width / viewSize.height
                    val imageAspectRatio = imageWidth.toFloat() / imageHeight.toFloat()
                    val scale: Float
                    var offsetX = 0f
                    var offsetY = 0f
                    if (viewAspectRatio > imageAspectRatio) {
                        scale = viewSize.height / imageHeight
                        offsetX = (viewSize.width - imageWidth * scale) / 2
                    } else {
                        scale = viewSize.width / imageWidth
                        offsetY = (viewSize.height - imageHeight * scale) / 2
                    }
                    return RectF(
                        offsetX + box.left * scale,
                        offsetY + box.top * scale,
                        offsetX + box.right * scale,
                        offsetY + box.bottom * scale
                    )
                }

                translatedBlocks.forEach { (blockData, translated) ->
                    val transformedRect = transformRect(blockData.box, blockData.sourceImageWidth, blockData.sourceImageHeight)

                    latestBitmap?.let { bmp ->
                        val blurredRegion = blurRegion(context, bmp, blockData.box, 25f)

                        if (blurredRegion != null) {
                            val srcSize = androidx.compose.ui.unit.IntSize(blurredRegion.width, blurredRegion.height)
                            val dstOffset = androidx.compose.ui.unit.IntOffset(
                                transformedRect.left.toInt().coerceAtLeast(0),
                                transformedRect.top.toInt().coerceAtLeast(0)
                            )
                            val dstSize = androidx.compose.ui.unit.IntSize(
                                transformedRect.width().toInt().coerceAtLeast(1),
                                transformedRect.height().toInt().coerceAtLeast(1)
                            )

                            drawImage(
                                image = blurredRegion.asImageBitmap(),
                                srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
                                srcSize = srcSize,
                                dstOffset = dstOffset,
                                dstSize = dstSize,
                                alpha = 1f,
                                filterQuality = androidx.compose.ui.graphics.FilterQuality.High
                            )
                        }
                    }



                    val lines = translated.split("\n")
                    val paintText = Paint().apply {
                        color = android.graphics.Color.WHITE
                        textAlign = Paint.Align.LEFT
                        isAntiAlias = true
                        setShadowLayer(8f, 0f, 0f, android.graphics.Color.BLACK)
                    }
                    paintText.textSize = calculateTextSize(paintText, lines, transformedRect.width(), transformedRect.height())

                    var y = transformedRect.top + paintText.textSize
                    lines.forEach { line ->
                        drawContext.canvas.nativeCanvas.drawText(line, transformedRect.left + 8f, y, paintText)
                        y += paintText.textSize * 1.2f
                    }
                }
            }
        }

        // --- Camera Logic ---
        LaunchedEffect(Unit) {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = CameraXPreview.Builder().setTargetResolution(Size(640, 480)).build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                val imageAnalyzer = ImageAnalysis.Builder().setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()

                imageAnalyzer.setAnalyzer(ContextCompat.getMainExecutor(context)) { imageProxy ->
                    val MIN_TRANSLATE_INTERVAL = 2500L
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastAnalyzedTimestamp < MIN_TRANSLATE_INTERVAL) {
                        imageProxy.close()
                        return@setAnalyzer
                    }

                    val mediaImage = imageProxy.image
                    if (mediaImage != null) {
                        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                        val imageWidth = if (rotationDegrees == 90 || rotationDegrees == 270) mediaImage.height else mediaImage.width
                        val imageHeight = if (rotationDegrees == 90 || rotationDegrees == 270) mediaImage.width else mediaImage.height

                        latestBitmap = imageProxy.toBitmap()?.rotate(rotationDegrees.toFloat())
                        val image = InputImage.fromMediaImage(mediaImage, rotationDegrees)

                        recognizer.process(image)
                            .addOnSuccessListener { visionText ->
                                if (visionText.textBlocks.isEmpty()) {
                                    translatedBlocks = emptyMap()
                                    singleTranslatedText = "Point at text to translate"
                                    return@addOnSuccessListener
                                }

                                if (currentMode == LensMode.HIGHLIGHT) {
                                    val allBlocksData = visionText.textBlocks.mapNotNull { block ->
                                        block.boundingBox?.let { TextBlockData(RectF(it), block.text, imageWidth, imageHeight) }
                                    }

                                    val tasks = allBlocksData.mapNotNull { data ->
                                        if (isPotentiallyMeaningful(data.text) && modelReady) {
                                            germanToEnglishTranslator.translate(data.text).continueWith { Pair(data, it.result ?: "") }
                                        } else null
                                    }

                                    if (tasks.isNotEmpty()) {
                                        Tasks.whenAllSuccess<Pair<TextBlockData, String>>(tasks).addOnSuccessListener { results ->
                                            translatedBlocks = results.toMap()
                                        }
                                    }

                                } else {
                                    val imageCenterX = imageWidth / 2f
                                    val imageCenterY = imageHeight / 2f
                                    val foundBlock = visionText.textBlocks.minByOrNull { block ->
                                        val blockCenterY = block.boundingBox?.centerY()?.toFloat() ?: 0f
                                        val blockCenterX = block.boundingBox?.centerX()?.toFloat() ?: 0f
                                        (blockCenterX - imageCenterX).pow(2) + (blockCenterY - imageCenterY).pow(2)
                                    }

                                    if (foundBlock != null) {
                                        val text = foundBlock.text
                                        if (isPotentiallyMeaningful(text)) {
                                            if (modelReady) {
                                                germanToEnglishTranslator.translate(text)
                                                    .addOnSuccessListener { translated -> singleTranslatedText = translated }
                                                    .addOnFailureListener { singleTranslatedText = "Translation failed." }
                                            }
                                        } else {
                                            singleTranslatedText = "..."
                                        }
                                    } else {
                                        singleTranslatedText = "Point at text to translate"
                                    }
                                }
                            }
                            .addOnFailureListener { e -> Log.e("TextRecognition", "Recognition failed: $e") }
                            .addOnCompleteListener { imageProxy.close() }
                        lastAnalyzedTimestamp = currentTime
                    } else {
                        imageProxy.close()
                    }
                }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalyzer)
                } catch (exc: Exception) {
                    Log.e("CameraX", "Use case binding failed: $exc")
                }
            }, ContextCompat.getMainExecutor(context))
        }
    }
}
