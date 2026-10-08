package dev.khanlabs.lanshare.protocol

import java.net.InetAddress
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The text inside a "connect" QR code: `klshare://connect?v=1&id=<64 hex>&name=<name>&ip=<address>&port=<port>`.
 *
 * Scanning it adds the device the same way "Add by IP" does, but the full device ID is pinned, so the device at that
 * address must hold the certificate the code names. A pairing code is `klshare://pair?v=2&...&secret=<22 characters>`:
 * the same fields and a one-time secret (PROTOCOL-v2.md section 10.1). Scanning it adds the device and also pairs with it.
 * A pair code whose secret is missing or not valid is read as a plain connect code.
 *
 * Only addresses on the local network are accepted ([isLocalAddress]). A printed or forged code must never make the app
 * contact the internet.
 *
 * A device that made its own Wi-Fi network (no router around) adds `&ssid=<name>&psk=<password>`, so one scan joins
 * the network and connects. The app that reads it still asks the person before it joins anything. Both fields are
 * kept only when they pass [WifiCode.isValid].
 */
data class ConnectCode(
    val id: String,
    val name: String,
    val hosts: List<String>,
    val port: Int,
    val ssid: String = "",
    val psk: String = "",
    /** The pairing secret as written in the code (22 characters of base64url), or empty for a plain connect code. */
    val secret: String = "",
) {
    /** The code can be used to pair, not only to connect. */
    val isPair: Boolean get() = secret.isNotEmpty()

    /** The 16 secret bytes of a pair code. */
    fun secretBytes(): ByteArray = V2.unb64url(secret)

    /** The code names a Wi-Fi network to join first. */
    val hasWifi: Boolean get() = ssid.isNotEmpty() && psk.isNotEmpty()

    fun toText(): String = buildString {
        if (isPair) append("klshare://pair?v=2&id=").append(id) else append("klshare://connect?v=1&id=").append(id)
        append("&name=").append(URLEncoder.encode(name.take(MAX_NAME), "UTF-8"))
        for (h in hosts.take(MAX_HOSTS)) append("&ip=").append(URLEncoder.encode(h, "UTF-8"))
        append("&port=").append(port)
        if (isPair) append("&secret=").append(secret)
        if (hasWifi && WifiCode.isValid(ssid, psk)) {
            append("&ssid=").append(URLEncoder.encode(ssid, "UTF-8"))
            append("&psk=").append(URLEncoder.encode(psk, "UTF-8"))
        }
    }

    companion object {
        const val MAX_NAME = 63
        const val MAX_HOSTS = 4
        private const val MAX_TEXT = 600
        private val PREFIX = Regex("^klshare://(connect|pair)\\?", RegexOption.IGNORE_CASE)
        private val V4 = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""")
        private val V6_CHARS = Regex("[0-9a-fA-F:.]+")
        private val SECRET = Regex("[A-Za-z0-9_-]{22}")

        /** A new pairing code for this device: a fresh random secret (PROTOCOL-v2.md section 10.1). */
        fun forPairing(id: String, name: String, hosts: List<String>, port: Int, random: java.security.SecureRandom = java.security.SecureRandom()): ConnectCode =
            ConnectCode(id, name, hosts, port, secret = V2.b64url(ByteArray(16).also { random.nextBytes(it) }))

        /** The code in [text], or null when it is not a usable Amanah code. */
        fun parse(text: String): ConnectCode? {
            val t = text.trim()
            if (t.length > MAX_TEXT) return null
            val head = PREFIX.find(t) ?: return null
            val params = HashMap<String, MutableList<String>>()
            try {
                for (pair in t.substring(head.range.last + 1).split('&')) {
                    if (pair.isEmpty()) continue
                    val key = pair.substringBefore('=')
                    params.getOrPut(key) { ArrayList() }.add(URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8"))
                }
            } catch (e: IllegalArgumentException) {
                return null
            }
            val version = params["v"]?.firstOrNull()?.toIntOrNull()
            if (version != null && version !in 1..2) return null
            val id = params["id"]?.firstOrNull()?.uppercase()
            if (!DeviceId.isValid(id)) return null
            val port = params["port"]?.firstOrNull()?.toIntOrNull()
            if (port == null || port !in 1..65535) return null
            val hosts = params["ip"].orEmpty().map { it.trim() }.filter { isLocalAddress(it) }.distinct().take(MAX_HOSTS)
            if (hosts.isEmpty()) return null
            val name = params["name"]?.firstOrNull().orEmpty().trim().filter { it >= ' ' }.take(MAX_NAME)
            val ssid = params["ssid"]?.firstOrNull().orEmpty()
            val psk = params["psk"]?.firstOrNull().orEmpty()
            val wifi = WifiCode.isValid(ssid, psk)
            val secret = params["secret"]?.firstOrNull().orEmpty().takeIf { head.value.contains("pair", ignoreCase = true) && SECRET.matches(it) }.orEmpty()
            return ConnectCode(id!!, name, hosts, port, if (wifi) ssid else "", if (wifi) psk else "", secret)
        }

        /**
         * True for a literal address that stays on the local network: 10.x, 172.16 to 172.31, 192.168.x, 169.254.x,
         * 127.x, and for IPv6 fe80:: (link-local), fc00::/7 (private) and ::1. With [allowVpn], also 100.64.0.0/10, the
         * range that Tailscale and other VPNs hand out. Host names are refused, so no lookup happens.
         */
        fun isLocalAddress(host: String, allowVpn: Boolean = false): Boolean {
            val m = V4.matchEntire(host)
            if (m != null) {
                val p = m.groupValues.drop(1).map { it.toInt() }
                if (p.any { it > 255 }) return false
                return p[0] == 10 || p[0] == 127 || (p[0] == 172 && p[1] in 16..31) ||
                    (p[0] == 192 && p[1] == 168) || (p[0] == 169 && p[1] == 254) ||
                    (allowVpn && p[0] == 100 && p[1] in 64..127)
            }
            val bare = host.substringBefore('%')
            if (':' !in bare || !V6_CHARS.matches(bare)) return false
            val bytes = try {
                InetAddress.getByName(bare).address
            } catch (e: Exception) {
                return false
            }
            if (bytes.size != 16) return false
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            val loopback = bytes.take(15).all { it.toInt() == 0 } && bytes[15].toInt() == 1
            return loopback || (first == 0xfe && (second and 0xc0) == 0x80) || (first and 0xfe) == 0xfc
        }
    }
}
