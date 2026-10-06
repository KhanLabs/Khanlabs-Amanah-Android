package dev.khanlabs.lanshare.net

import dev.khanlabs.lanshare.protocol.DeviceId
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The Kotlin sender against the Kotlin receiver, over real TLS on 127.0.0.1. */
class LoopbackTest {
    private lateinit var tmp: File
    private lateinit var inbox: File
    private lateinit var rx: Receiver
    private lateinit var answers: ConformanceAnswers
    private val clients = Clients()
    private val identity = Identity.create("test")

    @BeforeTest
    fun start() {
        tmp = Files.createTempDirectory("klshare-loopback-").toFile()
        inbox = File(tmp, "inbox")
        val started = startConformanceReceiver(inbox, 47900, identity = identity)
        rx = started.first
        answers = started.second
    }

    @AfterTest
    fun stop() {
        rx.stop()
        tmp.deleteRecursively()
    }

    private fun device(id: String = identity.id) = Device(id, "Loopback", "android", listOf("127.0.0.1"), rx.port)

    private fun me(name: String = "Test sender") = Me(DeviceId.of(ByteArray(1)), name, "android", "test")

    private fun item(name: String, data: ByteArray) = SendItem(name, data.size.toLong()) { ByteArrayInputStream(data) }

    @Test
    fun sendsFilesAndText() {
        val big = Random(1).nextBytes(3 * 1024 * 1024 + 17)
        val items = listOf(item("Trip/day 1/a.txt", "hello\n".toByteArray()), item("big.bin", big), item("empty.txt", ByteArray(0)))
        val job = SendJob(clients, me(), device(), items, "Gate B12")
        job.run()
        assertEquals(SendJob.State.DONE, job.state, job.message)
        assertEquals(listOf("Trip/day 1/a.txt", "big.bin", "empty.txt"), job.saved)
        assertEquals("hello\n", File(inbox, "Trip/day 1/a.txt").readText())
        assertContentEquals(big, File(inbox, "big.bin").readBytes())
        assertEquals(0, File(inbox, "empty.txt").length())
        assertEquals(job.total, job.sent)
        val offer = answers.finished.poll(5, TimeUnit.SECONDS)!!
        assertEquals("done", offer.state)
        assertEquals("Gate B12", offer.text)
        assertTrue(inbox.walk().none { FolderStore.isPartial(it.name) })
    }

    @Test
    fun neverReplacesAFile() {
        repeat(2) { SendJob(clients, me(), device(), listOf(item("a.txt", byteArrayOf(it.toByte())))).run() }
        val job = SendJob(clients, me(), device(), listOf(item("a.txt", byteArrayOf(9))))
        job.run()
        assertEquals(listOf("a (2).txt"), job.saved)
        assertContentEquals(byteArrayOf(0), File(inbox, "a.txt").readBytes())
        assertContentEquals(byteArrayOf(1), File(inbox, "a (1).txt").readBytes())
    }

    @Test
    fun textOnlyAndDeclined() {
        val text = SendJob(clients, me(), device(), emptyList(), "just text")
        text.run()
        assertEquals(SendJob.State.DONE, text.state)
        val declined = SendJob(clients, me("Someone [decline]"), device(), listOf(item("x.txt", byteArrayOf(1))))
        declined.run()
        assertEquals(SendJob.State.DECLINED, declined.state)
        assertTrue(inbox.listFiles().isNullOrEmpty())
    }

    @Test
    fun cancelWhileWaiting() {
        val job = SendJob(clients, me("Patient [wait]"), device(), listOf(item("x.txt", byteArrayOf(1))))
        val t = Thread { job.run() }.apply { start() }
        var offer: Offer? = null
        repeat(50) { if (offer == null) { Thread.sleep(100); offer = rx.get(job.offerId) } }
        assertEquals("waiting", offer?.state)
        job.cancel()
        t.join(10_000)
        assertEquals(SendJob.State.CANCELLED, job.state)
        repeat(50) { if (offer!!.state == "waiting") Thread.sleep(100) }
        assertEquals("cancelled", offer!!.state)
    }

    @Test
    fun pinsTheDeviceId() {
        val other = Identity.create("other").id
        val job = SendJob(clients, me(), device(other), listOf(item("x.txt", byteArrayOf(1))))
        job.run()
        assertEquals(SendJob.State.FAILED, job.state)
        assertTrue("identity has changed" in job.message, job.message)
        assertFailsWith<IdentityMismatchException> {
            try {
                fetchInfo(clients, "127.0.0.1", rx.port, other)
            } catch (e: Exception) {
                throw Clients.mismatch(e) ?: e
            }
        }
        val found = fetchInfo(clients, "127.0.0.1", rx.port)
        assertEquals(identity.id, found.id)
        assertEquals("Conformance Android", found.name)
    }

