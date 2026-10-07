package dev.khanlabs.lanshare.store

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import dev.khanlabs.lanshare.net.FolderStore
import dev.khanlabs.lanshare.net.ReceiveStore
import dev.khanlabs.lanshare.net.SaveFolder
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.SecureRandom

/**
 * Received files in a folder the person picked with the system folder picker (a tree address). It talks to the
 * folder's provider through DocumentsContract and reads each folder once, because DocumentFile's find-by-name reads the
 * whole folder again for every file, which is slow with thousands of files.
 *
 * A file is written under a hidden name and renamed when it is whole, as [dev.khanlabs.lanshare.net.FolderStore] does;
 * a provider that can't rename gets a copy under the right name instead. The provider can't keep the sender's file
 * date, so a per-folder index does, like [MediaStoreStore].
 */
class TreeStore(context: Context, private val tree: Uri) : ReceiveStore {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val authority = tree.authority.orEmpty()
    private val rootId = DocumentsContract.getTreeDocumentId(tree)
    private val index = Stores.treeIndex(this.context, tree)

    /** The folders read so far: folder ID -> lower-case name -> what is in it. */
    private val dirs = HashMap<String, MutableMap<String, Child>>()

    private class Child(val docId: String, val name: String, val isDir: Boolean, val size: Long)

    private fun docUri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    override fun freeSpace(): Long {
        val volume = SaveFolder.volume(authority, rootId) ?: return UNKNOWN
        val dir = if (volume == "primary") Environment.getExternalStorageDirectory() else File("/storage/$volume")
        return try {
            maxOf(0L, StatFs(dir.path).availableBytes - ReceiveStore.MIN_FREE)
        } catch (e: Exception) {
            UNKNOWN // a write that runs out of room fails with "no space" and is reported as such
        }
    }

    override fun existing(relPath: String): ReceiveStore.Existing? {
        // the newest copy the sender's name was saved as (maybe "a (1).txt"), then the plain name
        for (saved in listOfNotNull(index.byOffered(relPath)?.saved, relPath).distinct()) {
            val size = find(saved)?.size?.takeIf { it >= 0 } ?: continue
            val record = index.bySaved(saved)?.takeIf { it.size == size }
            return ReceiveStore.Existing(saved, size, record?.mtime)
        }
        return null
    }

    override fun remember(offered: String, saved: String, size: Long, mtime: Long?) {
        index.add(offered, saved, size, mtime)
    }

    /** A content address for a file saved in this folder (the name [ReceiveStore.Upload.commit] returned), or null. */
    fun uriOf(saved: String): Uri? = try {
        find(saved)?.let { docUri(it.docId) }
    } catch (e: Exception) {
        null
    }

    override fun open(relPath: String, size: Long): ReceiveStore.Upload {
        val name = relPath.substringAfterLast('/')
        val relDir = relPath.substringBeforeLast('/', "")
        val dirId = dirFor(relDir, create = true) ?: throw IOException("Couldn't create the folder for $relPath")
        val temp = create(dirId, BINARY, SaveFolder.partialName(name, randomHex()))
        val tempUri = docUri(temp.docId)
        val out = try {
            resolver.openOutputStream(tempUri, "w") ?: throw IOException("Couldn't open $relPath")
        } catch (e: Exception) {
            discard(dirId, temp)
            throw e as? IOException ?: IOException(e)
        }
        return object : ReceiveStore.Upload {
            override val output: OutputStream = out

            override fun commit(): String {
                out.close()
                synchronized(LOCK) {
                    val wanted = SaveFolder.freeName(name) { taken -> children(dirId).containsKey(taken) }
                    val saved = rename(dirId, temp, tempUri, wanted, relPath, size)
                    return if (relDir.isEmpty()) saved else "$relDir/$saved"
                }
            }

            override fun abort() {
                try {
                    out.close()
                } catch (e: IOException) {
                    // closing is all that's left
                }
                discard(dirId, temp)
            }
        }
    }

