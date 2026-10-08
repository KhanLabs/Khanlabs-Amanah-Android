package dev.khanlabs.lanshare.net

import dev.khanlabs.lanshare.protocol.ApiError
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Messages for things that go wrong, in one place, like the Windows app's errors.py.
 *
 * Every message says what happened, what was saved, and what to do next, in plain words. Raw error text such as
 * "failed to connect to /192.168.1.5 (port 47820) after 5000ms: isConnectFailed: ECONNREFUSED" is never shown; the
 * error log has it. The `error` codes are the ones of the send API (PROTOCOL.md 3.5); an unknown code still gets a
 * sensible message.
 */
object Errors {
    enum class Kind { REFUSED, TIMEOUT, UNREACHABLE, DROPPED, SECURITY, LOOKUP, OTHER }

    /** What the receiving device can answer to an offer or an upload. `{name}` is the device's name. */
    val BY_CODE: Map<String, String> = mapOf(
        "not_receiving" to "{name} isn't accepting files right now. Turn Receiving on in Amanah there, then send again.",
        "not_trusted" to "{name} only takes files from paired devices. Pair with it first (Settings, Paired devices, on both devices), then send again.",
        "no_pairing" to "{name} is not showing a pairing code any more. Show a new one there and scan it again.",
        "bad_secret" to "That pairing code does not match. Show a new one on {name} and scan it again.",
        "declined" to "{name} declined.",
        "cancelled" to "{name} cancelled this send.",
        "timeout" to "No answer from {name} in time. Ask the person to look at the screen, then send again.",
        "busy" to "{name} already has sends waiting for an answer. Wait until it has answered them, then send again.",
        "no_space" to "{name} doesn't have enough free space for this. Free some space there, then send again.",
        "wrong_host" to "{name} only answers to its IP address. Pick it from the list instead of typing a name.",
        "duplicate" to "Something went wrong with the offer id. Send again.",
        "bad_token" to "{name} no longer knows this send. It was probably cancelled there. Send again.",
        "already" to "{name} already has this file. Nothing more to do.",
        "wrong_size" to "A file changed size while it was being sent. Send it again.",
        "folder" to "{name} couldn't make the folder for a file. Check its receive folder, then send again.",
        "failed" to "{name} couldn't save a file. Check its free space and receive folder, then send again.",
        "too_large" to "That send is too big for {name} to list at once. Send fewer files at a time.",
        "empty" to "There was nothing to send.",
        "not_found" to "{name} doesn't understand this request. It may run another version of Amanah. Update both, then try again.",
    )

    /** Which kind of network problem an exception is, looking through its causes. */
    fun kind(e: Throwable): Kind {
        var t: Throwable? = e
        var couldNotConnect = false
        repeat(8) {
            val now = t ?: return if (couldNotConnect) Kind.UNREACHABLE else Kind.OTHER
            kindOf(now)?.let { return it }
            // OkHttp says "Failed to connect to /ip:port" and keeps the system's reason as the cause
            if (now is ConnectException) couldNotConnect = true
            t = now.cause
        }
        return if (couldNotConnect) Kind.UNREACHABLE else Kind.OTHER
    }

    private fun kindOf(t: Throwable): Kind? = when (t) {
        is UnknownHostException -> Kind.LOOKUP
        is NoRouteToHostException -> Kind.UNREACHABLE
        is SocketTimeoutException -> Kind.TIMEOUT
        is SSLException -> byMessage(t.message) ?: Kind.SECURITY
        is ConnectException -> byMessage(t.message)
        is EOFException -> Kind.DROPPED
        is IOException -> byMessage(t.message)
        else -> null
    }

