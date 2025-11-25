package com.example.realtimetranslator

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview as CameraXPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.realtimetranslator.ui.theme.RealTimeTranslatorTheme
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.pow
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            RealTimeTranslatorTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    CameraPermissionWrapper(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }
        }
    }
}

fun isMeaningfulText(text: String): Boolean {
    val t = text.trim()

    // Reject extremely short text
    if (t.length < 4) return false

    // Reject text with few letters (OCR garbage)
    val letters = t.count { it.isLetter() }
    if (letters < 3) return false

    // Reject if too many non-letter characters
    val ratio = letters.toDouble() / t.length
    if (ratio < 0.6) return false

    // Reject text with no vowels (most random OCR junk)
    if (!t.contains(Regex("[aeiouAEIOUäöüÄÖÜ]"))) return false

    // Reject isolated numbers or product codes
    if (t.matches(Regex("\\d+"))) return false

    // Reject special character noise
    if (t.contains(Regex("[~`@#%^*_+=<>]"))) return false

    // Accept text containing at least one space or long words
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

    var highlightedBlock by remember { mutableStateOf<TextBlockData?>(null) }
    var translatedText by remember { mutableStateOf("Point at text to translate") }
    var viewSize by remember { mutableStateOf(ComposeSize.Zero) }
    
    var isFrozen by remember { mutableStateOf(false) }
    var frozenTranslatedText by remember { mutableStateOf("") }
    var lastAnalyzedTimestamp by remember { mutableStateOf(0L) }

    DisposableEffect(Unit) {
        val conditions = DownloadConditions.Builder().build()
        germanToEnglishTranslator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener { modelReady = true; Log.d("Translation", "Model downloaded.") }
            .addOnFailureListener { e -> Log.e("Translation", "Model download failed.", e) }
        onDispose { germanToEnglishTranslator.close() }
    }

    Box(modifier = modifier
        .onGloballyPositioned { viewSize = it.size.toSize() }
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
            if (highlightedBlock != null || isFrozen) {
                isFrozen = !isFrozen
                if (isFrozen) {
                    frozenTranslatedText = translatedText
                }
            }
        }
    ) {
        val previewView = remember {
            PreviewView(context).apply { this.scaleType = PreviewView.ScaleType.FIT_CENTER }
        }
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        
        // Visible crosshair
        if (!isFrozen) {
             Text("+", modifier = Modifier.align(Alignment.Center), color = Color.White, fontSize = 32.sp)
        }

        // Bounding Box Canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (!isFrozen) {
                highlightedBlock?.let { data ->
                    val imageWidth = data.sourceImageWidth
                    val imageHeight = data.sourceImageHeight

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

                    val transformedRect = RectF().apply {
                        left = data.box.left * scale + offsetX
                        right = data.box.right * scale + offsetX
                        top = data.box.top * scale + offsetY
                        bottom = data.box.bottom * scale + offsetY
                    }

                    drawRect(
                        color = Color.Blue.copy(alpha = 0.4f),
                        topLeft = Offset(transformedRect.left, transformedRect.top),
                        size = ComposeSize(transformedRect.width(), transformedRect.height()),
                        style = Stroke(width = 4f)
                    )
                }
            }
        }

        if (isFrozen) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.8f))
                    .padding(32.dp) // Increased padding
                    .verticalScroll(rememberScrollState()), // Make the column scrollable
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = frozenTranslatedText,
                    color = Color.White, 
                    fontSize = 22.sp, // More readable font size
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(32.dp))
                Text(
                    text = "Tap anywhere to resume", 
                    color = Color.White.copy(alpha = 0.7f), 
                    fontSize = 18.sp
                )
            }
        } else {
            // Live Translation Panel
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = translatedText,
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

        LaunchedEffect(Unit) { // Runs only ONCE
            val cameraProvider = ProcessCameraProvider.getInstance(context).get()
            val preview = CameraXPreview.Builder().setTargetResolution(Size(640, 480)).build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            val imageAnalyzer = ImageAnalysis.Builder().setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()

            imageAnalyzer.setAnalyzer(ContextCompat.getMainExecutor(context)) { imageProxy ->
                if (isFrozen) {
                    imageProxy.close()
                    return@setAnalyzer
                }
                
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastAnalyzedTimestamp < 500) { // Faster analysis
                    imageProxy.close()
                    return@setAnalyzer
                }

                val mediaImage = imageProxy.image
                if (mediaImage != null) {
                    val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                    val imageWidth = if (rotationDegrees == 90 || rotationDegrees == 270) mediaImage.height else mediaImage.width
                    val imageHeight = if (rotationDegrees == 90 || rotationDegrees == 270) mediaImage.width else mediaImage.height
                    val image = InputImage.fromMediaImage(mediaImage, rotationDegrees)

                    recognizer.process(image)
                        .addOnSuccessListener { visionText ->
                            val imageCenterX = imageWidth / 2f
                            val imageCenterY = imageHeight / 2f

                            val foundBlock = visionText.textBlocks.minByOrNull { block ->
                                val blockCenterY = block.boundingBox?.centerY()?.toFloat() ?: 0f
                                val blockCenterX = block.boundingBox?.centerX()?.toFloat() ?: 0f
                                // Calculate squared distance to avoid sqrt
                                (blockCenterX - imageCenterX).pow(2) + (blockCenterY - imageCenterY).pow(2)
                            }

                            if (foundBlock != null) {
                                // Only update if the text block has changed
                                if (foundBlock.text != highlightedBlock?.text) {
                                    val newBlockData = TextBlockData(RectF(foundBlock.boundingBox!!), foundBlock.text, imageWidth, imageHeight)
                                    highlightedBlock = newBlockData

                                    // --- Meaningful Text Filter ---
                                    val isMeaningful = isMeaningfulText(newBlockData.text)


                                    if (isMeaningful) {
                                        if (modelReady) {
                                            germanToEnglishTranslator.translate(newBlockData.text)
                                                .addOnSuccessListener { translated -> translatedText = translated }
                                                .addOnFailureListener { translatedText = "Translation failed." }
                                        } else {
                                            translatedText = "Translator not ready."
                                        }
                                    } else {
                                        translatedText = "..."
                                    }
                                }
                            } else {
                                highlightedBlock = null
                                translatedText = "Point at text to translate"
                            }
                        }
                        .addOnFailureListener { e -> Log.e("TextRecognition", "Recognition failed", e) }
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
                Log.e("CameraX", "Use case binding failed", exc)
            }
        }
    }
}