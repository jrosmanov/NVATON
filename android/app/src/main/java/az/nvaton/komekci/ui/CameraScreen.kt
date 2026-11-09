package az.nvaton.komekci.ui

import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import az.nvaton.komekci.core.tr
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import az.nvaton.komekci.Status
import az.nvaton.komekci.UiState
import az.nvaton.komekci.VisionUi
import az.nvaton.komekci.net.VisionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

/**
 * Live camera with spoken questions: "Bu nədir?", "Son istifadə tarixi keçib?". Every question takes a
 * fresh photo; earlier answers on this screen go along so follow-ups make sense. Tap to focus, pinch
 * to zoom, and a torch for dim rooms (when the phone has one).
 */
@Composable
fun CameraScreen(
    state: UiState,
    onRead: () -> Unit,
    onAsk: () -> Unit,
    onCapture: (ByteArray) -> Unit,
    onCaptureFailed: () -> Unit,
    onBack: () -> Unit,
    onRepeat: () -> Unit,
    onRetake: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA) }

    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
            isTapToFocusEnabled = true
            isPinchToZoomEnabled = true
        }
    }
    DisposableEffect(hasPermission, lifecycleOwner) {
        if (hasPermission) controller.bindToLifecycle(lifecycleOwner)
        onDispose { controller.unbind() } // release the camera when leaving
    }
    var hasTorch by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    LaunchedEffect(hasPermission) {
        if (hasPermission) hasTorch = awaitCamera(controller) && controller.cameraInfo?.hasFlashUnit() == true
    }

    var capturing by remember { mutableStateOf(false) }
    fun capture() {
        if (!hasPermission || capturing) return
        capturing = true
        scope.launch {
            awaitCamera(controller) // a spoken question can arrive while the camera is still starting
            takePhoto(context, controller) { bitmap, rotation ->
                if (bitmap == null) {
                    capturing = false
                    onCaptureFailed()
                } else {
                    scope.launch {
                        val jpeg = withContext(Dispatchers.Default) { ImageUtils.toJpeg(bitmap, rotation) }
                        capturing = false
                        onCapture(jpeg)
                    }
                }
            }
        }
    }
    // The view model asks for a photo by bumping captureRequest; requests from before this screen opened don't count.
    val requestsBefore = remember { state.captureRequest }
    LaunchedEffect(state.captureRequest) { if (state.captureRequest > requestsBefore) capture() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        this.controller = controller
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    tr("Kamera icazəsi lazımdır.", "Camera permission is needed."),
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                PrimaryButton(tr("İcazə ver", "Allow"), { permissionLauncher.launch(Manifest.permission.CAMERA) }, icon = AppIcons.PhotoCamera)
            }
        }

        Row(Modifier.fillMaxWidth().safeDrawingPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton(AppIcons.ArrowBack, tr("Geri", "Back"), onBack, container = Scrim, content = Color.White)
            Spacer(Modifier.weight(1f))
            if (hasTorch) {
                CircleIconButton(
                    icon = if (torchOn) AppIcons.FlashlightOffFilled else AppIcons.FlashlightOnFilled,
                    label = if (torchOn) tr("İşığı söndür", "Light off") else tr("İşığı yandır", "Light on"),
                    onClick = {
                        torchOn = !torchOn
                        controller.enableTorch(torchOn)
                    },
                    container = if (torchOn) Color.White else Scrim,
                    content = if (torchOn) Color.Black else Color.White,
                )
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val listening = state.status == Status.LISTENING
            val stoppable = listening || state.speaking || state.status == Status.SPEAKING
            val vision = state.vision
            val bubble = when {
                listening -> state.partial.ifBlank { tr("Dinləyirəm…", "Listening…") }
                state.status == Status.THINKING && vision == VisionUi.Idle -> state.partial.ifBlank { tr("Eşitdim…", "Got it…") }
                vision == VisionUi.Idle -> state.cameraQuestion?.let { "«$it»" } ?: tr("Kameranı tutun və soruşun: «Bu nədir?»", "Point the camera and ask: «What is this?»")
                vision == VisionUi.Loading -> state.cameraQuestion?.let { "«$it»" }
                else -> null
            }
            if (bubble != null) {
                Text(
                    bubble,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .background(Scrim, MaterialTheme.shapes.large)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                )
                Spacer(Modifier.height(20.dp))
            }
            when (vision) {
                VisionUi.Idle -> Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    RoundAction(
                        icon = if (stoppable) AppIcons.StopFilled else AppIcons.MicFilled,
                        label = if (stoppable) tr("Dayan", "Stop") else tr("Soruş", "Ask"),
                        onClick = onAsk,
                        size = 96.dp,
                        container = if (stoppable) MaterialTheme.colorScheme.error else Color.White,
                        content = if (stoppable) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.primary,
                        labelColor = Color.White,
                        listening = listening,
                    )
                    RoundAction(
                        icon = if (capturing) AppIcons.HourglassTop else AppIcons.PhotoCameraFilled,
                        label = tr("Oxu", "Read"),
                        onClick = { if (!capturing) onRead() },
                        size = 112.dp,
                        container = MaterialTheme.colorScheme.primary,
                        content = MaterialTheme.colorScheme.onPrimary,
                        labelColor = Color.White,
                    )
                }
                VisionUi.Loading -> AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 4.dp)
                        Spacer(Modifier.width(18.dp))
                        Text(tr("Baxıram…", "Looking…"), style = MaterialTheme.typography.headlineSmall)
                    }
                }
                is VisionUi.Done -> ResultCard(vision.result, state.cameraQuestion, listening, stoppable && !listening, onAsk, onRepeat, onRetake)
                is VisionUi.Error -> AppCard {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(AppIcons.ErrorFilled, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(vision.message, style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        TonalButton(tr("Soruş", "Ask"), onAsk, Modifier.weight(1f), icon = AppIcons.Mic)
                        PrimaryButton(tr("Yenidən", "Retake"), onRetake, Modifier.weight(1f), icon = AppIcons.AddAPhoto)
                    }
                }
            }
        }
    }
}

