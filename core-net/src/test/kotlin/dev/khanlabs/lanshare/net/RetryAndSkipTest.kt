package dev.khanlabs.lanshare.net

import dev.khanlabs.lanshare.protocol.ApiError
import dev.khanlabs.lanshare.protocol.DeviceId
import dev.khanlabs.lanshare.protocol.OfferAccepted
import dev.khanlabs.lanshare.protocol.OfferFile
import dev.khanlabs.lanshare.protocol.OfferRequest
import dev.khanlabs.lanshare.protocol.ProtocolJson
import dev.khanlabs.lanshare.protocol.Sender
import dev.khanlabs.lanshare.protocol.UploadReply
import dev.khanlabs.lanshare.protocol.V1
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Sends that break and start again inside the same offer, files that are already there, and the SHA-256 check
 * (PROTOCOL.md 3.2 and 3.3), the Kotlin sender against the Kotlin receiver over real TLS on 127.0.0.1.
 */
class RetryAndSkipTest {
    private lateinit var tmp: File
    private lateinit var inbox: File
    private lateinit var rx: Receiver
    private val clients = Clients()
    private val identity = Identity.create("test")
    private val json = "application/json".toMediaType()

    @BeforeTest
    fun start() {
        tmp = Files.createTempDirectory("klshare-retry-").toFile()
        inbox = File(tmp, "inbox")
        rx = startConformanceReceiver(inbox, 47930, identity = identity).first
    }

    @AfterTest
    fun stop() {
        rx.stop()
        tmp.deleteRecursively()
    }

    private fun device(port: Int = rx.port) = Device(identity.id, "Loopback", "android", listOf("127.0.0.1"), port)

    private fun me(name: String = "Test sender") = Me(DeviceId.of(ByteArray(1)), name, "android", "test")

    private fun item(name: String, data: ByteArray, modified: Long = 0L) =
        SendItem(name, data.size.toLong(), modified) { ByteArrayInputStream(data) }