    @Test
    fun aRefusedOfferNamesTheFileAndTheReason() {
        val cases = mapOf(
            "  notes.txt" to "a name cannot start with a space",
            "a|b.txt" to "a name cannot contain |",
            "CON.txt" to "CON is a name Windows keeps for itself",
            "trailing." to "a name cannot end with a dot",
        )
        for ((name, reason) in cases) {
            val job = SendJob(clients, me(), device(), listOf(item(name, byteArrayOf(1))))  // a sender that did not make the name safe
            job.run()
            assertEquals(SendJob.State.FAILED, job.state, name)
            assertTrue(reason in job.message, job.message)
            assertTrue(name.trim() in job.message, job.message)
            assertTrue("Loopback refused the send" in job.message, job.message)
            assertTrue("Nothing was sent" in job.message, job.message)
        }
        assertTrue(inbox.listFiles().isNullOrEmpty())
    }

    @Test
    fun failedSendsSayWhatToDo() {
        val closed = SendJob(clients, me(), device().copy(port = 1), listOf(item("x.txt", byteArrayOf(1))))
        closed.run()
        assertEquals(SendJob.State.FAILED, closed.state)
        assertTrue("Loopback isn't answering" in closed.message, closed.message)
        assertTrue("Nothing was sent" in closed.message, closed.message)
        assertTrue(closed.failure != null)

        val gone = SendJob(clients, me(), device(), listOf(SendItem("gone.txt", 3) { throw FileNotFoundException("gone.txt") }))
        gone.run()
        assertEquals(SendJob.State.FAILED, gone.state)
        assertTrue("gone.txt was moved or deleted" in gone.message, gone.message)

        val short = SendJob(clients, me(), device(), listOf(SendItem("short.txt", 10) { ByteArrayInputStream(ByteArray(3)) }))
        short.run()
        assertEquals(SendJob.State.FAILED, short.state)
        assertTrue("short.txt got shorter while it was being sent" in short.message, short.message)
    }

    @Test
    fun onlyAddressesOnThisNetworkAreContacted() {
        val offNetwork = SendJob(clients, me(), device().copy(hosts = listOf("8.8.8.8")), listOf(item("x.txt", byteArrayOf(1))))
        offNetwork.run()
        assertEquals(SendJob.State.FAILED, offNetwork.state)
        assertTrue("isn't on your local network" in offNetwork.message, offNetwork.message)
        assertFailsWith<NotLocalException> { fetchInfo(clients, "8.8.8.8", 47820) }
        assertFailsWith<NotLocalException> { fetchInfo(clients, "example.com", 47820) }
        assertFailsWith<NotLocalException> { fetchInfo(clients, "100.64.0.1", 47820) } // the VPN range, until it is allowed
        // allowed: the guard lets it through, so the connection is tried (and fails here, as nothing answers)
        val vpn = Clients(allowVpn = { true })
        val e = assertFailsWith<Exception> { fetchInfo(vpn, "100.64.0.1", 47820, timeoutS = 1) }
        assertTrue(e !is NotLocalException, e.toString())
    }

    @Test
    fun plainHttpIsRedirected() {
        Socket("127.0.0.1", rx.port).use { s ->
            s.soTimeout = 5000
            s.getOutputStream().write("GET /api/kl/v1/info HTTP/1.1\r\nHost: 127.0.0.1:${rx.port}\r\n\r\n".toByteArray())
            val reply = s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
            assertTrue(reply.startsWith("HTTP/1.1 301"), reply)
            assertTrue("Location: https://127.0.0.1:${rx.port}/api/kl/v1/info\r\n" in reply, reply)
        }
    }

    @Test
    fun hostRules() {
        assertTrue(HttpsServer.hostAllowed(null))
        assertTrue(HttpsServer.hostAllowed("192.168.1.5:47820"))
        assertTrue(HttpsServer.hostAllowed("[fe80::1%25wlan0]:47820".replace("%25", "%")))
        assertTrue(HttpsServer.hostAllowed("[::1]"))
        assertTrue(HttpsServer.hostAllowed("localhost"))
        for (bad in listOf("evil.example", "evil.example:47820", "192.168.1.256", "01.2.3.4", "[evil]", "1.2.3")) {
            assertTrue(!HttpsServer.hostAllowed(bad), bad)
        }
        assertEquals(mapOf("a" to "1", "b" to "x y"), HttpsServer.parseQuery("a=1&a=2&b=x+y&c=&d"))
    }

    @Test
    fun identityIsKept() {
        val dir = File(tmp, "id")
        val a = Identity.loadOrCreate(dir, "phone")
        val b = Identity.loadOrCreate(dir, "phone")
        assertEquals(a.id, b.id)
        assertEquals(a.id, DeviceId.of(File(dir, Identity.CERT_FILE).readBytes()))
    }
}
