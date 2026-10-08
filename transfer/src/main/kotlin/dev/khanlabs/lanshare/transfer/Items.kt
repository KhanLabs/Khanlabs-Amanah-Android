package dev.khanlabs.lanshare.transfer

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import dev.khanlabs.lanshare.net.SendItem
import dev.khanlabs.lanshare.protocol.Names
import dev.khanlabs.lanshare.protocol.V1
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.io.FileNotFoundException
import java.io.IOException

/** Turns picked or shared content into things to send, with names the Windows app accepts. */
object Items {
    /**
     * Items for the URIs; the ones that can't be read are left out and counted in the second value.
     *
     * With [hold], each file that can be held is opened now and kept open until its item is released, so the app that
     * shared it can't take its access back, or end, before the send reaches the file. A file that can't be held (the
     * provider gives a pipe, or no descriptor) is opened later, as without [hold]. Only up to [MAX_HELD] files are held,
     * to stay under the limit of open files.
     */
    fun fromUris(context: Context, uris: List<Uri>, hold: Boolean = false): Pair<List<SendItem>, Int> {
        val resolver = context.contentResolver
        val items = ArrayList<SendItem>()
        val used = HashSet<String>()
        var skipped = 0
        var held = 0
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
            var pfd: ParcelFileDescriptor? = null
            if (hold && held < MAX_HELD) {
                try {
                    pfd = holdable(resolver.openFileDescriptor(uri, "r"))
                } catch (e: SecurityException) {
                    skipped++ // access is already gone
                    continue
                } catch (e: FileNotFoundException) {
                    skipped++
                    continue
                } catch (e: Exception) {
                    // opened later, from the URI
                }
            }
            var safe = Names.safePart(name ?: uri.lastPathSegment ?: "file-${i + 1}")
            var n = 1
            val base = safe
            while (!used.add(safe.lowercase())) safe = Names.numbered(base, n++) // two picks with one name
            if (pfd == null) {
                items += SendItem(safe, size, modified(context, uri), uri.toString()) { resolver.openInputStream(uri) ?: throw IOException("Couldn't open $safe") }
            } else {
                held++
                val file = pfd
                items += SendItem(safe, size, modified(context, uri), uri.toString(), onRelease = { file.close() }) {
                    // a copy of the descriptor shares the file position, so every try starts again from the first byte
                    val stream = ParcelFileDescriptor.AutoCloseInputStream(file.dup())
                    try {
                        stream.channel.position(0)
                    } catch (e: IOException) {
                        stream.close()
                        throw e
                    }
                    stream
                }
            }
        }
        return items to skipped
    }

    /** The descriptor if it is a real file (it can be read again from the start), else it is closed and this is null. */
    private fun holdable(pfd: ParcelFileDescriptor?): ParcelFileDescriptor? {
        if (pfd == null) return null
        if (pfd.statSize >= 0) return pfd
        pfd.close()
        return null
    }

    /**
     * When the file was last changed, in milliseconds since 1970, or 0 when the provider doesn't say. A ZIP made
     * from the file keeps it, so a photo keeps its date after it is unzipped.
     */
    private fun modified(context: Context, uri: Uri): Long = try {
        val column = if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.Document.COLUMN_LAST_MODIFIED else null
        var ms = 0L
        if (column != null) {
            context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) ms = c.getLong(0)
            }
        }
        if (ms <= 0 && uri.authority == "media") { // the photo picker and gallery: seconds, not milliseconds
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATE_MODIFIED), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) ms = c.getLong(0) * 1000
            }
        }
        ms.coerceAtLeast(0L)
    } catch (e: Exception) {
        0L
    }

    /** A folder the person picked: every file in it, with names like "Trip/day 1/a.jpg". */
    class Tree(val folderName: String, val items: List<SendItem>, val skipped: Int, val tooMany: Boolean) {
        val bytes: Long get() = items.sumOf { it.size }
    }

    /** Walks a folder from the system folder picker. Slow on big folders, so call it off the main thread. */
    fun fromTree(context: Context, tree: Uri): Tree {
        val resolver = context.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        var rootName: String? = null
        try {
            resolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, rootId),
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) rootName = it.getString(0)
            }
        } catch (e: Exception) {
            // the folder keeps a generic name
        }
        val base = Names.safePart(rootName ?: "Folder")
        val items = ArrayList<SendItem>()
        val used = HashSet<String>()
        var skipped = 0
        val todo = ArrayDeque<Pair<String, String>>() // document id, path so far
        todo.addLast(rootId to base)
        while (todo.isNotEmpty()) {
            val (docId, dir) = todo.removeLast()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
            try {
                resolver.query(children, cols, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0)
                        val safe = Names.safePart(c.getString(1) ?: "file")
                        if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            todo.addLast(id to "$dir/$safe")
                            continue
                        }
                        val size = if (c.isNull(3)) -1L else c.getLong(3)
                        var path = "$dir/$safe"
                        var n = 1
                        while (path.length <= MAX_PATH && !used.add(path.lowercase())) path = "$dir/${Names.numbered(safe, n++)}"
                        if (size < 0 || path.length > MAX_PATH) {
                            skipped++
                            continue
                        }
                        if (items.size >= V1.MAX_FILES) return Tree(base, emptyList(), skipped, tooMany = true)
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        val changed = if (c.isNull(4)) 0L else c.getLong(4).coerceAtLeast(0L)
                        items += SendItem(path, size, changed, uri.toString()) { resolver.openInputStream(uri) ?: throw IOException("Couldn't open $path") }
                    }
                }
            } catch (e: Exception) {
                skipped++ // a folder that can't be listed
            }
        }
        return Tree(base, items, skipped, tooMany = false)
    }

    private const val MAX_PATH = 1000 // the protocol allows 1024; this leaves room for "(n)"
    private const val MAX_HELD = 200
}

/**
 * Files another app shared, being opened as soon as they arrive (Engine.holdFiles). [await] gives the items once they
 * are open; [release] gives them all back, for when the person never picks a device. A send that takes the items
 * releases each file itself as it finishes.
 */
class HeldFiles internal constructor(private val result: Deferred<Pair<List<SendItem>, Int>>) {
    suspend fun await(): Pair<List<SendItem>, Int> = result.await()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun release() {
        result.invokeOnCompletion { error ->
            if (error == null) result.getCompleted().first.forEach { it.release() }
        }
    }
}
