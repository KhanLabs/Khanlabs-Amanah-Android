package dev.khanlabs.lanshare.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
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
import dev.khanlabs.lanshare.transfer.Previews
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val THUMB_PX = 216

/** Small previews of received photos (and videos, on Android 10 and up), made on this phone from the saved files. Tap one to open it. */
@Composable
internal fun Thumbnails(saved: List<String>) {
    val shown = saved.filter { Previews.canPreview(it) }.take(Previews.MAX)
    if (shown.isEmpty()) return
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (name in shown) {
            val context = LocalContext.current
            Thumb(key = name, description = "Preview of ${name.substringAfterLast('/')}", onClick = { openSaved(context, name) }) { c ->
                Stores.uriFor(c, name)?.let { loadThumb(c, it) }
            }
        }
    }
}

/**
 * Small previews of the photos and videos being sent, drawn from where they came from ([previews] are addresses as
 * text, see Engine's OutgoingView). A file the phone can't read any more leaves no empty box.
 */
@Composable
internal fun SentThumbnails(previews: List<String>) {
    if (previews.isEmpty()) return
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (address in previews) {
            Thumb(key = address, description = "Preview of a file being sent", onClick = null) { c -> loadThumb(c, Uri.parse(address)) }
        }
    }
}

/** One square picture. It is made off the main thread; while it loads there is a quiet box, and if it can't be made there is nothing. */
@Composable
private fun Thumb(key: String, description: String, onClick: (() -> Unit)?, load: (Context) -> ImageBitmap?) {
    val context = LocalContext.current
    val made by produceState<Pair<Boolean, ImageBitmap?>>(false to null, key) {
        value = true to withContext(Dispatchers.IO) { load(context) }
    }
    val (done, bitmap) = made
    if (done && bitmap == null) return
    Box(
        Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        bitmap?.let {
            Image(it, contentDescription = description, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** A reduced copy of the file at [uri], or null when it is gone or can't be read as a picture. */
private fun loadThumb(context: Context, uri: Uri): ImageBitmap? {
    try {
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
