package dev.khanlabs.lanshare.store

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

    fun mimeType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
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
 */
@RequiresApi(29)
class MediaStoreStore(private val context: Context) : ReceiveStore {
    override fun freeSpace(): Long = Stores.freeBytes()

    override fun open(relPath: String, size: Long): ReceiveStore.Upload {
        val name = relPath.substringAfterLast('/')
        val relDir = relPath.substringBeforeLast('/', "")
        val folder = listOf(Environment.DIRECTORY_DOWNLOADS, Stores.FOLDER, relDir).filter { it.isNotEmpty() }
            .joinToString("/") + "/"
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
