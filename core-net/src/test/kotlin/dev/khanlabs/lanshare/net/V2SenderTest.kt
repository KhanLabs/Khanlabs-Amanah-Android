package dev.khanlabs.lanshare.net

import dev.khanlabs.lanshare.protocol.V2
import java.io.ByteArrayInputStream
import java.io.File
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

/** The v2 sender (a [SendJob] that finds the receiver speaks v2) against the real v2 receiver, over TLS on 127.0.0.1. */
class V2SenderTest {
    /** A hang is a failure, not a stuck build. */
    @get:org.junit.Rule
    val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(150)

    private lateinit var tmp: File
    private lateinit var inbox: File
    private lateinit var parts: File
    private lateinit var rx: Receiver
    private lateinit var answers: ConformanceAnswers
    private val rxIdentity = Identity.create("v2-rx")
    private val myIdentity = Identity.create("v2-tx")
    private val clients = Clients()
    private val receiving = AtomicBoolean(true)
    private var port = 0

    @BeforeTest
    fun start() {
        tmp = Files.createTempDirectory("klshare-v2s-").toFile()
        inbox = File(tmp, "inbox")
        parts = File(tmp, "parts")
        launch(48010)
    }

    private fun launch(wantPort: Int, v2: Boolean = true) {
        val (r, a) = startConformanceReceiver(inbox, wantPort, identity = rxIdentity, v2Dir = if (v2) parts else null, receiving = { receiving.get() })
        rx = r
        answers = a
        port = rx.port
    }

    @AfterTest
    fun stop() {
        rx.stop()
        tmp.deleteRecursively()
    }

    private fun device() = Device(rxIdentity.id, "Loopback", "android", listOf("127.0.0.1"), port)

    private fun me(name: String = "Test sender") = Me(myIdentity.id, name, "android", "test")

    private fun item(name: String, data: ByteArray, modified: Long = 0L) =
        SendItem(name, data.size.toLong(), modified) { ByteArrayInputStream(data) }

    private fun job(vararg items: SendItem, name: String = "Test sender", allowV2: Boolean = true, identity: Identity? = myIdentity) =
        SendJob(clients, me(name), device(), items.toList(), identity = identity, allowV2 = allowV2)

    private fun bytes(n: Int, seed: Int = 1) = Random(seed).nextBytes(n)

    private fun saved(name: String) = File(inbox, name)

    private fun usedV2(job: SendJob) = rx.get(job.offerId)?.v2 != null

    // --- the basics

    @Test
    fun aSmallFileIsSentWithV2AndArrivesWhole() {
        val data = bytes(1000)
        val j = job(item("hello.txt", data, 1_700_000_000_000))
        j.run()
        assertEquals(SendJob.State.DONE, j.state, j.message)
        assertTrue(usedV2(j), "the session is a v2 session")
        assertContentEquals(data, saved("hello.txt").readBytes())
        assertEquals(1_700_000_000L, saved("hello.txt").lastModified() / 1000)
        assertEquals(1, j.verified)
        assertEquals(listOf("hello.txt"), j.saved)
        assertEquals(j.total, j.sent)
        assertEquals("Sent 1 file", j.message)
        assertEquals(listOf(SendJob.SENT), j.results())
        assertTrue(j.unfinished().isEmpty())
    }

    @Test
    fun aBigFileGoesInChunksOverSeveralConnections() {
        val data = bytes(40 * 1024 * 1024 + 123, seed = 2)
        val j = job(item("big.bin", data))
        j.run()
        assertEquals(SendJob.State.DONE, j.state, j.message)
        assertContentEquals(data, saved("big.bin").readBytes())
        assertEquals(data.size.toLong(), j.sent)
        assertEquals(1, j.verified)
        assertTrue(j.peakSpeed > 0)
    }

    @Test
    fun manySmallFilesGoInBatchesAndAFolderKeepsItsShape() {
        val files = (0 until 300).associate { i -> "photos/day ${i % 7}/img$i.jpg" to bytes(100 + i * 3, seed = i) }
        val j = job(*files.map { (n, d) -> item(n, d) }.toTypedArray())
        val t0 = System.nanoTime()
        j.run()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(SendJob.State.DONE, j.state, j.message)
        for ((n, d) in files) assertContentEquals(d, saved(n).readBytes(), n)
        assertEquals(300, j.verified)
        assertEquals(files.values.sumOf { it.size.toLong() }, j.sent, "the bytes counted are the files' own")
        println("300 small files over v2 in $ms ms")
    }

