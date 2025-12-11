package com.example.realtimetranslator

import kotlinx.coroutines.*
import okhttp3.OkHttpClient

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
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
                var showSplash by remember { mutableStateOf(true) }

                if (showSplash) {
                    SplashScreen {
                        showSplash = false
                    }
                } else {
                    Scaffold(modifier = Modifier.fillMaxSize()) {
                        CameraPermissionWrapper(modifier = Modifier.padding(it))
                    }
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

suspend fun translateBasedOnMode(
    text: String,
    mode: LensMode,
    offlineTranslator: com.google.mlkit.nl.translate.Translator
): String = withContext(Dispatchers.IO) {
    if (!isPotentiallyMeaningful(text)) return@withContext text

    return@withContext try {
        if (mode == LensMode.ONLINE) {
            translateOnline(text)  // your suspend function
        } else {
            // ML Kit offline translator: suspend until result
            Tasks.await(offlineTranslator.translate(text))
        }
    } catch (e: Exception) {
        text
    }
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

enum class LensMode { ONLINE, OFFLINE }

@Composable
fun ModePicker(
    modifier: Modifier = Modifier,
    mode: LensMode,
    onSelect: (LensMode) -> Unit
) {
    val modes = listOf(LensMode.ONLINE, LensMode.OFFLINE)

    Row(
        modifier = modifier
            .padding(12.dp)
            .background(Color.Black.copy(alpha = 0.35f), shape = RoundedCornerShape(24.dp))
            .padding(8.dp)
            .horizontalScroll(rememberScrollState())
    ) {
        modes.forEach { m ->
            val selected = m == mode
            val isOnlineDisabled = m == LensMode.ONLINE

            Box(
                modifier = Modifier
                    .padding(4.dp)
                    .let { base ->
                        if (isOnlineDisabled)
                            base.graphicsLayer(alpha = 0.4f)
                        else
                            base
                    }
                    .clickable(enabled = !isOnlineDisabled) {
                        onSelect(m)
                    }
                    .background(
                        if (selected) Color.White.copy(alpha = 0.12f) else Color.Transparent,
                        RoundedCornerShape(16.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    text = when (m) {
                        LensMode.ONLINE -> "Online (soon)"
                        LensMode.OFFLINE -> "Offline"
                    },
                    color = Color.White,
                    fontSize = 15.sp,
                )
            }
        }
    }
}


private fun calculateTextSizeForBox(
    paint: Paint,
    lines: List<String>,
    targetWidth: Float,
    targetHeight: Float
): Float {
    // Upper and lower limits
    val minSize = 14f
    val maxSize = 80f

    var low = minSize
    var high = maxSize
    var result = minSize

    // Binary search — fast & accurate
    repeat(15) {
        val mid = (low + high) / 2f
        paint.textSize = mid

        val textHeight = mid * 1.2f * lines.size
        val maxLineWidth = lines.maxOf { paint.measureText(it) }

        if (maxLineWidth <= targetWidth && textHeight <= targetHeight) {
            result = mid
            low = mid
        } else {
            high = mid
        }
    }

    return result
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


// Online translation placeholder
private val httpClient = OkHttpClient()

suspend fun translateOnline(text: String): String = withContext(Dispatchers.IO) {
    val apiKey = "DEEPL_API_KEY" // not available due to it's not free
    try {
        val encodedText = java.net.URLEncoder.encode(text, "UTF-8")
        val url = "https://api-free.deepl.com/v2/translate?text=$encodedText&source_lang=DE&target_lang=EN"

        val request = okhttp3.Request.Builder()
            .url(url)
            .addHeader("Authorization", "DeepL-Auth-Key $apiKey")
            .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body ?: return@withContext text

        responseBody.use { body ->
            val jsonString = body.string()
            val jsonObj = org.json.JSONObject(jsonString)
            val translatedText = jsonObj.getJSONArray("translations")
                .getJSONObject(0)
                .getString("text")
            return@withContext translatedText
        }

    } catch (e: Exception) {
        e.printStackTrace()
        return@withContext text
    }
}

@Composable
fun SplashScreen(onFinish: () -> Unit) {
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(0.8f) }

    LaunchedEffect(Unit) {
        // Animate alpha and scale together
        launch {
            alpha.animateTo(1f, tween(1000))
        }
        launch {
            scale.animateTo(1f, tween(1000, easing = EaseOutBack))
        }
        delay(1200) // Keep splash visible for a short moment
        onFinish()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF2196F3), Color(0xFF21CBF3))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Real Time Translator",
            color = Color.White,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.graphicsLayer {
                // Use the .value of the Animatable, do NOT assign the Animatable itself
                scaleX = scale.value
                scaleY = scale.value
                this.alpha = alpha.value // note the 'this.alpha', not 'alpha ='
            }
        )
    }
}







