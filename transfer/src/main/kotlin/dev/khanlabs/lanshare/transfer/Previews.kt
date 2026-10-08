package dev.khanlabs.lanshare.transfer

import android.os.Build
import dev.khanlabs.lanshare.net.SendItem
import dev.khanlabs.lanshare.store.Stores

/** Which files get a small picture on the transfer cards: photos, and videos from Android 10, where the system makes the picture. */
object Previews {
    /** How many pictures a card shows. */
    const val MAX = 4

    fun isPreviewMime(mime: String, sdk: Int): Boolean = mime.startsWith("image/") || (sdk >= 29 && mime.startsWith("video/"))

    /** True when a file with this name (or path) is a photo or video the phone can make a picture of. */
    fun canPreview(name: String): Boolean = isPreviewMime(Stores.mimeType(name.substringAfterLast('/')), Build.VERSION.SDK_INT)

    /** The addresses to draw pictures from for a send: the first [MAX] photos and videos in it. */
    fun of(items: List<SendItem>): List<String> {
        val found = ArrayList<String>()
        for (item in items) {
            val preview = item.preview ?: continue
            if (!canPreview(item.name)) continue
            found += preview
            if (found.size == MAX) break
        }
        return found
    }
}