    @Test
    fun smallAndBigFilesTogether() {
        val big = bytes(20 * 1024 * 1024, seed = 3)
        val small = (0 until 20).map { item("s/f$it.txt", bytes(50, it)) }
        val j = job(*small.toTypedArray(), item("b/big.bin", big), item("empty.txt", ByteArray(0)))
        j.run()
        assertEquals(SendJob.State.DONE, j.state, j.message)
        assertContentEquals(big, saved("b/big.bin").readBytes())
        assertEquals(0L, saved("empty.txt").length())
        assertEquals(20, saved("s").list()!!.size)
    }

    @Test
    fun aNameFineForV2ButNotForWindowsArrivesRenamed() {
        val j = job(item("pics/a:b?.txt", bytes(5)))
        j.run()
        assertEquals(SendJob.State.DONE, j.state, j.message)
        assertEquals(listOf("pics/a_b_.txt"), j.saved)
        assertTrue(saved("pics/a_b_.txt").isFile)
    }

    // --- the choice of version

    @Test
    fun aReceiverThatOnlyDoesV1IsSentToWithV1() {
        rx.stop()
        launch(48020, v2 = false)
        val data = bytes(3000)
        val j = job(item("v1.txt", data))
        j.run()
        assertEquals(SendJob.State.DONE, j.state, j.message)
        assertFalse(usedV2(j))
        assertContentEquals(data, saved("v1.txt").readBytes())
    }

    @Test
    fun v1CanBeForcedAndATextOnJobStaysOnV1() {
        val forced = job(item("a.txt", bytes(10)), allowV2 = false)
        forced.run()
        assertEquals(SendJob.State.DONE, forced.state, forced.message)
        assertFalse(usedV2(forced))
        val text = SendJob(clients, me(), device(), emptyList(), "hello there", identity = myIdentity)
        text.run()
        assertEquals(SendJob.State.DONE, text.state, text.message)
        assertFalse(usedV2(text))
        val noIdentity = job(item("b.txt", bytes(10)), identity = null)
        noIdentity.run()
        assertEquals(SendJob.State.DONE, noIdentity.state, noIdentity.message)
        assertFalse(usedV2(noIdentity), "without an identity there is no proof to give")
    }

    // --- answers other than yes

    @Test
    fun aDeclinedSendEndsAsDeclined() {
        val j = job(item("a.txt", bytes(10)), name = "X [decline]")
        j.run()
        assertEquals(SendJob.State.DECLINED, j.state, j.message)
        assertTrue("declined" in j.message, j.message)
        assertFalse(saved("a.txt").exists())
    }

    @Test
    fun aReceiverThatIsNotReceivingGivesAClearMessage() {
        receiving.set(false)
        val j = job(item("a.txt", bytes(10)))
        j.run()
        assertEquals(SendJob.State.FAILED, j.state, j.message)
        assertTrue("isn't accepting files" in j.message, j.message)
    }

    @Test
    fun theReceiverCancellingInTheMiddleStopsTheSend() {
        val data = bytes(60 * 1024 * 1024, seed = 4)
        val j = job(item("big.bin", data))
        val t = Thread { j.run() }.apply { start() }
        waitUntil { j.sent > 4 * 1024 * 1024 }
        rx.cancel(j.offerId)
        t.join(30_000)
        assertFalse(t.isAlive, "the send stopped")
        assertEquals(SendJob.State.FAILED, j.state, j.message)
        assertTrue("cancelled" in j.message, j.message)
        assertFalse(saved("big.bin").exists())
    }

