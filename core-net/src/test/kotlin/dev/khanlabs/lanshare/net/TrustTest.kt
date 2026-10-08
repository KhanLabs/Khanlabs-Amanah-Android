package dev.khanlabs.lanshare.net

import dev.khanlabs.lanshare.net.V2TestClient.Companion.entry
import dev.khanlabs.lanshare.protocol.ConnectCode
import dev.khanlabs.lanshare.protocol.Summary
import dev.khanlabs.lanshare.protocol.V2
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A [Trust] for tests: a real paired-devices file, a switch for strangers and a code on "screen". */
class TestTrust(dir: File) : Trust {
    val store = TrustStore(File(dir, "trusted.json"))
    @Volatile var strangers = true
    @Volatile var code: PairingCode? = null
    val pairedNow = java.util.concurrent.LinkedBlockingQueue<Pair<String, String>>()

    override fun isTrusted(id: String) = store.get(id) != null
    override fun autoAcceptFiles(id: String) = store.get(id)?.autoFiles == true
    override fun autoAcceptText(id: String) = store.get(id)?.autoText == true
    override fun acceptsStrangers() = strangers
    override fun pairing(): PairingCode? = code
    override fun paired(id: String, name: String) {
        store.add(id, name)
        pairedNow.add(id to name)
    }
    override fun withdrawPairing() {
        code = null
    }

    fun show(lifetimeMs: Long = PairingCode.LIFETIME_MS): PairingCode =
        PairingCode(Random.nextBytes(16), System.currentTimeMillis() + lifetimeMs).also { code = it }
}

class TrustTest {
    private lateinit var tmp: File
    private lateinit var inbox: File
    private lateinit var rx: Receiver
    private lateinit var answers: ConformanceAnswers
    private lateinit var trust: TestTrust
    private val rxIdentity = Identity.create("trust-rx")
    private val me = Identity.create("trust-tx")
    private val clients = Clients()

    @BeforeTest
    fun start() {
        tmp = Files.createTempDirectory("klshare-trust-").toFile()
        inbox = File(tmp, "inbox")
        trust = TestTrust(tmp)
        val (r, a) = startConformanceReceiver(inbox, 48050, identity = rxIdentity, v2Dir = File(tmp, "parts"), trust = trust)
        rx = r
        answers = a
    }

    @AfterTest
    fun stop() {
        rx.stop()
        tmp.deleteRecursively()
    }

    private fun meInfo(name: String = "My phone") = Me(me.id, name, "android", "test")
    private fun device() = Device(rxIdentity.id, "Receiver", "android", listOf("127.0.0.1"), rx.port)
    private fun client(identity: Identity = me) = V2TestClient(rx.port, rxIdentity.id, identity)
    private fun codeFor(c: PairingCode, id: String = rxIdentity.id) =
        ConnectCode(id, "Receiver", listOf("127.0.0.1"), rx.port, secret = V2.b64url(c.secret))

    private fun item(name: String, data: ByteArray) = SendItem(name, data.size.toLong()) { ByteArrayInputStream(data) }

    // --- the paired-devices file and the policy

    @Test
    fun theStoreKeepsDevicesAcrossRestartsAndKeepsTheirSwitchesWhenRenamed() {
        val file = File(tmp, "list.json")
        val a = TrustStore(file)
        a.add("A".repeat(64), "Pixel", now = 5)
        a.setAuto("A".repeat(64), files = true)
        a.add("A".repeat(64), "Pixel 8")
        a.add("B".repeat(64), "Laptop")
        val b = TrustStore(file)
        assertEquals(setOf("Pixel 8", "Laptop"), b.all.map { it.name }.toSet())
        val pixel = b.get("A".repeat(64))!!
        assertTrue(pixel.autoFiles)
        assertFalse(pixel.autoText)
        assertEquals(5L, pixel.pairedAt, "the first pairing date stays")
        b.remove("A".repeat(64))
        assertNull(TrustStore(file).get("A".repeat(64)))
        assertEquals(1, TrustStore(file).all.size)
    }

    @Test
    fun anUnreadableFileMeansNobodyIsTrusted() {
        val file = File(tmp, "bad.json")
        file.writeText("{not json")
        assertTrue(TrustStore(file).all.isEmpty())
    }

