package dev.khanlabs.lanshare.net

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The receiver's record of one accepted v2 session (PROTOCOL-v2.md section 9): one line per fact, only ever added to, so
 * a crash can lose at most the last line. A line is a one-letter tag, a space and the rest; nothing in it holds a newline.
 *
 *  - `S json` the session: sender, summary, text, chunk size, when it was made
 *  - `F json` a page of the file list; `M` the list is complete
 *  - `A json` the person accepted, with the chosen files (or all)
 *  - `C id n digest` chunk n of file id arrived, with its SHA-256
 *  - `D id name` file id is saved as name; `K id reason` left out; `R id` the chunks of id were thrown away
 *  - `X state` the session ended
 *
 * Chunk data goes to disk before its `C` line (the caller flushes the part first).
 */
internal class Journal(private val file: File) {
    private var out: FileOutputStream? = null
    private var closed = false

    /**
     * Adds a line. A journal that can't be written (the disk is full, the session already ended) only costs the resume:
     * what the line said is sent again, so this never fails the request that called it.
     */
    @Synchronized
    fun append(tag: Char, payload: String = "") {
        require('\n' !in payload && '\r' !in payload) { "a journal line has no newline" }
        if (closed) return
        try {
            val stream = out ?: FileOutputStream(file, true).also { out = it }
            stream.write("$tag $payload\n".toByteArray(Charsets.UTF_8))
            stream.flush() // to the file system; a crash of the app (not of the phone) loses nothing
        } catch (e: IOException) {
            // see above
        }
    }

    @Synchronized
    fun close() {
        closed = true
        try {
            out?.close()
        } catch (e: IOException) {
            // nothing more to write
        }
        out = null
    }

    val lastWrite: Long get() = file.lastModified()

    companion object {
        /** Every line of a journal, as (tag, payload). A line cut short by a crash, or not shaped like a line, is left out. */
        fun read(file: File): List<Pair<Char, String>> {
            if (!file.isFile) return emptyList()
            val lines = ArrayList<Pair<Char, String>>()
            val text = try {
                file.readText(Charsets.UTF_8)
            } catch (e: IOException) {
                return emptyList()
            }
            // the last line only counts if it ended: a crash in the middle of one leaves it cut
            val complete = if (text.endsWith("\n")) text else text.substringBeforeLast('\n', "")
            for (raw in complete.split('\n')) {
                if (raw.length < 2 || raw[1] != ' ') continue
                lines += raw[0] to raw.substring(2)
            }
            return lines
        }
    }
}
