package dev.khanlabs.lanshare.net

import dev.khanlabs.lanshare.protocol.DeviceId
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Chat texts (PROTOCOL.md 3.2, `kind`): who may skip the prompt, over real TLS on 127.0.0.1. */
class MessageTest {
    private lateinit var tmp: File
    private lateinit var rx: Receiver
    private lateinit var answers: ConformanceAnswers
    private val clients = Clients()
    private val identity = Identity.create("test")

    /** Set by each test: the rule the receiver uses to trust a chat text. */
    @Volatile private var trust: (Offer) -> Boolean = { false }

    @BeforeTest
    fun start() {
        tmp = Files.createTempDirectory("klshare-message-").toFile()
        val started = startConformanceReceiver(File(tmp, "inbox"), 47910, identity = identity, messageTrusted = { trust(it) })
        rx = started.first
        answers = started.second
    }

    @AfterTest
    fun stop() {
        rx.stop()
        tmp.deleteRecursively()
    }

    private val device = Device("", "Loopback", "android", listOf("127.0.0.1"), 47910)
    private fun me(name: String) = Me(DeviceId.of(ByteArray(1)), name, "android", "test")

    private fun send(text: String, kind: String = "message", items: List<SendItem> = emptyList(), name: String = "Test sender"): SendJob {
        val job = SendJob(clients, me(name), device.copy(id = identity.id), items, text, kind)
        job.run()
        return job
    }

    @Test
    fun aTrustedMessageArrivesWithoutAPrompt() {
        trust = { it.ip == "127.0.0.1" }
        val job = send("on my way")
        assertEquals(SendJob.State.DONE, job.state, job.message)
        val got = assertNotNull(answers.messages.poll(5, TimeUnit.SECONDS))
        assertEquals("on my way", got.text)
        assertEquals("done", got.state)
        assertTrue(answers.asked.isEmpty(), "nobody should have been asked")
        assertTrue(answers.finished.isEmpty(), "a delivered message is not an offer to finish")
    }

    @Test
    fun anUntrustedMessageIsAskedFirst() {
        val job = send("hello")
        assertEquals(SendJob.State.DONE, job.state, job.message) // the conformance answers accept
        assertNotNull(answers.asked.poll(5, TimeUnit.SECONDS))
        assertTrue(answers.messages.isEmpty())
        assertEquals("hello", answers.finished.poll(5, TimeUnit.SECONDS)!!.text)
    }

    @Test
    fun anUntrustedMessageCanBeDeclined() {
        val job = send("hello", name = "Rude [decline]")
        assertEquals(SendJob.State.DECLINED, job.state)
        assertTrue(answers.messages.isEmpty())
    }

    @Test
    fun noKindMeansNoSkipping() {
        trust = { true }
        val job = send("plain text", kind = "")
        assertEquals(SendJob.State.DONE, job.state, job.message)
        assertNotNull(answers.asked.poll(5, TimeUnit.SECONDS))
        assertTrue(answers.messages.isEmpty())
    }

    @Test
    fun aMessageWithFilesIsNeverSkipped() {
        trust = { true }
        val data = ByteArray(10)
        val job = send("see attached", items = listOf(SendItem("a.bin", data.size.toLong()) { ByteArrayInputStream(data) }))
        assertEquals(SendJob.State.DONE, job.state, job.message)
        assertNotNull(answers.asked.poll(5, TimeUnit.SECONDS))
        assertTrue(answers.messages.isEmpty())
    }

    @Test
    fun aFailingTrustCheckAsksThePerson() {
        trust = { error("broken") }
        val job = send("hi")
        assertEquals(SendJob.State.DONE, job.state, job.message)
        assertNotNull(answers.asked.poll(5, TimeUnit.SECONDS))
    }
}
