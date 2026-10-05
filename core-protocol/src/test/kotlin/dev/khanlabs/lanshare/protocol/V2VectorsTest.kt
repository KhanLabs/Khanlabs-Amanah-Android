package dev.khanlabs.lanshare.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Every case in docs/v2/vectors.json (made by the Windows repo's tools/make_v2_vectors.py) must pass. */
class V2VectorsTest {
    private val v: JsonObject = ProtocolJson.parseToJsonElement(
        File(System.getProperty("klshare.docs"), "v2/vectors.json").readText()
    ).jsonObject

    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content

    /** Test file contents: byte i is i mod 251. */
    private fun pattern(n: Int) = ByteArray(n) { (it % 251).toByte() }

    @Test
    fun deviceIds() {
        for (case in v.getValue("device_ids").jsonArray.map { it.jsonObject }) {
            assertEquals(case.str("id"), DeviceId.of(V2.unb64(case.str("cert"))))
        }
    }

    @Test
    fun fileDigests() {
        for (case in v.getValue("file_digests").jsonArray.map { it.jsonObject }) {
            val size = case.getValue("size").jsonPrimitive.int
            val cs = case.getValue("chunk_size").jsonPrimitive.long
            val data = pattern(size)
            assertTrue(V2.validChunkSize(cs))
            assertEquals(case.getValue("chunk_count").jsonPrimitive.long, V2.chunkCount(size.toLong(), cs))
            val (tree, chunks) = V2.fileDigests(ByteArrayInputStream(data), cs.toInt())
            assertEquals(case.getValue("chunks").jsonArray.map { it.jsonPrimitive.content }, chunks)
            assertEquals(case.str("digest"), tree)
            assertEquals(case.str("plain_sha256"), V2.chunkDigest(data))
            chunks.forEachIndexed { n, d ->
                val (start, length) = V2.chunkSpan(n.toLong(), size.toLong(), cs)
                assertEquals(d, V2.chunkDigest(data, start.toInt(), length.toInt()))
            }
        }
    }

    @Test
    fun proofs() {
        for (case in v.getValue("proofs").jsonArray.map { it.jsonObject }) {
            val msg = V2.proofMessage(case.str("purpose"), case.str("receiver_id"), case.str("sender_id"),
                case.str("nonce"), case.str("subject"))
            assertEquals(case.str("message"), msg.toString(Charsets.US_ASCII), case.str("name"))
            val ok = V2.verifyProof(V2.unb64(case.str("cert")), case.str("signature"), msg)
            assertEquals(case.getValue("valid").jsonPrimitive.boolean, ok, case.str("name"))
        }
    }

    @Test
    fun proofRoundTripWithTheTestKey() {
        val ids = v.getValue("identities").jsonObject
        val sender = ids.getValue("sender").jsonObject
        val pem = sender.str("key_pem").lines().filter { !it.startsWith("-----") }.joinToString("")
        val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(V2.unb64(pem)))
        val nonce = V2.b64url(ByteArray(16).also { SecureRandom().nextBytes(it) })
        val msg = V2.proofMessage("session", ids.getValue("receiver").jsonObject.str("id"), sender.str("id"), nonce,
            "q3V9xY2kL0aB7cD4eF6gH8")
        val sig = V2.signProof(key, msg)
        val cert = V2.unb64(sender.str("cert"))
        assertTrue(V2.verifyProof(cert, sig, msg))
        assertFalse(V2.verifyProof(cert, sig, msg.copyOf().also { it[11] = 'x'.code.toByte() }))
        assertFalse(V2.verifyProof(cert, "not base64!", msg))
    }

    @Test
    fun proofMessageRefusesBadFields() {
        val ids = v.getValue("identities").jsonObject
        val a = ids.getValue("sender").jsonObject.str("id")
        val b = ids.getValue("receiver").jsonObject.str("id")
        val nonce = V2.b64url(ByteArray(16))
        assertFailsWith<IllegalArgumentException> { V2.proofMessage("upload", b, a, nonce) }
        assertFailsWith<IllegalArgumentException> { V2.proofMessage("pair", b.lowercase(), a, nonce) }
        assertFailsWith<IllegalArgumentException> { V2.proofMessage("pair", b, a, "short") }
        assertFailsWith<IllegalArgumentException> { V2.proofMessage("session", b, a, nonce, "has\nnewline") }
    }

    @Test
    fun pairMac() {
        val case = v.getValue("pair_mac").jsonObject
        val secret = V2.unb64url(case.str("secret"))
        assertEquals(16, secret.size)
        assertEquals(case.str("mac"), V2.pairMac(secret, case.str("shower_id"), case.str("scanner_id")))
        assertNotEquals(case.str("mac"), V2.pairMac(secret, case.str("scanner_id"), case.str("shower_id")))
    }

    @Test
    fun batch() {
        val case = v.getValue("batch").jsonObject
        val files = case.getValue("files").jsonArray.map { it.jsonObject }
            .map { it.str("file") to it.str("data_hex").hexToBytes() }
        val body = V2.encodeBatch(files)
        assertEquals(case.str("body_hex"), body.toHex())
        val got = ArrayList<Pair<V2.BatchHeader, ByteArray>>()
        V2.readBatch(ByteArrayInputStream(body), 1L shl 20) { h, d -> got += h to d }
        assertEquals(files.map { it.first }, got.map { it.first.file })
        files.indices.forEach { assertTrue(files[it].second.contentEquals(got[it].second)) }
        assertEquals(got.map { V2.chunkDigest(it.second) }, got.map { it.first.sha256 })
    }

    @Test
    fun brokenBatches() {
        val body = V2.encodeBatch(listOf("0" to "hello\n".toByteArray()))
        val tooLong = ByteArray(4).also { it[2] = 0x10; it[3] = 0x01 } + ByteArray(4097) { '{'.code.toByte() }
        for (bad in listOf(body.copyOf(body.size - 4), body.copyOf(body.size - 10), tooLong)) {
            assertFailsWith<V2.BatchException> { V2.readBatch(ByteArrayInputStream(bad), 1L shl 20) { _, _ -> } }
        }
        assertFailsWith<V2.BatchException> { V2.readBatch(ByteArrayInputStream(body), 5) { _, _ -> } }
    }

    @Test
    fun chunkHelpers() {
        assertFalse(V2.validChunkSize(1L shl 19))
        assertFalse(V2.validChunkSize(3L shl 20))
        assertFalse(V2.validChunkSize(1L shl 27))
        assertTrue(V2.validChunkSize(V2.DEFAULT_CHUNK))
        assertEquals(2097152L to 524288L, V2.chunkSpan(2, 2621440, 1L shl 20))
        assertFailsWith<IllegalArgumentException> { V2.chunkSpan(3, 2621440, 1L shl 20) }
        assertFailsWith<IllegalArgumentException> { V2.chunkSpan(0, 0, 1L shl 20) }
    }
}
