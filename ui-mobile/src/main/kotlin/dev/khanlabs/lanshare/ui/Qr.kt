package dev.khanlabs.lanshare.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dev.khanlabs.lanshare.protocol.ConnectCode
import dev.khanlabs.lanshare.protocol.DeviceId

/** A QR code for [text]. Always black on white, in the dark theme too: scanners need that contrast. */
@Composable
internal fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )
    }
    val quiet = 3 // empty modules around the code
    Canvas(
        modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(Color.White)
            .semantics { contentDescription = "QR code to connect to this device" },
    ) {
        val cell = size.width / (matrix.width + 2 * quiet)
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(Color.Black, Offset((x + quiet) * cell, (y + quiet) * cell), Size(cell + 0.6f, cell + 0.6f))
        }
    }
}

/** This device's QR code, for another device to scan. */
@Composable
internal fun QrSheet(code: ConnectCode?, onDismiss: () -> Unit) {
    Sheet(onDismiss) {
        Text("Scan to connect", style = MaterialTheme.typography.titleLarge)
        Muted("On the other device, open LAN Share and choose Scan QR code. Scanning proves it is this device, not another one with the same name.")
        if (code == null) {
            Text("There is no code yet. Connect to Wi-Fi and turn Receiving on.", Modifier.padding(vertical = 24.dp))
        } else {
            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                QrCode(code.toText(), Modifier.widthIn(max = 300.dp).fillMaxWidth())
                Text(code.name, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Muted("${code.hosts.first()}:${code.port}  ·  ID ${DeviceId.short(code.id)}", mono = true)
            }
        }
    }
}
