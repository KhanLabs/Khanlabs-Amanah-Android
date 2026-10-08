package dev.khanlabs.lanshare.net

import dev.khanlabs.lanshare.net.V2TestClient.Companion.entry
import dev.khanlabs.lanshare.net.V2TestClient.Companion.ids
import dev.khanlabs.lanshare.net.V2TestClient.Companion.rangesOf
import dev.khanlabs.lanshare.protocol.FileEntry
import dev.khanlabs.lanshare.protocol.Summary
import dev.khanlabs.lanshare.protocol.V2
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The v2 receiver (PROTOCOL-v2.md) against a hand-written client, over real TLS on 127.0.0.1. */
class ReceiverV2Test {
    private lateinit var tmp: File
    private lateinit var inbox: File
    private lateinit var parts: File
    private lateinit var rx: Receiver
    private lateinit var answers: ConformanceAnswers
    private lateinit var c: V2TestClient
    private val identity = Identity.create("v2-receiver")
    private val receiving = AtomicBoolean(true)
    private val chunk = V2.DEFAULT_CHUNK.toInt()

    @BeforeTest
    fun start() {
        tmp = Files.createTempDirectory("klshare-v2-").toFile()
        inbox = File(tmp, "inbox")
        parts = File(tmp, "parts")
        launch()
    }

    private fun launch() {
        val (r, a) = startConformanceReceiver(inbox, 47980, identity = identity, v2Dir = parts, receiving = { receiving.get() })
        rx = r
        answers = a
        c = if (::c.isInitialized) V2TestClient(rx.port, identity.id, c.me) else V2TestClient(rx.port, identity.id)
    }

    @AfterTest
    fun stop() {
        rx.stop()
        tmp.deleteRecursively()
    }

    private fun sid() = V2.b64url(Random.nextBytes(16))
    private fun bytes(n: Int, seed: Int = 1) = Random(seed).nextBytes(n)

    /** Makes a session the receiver accepts by itself, and waits until it says so. Returns its token. */
    private fun accepted(sid: String, files: List<FileEntry>, name: String = "Test sender", text: String? = null): String {
        val r = c.create(sid, files, text = text, senderName = name)
        assertTrue(r.code == 202 || r.code == 200, r.toString())
        val token = r.text("token")!!
        waitFor(token, sid) { it.text("state") == "accepted" || it.text("state") == "done" }
        return token
    }

    private fun waitFor(token: String, sid: String, seconds: Long = 10, until: (V2TestClient.Resp) -> Boolean): V2TestClient.Resp {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (true) {
            val r = c.state(token, sid)
            if (until(r)) return r
            check(System.nanoTime() < end) { "gave up waiting; last state: $r" }
            Thread.sleep(25)
        }
    }

    private fun saved(name: String) = File(inbox, name)

    // --- the basics

    @Test
    fun infoSaysWhatTheDeviceCanDoAndGivesANewNonceEachTime() {
        val a = c.info()
        val b = c.info()
        assertEquals(identity.id, a.id)
        assertEquals(listOf(1, 2), a.protocols)
        assertTrue(a.receiving)
        assertEquals(setOf("proof", "resume", "batch", "pair"), a.features.toSet())
        assertEquals(V2.DEFAULT_CHUNK, a.limits.chunkSize)
        assertEquals(4, a.limits.maxStreams)
        assertTrue(a.nonce != b.nonce)
        assertEquals(listOf(1, 2), rx.info().protocols, "the v1 info lists v2 too")
    }

    @Test
    fun aReceiverWithoutV2KnowsNoV2Paths() {
        val (plain, _) = startConformanceReceiver(File(tmp, "plain"), 47990, identity = Identity.create("plain"))
        try {
            val r = V2TestClient(plain.port, plain.info().id).get("/info")
            assertEquals(404, r.code)
            assertNull(plain.info().protocols)
        } finally {
            plain.stop()
        }
    }

