package dev.khanlabs.lanshare.transfer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.khanlabs.lanshare.net.SendItem
import dev.khanlabs.lanshare.protocol.Names
import java.io.IOException

/** Turns picked or shared content into things to send, with names the Windows app accepts. */
object Items {
    /** Items for the URIs; the ones that can't be read are left out and counted in the second value. */
    fun fromUris(context: Context, uris: List<Uri>): Pair<List<SendItem>, Int> {
        val resolver = context.contentResolver
        val items = ArrayList<SendItem>()
        val used = HashSet<String>()
        var skipped = 0
        for ((i, uri) in uris.withIndex()) {
            var name: String? = null
            var size = -1L
            try {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0)
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
                if (size < 0) size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1
            } catch (e: Exception) {
                size = -1
            }
            if (size < 0) {
                skipped++
                continue
            }
            var safe = Names.safePart(name ?: uri.lastPathSegment ?: "file-${i + 1}")
            var n = 1
            val base = safe
            while (!used.add(safe.lowercase())) safe = Names.numbered(base, n++) // two picks with one name
            items += SendItem(safe, size) { resolver.openInputStream(uri) ?: throw IOException("Couldn't open $safe") }
        }
        return items to skipped
    }
}
