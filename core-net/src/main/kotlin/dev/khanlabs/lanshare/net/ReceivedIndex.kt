package dev.khanlabs.lanshare.net

import java.io.File
import java.io.IOException

/**
 * A small list of the files this app received: the name it was saved as, the name the sender used, the size and the
 * sender's modified time. MediaStore can't keep a file's date, so this is how a later send of the same file is
 * recognised as "already here". One line per file: saved, offered, size, mtime (empty when unknown), split by tabs.
 * Names can't hold a tab or a line break (Names.cleanRelPath refuses control characters).
 */
class ReceivedIndex(private val file: File, private val limit: Int = 5000) {
    class Entry(val saved: String, val offered: String, val size: Long, val mtime: Long?)

    private val entries = LinkedHashMap<String, Entry>()
    private var lines = 0
    private var loaded = false

    @Synchronized
    fun add(offered: String, saved: String, size: Long, mtime: Long?) {
        load()
        val entry = Entry(saved, offered, size, mtime)
        entries.remove(saved)
        entries[saved] = entry
        try {
            if (entries.size > limit || lines >= limit * 2) {
                while (entries.size > limit * 4 / 5) entries.remove(entries.keys.first())
                rewrite()
            } else {
                file.parentFile?.mkdirs()
                file.appendText(line(entry), Charsets.UTF_8)
                lines++
            }
        } catch (e: IOException) {
            // the index is an extra; the file itself is saved
        }
    }

    /** The record for a file saved under this name. */
    @Synchronized
    fun bySaved(saved: String): Entry? {
        load()
        return entries[saved]
    }

    /** The newest record of a file the sender called this. */
    @Synchronized
    fun byOffered(offered: String): Entry? {
        load()
        return entries.values.lastOrNull { it.offered == offered }
    }

    private fun line(e: Entry) = "${e.saved}\t${e.offered}\t${e.size}\t${e.mtime ?: ""}\n"

    private fun load() {
        if (loaded) return
        loaded = true
        val text = try {
            if (file.isFile) file.readText(Charsets.UTF_8) else return
        } catch (e: IOException) {
            return
        }
        for (raw in text.split('\n')) {
            if (raw.isEmpty()) continue
            lines++
            val p = raw.split('\t')
            if (p.size != 4 || p[0].isEmpty() || p[1].isEmpty()) continue
            val size = p[2].toLongOrNull() ?: continue
            val mtime = if (p[3].isEmpty()) null else p[3].toLongOrNull() ?: continue
            entries.remove(p[0])
            entries[p[0]] = Entry(p[0], p[1], size, mtime)
        }
    }

    private fun rewrite() {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(entries.values.joinToString("") { line(it) }, Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) throw IOException("Couldn't save ${file.name}")
        }
        lines = entries.size
    }
}