    @Test
    fun aSmallFileGoesThroughFromOfferToSaved() {
        val data = bytes(1000)
        val sid = sid()
        val created = c.create(sid, listOf(entry("0", "hello.txt", 1000, 1_700_000_000_000)))
        assertEquals(202, created.code, created.toString())
        assertEquals("pending", created.text("state"))
        assertEquals(V2.DEFAULT_CHUNK, created.text("chunk_size")!!.toLong())
        assertEquals("true", created.text("manifest_complete"))
        assertNotNull(created.text("token"))
        assertNotNull(created["expires_in"])
        val token = created.text("token")!!
        waitFor(token, sid) { it.text("state") == "accepted" }
        val done = c.sendFile(token, sid, "0", data)
        assertEquals(200, done.code, done.toString())
        assertEquals("hello.txt", done.text("name"))
        assertContentEquals(data, saved("hello.txt").readBytes())
        assertEquals(1_700_000_000L, saved("hello.txt").lastModified() / 1000, "the sender's date is kept")
        val end = waitFor(token, sid) { it.text("state") == "done" }
        assertEquals("hello.txt", end["files"]!!.jsonObject["0"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        val offer = answers.finished.poll(5, TimeUnit.SECONDS)!!
        assertEquals("done", offer.state)
        assertEquals(1, offer.files.size)
        assertEquals("hello.txt", offer.files[0].savedName)
        assertTrue(File(parts, sid).let { !it.exists() }, "the parts and journal are gone")
    }

    @Test
    fun aBigFileInSeveralChunksMayArriveInAnyOrder() {
        val data = bytes(2 * chunk + 17, seed = 2)
        val sid = sid()
        val token = accepted(sid, listOf(entry("0", "big.bin", data.size.toLong())))
        fun part(n: Int) = data.copyOfRange(n * chunk, minOf(data.size, (n + 1) * chunk))
        assertEquals(201, c.putChunk(token, sid, "0", 2, part(2)).code)
        assertEquals(201, c.putChunk(token, sid, "0", 0, part(0)).code)
        val mid = c.state(token, sid)
        assertEquals(listOf(listOf(0L, 1L), listOf(2L, 3L)), rangesOf(mid["files"]!!.jsonObject["0"]!!.jsonObject["chunks"]))
        assertEquals("partial", mid["files"]!!.jsonObject["0"]!!.jsonObject["state"]!!.jsonPrimitive.content)
        assertEquals(201, c.putChunk(token, sid, "0", 1, part(1)).code)
        assertEquals(200, c.done(token, sid, "0", data).code)
        assertContentEquals(data, saved("big.bin").readBytes())
    }

    @Test
    fun aZeroByteFileAndAnEmptyFolderNeedNoChunks() {
        val sid = sid()
        val token = accepted(sid, listOf(entry("0", "photos/empty.txt", 0), FileEntry("1", "photos/empty folder", 0, kind = "dir")))
        val done = c.done(token, sid, "0", ByteArray(0))
        assertEquals(200, done.code, done.toString())
        assertEquals(0L, saved("photos/empty.txt").length())
        waitFor(token, sid) { it.text("state") == "done" }
        assertTrue(saved("photos/empty folder").isDirectory, "the empty folder was made")
    }

    // --- chunk rules

    @Test
    fun aChunkThatBreaksTheRulesIsRefusedWithTheRightCode() {
        val data = bytes(chunk + 100, seed = 3)
        val sid = sid()
        val token = accepted(sid, listOf(entry("0", "f.bin", data.size.toLong())))
        val first = data.copyOfRange(0, chunk)
        assertEquals("bad_chunk", c.putChunk(token, sid, "0", 5, first).error)
        assertEquals("bad_chunk", c.putChunk(token, sid, "0", -1, first).error)
        assertEquals("wrong_size", c.putChunk(token, sid, "0", 0, first.copyOf(10)).error)
        assertEquals("bad_digest", c.putChunk(token, sid, "0", 0, first, digest = "").error)
        assertEquals("bad_digest", c.putChunk(token, sid, "0", 0, first, digest = "zz").error)
        val bad = c.putChunk(token, sid, "0", 0, first, digest = V2.chunkDigest(byteArrayOf(1, 2, 3)))
        assertEquals(422, bad.code)
        assertEquals("digest_mismatch", bad.error)
        assertNull(c.state(token, sid)["files"], "a bad chunk is thrown away: nothing is recorded")
        assertEquals("no_file", c.putChunk(token, sid, "9", 0, first).error)
        assertEquals(201, c.putChunk(token, sid, "0", 0, first).code)
    }

    @Test
    fun aChunkSentTwiceIsSafeUnlessTheFileChanged() {
        val data = bytes(chunk + 5, seed = 4)
        val sid = sid()
        val token = accepted(sid, listOf(entry("0", "f.bin", data.size.toLong())))
        val first = data.copyOfRange(0, chunk)
        assertEquals(201, c.putChunk(token, sid, "0", 0, first).code)
        assertEquals(200, c.putChunk(token, sid, "0", 0, first).code)
        val other = bytes(chunk, seed = 99)
        val changed = c.putChunk(token, sid, "0", 0, other)
        assertEquals(409, changed.code)
        assertEquals("chunk_changed", changed.error)
        // the earlier chunk is still the one that counts
        assertEquals(201, c.putChunk(token, sid, "0", 1, data.copyOfRange(chunk, data.size)).code)
        assertEquals(200, c.done(token, sid, "0", data).code)
        assertContentEquals(data, saved("f.bin").readBytes())
    }

    @Test
    fun doneWithChunksMissingListsThem() {
        val data = bytes(3 * chunk, seed = 5)
        val sid = sid()
        val token = accepted(sid, listOf(entry("0", "f.bin", data.size.toLong())))
        assertEquals(201, c.putChunk(token, sid, "0", 1, data.copyOfRange(chunk, 2 * chunk)).code)
        val r = c.done(token, sid, "0", data)
        assertEquals(409, r.code)
        assertEquals("missing_chunks", r.error)
        assertEquals(listOf(listOf(0L, 1L), listOf(2L, 3L)), rangesOf(r["missing"]))
        assertFalse(saved("f.bin").exists())
    }

    @Test
    fun aWrongFileDigestMakesTheReceiverForgetTheChunks() {
        val data = bytes(chunk + 1, seed = 6)
        val sid = sid()
        val token = accepted(sid, listOf(entry("0", "f.bin", data.size.toLong())))
        assertEquals(201, c.putChunk(token, sid, "0", 0, data.copyOfRange(0, chunk)).code)
        assertEquals(201, c.putChunk(token, sid, "0", 1, data.copyOfRange(chunk, data.size)).code)
        val wrong = c.postJson("/sessions/$sid/files/0/done", """{"alg":"sha256-tree-v1","digest":"${"0".repeat(64)}"}""", token)
        assertEquals(422, wrong.code)
        assertEquals("digest_mismatch", wrong.error)
        assertNull(c.state(token, sid)["files"], "the chunks were thrown away")
        assertEquals("bad_digest", c.postJson("/sessions/$sid/files/0/done", """{"alg":"md5","digest":"x"}""", token).error)
        assertEquals(200, c.sendFile(token, sid, "0", data).code, "and the file can be sent again")
        assertContentEquals(data, saved("f.bin").readBytes())
    }

    @Test
    fun doneAgainForASavedFileGivesTheSameAnswerAndNewChunksAreFinished() {
        val data = bytes(500, seed = 7)
        val sid = sid()
        val token = accepted(sid, listOf(entry("0", "a.txt", 500), entry("1", "b.txt", 5)))
        val first = c.sendFile(token, sid, "0", data)
        val second = c.done(token, sid, "0", data)
        assertEquals(200, second.code)
        assertEquals(first.text("name"), second.text("name"))
        val late = c.putChunk(token, sid, "0", 0, data)
        assertEquals(409, late.code)
        assertEquals("finished", late.error)
    }

    // --- batches

    @Test
    fun smallFilesGoInOneBatchAndASentBatchCanBeSentAgain() {
        val a = bytes(10, 1)
        val b = bytes(2000, 2)
        val cc = bytes(300, 3)
        val sid = sid()
        val token = accepted(sid, listOf(entry("a", "notes/a.txt", 10), entry("b", "notes/b.txt", 2000), entry("c", "notes/c.txt", 300)))
        val r = c.batch(token, sid, listOf("a" to a, "b" to b, "c" to cc))
        assertEquals(200, r.code, r.toString())
        val results = r["results"]!!.jsonObject
        assertEquals("notes/a.txt", results["a"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertContentEquals(b, saved("notes/b.txt").readBytes())
        val again = c.batch(token, sid, listOf("a" to a, "b" to b, "c" to cc)) // a lost reply: the whole batch again
        assertEquals(200, again.code)
        assertEquals("true", again["results"]!!.jsonObject["a"]!!.jsonObject["ok"]!!.jsonPrimitive.content)
        assertEquals(1, saved("notes").list()!!.count { it.startsWith("a") }, "no (1) copy")
        waitFor(token, sid) { it.text("state") == "done" }
    }

    @Test
    fun aBadFileInABatchFailsAloneAndCanBeSentAgain() {
        val good = bytes(20, 4)
        val broken = bytes(30, 5)
        val sid = sid()
        val token = accepted(sid, listOf(entry("g", "g.txt", 20), entry("x", "x.txt", 30), entry("w", "w.txt", 7), entry("c", "c.bin", 100)))
        val enc = V2.encodeBatch(listOf("x" to broken))
        val headerLength = ByteBuffer.wrap(enc, 0, 4).int
        enc[4 + headerLength] = (enc[4 + headerLength] + 1).toByte() // one byte of the file changed in transit
        val mixed = V2.encodeBatch(listOf("g" to good)).let { it.copyOf(it.size - 4) } + enc.copyOf(enc.size - 4) +
            V2.encodeBatch(listOf("w" to bytes(8))).let { it.copyOf(it.size - 4) } + ByteArray(4)
        val r = c.postBytes("/sessions/$sid/batch", mixed, "application/x-kl-batch", token)
        assertEquals(200, r.code, r.toString())
        val res = r["results"]!!.jsonObject
        assertEquals("true", res["g"]!!.jsonObject["ok"]!!.jsonPrimitive.content)
        assertEquals("digest_mismatch", res["x"]!!.jsonObject["error"]!!.jsonPrimitive.content)
        assertEquals("wrong_size", res["w"]!!.jsonObject["error"]!!.jsonPrimitive.content)
        assertFalse(saved("x.txt").exists())
        // a file that already has chunks may not go in a batch
        assertEquals(201, c.putChunk(token, sid, "c", 0, bytes(100, 9)).code)
        assertEquals("chunks_sent", c.batch(token, sid, listOf("c" to bytes(100, 9)))["results"]!!.jsonObject["c"]!!.jsonObject["error"]!!.jsonPrimitive.content)
        assertEquals("no_file", c.batch(token, sid, listOf("nope" to bytes(5)))["results"]!!.jsonObject["nope"]!!.jsonObject["error"]!!.jsonPrimitive.content)
        val retry = c.batch(token, sid, listOf("x" to broken))
        assertEquals("true", retry["results"]!!.jsonObject["x"]!!.jsonObject["ok"]!!.jsonPrimitive.content)
        assertContentEquals(broken, saved("x.txt").readBytes())
    }

    @Test
    fun aBatchThatBreaksTheFormatOrIsTooBigIsRefused() {
        val sid = sid()
        val token = accepted(sid, listOf(entry("a", "a.txt", 10)))
        val cut = V2.encodeBatch(listOf("a" to bytes(10))).let { it.copyOf(it.size - 9) } // ends in the middle of the file
        assertEquals("bad_batch", c.postBytes("/sessions/$sid/batch", cut, "application/x-kl-batch", token).error)
        assertEquals("bad_batch", c.postBytes("/sessions/$sid/batch", "not a batch at all".toByteArray(), "application/x-kl-batch", token).error)
        val big = c.postBytes("/sessions/$sid/batch", ByteArray(33 shl 20), "application/x-kl-batch", token)
        assertEquals(413, big.code)
        assertEquals("too_large", big.error)
        assertEquals("true", c.batch(token, sid, listOf("a" to bytes(10)))["results"]!!.jsonObject["a"]!!.jsonObject["ok"]!!.jsonPrimitive.content)
    }

    // --- the file list in pages

    @Test
    fun aListInPagesCanBeAcceptedBeforeItsLastPage() {
        val sid = sid()
        val all = (0 until 3).map { entry("$it", "dir/f$it.txt", 10) }
        val r = c.create(sid, all.take(1), more = true, summary = Summary(3, 30))
        assertEquals(202, r.code, r.toString())
        assertEquals("false", r.text("manifest_complete"))
        val token = r.text("token")!!
        waitFor(token, sid) { it.text("state") == "accepted" }
        assertEquals(200, c.sendFile(token, sid, "0", bytes(10)).code)
        assertEquals("no_file", c.putChunk(token, sid, "1", 0, bytes(10)).error, "the page with file 1 has not arrived")
        assertEquals(200, c.manifest(token, sid, all.subList(1, 2), more = true).code)
        val last = c.manifest(token, sid, all.subList(2, 3), more = false)
        assertEquals(200, last.code, last.toString())
        assertEquals("3", last.text("count"))
        assertEquals("manifest_complete", c.manifest(token, sid, all.take(1), more = false).error)
        assertEquals(409, c.manifest(token, sid, emptyList(), more = false).code)
        assertEquals(200, c.sendFile(token, sid, "1", bytes(10)).code)
        assertEquals("accepted", c.state(token, sid).text("state"), "not done while a file is missing")
        assertEquals(200, c.sendFile(token, sid, "2", bytes(10)).code)
        waitFor(token, sid) { it.text("state") == "done" }
    }

    @Test
    fun filesInAPageThatComesAfterTheChoiceAreNotChosenAndTheSessionStillEnds() {
        answers.chooser = { setOf("0") }
        val sid = sid()
        val all = (0 until 3).map { entry("$it", "dir/f$it.txt", 10) }
        val token = c.create(sid, all.take(1), more = true, summary = Summary(3, 30)).text("token")!!
        waitFor(token, sid) { it.text("state") == "accepted" }
        assertEquals(200, c.manifest(token, sid, all.drop(1), more = false).code)
        assertEquals("not_wanted", c.putChunk(token, sid, "1", 0, bytes(10)).error)
        assertEquals(200, c.sendFile(token, sid, "0", bytes(10)).code)
        waitFor(token, sid) { it.text("state") == "done" }
    }

    @Test
    fun aListThatDoesNotAddUpToItsSummaryCancelsTheSession() {
        val sid = sid()
        val r = c.create(sid, listOf(entry("0", "a.txt", 10)), more = true, summary = Summary(3, 30))
        val token = r.text("token")!!
        val last = c.manifest(token, sid, listOf(entry("1", "b.txt", 10)), more = false)
        assertEquals(400, last.code)
        assertEquals("summary_mismatch", last.error)
        assertEquals("cancelled", c.state(token, sid).text("state"))
    }

    @Test
    fun aSingleBadEntryOrADuplicateIdRefusesTheWholePage() {
        val sid = sid()
        val token = c.create(sid, listOf(entry("0", "a.txt", 10)), more = true, summary = Summary(3, 30)).text("token")!!
        assertEquals("bad_files", c.manifest(token, sid, listOf(entry("1", "b.txt", 10), entry("0", "c.txt", 10)), more = true).error)
        assertEquals("bad_files", c.manifest(token, sid, listOf(entry("1", "b.txt", 10), entry("1", "c.txt", 10)), more = true).error)
        assertEquals("bad_files", c.manifest(token, sid, listOf(entry("1", "b.txt", 10), entry("2", "../c.txt", 10)), more = true).error)
        // nothing of the refused pages was kept: id 1 is free again
        assertEquals(200, c.manifest(token, sid, listOf(entry("1", "b.txt", 10), entry("2", "c.txt", 10)), more = false).code)
    }

    // --- creating a session

    @Test
    fun theSameSessionAgainGivesTheSameTokenAndStateAnotherSenderOrListIsRefused() {
        val sid = sid()
        val files = listOf(entry("0", "a.txt", 10))
        val first = c.create(sid, files, senderName = "A [wait]")
        val again = c.create(sid, emptyList(), summary = Summary(1, 10), senderName = "A [wait]")
        assertEquals(202, again.code, again.toString())
        assertEquals(first.text("token"), again.text("token"))
        assertEquals("pending", again.text("state"))
        val stranger = c.create(sid, files, as_ = Identity.create("stranger"))
        assertEquals(409, stranger.code)
        assertEquals("duplicate", stranger.error)
        val changed = c.create(sid, emptyList(), summary = Summary(1, 11))
        assertEquals(409, changed.code)
        assertEquals("changed", changed.error)
    }

    @Test
    fun theProofIsRequiredAndAnAnswerToThePersonIsNeverGivenWithoutIt() {
        val sid = sid()
        val files = listOf(entry("0", "a.txt", 10))
        assertEquals("bad_proof", c.create(sid, files) { it.copy(proof = "AAAA") }.error)
        assertEquals("bad_proof", c.create(sid, files) { it.copy(sender = it.sender.copy(id = "0".repeat(64))) }.error)
        assertEquals("bad_nonce", c.create(sid, files) { it.copy(nonce = "never-issued-nonce-123456") }.error)
        assertEquals("bad_cert", c.create(sid, files) { it.copy(cert = "AAAA") }.error)
        assertEquals(0, answers.asked.size, "nobody was asked")
        // one nonce, two requests: the second is refused
        val nonce = c.nonce()
        val made = DeviceProof.make(c.me, identity.id, "session", nonce, sid)
        val body = """{"session":"$sid","sender":{"id":"${c.me.id}","name":"x","model":"android","port":1},"cert":"${made.cert}","nonce":"$nonce","proof":"${made.proof}","summary":{"count":1,"bytes":10},"files":[{"id":"0","name":"a.txt","size":10}],"more":false}"""
        assertEquals(202, c.postJson("/sessions", body).code)
        assertEquals("bad_nonce", c.postJson("/sessions", body).error)
    }

    @Test
    fun badSessionsAreRefusedWithTheirCodes() {
        val ok = listOf(entry("0", "a.txt", 10))
        assertEquals("bad_session", c.create(sid(), ok) { it.copy(session = "short") }.error)
        assertEquals("bad_session", c.create(sid(), ok) { it.copy(session = "has spaces in it 0123456789") }.error)
        assertEquals("empty", c.create(sid(), emptyList(), summary = Summary(0, 0)).error)
        assertEquals("bad_text", c.create(sid(), ok, text = "x".repeat(65 * 1024)).error)
        val tooMany = c.create(sid(), emptyList(), more = true, summary = Summary(ReceiverV2.MAX_ENTRIES + 1L, 10))
        assertEquals(413, tooMany.code)
        assertEquals("too_many", tooMany.error)
        assertEquals("bad_json", c.postJson("/sessions", "{not json").error)
        assertEquals(400, c.postJson("/sessions", "[]").code)
        for (bad in listOf("../x.txt", "/abs.txt", "a\\b.txt", "", "a//b.txt", "tab\there.txt", "a/./b.txt")) {
            val r = c.create(sid(), listOf(entry("0", bad, 1)))
            assertEquals("bad_files", r.error, "name '${bad.replace("\t", "\\t")}': $r")
        }
        assertEquals("bad_files", c.create(sid(), listOf(entry("0", "a.txt", 1), entry("0", "b.txt", 1))).error)
        assertEquals("bad_files", c.create(sid(), listOf(entry("bad id!", "a.txt", 1))).error)
        assertEquals("bad_files", c.create(sid(), listOf(entry("0", "a.txt", -1))).error)
        assertEquals("bad_files", c.create(sid(), listOf(FileEntry("0", "d", 5, kind = "dir"))).error)
        assertEquals("bad_files", c.create(sid(), listOf(FileEntry("0", "d", 0, kind = "symlink"))).error)
        assertEquals(0, answers.asked.size)
    }

    @Test
    fun aNameThatIsFineForV2ButNotForWindowsIsMadeSafeAndReported() {
        val sid = sid()
        val token = accepted(sid, listOf(entry("0", "pics/a:b?.txt", 5)))
        val done = c.sendFile(token, sid, "0", bytes(5))
        assertEquals("pics/a_b_.txt", done.text("name"))
        assertTrue(saved("pics/a_b_.txt").isFile)
    }

    @Test
    fun aNameThatIsTakenGetsANumberAndNothingIsReplaced() {
        saved("x.txt").also { it.parentFile.mkdirs() }.writeText("mine")
        val sid = sid()
        val token = accepted(sid, listOf(entry("0", "x.txt", 5)))
        assertEquals("x (1).txt", c.sendFile(token, sid, "0", bytes(5)).text("name"))
        assertEquals("mine", saved("x.txt").readText())
    }

    @Test
    fun aTextOnlySessionIsDoneWhenAccepted() {
        val sid = sid()
        val r = c.create(sid, emptyList(), text = "Gate B12", summary = Summary(0, 0))
        assertEquals(202, r.code, r.toString())
        waitFor(r.text("token")!!, sid) { it.text("state") == "done" }
        val offer = answers.finished.poll(5, TimeUnit.SECONDS)!!
        assertEquals("Gate B12", offer.text)
    }

    @Test
    fun receivingOffAndTooManyWaitingSessionsAreRefused() {
        receiving.set(false)
        assertEquals("not_receiving", c.create(sid(), listOf(entry("0", "a.txt", 1))).error)
        receiving.set(true)
        val one = c.create(sid(), listOf(entry("0", "a.txt", 1)), senderName = "[wait] 1")
        val two = c.create(sid(), listOf(entry("0", "a.txt", 1)), senderName = "[wait] 2")
        assertEquals(202, one.code)
        assertEquals(202, two.code)
        val three = c.create(sid(), listOf(entry("0", "a.txt", 1)), senderName = "[wait] 3")
        assertEquals(429, three.code)
        assertEquals("busy", three.error)
    }

    @Test
    fun aSessionBiggerThanTheFreeSpaceIsRefused() {
        val r = c.create(sid(), emptyList(), more = true, summary = Summary(1, Long.MAX_VALUE / 4))
        assertEquals(507, r.code, r.toString())
        assertEquals("no_space", r.error)
    }

    // --- the person's answer, the long-poll and ending a session

    @Test
    fun aDeclinedSessionIsOverForUploads() {
        val sid = sid()
        val r = c.create(sid, listOf(entry("0", "a.txt", 5)), senderName = "X [decline]")
        val token = r.text("token")!!
        waitFor(token, sid) { it.text("state") == "declined" }
        val up = c.putChunk(token, sid, "0", 0, bytes(5))
        assertEquals(410, up.code)
        assertEquals("declined", up.error)
        assertFalse(saved("a.txt").exists())
    }

    @Test
    fun uploadsBeforeTheAnswerAreRefusedAndTheSenderCancelsWithDelete() {
        val sid = sid()
        val r = c.create(sid, listOf(entry("0", "a.txt", 5)), senderName = "X [wait]")
        val token = r.text("token")!!
        val early = c.putChunk(token, sid, "0", 0, bytes(5))
        assertEquals(409, early.code)
        assertEquals("not_accepted", early.error)
        assertEquals("true", c.delete("/sessions/$sid", token).text("ok"))
        assertEquals("cancelled", c.state(token, sid).text("state"))
        assertEquals("cancelled", c.putChunk(token, sid, "0", 0, bytes(5)).error)
        assertEquals("true", c.delete("/sessions/$sid", token).text("ok"), "cancelling twice is fine")
        assertEquals("cancelled", answers.finished.poll(5, TimeUnit.SECONDS)!!.state)
    }

    @Test
    fun theLongPollWaitsForAChangeAndTimesOutWithTheCurrentState() {
        val sid = sid()
        val r = c.create(sid, listOf(entry("0", "a.txt", 5)), senderName = "X [wait]")
        val token = r.text("token")!!
        val version = r.text("version")!!.toLong()
        val t0 = System.nanoTime()
        val quiet = c.get("/sessions/$sid?wait=1&after=$version", token)
        assertTrue(System.nanoTime() - t0 > 700_000_000L, "it waited")
        assertEquals("pending", quiet.text("state"))
        Thread { Thread.sleep(300); rx.answer(sid, true) }.start()
        val t1 = System.nanoTime()
        val changed = c.get("/sessions/$sid?wait=10&after=$version", token)
        assertTrue(System.nanoTime() - t1 < 5_000_000_000L, "it did not wait for the whole 10 seconds")
        assertEquals("accepted", changed.text("state"))
        assertTrue(changed.text("version")!!.toLong() > version)
        assertEquals("accepted", c.get("/sessions/$sid?wait=99", token).text("state"), "no `after`: it answers at once")
    }

    @Test
    fun theStateCanLeaveOutTheFileList() {
        val sid = sid()
        val token = accepted(sid, listOf(entry("0", "a.txt", 5)))
        c.sendFile(token, sid, "0", bytes(5))
        assertNotNull(waitFor(token, sid) { it.text("state") == "done" }["files"])
        assertNull(c.state(token, sid, filesToo = false)["files"])
    }

    @Test
    fun theTokenIsCheckedAndUnknownSessionsAreNotFound() {
        val sid = sid()
        val token = accepted(sid, listOf(entry("0", "a.txt", 5)))
        assertEquals("bad_token", c.state("wrong-token-0123456789ab", sid).error)
        assertEquals("bad_token", c.get("/sessions/$sid").error)
        assertEquals("bad_token", c.putChunk("x", sid, "0", 0, bytes(5)).error)
        assertEquals("no_session", c.state(token, sid2()).error)
        assertEquals("bad_session", c.get("/sessions/short", token).error)
        assertEquals("not_found", c.get("/nothing").error)
        assertEquals("not_found", c.get("/sessions/$sid/whatever", token).error)
    }

    private fun sid2() = V2.b64url(ByteArray(16) { 7 })

    @Test
    fun aFileCanBeDroppedOrRestartedAndTheSessionStillFinishes() {
        val a = bytes(chunk + 3, 11)
        val sid = sid()
        val token = accepted(sid, listOf(entry("0", "a.bin", a.size.toLong()), entry("1", "b.txt", 4)))
        assertEquals(201, c.putChunk(token, sid, "0", 0, a.copyOfRange(0, chunk)).code)
        assertEquals("true", c.delete("/sessions/$sid/files/0/chunks", token).text("ok"))
        assertNull(c.state(token, sid)["files"], "the chunks are forgotten")
        assertEquals(200, c.sendFile(token, sid, "0", a).code)
        assertEquals("finished", c.delete("/sessions/$sid/files/0", token).error)
        assertEquals("finished", c.delete("/sessions/$sid/files/0/chunks", token).error)
        assertEquals("true", c.delete("/sessions/$sid/files/1", token).text("ok"))
        val end = waitFor(token, sid) { it.text("state") == "done" }
        assertEquals("skipped", end["files"]!!.jsonObject["1"]!!.jsonObject["state"]!!.jsonPrimitive.content)
        assertEquals("dropped", end["files"]!!.jsonObject["1"]!!.jsonObject["reason"]!!.jsonPrimitive.content)
        assertEquals("no_file", c.delete("/sessions/$sid/files/9", token).error)
    }

    @Test
    fun filesAlreadyHereAreLeftOutWhenThePersonKeepsSkipOn() {
        val data = bytes(5, 12)
        val sid1 = sid()
        val t1 = accepted(sid1, listOf(entry("0", "x.txt", 5, 1_700_000_000_000)))
        c.sendFile(t1, sid1, "0", data)
        val sid2 = sid()
        val r = c.create(sid2, listOf(entry("0", "x.txt", 5, 1_700_000_000_000), entry("1", "y.txt", 6)), senderName = "S [skip]")
        val t2 = r.text("token")!!
        val st = waitFor(t2, sid2) { it.text("state") == "accepted" }
        assertEquals("skipped", st["files"]!!.jsonObject["0"]!!.jsonObject["state"]!!.jsonPrimitive.content)
        assertEquals("already_here", st["files"]!!.jsonObject["0"]!!.jsonObject["reason"]!!.jsonPrimitive.content)
        assertEquals("finished", c.putChunk(t2, sid2, "0", 0, data).error)
        assertEquals(200, c.sendFile(t2, sid2, "1", bytes(6)).code)
        waitFor(t2, sid2) { it.text("state") == "done" }
        assertEquals(1, inbox.list()!!.count { it.startsWith("x") }, "no second copy of x.txt")
    }

    // --- a restart

    @Test
    fun aSessionComesBackAfterARestartAndTheSenderCarriesOnFromTheChunksThatArrived() {
        val data = bytes(3 * chunk + 9, seed = 21)
        fun part(n: Int) = data.copyOfRange(n * chunk, minOf(data.size, (n + 1) * chunk))
        val sid = sid()
        val files = listOf(entry("0", "big.bin", data.size.toLong(), 1_700_000_100_000), entry("1", "after.txt", 3))
        val token = accepted(sid, files)
        assertEquals(201, c.putChunk(token, sid, "0", 0, part(0)).code)
        assertEquals(201, c.putChunk(token, sid, "0", 2, part(2)).code)
        assertEquals(200, c.sendFile(token, sid, "1", bytes(3)).code)

        rx.stop() // the app is closed
        launch()
        val back = answers.resumed.poll(5, TimeUnit.SECONDS)
        assertNotNull(back, "the session came back without a prompt")
        assertEquals("accepted", back.state)
        assertEquals(0, answers.asked.size)

        val resumed = c.create(sid, emptyList(), summary = Summary(2, data.size + 3L)) // the same session again
        assertEquals(200, resumed.code, resumed.toString())
        assertEquals(token, resumed.text("token"), "the same token, though none was kept on disk")
        assertEquals("accepted", resumed.text("state"))
        val files0 = resumed["files"]!!.jsonObject
        assertEquals(listOf(listOf(0L, 1L), listOf(2L, 3L)), rangesOf(files0["0"]!!.jsonObject["chunks"]))
        assertEquals("done", files0["1"]!!.jsonObject["state"]!!.jsonPrimitive.content)

        assertEquals(201, c.putChunk(token, sid, "0", 1, part(1)).code)
        assertEquals(201, c.putChunk(token, sid, "0", 3, part(3)).code)
        assertEquals(200, c.done(token, sid, "0", data).code)
        assertContentEquals(data, saved("big.bin").readBytes())
        waitFor(token, sid) { it.text("state") == "done" }
    }

    @Test
    fun aChunkWhoseBytesNeverReachedTheDiskIsAskedForAgainAfterARestart() {
        val data = bytes(2 * chunk, seed = 22)
        val sid = sid()
        val token = accepted(sid, listOf(entry("0", "f.bin", data.size.toLong())))
        assertEquals(201, c.putChunk(token, sid, "0", 0, data.copyOfRange(0, chunk)).code)
        assertEquals(201, c.putChunk(token, sid, "0", 1, data.copyOfRange(chunk, 2 * chunk)).code)
        rx.stop()
        // the phone lost power before the second chunk was written out: the part is shorter than the journal says
        File(File(parts, sid), "0${ReceiverV2.PART}").let { java.io.RandomAccessFile(it, "rw").use { f -> f.setLength(chunk + 10L) } }
        launch()
        val r = c.create(sid, emptyList(), summary = Summary(1, data.size.toLong()))
        assertEquals(listOf(listOf(0L, 1L)), rangesOf(r["files"]!!.jsonObject["0"]!!.jsonObject["chunks"]))
    }

    @Test
    fun aSessionThatWasNotAcceptedOrIsOverIsForgottenAfterARestart() {
        val waiting = sid()
        val tw = c.create(waiting, listOf(entry("0", "a.txt", 5)), senderName = "X [wait]").text("token")!!
        val finished = sid()
        val tf = accepted(finished, listOf(entry("0", "b.txt", 5)))
        c.sendFile(tf, finished, "0", bytes(5))
        waitFor(tf, finished) { it.text("state") == "done" }
        rx.stop()
        launch()
        assertEquals("no_session", c.state(tw, waiting).error)
        assertEquals("no_session", c.state(tf, finished).error)
        assertEquals(0, answers.resumed.size)
        assertEquals(0, parts.list()!!.size, "their parts and journals are gone")
    }

    @Test
    fun theConnectionIsKeptForManyRequests() {
        val sid = sid()
        val token = accepted(sid, (0 until 30).map { entry("$it", "many/f$it.txt", 10) })
        for (i in 0 until 30) assertEquals(200, c.sendFile(token, sid, "$i", bytes(10, i)).code)
        waitFor(token, sid) { it.text("state") == "done" }
        assertEquals(30, saved("many").list()!!.size)
    }
}
