package dev.khanlabs.lanshare.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen QR scanner. The camera is only used to read the code: nothing is recorded, saved or sent anywhere.
 * [onCode] is called once, with the text of the first QR code seen.
 */
@Composable
internal fun ScanScreen(onCode: (String) -> Unit, onAddByIp: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var asked by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; asked = true }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Glyph(Icons.Back, MaterialTheme.colorScheme.onBackground, description = "Close") }
                    Text("Scan QR code", style = MaterialTheme.typography.titleLarge)
                }
                Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (granted) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp))) {
                            CameraPreview(onCode)
                            // a frame to aim with
                            Box(Modifier.fillMaxSize().padding(36.dp).border(BorderStroke(3.dp, Color.White.copy(alpha = 0.85f)), RoundedCornerShape(16.dp)))
                        }
                        Spacer(Modifier.height(16.dp))
                        Text("Point the camera at the QR code on your PC or TV.", textAlign = TextAlign.Center)
                        Muted("The camera is used only to read the code. No photos are taken.", modifier = Modifier.padding(top = 4.dp))
                    } else if (!asked) {
                        Spacer(Modifier.height(24.dp))
                        Text("Allow the camera?", style = MaterialTheme.typography.titleMedium)
                        Muted("The camera is used only to read the QR code. No photos are taken or saved.", modifier = Modifier.padding(vertical = 8.dp))
                        Button(onClick = { ask.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                    } else {
                        Spacer(Modifier.height(24.dp))
                        Text("The camera is off for LAN Share", style = MaterialTheme.typography.titleMedium)
                        Muted("You can turn it on in Settings, or add the device by its address instead.", modifier = Modifier.padding(vertical = 8.dp))
                        Button(onClick = { openAppSettings(context) }) { Text("Open Settings") }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onAddByIp) { Text("Add by IP address instead") }
                }
                MadeWithBar()
            }
        }
    }
}

private fun openAppSettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        // no settings app to open
    }
}

private val mainExecutor = Executor { Handler(Looper.getMainLooper()).post(it) }

@Composable
private fun CameraPreview(onCode: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val analysisThread = remember { Executors.newSingleThreadExecutor() }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(owner) {
        val future = ProcessCameraProvider.getInstance(context)
        val done = AtomicBoolean(false)
        future.addListener({
            val p = future.get()
            provider = p
            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(analysisThread, QrAnalyzer { text -> if (done.compareAndSet(false, true)) mainExecutor.execute { onCode(text) } })
            try {
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                // no usable back camera: the buttons below still offer Add by IP
            }
        }, mainExecutor)
        onDispose {
            provider?.unbindAll()
            analysisThread.shutdown()
        }
    }
    AndroidView({ view }, Modifier.fillMaxSize())
}

/** Reads QR codes from camera frames with ZXing. Looks at the brightness plane only. */
private class QrAnalyzer(private val onText: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
    }

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val data = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
            val source = PlanarYUVLuminanceSource(data, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
            onText(reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text)
        } catch (e: Exception) {
            // no code in this frame
        } finally {
            reader.reset()
            image.close()
        }
    }
}
