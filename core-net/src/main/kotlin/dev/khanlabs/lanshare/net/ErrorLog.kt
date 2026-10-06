package dev.khanlabs.lanshare.net

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A short log of what went wrong, kept on this phone only, for the "Copy error report" button. Nothing is sent
 * anywhere. It holds the last [keep] lines, one per event, and never file contents.
 */
class ErrorLog(
    private val file: File? = null,
    private val keep: Int = KEEP,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lines = ArrayDeque<String>()
    private var onDisk = 0

    init {
        try {
            if (file != null && file.isFile) {
                val all = file.readLines()
                lines.addAll(all.takeLast(keep))
                onDisk = all.size
            }
        } catch (e: Exception) {
            // an unreadable log starts again empty
        }
    }

    @Synchronized
    fun add(what: String) {
        val clean = what.replace(CONTROL, " ").trim().take(MAX_LINE)
        val line = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(clock())) + " " + clean
        lines.addLast(line)
        while (lines.size > keep) lines.removeFirst()
        val f = file ?: return
        try {
            if (onDisk >= keep * 2) {
                f.writeText(lines.joinToString("\n", postfix = "\n"))
                onDisk = lines.size
            } else {
                f.appendText(line + "\n")
                onDisk++
            }
        } catch (e: Exception) {
            // a full disk must never break a send
        }
    }

    @Synchronized
    fun tail(count: Int): List<String> = lines.toList().takeLast(count)

    @Synchronized
    fun clear() {
        lines.clear()
        onDisk = 0
        try {
            file?.delete()
        } catch (e: Exception) {
            // nothing to do
        }
    }

    companion object {
        const val KEEP = 200
        const val MAX_LINE = 400
        private val CONTROL = Regex("\\p{Cntrl}+")
    }
}

/**
 * The text of "Copy error report": plain text a person can paste into a message to the developer. The app only puts
 * it on the clipboard. It has the version, the phone model, a few facts and the latest log lines. It has device names
 * and may have file names, so the person should read it first. It never has the private key.
 */
object ErrorReport {
    const val LOG_LINES = 100

    fun build(app: String, system: String, facts: List<Pair<String, String>>, log: List<String>): String = buildString {
        appendLine(app)
        appendLine(system)
        for ((k, v) in facts) appendLine("$k: $v")
        appendLine()
        val shown = log.takeLast(LOG_LINES)
        appendLine("--- Errors (last ${shown.size} lines) ---")
        if (shown.isEmpty()) appendLine("(none)") else shown.forEach { appendLine(it) }
    }.trimEnd()
}