    @Test
    fun theReceivePolicyAcceptsStrangersUnlessOnlyPairedDevicesAreAllowed() {
        assertTrue(ReceivePolicy.acceptsStrangers(ReceivePolicy.ASK, 0))
        assertFalse(ReceivePolicy.acceptsStrangers(ReceivePolicy.PAIRED, 0))
        assertTrue(ReceivePolicy.acceptsStrangers(ReceivePolicy.PAIRED, openUntil = 2_000, now = 1_000), "the 10 minutes are not over")
        assertFalse(ReceivePolicy.acceptsStrangers(ReceivePolicy.PAIRED, openUntil = 2_000, now = 2_000))
        assertTrue("another 10 minutes" in ReceivePolicy.describe(ReceivePolicy.PAIRED, 600_000, now = 1))
    }

    // --- pairing

    @Test
    fun aScannerThatReadTheCodePairsAndTheCodeIsUsedUp() {
        val code = trust.show()
        val result = Pairing.pair(clients, me, meInfo(), codeFor(code))
        assertEquals(rxIdentity.id, result.id)
        assertEquals("Conformance Android", result.name, "the name the receiver gives")
        assertTrue(trust.isTrusted(me.id))
        assertEquals("My phone", trust.store.get(me.id)!!.name)
        assertFalse(trust.store.get(me.id)!!.autoFiles, "paired does not mean auto-accepted")
        assertNull(trust.pairing(), "the code is off the screen")
        val again = assertFailsWith<SendException> { Pairing.pair(clients, Identity.create("other"), meInfo("Other"), codeFor(code)) }
        assertEquals("no_pairing", again.code)
        assertTrue("not showing a pairing code" in again.message!!, again.message)
    }

    @Test
    fun aWrongSecretIsRefusedAndFiveWrongAnswersWithdrawTheCode() {
        val code = trust.show()
        val wrong = ConnectCode(rxIdentity.id, "Receiver", listOf("127.0.0.1"), rx.port, secret = V2.b64url(Random.nextBytes(16)))
        for (i in 1..PairingCode.MAX_WRONG) {
            val e = assertFailsWith<SendException> { Pairing.pair(clients, me, meInfo(), wrong) }
            assertEquals("bad_secret", e.code, "try $i")
        }
        assertNull(trust.pairing(), "five wrong answers: a new code must be shown")
        val late = assertFailsWith<SendException> { Pairing.pair(clients, me, meInfo(), codeFor(code)) }
        assertEquals("no_pairing", late.code, "even the right secret is too late")
        assertFalse(trust.isTrusted(me.id))
    }

    @Test
    fun noCodeOnScreenOrAnExpiredCodeMeansNoPairing() {
        val none = assertFailsWith<SendException> { Pairing.pair(clients, me, meInfo(), ConnectCode(rxIdentity.id, "R", listOf("127.0.0.1"), rx.port, secret = V2.b64url(Random.nextBytes(16)))) }
        assertEquals("no_pairing", none.code)
        val expired = trust.show(lifetimeMs = -1)
        assertEquals("no_pairing", assertFailsWith<SendException> { Pairing.pair(clients, me, meInfo(), codeFor(expired)) }.code)
        assertFalse(trust.isTrusted(me.id))
    }

    @Test
    fun aDeviceWithAnotherCertificateThanTheCodeNamesIsNeverTold() {
        val code = trust.show()
        val impostor = codeFor(code, id = Identity.create("someone else").id)
        val failure = assertFailsWith<java.io.IOException> { Pairing.pair(clients, me, meInfo(), impostor) }
        assertNotNull(Clients.mismatch(failure), "the pin refused it: $failure")
        assertNotNull(trust.pairing(), "the secret was not even sent")
    }

    @Test
    fun aPlainConnectCodeCantPair() {
        val plain = ConnectCode(rxIdentity.id, "R", listOf("127.0.0.1"), rx.port)
        assertEquals("not_pair", assertFailsWith<SendException> { Pairing.pair(clients, me, meInfo(), plain) }.code)
    }

    @Test
    fun aPairCodeRoundTripsThroughItsText() {
        val made = ConnectCode.forPairing(rxIdentity.id, "Khan's PC", listOf("192.168.1.9", "10.0.0.4"), 47820)
        assertTrue(made.isPair)
        assertEquals(22, made.secret.length)
        assertEquals(16, made.secretBytes().size)
        val text = made.toText()
        assertTrue(text.startsWith("klshare://pair?v=2&id=${rxIdentity.id}"), text)
        val back = ConnectCode.parse(text)!!
        assertEquals(made, back)
        assertTrue(ConnectCode.forPairing("A".repeat(64), "x", listOf("10.0.0.1"), 1).secret != made.secret, "a new secret each time")
        // a pair code with a secret that is not valid, and a connect code with one, are read as plain connect codes
        assertFalse(ConnectCode.parse("klshare://pair?v=2&id=${rxIdentity.id}&ip=10.0.0.1&port=1&secret=short")!!.isPair)
        assertFalse(ConnectCode.parse("klshare://connect?v=1&id=${rxIdentity.id}&ip=10.0.0.1&port=1&secret=${made.secret}")!!.isPair)
    }