    private fun sha(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun noPartials() = inbox.walk().none { FolderStore.isPartial(it.name) }

    // --- the SHA-256 check

    @Test
    fun sentFilesAreVerified() {
        val job = SendJob(clients, me(), device(), listOf(item("a.txt", "hello".toByteArray()), item("empty.txt", ByteArray(0))))
        job.run()
        assertEquals(SendJob.State.DONE, job.state, job.message)
        assertEquals(2, job.verified)
        assertEquals("Sent 2 files", job.message)
    }

    // --- files that are already there

    @Test
    fun skipsFilesTheReceiverAlreadyHas() {
        val a = "first".toByteArray()
        val b = "second file".toByteArray()
        val when1 = 1_700_000_000_000L
        SendJob(clients, me(), device(), listOf(item("docs/a.txt", a, when1))).run()
        assertEquals(when1 / 1000, File(inbox, "docs/a.txt").lastModified() / 1000, "the sender's date is kept")

        val job = SendJob(clients, me("Test [skip]"), device(), listOf(item("docs/a.txt", a, when1), item("docs/b.txt", b, when1)))
        job.run()
        assertEquals(SendJob.State.DONE, job.state, job.message)
        assertEquals(1, job.skipped)
        assertEquals(a.size.toLong(), job.skippedBytes)
        assertEquals(listOf("docs/b.txt"), job.saved)
        assertEquals(b.size.toLong(), job.total)
        assertEquals(job.total, job.sent)
        assertEquals("Sent 1 new file. 1 was already on Loopback", job.message)
        assertTrue(job.unfinished().isEmpty())
        assertEquals(setOf("a.txt", "b.txt"), File(inbox, "docs").list()!!.toSet(), "no (1) copies")
    }

    @Test
    fun sendsAgainWhenTheDateOrSizeDiffers() {
        val when1 = 1_700_000_000_000L
        SendJob(clients, me(), device(), listOf(item("a.txt", "one".toByteArray(), when1))).run()
        // changed a minute later: not the same file
        val later = SendJob(clients, me("Test [skip]"), device(), listOf(item("a.txt", "two".toByteArray(), when1 + 60_000)))
        later.run()
        assertEquals(0, later.skipped)
        assertEquals(listOf("a (1).txt"), later.saved)
        // another size: not the same file
        val longer = SendJob(clients, me("Test [skip]"), device(), listOf(item("a.txt", "three!".toByteArray(), when1)))
        longer.run()
        assertEquals(0, longer.skipped)
        assertEquals(listOf("a (2).txt"), longer.saved)
    }

    @Test
    fun everythingAlreadyThereIsNothingNewToSend() {
        val data = "same".toByteArray()
        SendJob(clients, me(), device(), listOf(item("a.txt", data), item("b.txt", data))).run()
        // no date on either side: the name and size decide
        val job = SendJob(clients, me("Test [skip]"), device(), listOf(item("a.txt", data), item("b.txt", data)))
        job.run()
        assertEquals(SendJob.State.DONE, job.state, job.message)
        assertEquals("Nothing new to send. All 2 files were already on Loopback", job.message)
        assertEquals(2, job.skipped)
        assertEquals(0L, job.total)
        assertTrue(job.saved.isEmpty())
        assertEquals(setOf("a.txt", "b.txt"), inbox.list()!!.toSet())
    }

    @Test
    fun keepsEverythingWhenTheBoxIsNotTicked() {
        val data = "same".toByteArray()
        SendJob(clients, me(), device(), listOf(item("a.txt", data))).run()
        val job = SendJob(clients, me(), device(), listOf(item("a.txt", data))) // the receiver does not ask to skip
        job.run()
        assertEquals(0, job.skipped)
        assertEquals(listOf("a (1).txt"), job.saved)
    }

    // --- a connection that breaks

    @Test
    fun aCutUploadStartsAgainInTheSameOffer() {
        val big = Random(7).nextBytes(3 * 1024 * 1024)
        CuttingProxy(rx.port, cutUploadAt = 1_000_000).use { proxy ->
            var waited = false
            val job = SendJob(clients, me(), device(proxy.port), listOf(item("big.bin", big)), onUpdate = { j ->
                if (j.reconnecting) waited = true
            })
            job.run()
            assertEquals(SendJob.State.DONE, job.state, job.message)
            assertEquals(1, proxy.cutsDone.get())
            assertTrue(waited, "the job said it was trying again")
            assertTrue(!job.reconnecting)
            assertEquals(listOf("big.bin"), job.saved)
            assertEquals(1, job.verified)
            assertEquals(job.total, job.sent)
            assertContentEquals(big, File(inbox, "big.bin").readBytes())
            assertEquals(listOf("big.bin"), inbox.list()!!.toList(), "no (1) copy and no partial file")
        }
    }

    @Test
    fun retryNowSkipsTheWait() {
        val big = Random(8).nextBytes(2 * 1024 * 1024)
        CuttingProxy(rx.port, cutUploadAt = 500_000).use { proxy ->
            val job = SendJob(clients, me(), device(proxy.port), listOf(item("big.bin", big)), onUpdate = { j ->
                if (j.reconnecting) j.retryNow()
            })
            val t0 = System.nanoTime()
            job.run()
            val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
            assertEquals(SendJob.State.DONE, job.state, job.message)
            assertEquals(1, proxy.cutsDone.get())
            assertTrue(ms < 2000, "took $ms ms, the first wait alone is 2000")
            assertContentEquals(big, File(inbox, "big.bin").readBytes())
        }
    }

    @Test
    fun aLostReplyForAFileThatArrivedCountsAsSent() {
        val big = Random(9).nextBytes(3 * 1024 * 1024)
        // the whole file arrives and is saved, but the "201" never reaches the sender
        CuttingProxy(rx.port, dropReplyAfter = 3_000_000).use { proxy ->
            val job = SendJob(clients, me(), device(proxy.port), listOf(item("big.bin", big)))
            job.run()
            assertEquals(SendJob.State.DONE, job.state, job.message)
            assertEquals(1, proxy.cutsDone.get())
            assertEquals(listOf("big.bin"), job.saved)
            assertEquals(0, job.verified, "nothing to check it against")
            assertEquals(job.total, job.sent)
            assertContentEquals(big, File(inbox, "big.bin").readBytes())
            assertEquals(listOf("big.bin"), inbox.list()!!.toList(), "no (1) copy")
        }
    }

    @Test
    fun cancelStopsTheWaiting() {
        val big = Random(10).nextBytes(2 * 1024 * 1024)
        CuttingProxy(rx.port, cutUploadAt = 200_000, cuts = 1000).use { proxy ->
            val job = SendJob(clients, me(), device(proxy.port), listOf(item("big.bin", big)))
            val t = thread { job.run() }
            var waited = 0
            while (!job.reconnecting && waited++ < 100) Thread.sleep(100)
            assertTrue(job.reconnecting, "the connection kept breaking")
            job.cancel()
            t.join(10_000)
            assertEquals(SendJob.State.CANCELLED, job.state, job.message)
            assertEquals(1, job.unfinished().size, "the file did not get there, so it can be sent in a new offer")
        }
    }

    @Test
    fun aReceiverThatCancelledEndsTheSendAtOnce() {
        val big = Random(12).nextBytes(8 * 1024 * 1024)
        CuttingProxy(rx.port, cutUploadAt = 200_000).use { proxy ->
            val job = SendJob(clients, me(), device(proxy.port), listOf(item("big.bin", big)))
            val t = thread { job.run() }
            var waited = 0
            while (!job.reconnecting && waited++ < 100) Thread.sleep(50)
            assertTrue(job.reconnecting, "the connection was cut")
            rx.cancel(job.offerId)
            t.join(15_000)
            assertTrue(!t.isAlive, "the send went on trying after the receiver cancelled")
            assertEquals(SendJob.State.FAILED, job.state, job.message)
            assertEquals(1, job.unfinished().size)
        }
    }

    // --- an old upload that went quiet

    @Test
    fun aNewUploadTakesOverFromOneThatWentQuiet() {
        val data = Random(11).nextBytes(1024 * 1024)
        val offerId = "takeover-test-offer-0001"
        val client = clients.pinned(identity.id)
        val offerBody = ProtocolJson.encodeToString(
            OfferRequest.serializer(),
            OfferRequest(offerId, Sender("x", "Raw", "android", "test"), listOf(OfferFile("0", "t.bin", data.size.toLong()))),
        ).toRequestBody(json)
        val token = client.newCall(Request.Builder().url("https://127.0.0.1:${rx.port}${V1.API}/offer").post(offerBody).build())
            .execute().use { ProtocolJson.decodeFromString(OfferAccepted.serializer(), it.body.string()).files.getValue("0") }

        fun put(body: RequestBody) = client.newCall(
            Request.Builder().url("https://127.0.0.1:${rx.port}${V1.API}/upload?offer=$offerId&file=0&token=$token").put(body).build(),
        ).execute().use { it.code to it.body.string() }

        // the first upload sends 100 KB and then goes silent, like a phone that left the Wi-Fi
        val quiet = CountDownLatch(1)
        val stuck = thread {
            try {
                put(object : RequestBody() {
                    override fun contentType() = "application/octet-stream".toMediaType()
                    override fun contentLength() = data.size.toLong()
                    override fun isOneShot() = true
                    override fun writeTo(sink: BufferedSink) {
                        sink.write(data, 0, 100 * 1024)
                        sink.flush()
                        quiet.await()
                        sink.write(data, 100 * 1024, data.size - 100 * 1024)
                    }
                })
            } catch (e: Exception) {
                // the receiver let go of this connection
            }
        }
        val file = rx.get(offerId)!!.files.first()
        var waited = 0
        while (file.received == 0L && waited++ < 50) Thread.sleep(100)
        assertTrue(file.received > 0, "the first upload started")

        val whole = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = data.size.toLong()
            override fun writeTo(sink: BufferedSink) {
                sink.write(data)
            }
        }
        val (busyCode, busyBody) = put(whole)
        assertEquals(409, busyCode)
        assertEquals("receiving", ProtocolJson.decodeFromString(ApiError.serializer(), busyBody).state)

        Thread.sleep(3300) // more than 3 seconds without a byte
        val (code, reply) = put(whole)
        assertEquals(201, code, reply)
        val ok = ProtocolJson.decodeFromString(UploadReply.serializer(), reply)
        assertEquals("t.bin", ok.name)
        assertEquals(sha(data), ok.sha256)

        // the old upload wakes up and is turned away; it leaves nothing behind
        quiet.countDown()
        stuck.join(10_000)
        waited = 0
        while (!noPartials() && waited++ < 50) Thread.sleep(100)
        assertEquals(listOf("t.bin"), inbox.list()!!.toList())
        assertContentEquals(data, File(inbox, "t.bin").readBytes())
        assertEquals("done", file.state)

        // asking again for a finished file says so, with the name it was saved as
        val (doneCode, doneBody) = put(whole)
        assertEquals(409, doneCode)
        val done = ProtocolJson.decodeFromString(ApiError.serializer(), doneBody)
        assertEquals("done", done.state)
        assertEquals("t.bin", done.name)
        assertNotNull(rx.get(offerId))
    }
}

