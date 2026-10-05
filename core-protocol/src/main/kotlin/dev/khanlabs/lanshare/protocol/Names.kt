package dev.khanlabs.lanshare.protocol

/**
 * File name rules. Protocol v1 only allows names that are valid on Windows (PROTOCOL.md 3.2), and the Windows app
 * refuses a whole offer if one name breaks them. So a receiver checks names with [cleanRelPath], exactly like the
 * Windows app's clean_relpath(), and a sender makes its names safe first with [safeRelPath].
 */
object Names {
    private val BAD_CHARS: Set<Char> = "<>:\"/\\|?*".toSet() + (0 until 32).map { it.toChar() }
    private val RESERVED: Set<String> =
        setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }
    const val MAX_PART = 255
    const val MAX_PATH = 1024

    /** The name if it is a valid Windows file name, else null. Trailing dots and spaces are trimmed first. */
    fun cleanName(name: String): String? {
        val n = name.trim().trimEnd('.', ' ')
        if (n.isEmpty() || n.any { it in BAD_CHARS } || n.substringBefore('.').uppercase() in RESERVED) return null
        return n
    }

    /**
     * "Trip/day 1/a.jpg" with every part a valid Windows name, or null. Never absolute, never "..".
     * Like the Windows app, "\" counts as a separator and empty or "." parts are dropped.
     */
    fun cleanRelPath(path: String?): String? {
        if (path.isNullOrEmpty() || path.pyLength() > MAX_PATH || path[0] == '/' || path[0] == '\\') return null
        val parts = path.replace('\\', '/').split('/').filter { it != "" && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." || it.pyLength() > MAX_PART || cleanName(it) != it }) return null
        return parts.joinToString("/")
    }

    /** Length the way Python counts it, so both apps agree on the 255 and 1024 limits. */
    private fun String.pyLength(): Int = codePointCount(0, length)

    /** Makes one part of a name valid on Windows, so the other side doesn't refuse the send. */
    fun safePart(part: String): String {
        var n = buildString { for (c in part) append(if (c in BAD_CHARS) '_' else c) }.trim().trimEnd('.', ' ')
        if (n.isEmpty()) n = "_"
        val stem = n.substringBefore('.')
        if (stem.uppercase() in RESERVED) n = stem + "_" + n.substring(stem.length)
        if (n.length > MAX_PART) {
            val ext = n.substringAfterLast('.', "").let { if (it.length in 1..16) ".$it" else "" }
            var cut = MAX_PART - ext.length
            if (Character.isHighSurrogate(n[cut - 1])) cut-- // never split a character in two
            n = n.substring(0, cut).trimEnd('.', ' ') + ext
        }
        return n
    }

    /** A relative path for sending, made of safe parts. */
    fun safeRelPath(parts: List<String>): String = parts.filter { it.isNotEmpty() && it != "." }.joinToString("/") { safePart(it) }

    /** "a.txt" -> "a (n).txt", for names that are taken. */
    fun numbered(name: String, n: Int): String {
        if (n == 0) return name
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)"
    }
}
