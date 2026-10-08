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
    private val ELLIPSIS = Char(0x2026).toString()
    const val MAX_PART = 255
    const val MAX_PATH = 1024
    private const val MAX_PART_V2 = 255   // bytes of UTF-8
    private const val MAX_PATH_V2 = 4096  // bytes of UTF-8

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

    /** A name from another device, made fit to show in a message: control characters become "?", long names are cut. */
    fun shown(name: String?): String {
        val text = (name ?: "").map { if (it < ' ') '?' else it }.joinToString("")
        return if (text.pyLength() <= 80) text else text.substring(0, text.offsetByCodePoints(0, 79)) + ELLIPSIS
    }

    /**
     * Why [cleanRelPath] refuses this name, in words, like the Windows app's name_problem(). The Windows app and
     * this one give the same reason for the same name, so a sender shows the same text whichever app refused.
     */
    fun problem(path: String?): String {
        if (path.isNullOrEmpty()) return "the name is empty"
        if (path.pyLength() > MAX_PATH) return "the name is longer than 1,024 characters"
        if (path[0] == '/' || path[0] == '\\') return "a name cannot start with a slash"
        val parts = path.replace('\\', '/').split('/').filter { it != "" && it != "." }
        if (parts.isEmpty()) return "the name has no file name in it"
        for (part in parts) {
            if (part == "..") return "a name cannot contain .. (go up a folder)"
            if (part.pyLength() > MAX_PART) return "a file or folder name is longer than 255 characters"
            val bad = part.firstOrNull { it in BAD_CHARS }
            if (bad != null) return if (bad < ' ') "a name cannot contain control characters" else "a name cannot contain $bad"
            if (part != part.trimStart()) return "a name cannot start with a space"
            if (part != part.trimEnd()) return "a name cannot end with a space"
            if (part.endsWith(".")) return "a name cannot end with a dot"
            val stem = part.substringBefore('.')
            if (stem.uppercase() in RESERVED) return "$stem is a name Windows keeps for itself"
        }
        return "the name is not valid"
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

    /**
     * Protocol v2 (PROTOCOL-v2.md section 12): why a name a sender sent must be refused with `bad_files`, in words, or null
     * when it is acceptable. Much looser than v1: the receiver makes the name safe itself ([safeFromV2]) instead of refusing it.
     */
    fun problemV2(name: String): String? {
        if (name.isEmpty()) return "the name is empty"
        if (name.toByteArray(Charsets.UTF_8).size > MAX_PATH_V2) return "the name is longer than 4,096 bytes"
        if (name[0] == '/') return "a name cannot start with a slash"
        if ('\\' in name) return "a name cannot contain a backslash"
        if (name.any { it.code < 0x20 || it.code == 0x7F }) return "a name cannot contain control characters"
        for (part in name.split('/')) {
            if (part.isEmpty()) return "a name cannot have an empty part"
            if (part == "." || part == "..") return "a name cannot contain . or .. parts"
            if (part.toByteArray(Charsets.UTF_8).size > MAX_PART_V2) return "a file or folder name is longer than 255 bytes"
        }
        return null
    }

    /** The name of a v2 entry made valid on every platform the apps run on (the Windows rules), part by part. */
    fun safeFromV2(name: String): String = name.split('/').joinToString("/") { safePart(it) }

    /** "a.txt" -> "a (n).txt", for names that are taken. */
    fun numbered(name: String, n: Int): String {
        if (n == 0) return name
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)"
    }
}
