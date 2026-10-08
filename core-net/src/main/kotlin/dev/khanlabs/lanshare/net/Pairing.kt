package dev.khanlabs.lanshare.net

import dev.khanlabs.lanshare.protocol.ConnectCode
import dev.khanlabs.lanshare.protocol.InfoV2
import dev.khanlabs.lanshare.protocol.PairReply
import dev.khanlabs.lanshare.protocol.PairRequest
import dev.khanlabs.lanshare.protocol.ProtocolJson
import dev.khanlabs.lanshare.protocol.SenderV2
import dev.khanlabs.lanshare.protocol.V2
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** The scanner's side of pairing (PROTOCOL-v2.md section 10.2): reads a pair code and proves it to the device that showed it. */
object Pairing {
    /** The device that is now paired: its full ID and the name it gave. */
    class Paired(val id: String, val name: String)

    /**
     * Pairs with the device named by [code], which must be a pair code. The connection is pinned to the ID in the code, so
     * a device with another certificate is refused before anything is sent. Throws [SendException] with words for the person
     * when the device refuses (no code on screen any more, a wrong secret, an old Amanah without pairing), and an
     * [IOException] when it can't be reached.
     */
    fun pair(clients: Clients, identity: Identity, me: Me, code: ConnectCode): Paired {
        if (!code.isPair) throw SendException("That code connects to a device but does not pair with it. Show the pairing code instead.", "not_pair")
        val http = clients.pinnedV2(code.id)
        var last: IOException? = null
        for (host in code.hosts) {
            val base = "https://${if (':' in host) "[$host]" else host}:${code.port}${V2.API}"
            try {
                return pairAt(http, base, identity, me, code)
            } catch (e: SendException) {
                throw e
            } catch (e: IOException) {
                if (Clients.mismatch(e) != null) throw e
                last = e
            }
        }
        throw last ?: IOException("No address in the code")
    }

    private fun pairAt(http: okhttp3.OkHttpClient, base: String, identity: Identity, me: Me, code: ConnectCode): Paired {
        val name = code.name.ifEmpty { code.id.take(6) }
        for (attempt in 0..1) {
            val info = http.newCall(Request.Builder().url("$base/info").get().build()).execute().use { r ->
                val body = r.body.string()
                when (r.code) {
                    200 -> ProtocolJson.decodeFromString(InfoV2.serializer(), body)
                    404 -> throw SendException("$name does not do pairing. It runs an older Amanah: update it, then scan again.", "not_found")
                    else -> throw refused(r.code, body, name)
                }
            }
            if (info.id != code.id) throw SendException("$name did not give the ID in the code. Scan again.", "mismatch")
            if ("pair" !in info.features) throw SendException("$name does not do pairing. It runs an older Amanah: update it, then scan again.", "not_found")
            val made = DeviceProof.make(identity, code.id, "pair", info.nonce)
            val request = PairRequest(SenderV2(identity.id, me.name, me.model, me.app), made.cert, info.nonce, made.proof,
                V2.pairMac(code.secretBytes(), code.id, identity.id))
            val post = Request.Builder().url("$base/pair")
                .post(ProtocolJson.encodeToString(PairRequest.serializer(), request).toRequestBody(JSON)).build()
            val result = http.newCall(post).execute().use { r ->
                val body = r.body.string()
                when {
                    r.code == 200 -> ProtocolJson.decodeFromString(PairReply.serializer(), body)
                    r.code == 403 && errorOf(body)?.error == "bad_nonce" && attempt == 0 -> null
                    else -> throw refused(r.code, body, name)
                }
            }
            if (result != null) {
                if (result.id != code.id) throw SendException("$name answered with another ID. Scan again.", "mismatch")
                return Paired(result.id, result.name.ifEmpty { name })
            }
        }
        throw SendException("$name did not accept the proof of who is pairing. Scan again.", "bad_nonce")
    }

    private fun refused(status: Int, body: String, name: String) =
        SendException(Errors.refusal(status, errorOf(body), name), errorOf(body)?.error ?: "")
}
