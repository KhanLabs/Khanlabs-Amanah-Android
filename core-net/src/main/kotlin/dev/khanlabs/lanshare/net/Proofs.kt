package dev.khanlabs.lanshare.net

import dev.khanlabs.lanshare.protocol.DeviceId
import dev.khanlabs.lanshare.protocol.ProtocolException
import dev.khanlabs.lanshare.protocol.SenderV2
import dev.khanlabs.lanshare.protocol.V2
import java.security.SecureRandom

/**
 * The nonces a receiver has handed out in `GET /info` (PROTOCOL-v2.md sections 4 and 5). Each is good for one use, from
 * the address it was given to, for [V2.NONCE_SECONDS]; an address keeps at most [MAX_PER_IP] unused ones and the oldest
 * is forgotten first. Safe to call from any thread.
 */
class NonceStore(private val clock: () -> Long = System::currentTimeMillis) {
    private class Issued(val nonce: String, val at: Long)

    private val byIp = HashMap<String, ArrayDeque<Issued>>()
    private val random = SecureRandom()

    fun issue(ip: String): String {
        val nonce = V2.b64url(ByteArray(16).also { random.nextBytes(it) })
        val now = clock()
        synchronized(this) {
            val list = byIp.getOrPut(ip) { ArrayDeque() }
            list.removeAll { now - it.at > TTL_MS }
            list.addLast(Issued(nonce, now))
            while (list.size > MAX_PER_IP) list.removeFirst()
            if (byIp.size > MAX_ADDRESSES) byIp.entries.removeAll { (_, l) -> l.all { now - it.at > TTL_MS } }
        }
        return nonce
    }

    /** True once, for a nonce this store issued to [ip] less than 120 seconds ago. The nonce is used up whatever the answer. */
    fun consume(ip: String, nonce: String): Boolean {
        val now = clock()
        synchronized(this) {
            val list = byIp[ip] ?: return false
            val found = list.firstOrNull { it.nonce == nonce } ?: return false
            list.remove(found)
            if (list.isEmpty()) byIp.remove(ip)
            return now - found.at <= TTL_MS
        }
    }

    companion object {
        const val MAX_PER_IP = 32
        private const val MAX_ADDRESSES = 2048
        private const val TTL_MS = V2.NONCE_SECONDS * 1000L
    }
}

/** Who sent a request, as proven by the checks in [DeviceProof.check]. */
class ProvenSender(val id: String, val certDer: ByteArray, val sender: SenderV2)

/** The device proof of PROTOCOL-v2.md section 4, both sides. */
object DeviceProof {
    /** What the sender adds to a request: its certificate and the signature. */
    class Made(val cert: String, val proof: String)

    /**
     * Signs for [purpose] ("session", "pair" or "hello") to the receiver [receiverId], with a [nonce] the receiver gave in
     * `GET /info`. [subject] is the session ID for "session", the API port for "hello" and empty for "pair".
     */
    fun make(identity: Identity, receiverId: String, purpose: String, nonce: String, subject: String = ""): Made {
        val message = V2.proofMessage(purpose, receiverId, identity.id, nonce, subject)
        return Made(V2.b64(identity.certDer), V2.signProof(identity.privateKey, message))
    }

    /**
     * The receiver's checks, in the order the spec gives them. Throws [ProtocolException] with `403 bad_nonce`,
     * `400 bad_cert` or `403 bad_proof`. The nonce is used up before anything else is checked.
     */
    fun check(
        nonces: NonceStore, ip: String, receiverId: String, purpose: String, subject: String,
        sender: SenderV2, certB64: String, nonce: String, proof: String,
    ): ProvenSender {
        if (!nonces.consume(ip, nonce)) throw ProtocolException(403, "bad_nonce", "Get a new nonce from /info and try again")
        val der = try {
            V2.unb64(certB64)
        } catch (e: Exception) {
            throw ProtocolException(400, "bad_cert", "cert must be a certificate with a P-256 key")
        }
        if (!V2.certKeyIsP256(der)) throw ProtocolException(400, "bad_cert", "cert must be a certificate with a P-256 key")
        val senderId = DeviceId.of(der)
        if (sender.id != senderId) throw ProtocolException(403, "bad_proof", "The sender ID is not the ID of the certificate")
        val message = try {
            V2.proofMessage(purpose, receiverId, senderId, nonce, subject)
        } catch (e: IllegalArgumentException) {
            throw ProtocolException(403, "bad_proof", "The proof does not match this request")
        }
        if (!V2.verifyProof(der, proof, message)) throw ProtocolException(403, "bad_proof", "The proof does not match this request")
        return ProvenSender(senderId, der, sender)
    }
}