@OptIn(ExperimentalGetImage::class)
@Composable
fun CameraPreviewView(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    val scope = rememberCoroutineScope()
    val translationCache = remember { mutableMapOf<String, String>() }


    suspend fun stableTranslate(text: String, translator: com.google.mlkit.nl.translate.Translator): String =
        withContext(Dispatchers.IO) {
            val cleaned = text.trim().replace(Regex("[\\n]+"), " ")

            translationCache[cleaned]?.let { return@withContext it }

            val result = try {
                // This will block until translation is done
                Tasks.await(translator.translate(cleaned))
            } catch (e: Exception) {
                cleaned
            }

            translationCache[cleaned] = result
            result
        }

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

    var currentMode by remember { mutableStateOf(LensMode.OFFLINE) }

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

    // --- Stability Vars ---
    var lastOcrText by remember { mutableStateOf("") }
    var stableOcrText by remember { mutableStateOf("") }
    var lastStableTime by remember { mutableStateOf(0L) }
    var cachedStableMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

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

            if(currentMode != LensMode.OFFLINE) {
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

        if (currentMode == LensMode.ONLINE || currentMode == LensMode.OFFLINE) {
        Text("+", modifier = Modifier.align(Alignment.Center), color = Color.White, fontSize = 32.sp)
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            if ((currentMode == LensMode.OFFLINE || currentMode == LensMode.ONLINE)
                && translatedBlocks.isNotEmpty() && latestBitmap != null
            ) {

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
                            latestBitmap?.let { bmp ->
                                val blurredRegion = blurRegion(context, bmp, blockData.box, 25f)
                                if (blurredRegion != null) {
                                    val dst = android.graphics.RectF(
                                        transformedRect.left,
                                        transformedRect.top,
                                        transformedRect.right,
                                        transformedRect.bottom
                                    )
                                    drawContext.canvas.nativeCanvas.drawBitmap(blurredRegion, null, dst, null)
                                }
                            }

                        }
                    }

                    // --- Text sizing considering width AND height ---
                    val paintText = Paint().apply {
                        color = android.graphics.Color.WHITE
                        textAlign = Paint.Align.LEFT
                        isAntiAlias = true
                        isFakeBoldText = true
                        setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
                    }

                    val maxWidth = transformedRect.width() - 16f // padding left/right
                    val maxHeight = transformedRect.height() - 8f // padding top/bottom
                    val words = translated.split(Regex("\\s+"))

                    var textSize = maxHeight / 2f
                    paintText.textSize = textSize
                    var finalLines: List<String> = emptyList()

                    while (textSize >= 14f) {
                        val lines = mutableListOf<String>()
                        var currentLine = StringBuilder()
                        words.forEach { word ->
                            val testLine = if (currentLine.isEmpty()) word else "${currentLine} $word"
                            if (paintText.measureText(testLine) <= maxWidth) {
                                currentLine = StringBuilder(testLine)
                            } else {
                                lines.add(currentLine.toString())
                                currentLine = StringBuilder(word)
                            }
                        }
                        if (currentLine.isNotEmpty()) lines.add(currentLine.toString())

                        // check if fits vertically
                        if (lines.size * textSize * 1.2f <= maxHeight) {
                            finalLines = lines
                            break
                        }
                        textSize -= 1f
                        paintText.textSize = textSize
                        finalLines = lines
                    }

                    // truncate if still too tall
                    val maxLines = (maxHeight / (textSize * 1.2f)).toInt().coerceAtLeast(1)
                    if (finalLines.size > maxLines) {
                        finalLines = finalLines.take(maxLines - 1) + "..."
                    }

                    var y = transformedRect.top + textSize
                    finalLines.forEach { line ->
                        drawContext.canvas.nativeCanvas.drawText(line, transformedRect.left + 8f, y, paintText)
                        y += textSize * 1.2f
                    }
                }
            }
        }
        fun normalizeText(t: String): String {
            return t.trim()
                .lowercase()
                .replace(Regex("\\s+"), " ")
        }

        fun levenshtein(a: String, b: String): Int {
            val dp = Array(a.length + 1) { IntArray(b.length + 1) }
            for (i in 0..a.length) dp[i][0] = i
            for (j in 0..b.length) dp[0][j] = j

            for (i in 1..a.length) {
                for (j in 1..b.length) {
                    dp[i][j] = minOf(
                        dp[i - 1][j] + 1,
                        dp[i][j - 1] + 1,
                        dp[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                    )
                }
            }

            return dp[a.length][b.length]
        }

        fun similarity(a: String, b: String): Double {
            val maxLen = maxOf(a.length, b.length).toDouble()
            if (maxLen == 0.0) return 1.0
            return 1.0 - (levenshtein(a, b) / maxLen)
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

                                val allBlocksData = visionText.textBlocks.mapNotNull { block ->
                                    block.boundingBox?.let { TextBlockData(RectF(it), block.text, imageWidth, imageHeight) }
                                }

                                val mergedBlocks = mutableListOf<TextBlockData>()
                                allBlocksData.forEach { block ->
                                    if (mergedBlocks.isEmpty()) mergedBlocks.add(block)
                                    else {
                                        val last = mergedBlocks.last()
                                        if (Math.abs(last.box.top - block.box.top) < 20f) {
                                            val mergedText = last.text + " " + block.text
                                            val mergedRect = RectF(
                                                minOf(last.box.left, block.box.left),
                                                minOf(last.box.top, block.box.top),
                                                maxOf(last.box.right, block.box.right),
                                                maxOf(last.box.bottom, block.box.bottom)
                                            )
                                            mergedBlocks[mergedBlocks.lastIndex] = last.copy(text = mergedText, box = mergedRect)
                                        } else mergedBlocks.add(block)
                                    }
                                }

                                // Launch translation coroutine for both online & offline
                                if (mergedBlocks.isNotEmpty()) {

                                    // Join all merged block text into one single string to compare stability
                                    val currentMergedText = mergedBlocks.joinToString(" ") { it.text }
                                    val normalized = normalizeText(currentMergedText)
                                    val now = System.currentTimeMillis()

                                    // First frame of text ever → accept immediately
                                    if (lastOcrText.isEmpty()) {
                                        lastOcrText = normalized
                                        lastStableTime = now
                                    }

                                    // Compare stability
                                    val sim = similarity(normalized, lastOcrText)

                                    // If text changed too much, reset timer
                                    if (sim < 0.70) {
                                        lastOcrText = normalized
                                        lastStableTime = now
                                        return@addOnSuccessListener
                                    }

                                    // Require at least 200ms of stable text
                                    if (now - lastStableTime < 200) {
                                        return@addOnSuccessListener
                                    }

                                    // Only translate when OCR text is NEW and STABLE
                                    if (normalized != stableOcrText) {
                                        stableOcrText = normalized

                                        scope.launch {
                                            val results = mergedBlocks.map { data ->
                                                async {
                                                    val cleaned = data.text.trim().replace(Regex("[\\n]+"), " ")

                                                    // Use cache to avoid re-translating same text blocks
                                                    val cached = cachedStableMap[cleaned]
                                                    val translated = if (cached != null) cached else {
                                                        val t = stableTranslate(cleaned, germanToEnglishTranslator)
                                                        cachedStableMap = cachedStableMap + (cleaned to t)
                                                        t
                                                    }

                                                    data to translated
                                                }
                                            }.awaitAll()

                                            withContext(Dispatchers.Main) {
                                                translatedBlocks = results.toMap()
                                            }
                                        }
                                    }

                                    return@addOnSuccessListener
                                }


                                else {
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
                                            scope.launch {
                                                val translated = if (currentMode == LensMode.ONLINE) {
                                                    translateOnline(text) // call your online translator
                                                } else {
                                                    translateBasedOnMode(text, LensMode.OFFLINE, germanToEnglishTranslator)
                                                }
                                                val prev = translationCache[text]
                                                if (prev != translated) {
                                                    translationCache[text] = translated
                                                    withContext(Dispatchers.Main) {
                                                        singleTranslatedText = translated
                                                    }
                                                }

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