/**
 * Passes bytes between a sender and the receiver and breaks the connection on purpose. Test code only. The bytes stay
 * encrypted end to end, so the sender still checks the receiver's certificate.
 */
private class CuttingProxy(
    private val target: Int,
    /** Close a connection once the sender has sent this many bytes on it. */
    private val cutUploadAt: Long = Long.MAX_VALUE,
    /** Close a connection instead of passing on an answer that comes after the sender sent this many bytes. */
    private val dropReplyAfter: Long = Long.MAX_VALUE,
    cuts: Int = 1,
) : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val cutsLeft = AtomicInteger(cuts)
    val cutsDone = AtomicInteger(0)
    val port: Int get() = server.localPort

    init {
        thread(isDaemon = true) {
            while (true) {
                val c = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                val s = try {
                    Socket(InetAddress.getLoopbackAddress(), target)
                } catch (e: IOException) {
                    c.close()
                    continue
                }
                val up = AtomicLong()
                thread(isDaemon = true) { pump(c, s, up, true) }
                thread(isDaemon = true) { pump(s, c, up, false) }
            }
        }
    }

    private fun takeCut(): Boolean {
        while (true) {
            val n = cutsLeft.get()
            if (n <= 0) return false
            if (cutsLeft.compareAndSet(n, n - 1)) {
                cutsDone.incrementAndGet()
                return true
            }
        }
    }

    private fun pump(from: Socket, to: Socket, up: AtomicLong, toServer: Boolean) {
        val buf = ByteArray(16 * 1024)
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (toServer) {
                    output.write(buf, 0, n)
                    if (up.addAndGet(n.toLong()) >= cutUploadAt && takeCut()) break
                } else {
                    if (up.get() >= dropReplyAfter && takeCut()) break
                    output.write(buf, 0, n)
                }
            }
        } catch (e: IOException) {
            // the other side went away
        } finally {
            try { from.close() } catch (e: IOException) { }
            try { to.close() } catch (e: IOException) { }
        }
    }

    override fun close() {
        try { server.close() } catch (e: IOException) { }
    }
}