/** Dark glass over the camera picture, so white text stays readable on anything. */
private val Scrim = Color.Black.copy(alpha = 0.55f)

@Composable
private fun ResultCard(
    result: VisionResult,
    question: String?,
    listening: Boolean,
    speaking: Boolean,
    onAsk: () -> Unit,
    onRepeat: () -> Unit,
    onRetake: () -> Unit,
) {
    AppCard(padding = 22.dp) {
        if (!question.isNullOrBlank()) {
            Text("«$question»", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
        }
        if (result.title.isNotBlank()) Text(result.title, style = MaterialTheme.typography.headlineSmall)
        if (!result.readable) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr("Yazı aydın oxunmur", "The text isn't clear enough"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(10.dp))
        Column(Modifier.heightIn(max = 250.dp).verticalScroll(rememberScrollState())) {
            Text(result.speech, style = MaterialTheme.typography.bodyLarge)
            if (result.details.isNotEmpty()) Spacer(Modifier.height(12.dp))
            result.details.forEachIndexed { i, (label, value) ->
                if (i > 0) RowDivider()
                Column(Modifier.padding(vertical = 10.dp)) {
                    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        PrimaryButton(
            text = when {
                listening -> tr("Dinləyirəm… dayan", "Listening… stop")
                speaking -> tr("Oxumağı dayandır", "Stop reading")
                else -> tr("Başqa sual ver", "Ask another question")
            },
            onClick = onAsk,
            icon = if (listening || speaking) AppIcons.StopFilled else AppIcons.MicFilled,
            container = if (listening || speaking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            height = 68.dp,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            TonalButton(tr("Bir də de", "Again"), onRepeat, Modifier.weight(1f), icon = AppIcons.Replay)
            TonalButton(tr("Yeni şəkil", "New photo"), onRetake, Modifier.weight(1f), icon = AppIcons.AddAPhoto)
        }
    }
}

/** True once the camera is open (up to 4 s); the controller has no camera info before that. */
private suspend fun awaitCamera(controller: LifecycleCameraController): Boolean =
    withTimeoutOrNull(4_000) {
        while (controller.cameraInfo == null) delay(100)
        true
    } ?: false

private fun takePhoto(context: Context, controller: LifecycleCameraController, onResult: (Bitmap?, Int) -> Unit) {
    controller.takePicture(
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rotation = image.imageInfo.rotationDegrees
                val bitmap = runCatching { image.toBitmap() }.getOrNull()
                image.close()
                onResult(bitmap, rotation)
            }

            override fun onError(exception: ImageCaptureException) = onResult(null, 0)
        },
    )
}

object ImageUtils {
    /** Upright JPEG, long side at most [maxSide] px: enough to read small print, small enough to upload fast. */
    fun toJpeg(source: Bitmap, rotationDegrees: Int, maxSide: Int = 1568, quality: Int = 85): ByteArray {
        val scale = minOf(1f, maxSide.toFloat() / maxOf(source.width, source.height))
        val matrix = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            if (rotationDegrees != 0) postRotate(rotationDegrees.toFloat())
        }
        val upright = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        return ByteArrayOutputStream().use { stream ->
            upright.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            stream.toByteArray()
        }
    }
}
