package dev.khanlabs.lanshare.net

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * One file of a share. It is listed at once, but offered for download only after it has been read once and found
 * good ([State.READY]): that pass finds its real size, a CRC-32 (for the ZIP) and a SHA-256 (for people who want to
 * check it). Every download is then compared with what that pass found.
 */
class ShareFile internal constructor(
    /** Never reused, even if the share grows. Links use it, so a link keeps meaning the same file. */
    val id: Int,
    /** 1 for what was picked first, 2 for the next "Add more", and so on. */
    val batch: Int,
    /** Milliseconds since 1970. */
    val addedAt: Long,
    /** The name on the page and in the ZIP. A "/" puts it in a folder. */
    val name: String,
    internal val source: SendItem,
) {
    enum class State { PREPARING, READY, FAILED }

    @Volatile var state: State = State.PREPARING
        internal set

    /** What the picker said until the file is read; after that, what it really holds. */
    @Volatile var size: Long = maxOf(0L, source.size)
        internal set
    @Volatile var crc: Long = 0
        internal set
    @Volatile var sha256: String = ""
        internal set

    /** Why a [State.FAILED] file could not be read, for the sharer. */
    @Volatile var error: String = ""
        internal set

    /** Set when a download of this file was cut because it no longer matched its checksum. A new try may work. */
    @Volatile var problem: String = ""
        internal set

    /** A private copy made while the file was checked, so later downloads don't depend on the original staying put. */
    @Volatile private var copy: File? = null

    val folder: Boolean get() = '/' in name
    val top: String get() = if (folder) name.substringBefore('/') else name

    /** Seconds-accurate modified time for the ZIP and the listing; when the source gave none, the time it was added. */
    val modified: Long get() = if (source.modified > 0) source.modified else addedAt

    /** A strong validator, so a browser may ask for the rest of a download after a break. */
    internal val etag: String get() = "\"f-${sha256.take(32)}\""

    internal fun open(): InputStream = copy?.let { FileInputStream(it) } ?: source.open()

    internal fun discardCopy() {
        copy?.delete()
        copy = null
    }

    /**
     * Reads the file once. With a [stash] folder and [copyIt], the bytes are also written there, and later
     * downloads read that copy; if the copy can't be written (no room), the file is simply read in place.
     * Returns early, leaving the file PREPARING, when [stop] says the share is over.
     */
    internal fun prepare(stash: File?, copyIt: Boolean, stop: () -> Boolean) {
        val declared = source.size
        var target: File? = null
        var sink: OutputStream? = null
        try {
            if (stash != null && (copyIt || declared <= 0)) {
                try {
                    stash.mkdirs() // a folder that does not exist yet reports no free space
                    if (declared <= 0 || stash.usableSpace > declared + STASH_MARGIN) {
                        target = File(stash, "$id.part")
                        sink = FileOutputStream(target)
                    }
                } catch (e: IOException) {
                    sink = null
                    target?.delete()
                    target = null
                }
            }
            val md = MessageDigest.getInstance("SHA-256")
            val sum = CRC32()
            var n = 0L
            source.open().use { s ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    if (stop()) return
                    val r = s.read(buf)
                    if (r < 0) break
                    sum.update(buf, 0, r)
                    md.update(buf, 0, r)
                    n += r
                    val w = sink
                    if (w != null) {
                        try {
                            w.write(buf, 0, r)
                        } catch (e: IOException) { // most likely no room: go on reading, serve the original
                            try { w.close() } catch (_: IOException) {}
                            sink = null
                            target?.delete()
                            target = null
                        }
                    }
                }
            }
            if (n == 0L && declared > 0) throw IOException("Android gave an empty file for something that is $declared bytes")
            if (declared > 0 && n < declared) throw IOException("Android gave only $n of $declared bytes")
            sink?.close()
            sink = null
            val part = target
            if (part != null) {
                val done = File(stash, "$id.bin")
                if (part.length() == n && part.renameTo(done)) copy = done else part.delete()
                target = null
            }
            size = n
            crc = sum.value
            sha256 = md.digest().joinToString("") { "%02x".format(it) }
            state = State.READY
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
            state = State.FAILED
        } finally {
            try { sink?.close() } catch (_: IOException) {}
            if (state != State.READY) target?.delete()
        }
    }

    private companion object {
        /** A copy is only made when this much more than the file is free, so sharing never fills the phone. */
        const val STASH_MARGIN = 256L * 1024 * 1024
    }
}

/** What the page lists: a file, or a folder with the files inside it. */
class ShareItem internal constructor(val folder: Boolean, val name: String, val files: List<ShareFile>) {
    val size: Long get() = files.sumOf { it.size }
    val firstId: Int get() = files.first().id

    /** The newest "Add more" that put something into this item. */
    val batch: Int get() = files.maxOf { it.batch }
    val preparing: Int get() = files.count { it.state == ShareFile.State.PREPARING }
    val failed: Int get() = files.count { it.state == ShareFile.State.FAILED }
    val ready: Int get() = files.count { it.state == ShareFile.State.READY }

    companion object {
        /** Files into the things the page lists, the newest batch first. */
        internal fun group(files: List<ShareFile>): List<ShareItem> {
            val order = LinkedHashMap<String, MutableList<ShareFile>>()
            for (f in files) order.getOrPut(if (f.folder) "d:" + f.top.lowercase() else "f:${f.id}") { ArrayList() } += f
            return order.values.map { ShareItem(it[0].folder, it[0].top, it) }
                .sortedWith(compareByDescending<ShareItem> { it.batch }.thenBy { it.firstId })
        }
    }
}

/**
 * Hands out the names of a share, so two files never clash, also across later "Add more". A name with a "/" is in a
 * folder; a folder's name wins over a single file with the same name, which gets a number. Case does not matter.
 */
internal class NameBook {
    private val singles = HashSet<String>()
    private val folders = HashMap<String, String>() // lower case -> the name as first shown
    private val paths = HashSet<String>()

    /** The final names for [names], in the same order. Folders are settled first, so a clash always costs the single file. */
    fun assign(names: List<String>): List<String> {
        val out = arrayOfNulls<String>(names.size)
        for ((i, name) in names.withIndex()) {
            if ('/' !in name) continue
            val top = name.substringBefore('/')
            val actual = folders.getOrPut(top.lowercase()) {
                var t = top
                var n = 2
                while (t.lowercase() in singles) t = numbered(top, n++)
                t
            }
            val rest = name.substringAfter('/')
            var path = "$actual/$rest"
            var n = 2
            while (!paths.add(path.lowercase())) path = "$actual/" + numbered(rest, n++)
            out[i] = path
        }
        for ((i, name) in names.withIndex()) {
            if ('/' in name) continue
            var s = name
            var n = 2
            while (s.lowercase() in folders || !singles.add(s.lowercase())) s = numbered(name, n++)
            out[i] = s
        }
        return out.map { it!! }
    }

    private fun numbered(name: String, n: Int): String {
        val slash = name.lastIndexOf('/')
        val dot = name.lastIndexOf('.')
        return if (dot > slash + 1) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)"
    }
}