    // --- who may send, and who is taken without asking

    @Test
    fun withPairedDevicesOnlyAStrangerIsTurnedAwayAndAPairedDeviceIsAskedAsUsual() {
        trust.strangers = false
        val stranger = client().create(V2.b64url(Random.nextBytes(16)), listOf(entry("0", "a.txt", 5)))
        assertEquals(403, stranger.code, stranger.toString())
        assertEquals("not_trusted", stranger.error)
        assertEquals(0, answers.asked.size, "the person was not bothered")
        // v1 offers prove nothing, so they are turned away too
        val v1 = SendJob(clients, meInfo(), device(), listOf(item("b.txt", Random.nextBytes(10))), allowV2 = false)
        v1.run()
        assertEquals(SendJob.State.FAILED, v1.state, v1.message)
        assertTrue("only takes files from paired devices" in v1.message, v1.message)

        trust.store.add(me.id, "My phone")
        val paired = client().create(V2.b64url(Random.nextBytes(16)), listOf(entry("0", "a.txt", 5)), senderName = "X [wait]")
        assertEquals(202, paired.code, paired.toString())
        assertEquals(1, answers.asked.size, "a paired device is still asked until the person turns on auto-accept")
    }

    @Test
    fun aPairedDeviceWithAutoAcceptSendsWithoutAnyPrompt() {
        trust.store.add(me.id, "My phone")
        trust.store.setAuto(me.id, files = true)
        val data = Random.nextBytes(3 * 1024 * 1024)
        val j = SendJob(clients, meInfo(), device(), listOf(item("auto/big.bin", data), item("auto/small.txt", Random.nextBytes(20))), identity = me)
        j.run()
        assertEquals(SendJob.State.DONE, j.state, j.message)
        assertContentEquals(data, File(inbox, "auto/big.bin").readBytes())
        assertEquals(0, answers.asked.size, "nobody was asked")
        val offer = answers.autoAccepted.poll(5, TimeUnit.SECONDS)
        assertNotNull(offer)
        assertEquals(me.id, offer.sender.id)
    }

    @Test
    fun theTwoAutoAcceptSwitchesAreSeparateAndOnlyForThePairedDevice() {
        trust.store.add(me.id, "My phone")
        trust.store.setAuto(me.id, text = true) // texts yes, files no
        val files = client().create(V2.b64url(Random.nextBytes(16)), listOf(entry("0", "a.txt", 5)))
        assertEquals(202, files.code, "files still ask")
        assertEquals(1, answers.asked.size)
        val text = client().create(V2.b64url(Random.nextBytes(16)), emptyList(), text = "Gate B12", summary = Summary(0, 0))
        assertEquals(200, text.code, text.toString())
        assertEquals("done", text.text("state"), "an auto-accepted text is done at once")
        assertEquals("Gate B12", answers.finished.poll(5, TimeUnit.SECONDS)!!.text)
        // another device with the same switches off in the list is not auto-accepted
        val stranger = Identity.create("stranger")
        trust.store.add(stranger.id, "Stranger")
        val still = client(stranger).create(V2.b64url(Random.nextBytes(16)), listOf(entry("0", "b.txt", 5)))
        assertEquals(202, still.code)
        // and a device that is not paired at all is not auto-accepted even though the switches of others are on
        val unpaired = client(Identity.create("unpaired")).create(V2.b64url(Random.nextBytes(16)), emptyList(), text = "hi", summary = Summary(0, 0))
        assertEquals(202, unpaired.code)
    }

    @Test
    fun theProofIsWhatMakesAnIdBelongToADevice() {
        // a paired device's ID typed into a v1 offer by someone else gets no free pass: v1 offers are never trusted
        trust.store.add(me.id, "My phone")
        trust.store.setAuto(me.id, files = true)
        val forged = SendJob(clients, Me(me.id, "Forger [wait]", "android", "x"), device(), listOf(item("evil.txt", Random.nextBytes(8))), identity = null, allowV2 = false)
        val t = Thread { forged.run() }.apply { isDaemon = true; start() }
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (answers.asked.isEmpty() && System.nanoTime() < end) Thread.sleep(20)
        assertEquals(1, answers.asked.size, "it was asked, not taken")
        assertFalse(File(inbox, "evil.txt").exists())
        forged.cancel()
        t.join(10_000)
    }
}