    /** Gives the finished file its name: renamed in place, or copied under it when the provider can't rename. Returns the name it got. */
    private fun rename(dirId: String, temp: Child, tempUri: Uri, wanted: String, relPath: String, size: Long): String {
        val renamed = try {
            DocumentsContract.renameDocument(resolver, tempUri, wanted)
        } catch (e: Exception) {
            null
        }
        val target: Child
        if (renamed != null) {
            target = Child(DocumentsContract.getDocumentId(renamed), displayName(renamed) ?: wanted, false, size)
        } else {
            val copy = create(dirId, Stores.mimeType(wanted), wanted)
            try {
                resolver.openInputStream(tempUri)!!.use { input ->
                    resolver.openOutputStream(docUri(copy.docId), "w")!!.use { out -> input.copyTo(out) }
                }
            } catch (e: Exception) {
                discard(dirId, copy)
                throw IOException("Couldn't save $relPath", e)
            }
            discard(dirId, temp)
            target = Child(copy.docId, copy.name, false, size)
        }
        synchronized(dirs) {
            dirs[dirId]?.remove(temp.name.lowercase())
            dirs[dirId]?.put(target.name.lowercase(), target)
        }
        return target.name
    }

    private fun discard(dirId: String, doc: Child) {
        try {
            DocumentsContract.deleteDocument(resolver, docUri(doc.docId))
        } catch (e: Exception) {
            // a hidden half-written file is all that is left
        }
        synchronized(dirs) { dirs[dirId]?.remove(doc.name.lowercase()) }
    }

    // --- reading and making folders

    private fun children(dirId: String): MutableMap<String, Child> = synchronized(dirs) { dirs.getOrPut(dirId) { list(dirId) } }

    private fun list(dirId: String): MutableMap<String, Child> {
        val found = HashMap<String, Child>()
        val cursor = resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE), null, null, null)
            ?: throw IOException("Couldn't read the folder")
        cursor.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val isDir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                found[name.lowercase()] = Child(c.getString(0), name, isDir, if (c.isNull(3)) -1L else c.getLong(3))
            }
        }
        return found
    }

    /** The ID of the folder at [relDir] inside the chosen folder. Missing folders are made when [create], else null. */
    private fun dirFor(relDir: String, create: Boolean): String? {
        var id = rootId
        if (relDir.isEmpty()) return id
        for (part in relDir.split('/')) {
            val found = children(id)[part.lowercase()]
            id = when {
                found != null && found.isDir -> found.docId
                found != null -> if (create) throw IOException("A file called $part is in the way") else return null
                create -> create(id, DocumentsContract.Document.MIME_TYPE_DIR, part).docId
                else -> return null
            }
        }
        return id
    }

    private fun find(saved: String): Child? {
        val dirId = dirFor(saved.substringBeforeLast('/', ""), create = false) ?: return null
        val child = children(dirId)[saved.substringAfterLast('/').lowercase()] ?: return null
        return child.takeIf { !it.isDir && !FolderStore.isPartial(it.name) }
    }

    private fun create(parentId: String, mime: String, name: String): Child {
        val uri = try {
            DocumentsContract.createDocument(resolver, docUri(parentId), mime, name)
        } catch (e: Exception) {
            throw IOException("Couldn't create $name", e)
        } ?: throw IOException("Couldn't create $name")
        val child = Child(DocumentsContract.getDocumentId(uri), displayName(uri) ?: name,
            mime == DocumentsContract.Document.MIME_TYPE_DIR, 0L)
        synchronized(dirs) { dirs[parentId]?.put(child.name.lowercase(), child) }
        return child
    }

    private fun displayName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val BINARY = "application/octet-stream"
        private const val UNKNOWN = Long.MAX_VALUE / 2
        private val LOCK = Any()
        private val random = SecureRandom()

        private fun randomHex(): String = ByteArray(4).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }
}