    private fun byMessage(message: String?): Kind? {
        val s = (message ?: return null).lowercase()
        return when {
            "refused" in s -> Kind.REFUSED
            "unreachable" in s || "no route to host" in s || "enetdown" in s -> Kind.UNREACHABLE
            "timed out" in s || "timeout" in s -> Kind.TIMEOUT
            "reset" in s || "broken pipe" in s || "epipe" in s || "abort" in s || "unexpected end of stream" in s ||
                "closed" in s || "terminated" in s -> Kind.DROPPED
            else -> null
        }
    }

    /** What reached the other device. Empty when the send had no files. */
    fun progressNote(done: Int, total: Int): String = when {
        total <= 0 -> ""
        done <= 0 -> " Nothing was sent."
        done >= total -> ""
        else -> " $done of $total files were sent before it stopped; the rest were not."
    }

    /** The text for an exception while talking to another device. */
    fun connectionProblem(e: Throwable, name: String, done: Int = 0, total: Int = 0): String {
        if (e is NotLocalException) return e.message.orEmpty()
        val text = when (kind(e)) {
            Kind.REFUSED -> "$name isn't answering. Check that Amanah is open on it and that Receiving is on, then send again."
            Kind.TIMEOUT -> "$name didn't answer in time. Check that it is awake and on the same Wi-Fi, then send again."
            Kind.UNREACHABLE -> "This phone can't reach $name. Check that both are on the same network " +
                "(the same Wi-Fi or the same hotspot), then send again."
            Kind.DROPPED -> "The connection to $name dropped. A file that was cut off is not kept. " +
                "Check the Wi-Fi on both devices, then send again."
            Kind.SECURITY -> "$name did not give the expected security answer. Check that it is running Amanah " +
                "and that it is the device you meant, then send again."
            Kind.LOOKUP -> "This phone can't find $name by name. Pick it from the list, or add it by its IP address."
            Kind.OTHER -> "The send to $name stopped because of a network problem. Check the Wi-Fi on both devices, then send again."
        }
        return text + progressNote(done, total)
    }

    /** A file on this phone could not be read for sending. */
    fun fileProblem(name: String, e: Throwable): String = when (e) {
        is FileNotFoundException -> "$name was moved or deleted before it was sent. Pick it again."
        is SecurityException -> "Amanah can no longer open $name. Android gives access to a shared file only for a while. Pick it again."
        else -> "Amanah can't read $name (${e.message ?: "unknown error"}). Check the file, then send again."
    }

    /** The text for an answer other than success. [reply] is the JSON body, or null. */
    fun refusal(status: Int, reply: ApiError?, name: String, done: Int = 0, total: Int = 0): String {
        val code = reply?.error.orEmpty()
        val server = reply?.message.orEmpty()
        val template = BY_CODE[code]
        val text = when {
            code == "bad_files" || code == "bad_offer" || code == "bad_text" ->
                // The other device says exactly what it did not accept; it names the file and the reason.
                if (server.isNotEmpty()) "$name refused the send. $server" else "$name refused the send."
            template != null -> template.replace("{name}", name)
            status >= 500 -> "$name had a problem and could not take this send. Try again in a moment."
            server.isNotEmpty() -> "$name said: $server"
            else -> "$name refused the send (answer $status). Check that both devices run the latest Amanah."
        }
        return text + progressNote(done, total)
    }

    /** The text under the Add by IP box. */
    fun addByIp(host: String, e: Throwable): String {
        if (e is NotLocalException) {
            return "$host isn't on your local network, so Amanah won't connect to it. Enter an address like 192.168.1.20."
        }
        return when (kind(e)) {
            Kind.REFUSED -> "Nothing answered at $host. Check the address, and that Amanah is open there with Receiving on."
            Kind.TIMEOUT -> "$host didn't answer. Check that it is on the same network as this phone and awake."
            Kind.UNREACHABLE -> "This phone can't reach $host. Check that both are on the same network."
            Kind.SECURITY -> "$host answered, but not like Amanah. Check the address."
            else -> "No Amanah device answered at $host. Check the address, and that Amanah is open there."
        }
    }
}
