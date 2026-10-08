package dev.khanlabs.lanshare.net

import dev.khanlabs.lanshare.protocol.ProtocolException
import dev.khanlabs.lanshare.protocol.SenderV2
import dev.khanlabs.lanshare.protocol.V2
import java.security.KeyPairGenerator
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProofsTest {
    private var now = 1_000_000L
    private val nonces = NonceStore { now }
    private val receiver = Identity.create("receiver")
    private val sender = Identity.create("sender")
    private val ip = "192.168.1.20"

    private fun senderOf(identity: Identity = sender) = SenderV2(identity.id, "Sender", "android", "test", 47820)

    private fun code(block: () -> Unit): String = assertFailsWith<ProtocolException> { block() }.let { "${it.status} ${it.code}" }

    private fun check(
        made: DeviceProof.Made, nonce: String, purpose: String = "session", subject: String = "q3V9xY2kL0aB7cD4eF6gH8",
        from: String = ip, who: SenderV2 = senderOf(),
    ) = DeviceProof.check(nonces, from, receiver.id, purpose, subject, who, made.cert, nonce, made.proof)

    // --- nonces

    @Test
    fun aNonceWorksOnceForTheAddressItWasGivenTo() {
        val n = nonces.issue(ip)
        assertTrue(Regex("[A-Za-z0-9_-]{22,64}").matches(n), n)
        assertFalse(nonces.consume("192.168.1.99", n), "another address")
        assertTrue(nonces.consume(ip, n))
        assertFalse(nonces.consume(ip, n), "used up")
    }

    @Test
    fun aNonceFromTheWrongAddressIsNotUsedUp() {
        val n = nonces.issue(ip)
        assertFalse(nonces.consume("10.0.0.1", n))
        assertTrue(nonces.consume(ip, n), "the real address can still use it")
    }

    @Test
    fun aNonceLapsesAfter120Seconds() {
        val n = nonces.issue(ip)
        now += 121_000
        assertFalse(nonces.consume(ip, n))
        val fresh = nonces.issue(ip)
        now += 120_000
        assertTrue(nonces.consume(ip, fresh), "exactly 120 seconds is still fine")
    }

    @Test
    fun anAddressKeepsAtMost32UnusedNoncesAndTheOldestGoesFirst() {
        val all = (1..NonceStore.MAX_PER_IP + 1).map { nonces.issue(ip) }
        assertFalse(nonces.consume(ip, all.first()), "the oldest was forgotten")
        assertTrue(nonces.consume(ip, all.last()))
        assertTrue(nonces.consume(ip, all[1]))
    }

    @Test
    fun noncesDifferEachTime() {
        assertEquals(100, (1..100).map { nonces.issue(ip) }.toSet().size)
    }

    // --- the proof

    @Test
    fun aProofMadeByTheSenderPassesTheReceiversChecks() {
        val nonce = nonces.issue(ip)
        val made = DeviceProof.make(sender, receiver.id, "session", nonce, "q3V9xY2kL0aB7cD4eF6gH8")
        val proven = check(made, nonce)
        assertEquals(sender.id, proven.id)
    }

    @Test
    fun everyPurposeWorksWithItsOwnSubject() {
        for ((purpose, subject) in listOf("session" to "q3V9xY2kL0aB7cD4eF6gH8", "pair" to "", "hello" to "47820")) {
            val nonce = nonces.issue(ip)
            val made = DeviceProof.make(sender, receiver.id, purpose, nonce, subject)
            assertEquals(sender.id, check(made, nonce, purpose, subject).id, purpose)
        }
    }

    @Test
    fun aProofForAnotherPurposeSubjectOrReceiverIsRefused() {
        val nonce = nonces.issue(ip)
        val made = DeviceProof.make(sender, receiver.id, "session", nonce, "q3V9xY2kL0aB7cD4eF6gH8")
        assertEquals("403 bad_proof", code { check(made, nonce, purpose = "pair", subject = "") })
        val n2 = nonces.issue(ip)
        val made2 = DeviceProof.make(sender, receiver.id, "session", n2, "q3V9xY2kL0aB7cD4eF6gH8")
        assertEquals("403 bad_proof", code { check(made2, n2, subject = "another-session-id-0000") })
        // a proof made for a third device can't be passed on to this one
        val third = Identity.create("third")
        val n3 = nonces.issue(ip)
        val madeForThird = DeviceProof.make(sender, third.id, "session", n3, "q3V9xY2kL0aB7cD4eF6gH8")
        assertEquals("403 bad_proof", code { check(madeForThird, n3) })
    }

    @Test
    fun aUsedNonceTheWrongAddressAndAnOldNonceAreRefused() {
        val nonce = nonces.issue(ip)
        val made = DeviceProof.make(sender, receiver.id, "session", nonce, "q3V9xY2kL0aB7cD4eF6gH8")
        check(made, nonce)
        assertEquals("403 bad_nonce", code { check(made, nonce) }, "the same proof twice")
        val n2 = nonces.issue(ip)
        val made2 = DeviceProof.make(sender, receiver.id, "session", n2, "q3V9xY2kL0aB7cD4eF6gH8")
        assertEquals("403 bad_nonce", code { check(made2, n2, from = "10.9.9.9") })
        val n3 = nonces.issue(ip)
        val made3 = DeviceProof.make(sender, receiver.id, "session", n3, "q3V9xY2kL0aB7cD4eF6gH8")
        now += 200_000
        assertEquals("403 bad_nonce", code { check(made3, n3) })
        assertEquals("403 bad_nonce", code { check(made3, "never-issued-nonce-123456") })
    }

    @Test
    fun theNonceIsUsedUpEvenWhenTheProofFails() {
        val nonce = nonces.issue(ip)
        val bad = DeviceProof.make(sender, receiver.id, "pair", nonce, "")
        assertEquals("403 bad_proof", code { check(bad, nonce) }) // wrong purpose
        val good = DeviceProof.make(sender, receiver.id, "session", nonce, "q3V9xY2kL0aB7cD4eF6gH8")
        assertEquals("403 bad_nonce", code { check(good, nonce) }, "a second try with the same nonce")
    }

    @Test
    fun aSenderIdThatIsNotTheCertificatesIdIsRefused() {
        val nonce = nonces.issue(ip)
        val made = DeviceProof.make(sender, receiver.id, "session", nonce, "q3V9xY2kL0aB7cD4eF6gH8")
        val other = Identity.create("other")
        assertEquals("403 bad_proof", code { check(made, nonce, who = senderOf(other)) })
    }

    @Test
    fun anotherDevicesCertificateWithTheSendersIdCantSignForIt() {
        // sends the real sender's ID but its own certificate and signature
        val nonce = nonces.issue(ip)
        val impostor = Identity.create("impostor")
        val made = DeviceProof.make(impostor, receiver.id, "session", nonce, "q3V9xY2kL0aB7cD4eF6gH8")
        assertEquals("403 bad_proof", code { check(made, nonce, who = senderOf(sender)) })
    }

    @Test
    fun aSignatureMadeWithAnotherKeyIsRefused() {
        val nonce = nonces.issue(ip)
        val made = DeviceProof.make(sender, receiver.id, "session", nonce, "q3V9xY2kL0aB7cD4eF6gH8")
        val stolenCert = DeviceProof.Made(made.cert, DeviceProof.make(Identity.create("x"), receiver.id, "session", nonce, "q3V9xY2kL0aB7cD4eF6gH8").proof)
        assertEquals("403 bad_proof", code { check(stolenCert, nonce) })
    }

    @Test
    fun aCertificateThatIsNotP256IsRefusedAsBadCert() {
        val nonce = nonces.issue(ip)
        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
        val cert = org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
            org.bouncycastle.asn1.x500.X500Name("CN=rsa"), java.math.BigInteger.ONE, java.util.Date(0), java.util.Date(System.currentTimeMillis() + 100000),
            org.bouncycastle.asn1.x500.X500Name("CN=rsa"), rsa.public)
            .build(org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA").build(rsa.private)).encoded
        val who = SenderV2(dev.khanlabs.lanshare.protocol.DeviceId.of(cert), "RSA", "android")
        val made = DeviceProof.Made(V2.b64(cert), "AAAA")
        assertEquals("400 bad_cert", code { check(made, nonce, who = who) })
        val n2 = nonces.issue(ip)
        assertEquals("400 bad_cert", code { check(DeviceProof.Made("not base64 !!", "AAAA"), n2) })
        val n3 = nonces.issue(ip)
        assertEquals("400 bad_cert", code { check(DeviceProof.Made(V2.b64(byteArrayOf(1, 2, 3)), "AAAA"), n3) })
    }

    @Test
    fun aGarbageProofIsRefusedWithoutAnError() {
        val nonce = nonces.issue(ip)
        val made = DeviceProof.make(sender, receiver.id, "session", nonce, "q3V9xY2kL0aB7cD4eF6gH8")
        assertEquals("403 bad_proof", code { check(DeviceProof.Made(made.cert, "!!!not base64"), nonce) })
    }
}
