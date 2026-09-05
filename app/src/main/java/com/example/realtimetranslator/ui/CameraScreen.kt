package com.example.realtimetranslator.ui

import android.graphics.RectF
import android.util.Log
import android.util.Size as AndroidSize
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.realtimetranslator.R
import com.example.realtimetranslator.TranslatorViewModel
import com.example.realtimetranslator.model.Corner
import com.example.realtimetranslator.model.LensMode
import java.util.concurrent.Executors
import kotlin.math.min

private const val TAG = "CameraScreen"
/**
 * Recognition quality is bounded by how many pixels tall the text is, and at
 * 720p small label text lands around fifteen pixels - marginal for the
 * recognizer. Recognition only runs a few times a second, so the extra
 * conversion cost is affordable; tracking is unaffected because it downsamples
 * the luminance plane to a fixed size either way.
 */
private const val ANALYSIS_WIDTH = 1920
private const val ANALYSIS_HEIGHT = 1080

/** Starting selection, as a fraction of the shorter screen edge. */
private const val INITIAL_SELECTION_RATIO = 0.3f

@Composable
fun CameraScreen(
    modifier: Modifier = Modifier,
    viewModel: TranslatorViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Deliberately not read here: the overlay changes on every camera frame, and
    // reading it in this composable would recompose the whole screen that often.
    // TranslationOverlay reads it during its draw pass instead.
    val overlayState = viewModel.overlay.collectAsStateWithLifecycle()

    // Only the message itself matters up here, so a derived read keeps boxes
    // moving without recomposing the hint.
    val hint by remember(overlayState) {
        derivedStateOf {
            val current = overlayState.value
            when {
                !current.isModelReady -> R.string.status_preparing_model
                current.blocks.isEmpty() -> R.string.status_point_at_text
                else -> null
            }
        }
    }

    var viewSize by remember { mutableStateOf(Size.Zero) }
    var selection by remember { mutableStateOf<Rect?>(null) }
    var activeCorner by remember { mutableStateOf<Corner?>(null) }
    var mode by remember { mutableStateOf(LensMode.OFFLINE) }

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
    }

    // Owned by the composition rather than recreated on every recomposition, and
    // shut down when the screen goes away.
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null

        future.addListener({
            val cameraProvider = runCatching { future.get() }.getOrNull()
            if (cameraProvider == null) {
                Log.e(TAG, "Camera provider unavailable")
                return@addListener
            }
            provider = cameraProvider

            val resolution = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        AndroidSize(ANALYSIS_WIDTH, ANALYSIS_HEIGHT),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            val preview = CameraPreview.Builder()
                .setResolutionSelector(resolution)
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { useCase ->
                    useCase.setAnalyzer(analysisExecutor) { image -> viewModel.onFrame(image) }
                }

            runCatching {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            }.onFailure { Log.e(TAG, "Use case binding failed", it) }
        }, ContextCompat.getMainExecutor(context))

        onDispose { provider?.unbindAll() }
    }

    LaunchedEffect(viewSize) {
        if (viewSize.width <= 0f || viewSize.height <= 0f) return@LaunchedEffect
        if (selection == null) {
            val half = min(viewSize.width, viewSize.height) * INITIAL_SELECTION_RATIO
            selection = Rect(
                viewSize.width / 2f - half,
                viewSize.height / 2f - half,
                viewSize.width / 2f + half,
                viewSize.height / 2f + half
            )
        }
    }

    LaunchedEffect(viewSize, selection) {
        val current = selection ?: return@LaunchedEffect
        viewModel.updateViewport(
            viewSize.width,
            viewSize.height,
            RectF(current.left, current.top, current.right, current.bottom)
        )
    }

    Box(modifier = modifier.onGloballyPositioned { viewSize = it.size.toSize() }) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        TranslationOverlay(state = overlayState, modifier = Modifier.fillMaxSize())

        SelectionOverlay(
            selection = selection,
            activeCorner = activeCorner,
            onDragStart = { offset -> activeCorner = selection?.let { cornerNear(it, offset) } },
            onDrag = { delta ->
                selection = selection?.let { resizeSelection(it, activeCorner, delta, viewSize) }
            },
            onDragEnd = { activeCorner = null },
            modifier = Modifier.fillMaxSize()
        )

        Column(modifier = Modifier.fillMaxSize()) {
            ModePicker(
                mode = mode,
                onSelect = { mode = it },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Spacer(modifier = Modifier.weight(1f))

            hint?.let { message ->
                Text(
                    text = stringResource(message),
                    color = Color.White,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
    }
}
