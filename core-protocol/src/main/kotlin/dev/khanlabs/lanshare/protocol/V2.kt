package dev.khanlabs.lanshare.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

/**
 * Protocol v2 formats that every device must compute the same way (docs/PROTOCOL-v2.md): file digests, device
 * proofs, the pairing MAC and batch records. The same as klv2.py in the Windows repo, and checked against
 * docs/v2/vectors.json.
 */
object V2 {
    const val PROTOCOL = 2
    const val API = "/api/kl/v2"
    const val DIGEST_ALG = "sha256-tree-v1"
    const val MIN_CHUNK = 1L shl 20
    const val MAX_CHUNK = 1L shl 26
    const val DEFAULT_CHUNK = 1L shl 23
    const val BATCH_HEADER_MAX = 4096
    const val NONCE_SECONDS = 120
    val PURPOSES = setOf("session", "pair", "hello")

    private val ID = Regex("[0-9A-F]{64}")
    val TOKEN = Regex("[A-Za-z0-9_-]{22,64}")
    private val SUBJECT = Regex("[A-Za-z0-9_-]{0,64}")
    val FILE_ID = Regex("[A-Za-z0-9_-]{1,64}")

    private val P256_ORDER = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)
    private val urlSafe = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

    fun b64url(data: ByteArray): String = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(data)
    fun unb64url(text: String): ByteArray = urlSafe.decode(text)
    fun b64(data: ByteArray): String = Base64.Default.encode(data)
    fun unb64(text: String): ByteArray = Base64.Default.decode(text)

    // --- file digests

    fun validChunkSize(n: Long): Boolean = n in MIN_CHUNK..MAX_CHUNK && (n and (n - 1)) == 0L

    fun chunkCount(size: Long, chunkSize: Long): Long = (size + chunkSize - 1) / chunkSize

    /** (offset, length) of chunk n. The last chunk may be shorter; a 0-byte file has no chunks. */
    fun chunkSpan(n: Long, size: Long, chunkSize: Long): Pair<Long, Long> {
        require(n in 0 until chunkCount(size, chunkSize)) { "chunk $n is outside a $size-byte file" }
        val start = n * chunkSize
        return start to minOf(chunkSize, size - start)
    }

    fun chunkDigest(data: ByteArray, offset: Int = 0, length: Int = data.size): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(data, offset, length)
        return md.digest().toHex()
    }

    /** sha256-tree-v1: SHA-256 of the chunk size as 8 bytes (big-endian), then each chunk's SHA-256 in order. */
    fun treeDigest(chunkDigests: List<String>, chunkSize: Long): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(ByteBuffer.allocate(8).putLong(chunkSize).array())
        for (d in chunkDigests) md.update(d.hexToBytes())
        return md.digest().toHex()
    }

    /** Reads a stream to the end: (tree digest, chunk digests). */
    fun fileDigests(input: InputStream, chunkSize: Int): Pair<String, List<String>> {
        val digests = ArrayList<String>()
        val buf = ByteArray(chunkSize)
        while (true) {
            var got = 0
            while (got < chunkSize) {
                val n = input.read(buf, got, chunkSize - got)
                if (n < 0) break
                got += n
            }
            if (got == 0) break
            digests += chunkDigest(buf, 0, got)
            if (got < chunkSize) break
        }
        return treeDigest(digests, chunkSize.toLong()) to digests
    }

    // --- device proof

    /** The bytes a sender signs: "KLv2-proof", purpose, receiver ID, sender ID, nonce, subject, one per line. */
    fun proofMessage(purpose: String, receiverId: String, senderId: String, nonce: String, subject: String = ""): ByteArray {
        require(purpose in PURPOSES) { "unknown purpose $purpose" }
        require(ID.matches(receiverId)) { "receiverId must be 64 upper-case hex digits" }
        require(ID.matches(senderId)) { "senderId must be 64 upper-case hex digits" }
        require(TOKEN.matches(nonce)) { "bad nonce" }
        require(SUBJECT.matches(subject)) { "bad subject" }
        return listOf("KLv2-proof", purpose, receiverId, senderId, nonce, subject)
            .joinToString("") { "$it\n" }.toByteArray(Charsets.US_ASCII)
    }

    /** ECDSA P-256 with SHA-256, DER signature, standard base64. */
    fun signProof(key: PrivateKey, message: ByteArray): String {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(key)
        s.update(message)
        return b64(s.sign())
    }

    fun parseCert(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate

    /** True when the signature over message was made by the certificate's key, which must be ECDSA P-256. */
    fun verifyProof(certDer: ByteArray, signatureB64: String, message: ByteArray): Boolean = try {
        val key = parseCert(certDer).publicKey
        if (key !is ECPublicKey || key.params.order != P256_ORDER) {
            false
        } else {
            val s = Signature.getInstance("SHA256withECDSA")
            s.initVerify(key)
            s.update(message)
            s.verify(unb64(signatureB64))
        }
    } catch (e: Exception) {
        false
    }

    // --- pairing

    /** HMAC-SHA256 with the QR code's secret over "KLv2-pair", the shower's ID and the scanner's ID, one per line. */
    fun pairMac(secret: ByteArray, showerId: String, scannerId: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(listOf("KLv2-pair", showerId, scannerId).joinToString("") { "$it\n" }
            .toByteArray(Charsets.US_ASCII)).toHex()
    }

    // --- batches

    @Serializable
    data class BatchHeader(val file: String, val size: Long, val sha256: String)

    class BatchException(message: String) : Exception(message)

    private fun header(fileId: String, data: ByteArray): ByteArray =
        "{\"file\":${ProtocolJson.encodeToString(String.serializer(), fileId)},\"size\":${data.size},\"sha256\":\"${chunkDigest(data)}\"}"
            .toByteArray(Charsets.UTF_8)

    /** Each record: 4-byte big-endian header length, the JSON header, the file's bytes. A length of 0 ends it. */
    fun encodeBatch(items: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((fid, data) in items) {
            val h = header(fid, data)
            out.write(ByteBuffer.allocate(4).putInt(h.size).array())
            out.write(h)
            out.write(data)
        }
        out.write(ByteArray(4))
        return out.toByteArray()
    }

    /** Reads records until the end marker. Throws [BatchException] when the stream breaks the format. */
    fun readBatch(input: InputStream, maxFile: Long, onFile: (BatchHeader, ByteArray) -> Unit) {
        while (true) {
            val length = ByteBuffer.wrap(readExact(input, 4)).int.toLong() and 0xffffffffL
            if (length == 0L) return
            if (length > BATCH_HEADER_MAX) throw BatchException("batch header too long")
            val h = try {
                val obj = ProtocolJson.parseToJsonElement(readExact(input, length.toInt()).toString(Charsets.UTF_8)).jsonObject
                val file = obj["file"]?.jsonPrimitive?.takeIf { it.isString }?.content
                val size = obj["size"]?.jsonPrimitive?.takeIf { !it.isString }?.longOrNull
                val sha = obj["sha256"]?.jsonPrimitive?.takeIf { it.isString }?.content
                if (file == null || size == null || sha == null) null else BatchHeader(file, size, sha)
            } catch (e: BatchException) {
                throw e
            } catch (e: Exception) {
                throw BatchException("batch header is not JSON")
            } ?: throw BatchException("batch header needs file, size and sha256")
            if (h.size !in 0..maxFile) throw BatchException("batch file too large")
            onFile(h, readExact(input, h.size.toInt()))
        }
    }

    private fun readExact(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(buf, got, n - got)
            if (r < 0) throw BatchException("the batch ended early")
            got += r
        }
        return buf
    }
}
