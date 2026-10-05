package dev.khanlabs.lanshare.net

import dev.khanlabs.lanshare.protocol.Names
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.SecureRandom

/** Where received files go. On Android 10 and newer that is MediaStore; [FolderStore] is a plain folder. */
interface ReceiveStore {
    /** Bytes that may still be received. */
    fun freeSpace(): Long

    /** Starts one file. [relPath] is already checked with Names.cleanRelPath. */
    fun open(relPath: String, size: Long): Upload

    interface Upload {
        val output: OutputStream

        /**
         * Puts the finished file in place, never replacing anything: "a.txt" becomes "a (1).txt" if the name is
         * taken. Returns the relative name it was saved as. [output] must be closed first.
         */
        fun commit(): String

        /** Throws the partial file away. */
        fun abort()
    }

    companion object {
        /** Uploads never take the disk below this, like the Windows app. */
        const val MIN_FREE = 64L * 1024 * 1024
    }
}

/** True when an exception means the disk is full. */
fun isDiskFull(e: Throwable): Boolean {
    val text = generateSequence(e) { it.cause }.take(5).joinToString(" ") { it.message ?: "" }
    return "ENOSPC" in text || "No space left" in text || "not enough space" in text.lowercase()
}

/** Received files in a folder. A file is written to a hidden temporary name and renamed when it is complete. */
class FolderStore(private val root: File) : ReceiveStore {
    override fun freeSpace(): Long {
        root.mkdirs()
        return maxOf(0L, root.usableSpace - ReceiveStore.MIN_FREE)
    }

    override fun open(relPath: String, size: Long): ReceiveStore.Upload {
        val wanted = File(root, relPath)
        val dir = wanted.parentFile!!
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Couldn't create the folder for $relPath")
        val temp = File(dir, ".${wanted.name}.${randomHex()}$PARTIAL_SUFFIX")
        val out = FileOutputStream(temp)
        val relDir = relPath.substringBeforeLast('/', "")
        return object : ReceiveStore.Upload {
            override val output: OutputStream = out

            override fun commit(): String {
                out.close()
                synchronized(LOCK) {
                    var n = 0
                    while (true) {
                        val name = Names.numbered(wanted.name, n)
                        val target = File(dir, name)
                        if (!target.exists()) {
                            if (!temp.renameTo(target)) throw IOException("Couldn't save $relPath")
                            return if (relDir.isEmpty()) name else "$relDir/$name"
                        }
                        n++
                    }
                }
            }

            override fun abort() {
                try {
                    out.close()
                } catch (e: IOException) {
                    // closing is all that's left
                }
                temp.delete()
            }
        }
    }

    companion object {
        const val PARTIAL_SUFFIX = ".uploading"
        private val LOCK = Any()
        private val random = SecureRandom()

        private fun randomHex(): String = ByteArray(4).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

        fun isPartial(name: String) = name.startsWith(".") && name.endsWith(PARTIAL_SUFFIX)
    }
}
