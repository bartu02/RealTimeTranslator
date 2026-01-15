package com.example.realtimetranslator

import kotlinx.coroutines.*
import okhttp3.OkHttpClient

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput

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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
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
import kotlin.math.absoluteValue
import java.util.concurrent.Executors


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

fun isPotentiallyMeaningful(text: String): Boolean {
    val t = text.trim()
    if (t.length < 2) return false  // allow 2-letter words like "im", "an"

    val letters = t.count { it.isLetter() }
    val digits = t.count { it.isDigit() }

    // Mostly letters, allow some digits
    if (letters.toDouble() / t.length < 0.5) return false
    if (digits > letters) return false  // too many digits → likely junk

    // Must contain a vowel or ß or umlaut if > 5 chars
    if (t.length > 5 && !t.contains(Regex("[aeiouAEIOUäöüÄÖÜß]"))) return false

    // Reject repeated nonsense (aaaa, ||||, 1111)
    if (t.all { it == t[0] }) return false

    // Reject symbol-heavy noise
    if (t.contains(Regex("[~`@#%^*_+=<>|]"))) return false

    // Reject sequences of suspicious OCR characters
    if (t.contains(Regex("[Il1|]{3,}"))) return false

    return true
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



enum class Corner {
    TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
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
    var resizingCorner by remember { mutableStateOf<Corner?>(null) }




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
    var viewSize by remember { mutableStateOf(ComposeSize.Zero) }

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
    var selectionBox by remember {
        mutableStateOf(
            RectF(
                0f, 0f, 0f, 0f
            )
        )
    }

    var latestBitmap by remember { mutableStateOf<Bitmap?>(null) }

    var currentMode by remember { mutableStateOf(LensMode.OFFLINE) }


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
        if (selectionBox.width() == 0f && selectionBox.height() == 0f && viewSize.width > 0f && viewSize.height > 0f) {
            val boxSize = 300f
            selectionBox = RectF(
                viewSize.width / 2 - boxSize / 2,
                viewSize.height / 2 - boxSize / 2,
                viewSize.width / 2 + boxSize / 2,
                viewSize.height / 2 + boxSize / 2
            )
        }

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

//        if (currentMode == LensMode.ONLINE || currentMode == LensMode.OFFLINE) {
//            Text("+", modifier = Modifier.align(Alignment.Center), color = Color.White, fontSize = 32.sp)
//        }



        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val visualCornerRadius = 20f   // keeps the visual small
                            val hitboxCornerRadius = 80f    // bigger hit area for touch

                            resizingCorner = when {
                                (offset.x - selectionBox.left).absoluteValue < hitboxCornerRadius &&
                                        (offset.y - selectionBox.top).absoluteValue < hitboxCornerRadius -> Corner.TOP_LEFT
                                (offset.x - selectionBox.right).absoluteValue < hitboxCornerRadius &&
                                        (offset.y - selectionBox.top).absoluteValue < hitboxCornerRadius -> Corner.TOP_RIGHT
                                (offset.x - selectionBox.left).absoluteValue < hitboxCornerRadius &&
                                        (offset.y - selectionBox.bottom).absoluteValue < hitboxCornerRadius -> Corner.BOTTOM_LEFT
                                (offset.x - selectionBox.right).absoluteValue < hitboxCornerRadius &&
                                        (offset.y - selectionBox.bottom).absoluteValue < hitboxCornerRadius -> Corner.BOTTOM_RIGHT
                                else -> null
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()

                            val dx = dragAmount.x
                            val dy = dragAmount.y
                            selectionBox = when (resizingCorner) {

                                Corner.TOP_LEFT -> RectF(
                                    selectionBox.left + dx,
                                    selectionBox.top + dy,
                                    selectionBox.right,
                                    selectionBox.bottom
                                )

                                Corner.TOP_RIGHT -> RectF(
                                    selectionBox.left,
                                    selectionBox.top + dy,
                                    selectionBox.right + dx,
                                    selectionBox.bottom
                                )

                                Corner.BOTTOM_LEFT -> RectF(
                                    selectionBox.left + dx,
                                    selectionBox.top,
                                    selectionBox.right,
                                    selectionBox.bottom + dy
                                )

                                Corner.BOTTOM_RIGHT -> RectF(
                                    selectionBox.left,
                                    selectionBox.top,
                                    selectionBox.right + dx,
                                    selectionBox.bottom + dy
                                )

                                null -> RectF(
                                    selectionBox.left + dx,
                                    selectionBox.top + dy,
                                    selectionBox.right + dx,
                                    selectionBox.bottom + dy
                                )
                            }
                        },
                        onDragEnd = { resizingCorner = null }
                    )
                }
        ) {

            drawRect(
                color = Color.White.copy(alpha = 0.3f),
                topLeft = Offset(selectionBox.left, selectionBox.top),
                size = ComposeSize(selectionBox.width(), selectionBox.height()),
                style = Stroke(width = 3f)
            )

            val handleRadius = 12f
            val handleColor = Color.White
            val highlightColor = Color.Yellow.copy(alpha = 0.7f)

            // Draw handles
            fun drawHandle(x: Float, y: Float, corner: Corner) {
                val color = if (resizingCorner == corner) highlightColor else handleColor
                drawCircle(color, handleRadius, Offset(x, y))
            }

            drawHandle(selectionBox.left, selectionBox.top, Corner.TOP_LEFT)
            drawHandle(selectionBox.right, selectionBox.top, Corner.TOP_RIGHT)
            drawHandle(selectionBox.left, selectionBox.bottom, Corner.BOTTOM_LEFT)
            drawHandle(selectionBox.right, selectionBox.bottom, Corner.BOTTOM_RIGHT)



            if ((currentMode == LensMode.OFFLINE || currentMode == LensMode.ONLINE)
                && translatedBlocks.isNotEmpty() && latestBitmap != null
            ) {

                drawContext.canvas.nativeCanvas.apply {
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 32f
                        isAntiAlias = true
                        textAlign = android.graphics.Paint.Align.CENTER
                        setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
                    }
                    drawText(
                        "${selectionBox.width().toInt()} × ${selectionBox.height().toInt()} px",
                        selectionBox.centerX(),
                        selectionBox.top - 16f, // slightly above the rectangle
                        paint
                    )
                }

                translatedBlocks.forEach { (blockData, translated) ->
                    val transformedRect = transformRect(blockData.box, blockData.sourceImageWidth, blockData.sourceImageHeight)

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

        val analysisExecutor = Executors.newSingleThreadExecutor()


        // --- Camera Logic ---
        LaunchedEffect(Unit) {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = CameraXPreview.Builder().setTargetResolution(Size(1280, 720)).build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                val imageAnalyzer = ImageAnalysis.Builder().setTargetResolution(Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()

                imageAnalyzer.setAnalyzer(analysisExecutor) { imageProxy ->

                val MIN_TRANSLATE_INTERVAL = 1500L
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastAnalyzedTimestamp < MIN_TRANSLATE_INTERVAL) {
                        imageProxy.close()
                        return@setAnalyzer
                    }

                    val mediaImage = imageProxy.image
                    if (mediaImage == null) {
                        imageProxy.close()
                        return@setAnalyzer
                    }

                    val rotationDegrees = imageProxy.imageInfo.rotationDegrees

                    // Convert ImageProxy → Bitmap → rotate
                    val bitmap = imageProxy.toBitmap()?.rotate(rotationDegrees.toFloat())
                    if (bitmap == null) {
                        imageProxy.close()
                        return@setAnalyzer
                    }

                    latestBitmap = bitmap

                    val imageWidth = bitmap.width
                    val imageHeight = bitmap.height

                    // NEW OCR INPUT: bitmap-based (more stable)
                    //val normalizedBitmap = ImagePreprocess.normalizeForOcr(bitmap)
                    val inputImage = InputImage.fromBitmap(bitmap, 0)


                    recognizer.process(inputImage)
                        .addOnSuccessListener { visionText ->

                            if (visionText.textBlocks.isEmpty()) {
                                translatedBlocks = emptyMap()
                                stableOcrText = ""
                                singleTranslatedText = "Point at text to translate"
                                return@addOnSuccessListener
                            }

                            // Extract OCR blocks
                            val blocks = visionText.textBlocks.mapNotNull { block ->
                                block.boundingBox?.let { box ->
                                    val expanded = RectF(box).apply { inset(-8f, -8f) }
                                    TextBlockData(expanded, block.text, imageWidth, imageHeight)
                                }
                            }

                            //  Merge nearby horizontal blocks
                            val mergedBlocks = mutableListOf<TextBlockData>()
                            for (block in blocks) {
                                val last = mergedBlocks.lastOrNull()
                                if (last != null && kotlin.math.abs(last.box.top - block.box.top) < 20f) {
                                    val mergedText = last.text + " " + block.text
                                    val mergedRect = RectF(
                                        minOf(last.box.left, block.box.left),
                                        minOf(last.box.top, block.box.top),
                                        maxOf(last.box.right, block.box.right),
                                        maxOf(last.box.bottom, block.box.bottom)
                                    )
                                    mergedBlocks[mergedBlocks.lastIndex] =
                                        last.copy(text = mergedText, box = mergedRect)
                                } else {
                                    mergedBlocks.add(block)
                                }
                            }

                            //  Selection-based OCR
                            val selectedBlocks = mergedBlocks.filter { block ->
                                val transformed = transformRect(
                                    block.box,
                                    block.sourceImageWidth,
                                    block.sourceImageHeight
                                )
                                selectionBox.contains(transformed.centerX(), transformed.centerY())
                            }

                            if (selectedBlocks.isEmpty()) return@addOnSuccessListener

                            //  Stability check
                            val currentText = selectedBlocks.joinToString(" ") { it.text }
                            val normalized = normalizeText(currentText)
                            val now = System.currentTimeMillis()

                            if (lastOcrText.isEmpty()) {
                                lastOcrText = normalized
                                lastStableTime = now
                                return@addOnSuccessListener
                            }

                            val sim = similarity(normalized, lastOcrText)
                            if (sim < 0.5) {
                                lastOcrText = normalized
                                lastStableTime = now
                                return@addOnSuccessListener
                            }

                            if (now - lastStableTime < 100) return@addOnSuccessListener

                            if (normalized == stableOcrText) return@addOnSuccessListener
                            stableOcrText = normalized

                            // ---- Translation ----
                            scope.launch {
                                val results = mutableListOf<Pair<TextBlockData, String>>()

                                for (block in selectedBlocks) {
                                    val cleaned = block.text.replace("\n", " ").trim()
                                    if (!isPotentiallyMeaningful(cleaned)) continue

                                    val translated = stableTranslate(cleaned, germanToEnglishTranslator)
                                    results.add(block.copy(text = cleaned) to translated)
                                }


                                withContext(Dispatchers.Main) {
                                    translatedBlocks = results.toMap()
                                }
                            }
                        }
                        .addOnFailureListener {
                            Log.e("OCR", "Recognition failed", it)
                        }
                        .addOnCompleteListener {
                            imageProxy.close()
                        }

                    lastAnalyzedTimestamp = currentTime
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