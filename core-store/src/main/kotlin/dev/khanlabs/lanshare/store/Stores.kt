package dev.khanlabs.lanshare.store

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import dev.khanlabs.lanshare.net.FolderStore
import dev.khanlabs.lanshare.net.ReceiveStore
import dev.khanlabs.lanshare.net.ReceivedIndex
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** Received files go to Download/KhanLabs, the same folder name as on Windows. */
object Stores {
    const val FOLDER = "KhanLabs"

    /** MediaStore on Android 10 and newer; a plain folder (needs WRITE_EXTERNAL_STORAGE) before that. */
    fun default(context: Context): ReceiveStore =
        if (Build.VERSION.SDK_INT >= 29) MediaStoreStore(context.applicationContext) else LegacyStore(context.applicationContext)

    /** Where people find the files, for showing in the app. */
    const val SHOWN_PATH = "Download/$FOLDER"

    private var index: ReceivedIndex? = null

    /** One list for the whole app, so two stores never keep two different copies of it. */
    @Synchronized
    internal fun receivedIndex(context: Context): ReceivedIndex =
        index ?: ReceivedIndex(File(context.noBackupFilesDir, "received-index.tsv")).also { index = it }

    /** MediaStore's RELATIVE_PATH for a folder inside the receive folder ("" for the folder itself), with its trailing slash. */
    internal fun downloadFolder(relDir: String): String =
        listOf(Environment.DIRECTORY_DOWNLOADS, FOLDER, relDir).filter { it.isNotEmpty() }.joinToString("/") + "/"

    fun mimeType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    /**
     * A content:// address for a file this app saved (the name [ReceiveStore.Upload.commit] returned), so other apps
     * can open or share it. Null if the file is gone or was saved by an earlier install.
     */
    fun uriFor(context: Context, saved: String): Uri? {
        val name = saved.substringAfterLast('/')
        val relDir = saved.substringBeforeLast('/', "")
        val resolver = context.contentResolver
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val folder = downloadFolder(relDir)
                val base = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                resolver.query(base, arrayOf(MediaStore.Downloads._ID),
                    "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.DISPLAY_NAME}=?",
                    arrayOf(folder, name), null)?.use { if (it.moveToFirst()) return ContentUris.withAppendedId(base, it.getLong(0)) }
            } else {
                @Suppress("DEPRECATION")
                val path = File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER), saved).path
                val base = MediaStore.Files.getContentUri("external")
                @Suppress("DEPRECATION")
                resolver.query(base, arrayOf(MediaStore.Files.FileColumns._ID), "${MediaStore.Files.FileColumns.DATA}=?",
                    arrayOf(path), null)?.use { if (it.moveToFirst()) return ContentUris.withAppendedId(base, it.getLong(0)) }
            }
        } catch (e: Exception) {
            // no access or no such file
        }
        return null
    }

    internal fun freeBytes(): Long = try {
        val stat = StatFs(Environment.getExternalStorageDirectory().path)
        maxOf(0L, stat.availableBytes - ReceiveStore.MIN_FREE)
    } catch (e: Exception) {
        0L
    }
}

/**
 * MediaStore.Downloads (Android 10+). Each file is inserted with IS_PENDING=1, so other apps can't see it, and
 * shown only when every byte has arrived. MediaStore itself picks "name (1).ext" when the name is taken.
 *
 * MediaStore can't keep the sender's file date, so [Stores.receivedIndex] does: it holds the date and the name the
 * sender used for every file saved here, and [existing] reads it back. An app can only see its own MediaStore files,
 * so files from an earlier install, or put there by hand, are never "already here".
 */
@RequiresApi(29)
class MediaStoreStore(private val context: Context) : ReceiveStore {
    private val index = Stores.receivedIndex(context)

    override fun freeSpace(): Long = Stores.freeBytes()

    override fun existing(relPath: String): ReceiveStore.Existing? {
        // the newest copy the sender's name was saved as (maybe "a (1).txt"), then the plain name
        for (saved in listOfNotNull(index.byOffered(relPath)?.saved, relPath).distinct()) {
            val size = sizeOf(saved) ?: continue
            val record = index.bySaved(saved)?.takeIf { it.size == size }
            return ReceiveStore.Existing(saved, size, record?.mtime)
        }
        return null
    }

    override fun remember(offered: String, saved: String, size: Long, mtime: Long?) {
        index.add(offered, saved, size, mtime)
    }

    /** Size of a file this app saved, or null when it is gone. A file still arriving (pending) doesn't count. */
    private fun sizeOf(saved: String): Long? {
        val folder = Stores.downloadFolder(saved.substringBeforeLast('/', ""))
        return try {
            context.contentResolver.query(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                arrayOf(MediaStore.Downloads.SIZE),
                "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.DISPLAY_NAME}=?",
                arrayOf(folder, saved.substringAfterLast('/')), null)?.use { if (it.moveToFirst()) it.getLong(0) else null }
        } catch (e: Exception) {
            null
        }
    }

    override fun open(relPath: String, size: Long): ReceiveStore.Upload {
        val name = relPath.substringAfterLast('/')
        val relDir = relPath.substringBeforeLast('/', "")
        val folder = Stores.downloadFolder(relDir)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, Stores.mimeType(name))
            put(MediaStore.Downloads.RELATIVE_PATH, folder)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("Couldn't create $relPath")
        val out = try {
            resolver.openOutputStream(uri, "w") ?: throw IOException("Couldn't open $relPath")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e as? IOException ?: IOException(e)
        }
        return object : ReceiveStore.Upload {
            override val output: OutputStream = out

            override fun commit(): String {
                out.close()
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                val saved = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: name
                return if (relDir.isEmpty()) saved else "$relDir/$saved"
            }

            override fun abort() {
                try {
                    out.close()
                } catch (e: IOException) {
                    // closing is all that's left
                }
                try {
                    resolver.delete(uri, null, null)
                } catch (e: Exception) {
                    // a pending item MediaStore cleans up by itself after a week
                }
            }
        }
    }
}

/** Android 6 to 9: a plain folder in Download, then the media scanner so the files show up in other apps. */
class LegacyStore(private val context: Context) : ReceiveStore {
    @Suppress("DEPRECATION")
    private val root = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), Stores.FOLDER)
    private val folder = FolderStore(root)

    override fun freeSpace(): Long = Stores.freeBytes()

    // a plain folder keeps the file date itself, so the folder store does both
    override fun existing(relPath: String): ReceiveStore.Existing? = folder.existing(relPath)

    override fun remember(offered: String, saved: String, size: Long, mtime: Long?) =
        folder.remember(offered, saved, size, mtime)

    override fun open(relPath: String, size: Long): ReceiveStore.Upload {
        val inner = folder.open(relPath, size)
        return object : ReceiveStore.Upload by inner {
            override fun commit(): String {
                val saved = inner.commit()
                MediaScannerConnection.scanFile(context, arrayOf(File(root, saved).path), null, null)
                return saved
            }
        }
    }
}