    @Test
    fun theSenderCancellingStopsAtOnceAndTheReceiverKnows() {
        val data = bytes(80 * 1024 * 1024, seed = 5)
        val j = job(item("big.bin", data))
        val t = Thread { j.run() }.apply { start() }
        waitUntil { j.sent > 2 * 1024 * 1024 }
        j.cancel()
        t.join(30_000)
        assertFalse(t.isAlive)
        assertEquals(SendJob.State.CANCELLED, j.state, j.message)
        waitUntil { rx.get(j.offerId)?.state == "cancelled" }
        assertFalse(saved("big.bin").exists())
        assertEquals(listOf(SendJob.UNSENT), j.results())
    }

    // --- files already there

    @Test
    fun filesTheReceiverAlreadyHasAreLeftOutAndSaidSo() {
        val a = bytes(500, 1)
        val b = bytes(700, 2)
        val first = job(item("docs/a.txt", a, 1_700_000_000_000))
        first.run()
        assertEquals(SendJob.State.DONE, first.state, first.message)
        val second = job(item("docs/a.txt", a, 1_700_000_000_000), item("docs/b.txt", b, 1_700_000_000_000), name = "T [skip]")
        second.run()
        assertEquals(SendJob.State.DONE, second.state, second.message)
        assertEquals(1, second.skipped)
        assertEquals(a.size.toLong(), second.skippedBytes)
        assertEquals(b.size.toLong(), second.total)
        assertEquals(listOf("docs/b.txt"), second.saved)
        assertEquals(listOf(SendJob.ALREADY, SendJob.SENT), second.results())
        assertTrue("already on" in second.message, second.message)
        val all = job(item("docs/a.txt", a, 1_700_000_000_000), item("docs/b.txt", b, 1_700_000_000_000), name = "T [skip]")
        all.run()
        assertEquals(SendJob.State.DONE, all.state, all.message)
        assertTrue(all.message.startsWith("Nothing new"), all.message)
    }

    // --- a break in the middle

    @Test
    fun aReceiverThatStopsAndComesBackInTheMiddleOfABigFileGetsTheRestAndNotTheWholeFileAgain() {
        val data = bytes(64 * 1024 * 1024, seed = 6)
        val j = job(item("big.bin", data), item("after.txt", bytes(30, 9)))
        val t = Thread { j.run() }.apply { isDaemon = true; start() }
        waitUntil { j.sent > 24 * 1024 * 1024 }
        val port0 = port
        rx.stop() // the app on the other side is closed
        Thread.sleep(300)
        launch(port0)
        assertEquals(port0, port, "the same port, so the sender finds it again")
        t.join(120_000)
        assertFalse(t.isAlive, "the send finished")
        assertEquals(SendJob.State.DONE, j.state, j.message)
        assertTrue(j.resumes >= 1, "it noticed the break")
        assertContentEquals(data, saved("big.bin").readBytes())
        assertEquals(2, j.verified)
        assertNotNull(answers.resumed.poll(), "the receiver brought the session back from its journal")
    }

    @Test
    fun aReceiverThatNeverComesBackFailsTheSendWithAClearMessageWhenTheSenderGivesUp() {
        // not the full 3 minutes: the first pause is 2 seconds and Cancel ends the wait at once
        val j = job(item("big.bin", bytes(48 * 1024 * 1024, seed = 7)))
        val t = Thread { j.run() }.apply { isDaemon = true; start() }
        waitUntil { j.sent > 8 * 1024 * 1024 }
        rx.stop()
        waitUntil { j.reconnecting }
        j.cancel()
        t.join(30_000)
        assertFalse(t.isAlive)
        assertEquals(SendJob.State.CANCELLED, j.state, j.message)
    }

    private fun waitUntil(seconds: Long = 30, cond: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!cond()) {
            check(System.nanoTime() < end) { "gave up waiting" }
            Thread.sleep(20)
        }
    }

    @Test
    fun theChunkSizeIsTheReceiversAndEveryChunkIsChecked() {
        // a file of exactly 2 chunks and one byte more: 3 chunks, the last of 1 byte
        val data = bytes(2 * V2.DEFAULT_CHUNK.toInt() + 1, seed = 8)
        val j = job(item("edge.bin", data))
        j.run()
        assertEquals(SendJob.State.DONE, j.state, j.message)
        assertContentEquals(data, saved("edge.bin").readBytes())
        assertNull(File(parts, j.offerId).takeIf { it.exists() }, "the parts and journal were cleaned up")
    }
}
