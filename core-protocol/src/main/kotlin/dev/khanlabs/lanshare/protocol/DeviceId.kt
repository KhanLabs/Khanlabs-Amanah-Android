package dev.khanlabs.lanshare.protocol

import java.security.MessageDigest

/** Device IDs (PROTOCOL.md section 1): SHA-256 of the certificate in DER form, 64 upper-case hex digits. */
object DeviceId {
    private val ID = Regex("[0-9A-F]{64}")

    fun of(certDer: ByteArray): String = sha256(certDer).toHex().uppercase()

    fun isValid(id: String?): Boolean = id != null && ID.matches(id)

    /** The first 8 hex digits, grouped for reading aloud: "A1B2-C3D4". */
    fun short(id: String): String = if (id.length >= 8) "${id.substring(0, 4)}-${id.substring(4, 8)}" else id
}

internal fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

private val HEX = "0123456789abcdef".toCharArray()

fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val b = this[i].toInt() and 0xff
        out[i * 2] = HEX[b ushr 4]
        out[i * 2 + 1] = HEX[b and 0x0f]
    }
    return String(out)
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd number of hex digits" }
    return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
