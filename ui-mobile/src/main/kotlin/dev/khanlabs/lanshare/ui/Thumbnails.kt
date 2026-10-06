package dev.khanlabs.lanshare.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.khanlabs.lanshare.store.Stores
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val THUMB_PX = 216

/** Small previews of received photos (and videos, on Android 10 and up), made on this phone from the saved files. Tap one to open it. */
@Composable
internal fun Thumbnails(saved: List<String>) {
    val shown = saved.filter { isPreviewable(it) }.take(4)
    if (shown.isEmpty()) return
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (name in shown) Thumb(name)
    }
}

private fun isPreviewable(saved: String): Boolean {
    val mime = Stores.mimeType(saved.substringAfterLast('/'))
    return mime.startsWith("image/") || (Build.VERSION.SDK_INT >= 29 && mime.startsWith("video/"))
}

@Composable
private fun Thumb(saved: String) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, saved) { value = withContext(Dispatchers.IO) { loadThumb(context, saved) } }
    Box(
        Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { openSaved(context, saved) },
    ) {
        bitmap?.let {
            Image(it, contentDescription = "Preview of ${saved.substringAfterLast('/')}", contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize())
        }
    }
}

/** A reduced copy of the saved file, or null when it is gone or can't be read as a picture. */
private fun loadThumb(context: Context, saved: String): ImageBitmap? {
    try {
        val uri = Stores.uriFor(context, saved) ?: return null
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= 29) return resolver.loadThumbnail(uri, Size(THUMB_PX, THUMB_PX), null).asImageBitmap()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= THUMB_PX && bounds.outHeight / (sample * 2) >= THUMB_PX) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }?.asImageBitmap()
    } catch (e: Exception) {
        return null
    }
}
