package dev.khanlabs.lanshare.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.khanlabs.lanshare.net.Device
import dev.khanlabs.lanshare.transfer.Format

@Composable
internal fun Muted(text: String, mono: Boolean = false, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = if (mono) FontFamily.Monospace else null)
}

@Composable
internal fun SectionHeader(title: String, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        // small, spaced capitals: the heading names the group without competing with the cards under it
        Text(title.uppercase(), style = MaterialTheme.typography.labelLarge, letterSpacing = 1.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        actions()
    }
}

/**
 * A big rounded tile with a soft slant of light: the corner at the top left is a little lighter than the one at the
 * bottom right. With [onClick] it is a button.
 */
@Composable
internal fun SoftCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = MaterialTheme.shapes.large
    val look = Modifier.background(
        Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.surfaceContainerLow)),
        shape,
    )
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = Color.Transparent) { Box(look) { content() } }
    } else {
        Surface(modifier = modifier, shape = shape, color = Color.Transparent) { Box(look) { content() } }
    }
}

@Composable
internal fun Progress(done: Long, total: Long) {
    val f = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
}

@Composable
internal fun Glyph(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant, size: Int = 24, description: String? = null) {
    Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(size.dp))
}

/** "Windows PC", "Android phone": what the other device is, in words. */
internal fun deviceKind(model: String): String = when (model.lowercase()) {
    "windows" -> "Windows PC"
    "android" -> "Android"
    "ios" -> "iPhone or iPad"
    "macos" -> "Mac"
    "linux" -> "Linux PC"
    else -> "Device"
}

internal fun deviceIcon(model: String): ImageVector = when (model.lowercase()) {
    "android", "ios" -> Icons.Phone
    else -> Icons.Computer
}

internal fun Device.kind() = deviceKind(model)

internal fun countText(files: Int, bytes: Long): String = when {
    files == 0 -> "Message"
    files == 1 -> "1 file, ${Format.size(bytes)}"
    else -> "$files files, ${Format.size(bytes)}"
}

/** "Just now", "5 min ago", "Today, 2:32 PM", "Yesterday, 9:00 AM" or "Oct 3, 9:00 AM", in the phone's language. */
internal fun whenText(context: Context, millis: Long): String {
    val ago = System.currentTimeMillis() - millis
    return when {
        ago < DateUtils.MINUTE_IN_MILLIS -> "Just now"
        ago < DateUtils.HOUR_IN_MILLIS -> "${ago / DateUtils.MINUTE_IN_MILLIS} min ago"
        else -> DateUtils.getRelativeDateTimeString(context, millis, DateUtils.DAY_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0).toString()
    }
}

internal fun copyText(context: Context, text: String, message: String = "Copied") {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Received text", text))
    Toast.makeText(context, message, if (message.length > 20) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
}

/** "1:52" for 112 seconds. */
internal fun clock(seconds: Long): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
