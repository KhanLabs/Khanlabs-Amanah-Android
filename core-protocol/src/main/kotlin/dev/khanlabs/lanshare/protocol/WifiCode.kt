package dev.khanlabs.lanshare.protocol

/**
 * The standard Wi-Fi QR code: `WIFI:T:WPA;S:<network name>;P:<password>;;`. Phone cameras read it and offer to join.
 * `\`, `;`, `,`, `:` and `"` are escaped with a backslash. Only password-protected WPA and WPA2 networks are used
 * here: Amanah never joins or creates an open network.
 *
 * Also the rules for a network name and password inside an Amanah connect code ([ConnectCode.ssid]). The same rules
 * are in `connect_code.py` in the Windows repo.
 */
object WifiCode {
    const val MAX_SSID_BYTES = 32
    const val MIN_PSK = 8
    const val MAX_PSK = 63
    private const val MAX_TEXT = 400

    /** A network name of 1 to 32 bytes with no control characters, and a printable-ASCII password of 8 to 63 characters. */
    fun isValid(ssid: String, psk: String): Boolean =
        ssid.isNotEmpty() && ssid.toByteArray(Charsets.UTF_8).size <= MAX_SSID_BYTES &&
            ssid.none { it < ' ' || it == '\u007f' } &&
            psk.length in MIN_PSK..MAX_PSK && psk.all { it in ' '..'~' }

    fun toText(ssid: String, psk: String): String = "WIFI:T:WPA;S:${escape(ssid)};P:${escape(psk)};;"

    /** (network name, password) for a usable code, or null. */
    fun parse(text: String): Pair<String, String>? {
        val t = text.trim()
        if (t.length > MAX_TEXT || !t.startsWith("WIFI:", ignoreCase = true)) return null
        var type = ""
        var ssid: String? = null
        var psk: String? = null
        for (field in split(t.substring(5))) {
            val colon = field.indexOf(':')
            if (colon != 1) continue // a one-letter key, then a colon
            val raw = field.substring(2)
            when (field[0].uppercaseChar()) {
                'T' -> type = unescape(raw).uppercase()
                'S' -> ssid = value(raw)
                'P' -> psk = value(raw)
            }
        }
        if (type != "WPA" && type != "WPA2") return null
        val s = ssid ?: return null
        val p = psk ?: return null
        return if (isValid(s, p)) s to p else null
    }

    /** A value, without the quotes some phones put around it (an escaped quote at the end belongs to the value). */
    private fun value(raw: String): String =
        if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"') && !raw.endsWith("\\\"")) unescape(raw.substring(1, raw.length - 1))
        else unescape(raw)

    private fun escape(s: String): String = buildString {
        for (c in s) {
            if (c == '\\' || c == ';' || c == ',' || c == ':' || c == '"') append('\\')
            append(c)
        }
    }

    private fun unescape(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) i++
            append(s[i++])
        }
    }

    /** The text cut at every `;` that is not escaped. A backslash is kept so [unescape] can still read the field. */
    private fun split(s: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                cur.append(c).append(s[i + 1])
                i += 2
                continue
            }
            if (c == ';') {
                out += cur.toString()
                cur.setLength(0)
            } else {
                cur.append(c)
            }
            i++
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }
}
