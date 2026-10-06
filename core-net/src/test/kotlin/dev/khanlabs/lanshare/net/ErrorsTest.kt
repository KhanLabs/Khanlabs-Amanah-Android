package dev.khanlabs.lanshare.net

import dev.khanlabs.lanshare.protocol.ApiError
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The error messages: each says what to do, and none shows raw system text. */
class ErrorsTest {
    private val name = "Khan's PC"

    @Test
    fun everyKindSaysWhatToDoAndHidesRawText() {
        val cases = mapOf(
            "isn't answering" to ConnectException("failed to connect to /192.168.1.5 (port 47820): ECONNREFUSED (Connection refused)"),
            "didn't answer in time" to SocketTimeoutException("failed to connect to /192.168.1.5 after 5000ms"),
            "can't reach" to ConnectException("failed to connect: ENETUNREACH (Network is unreachable)"),
            "connection to" to SocketException("Connection reset"),
            "security answer" to SSLHandshakeException("PKIX path building failed"),
            "find $name by name" to UnknownHostException("pc.local"),
            "network problem" to IOException("odd"),
        )
        for ((expect, e) in cases) {
            val text = Errors.connectionProblem(e, name)
            assertTrue(expect in text, "$expect in $text")
            assertTrue(name in text)
            assertFalse("ECONN" in text || "ENET" in text || "PKIX" in text || "Exception" in text, text)
            assertTrue(text.trimEnd('.').endsWith("again") || text.endsWith("list, or add it by its IP address."), text)
        }
    }

    @Test
    fun kindsAreFoundThroughTheCauses() {
        assertEquals(Errors.Kind.REFUSED, Errors.kind(IOException("wrapped", ConnectException("Connection refused"))))
        assertEquals(Errors.Kind.TIMEOUT, Errors.kind(IOException("timeout")))
        assertEquals(Errors.Kind.UNREACHABLE, Errors.kind(NoRouteToHostException("No route to host")))
        assertEquals(Errors.Kind.DROPPED, Errors.kind(EOFException()))
        assertEquals(Errors.Kind.DROPPED, Errors.kind(IOException("unexpected end of stream on https://x")))
        assertEquals(Errors.Kind.DROPPED, Errors.kind(SSLHandshakeException("Remote host terminated the handshake")))
        assertEquals(Errors.Kind.OTHER, Errors.kind(IllegalStateException("x")))
    }

    @Test
    fun progressIsPartOfTheMessage() {
        val e = SocketException("Connection reset")
        assertTrue("Nothing was sent" in Errors.connectionProblem(e, name, 0, 4))
        assertTrue("2 of 4 files were sent before it stopped" in Errors.connectionProblem(e, name, 2, 4))
        assertFalse("files were sent" in Errors.connectionProblem(e, name, 4, 4))
        assertFalse("Nothing was sent" in Errors.connectionProblem(e, name, 0, 0)) // a text has no files
    }

    @Test
    fun anAddressOffTheNetworkSaysSo() {
        val e = NotLocalException("8.8.8.8")
        assertEquals("That address isn't on your local network, so Amanah won't connect to it.", Errors.connectionProblem(e, name))
        assertTrue("192.168.1.20" in Errors.addByIp("8.8.8.8", e))
    }

    @Test
    fun filesThatCannotBeRead() {
        assertTrue("moved or deleted" in Errors.fileProblem("a.txt", FileNotFoundException()))
        assertTrue("no longer open a.txt" in Errors.fileProblem("a.txt", SecurityException("denied")))
        val other = Errors.fileProblem("a.txt", IOException("I/O error"))
        assertTrue("a.txt" in other && "I/O error" in other)
    }

    @Test
    fun everyKnownCodeHasAMessageThatNamesTheDeviceOrSaysWhatToDo() {
        for ((code, template) in Errors.BY_CODE) {
            val text = Errors.refusal(400, ApiError(code, "server words"), name)
            assertEquals(template.replace("{name}", name), text)
            assertFalse("{" in text)
        }
    }

    @Test
    fun theOtherDeviceNamesTheFile() {
        val reply = ApiError("bad_files", "\"  notes.txt\": a name cannot start with a space.")
        assertEquals("$name refused the send. \"  notes.txt\": a name cannot start with a space. Nothing was sent.",
            Errors.refusal(400, reply, name, 0, 3))
    }

    @Test
    fun unknownAnswersStillGetAMessage() {
        assertTrue("had a problem" in Errors.refusal(503, null, name))
        assertTrue("said: slow down" in Errors.refusal(429, ApiError("new_thing", "slow down"), name))
        assertTrue("answer 418" in Errors.refusal(418, null, name))
    }

    @Test
    fun addByIpMessages() {
        assertTrue("Nothing answered at 192.168.1.5" in Errors.addByIp("192.168.1.5", ConnectException("Connection refused")))
        assertTrue("didn't answer" in Errors.addByIp("192.168.1.5", SocketTimeoutException()))
        assertTrue("can't reach" in Errors.addByIp("192.168.1.5", NoRouteToHostException()))
        assertTrue("not like Amanah" in Errors.addByIp("192.168.1.5", SSLHandshakeException("x")))
        assertTrue("No Amanah device answered" in Errors.addByIp("192.168.1.5", IOException("odd")))
    }
}
