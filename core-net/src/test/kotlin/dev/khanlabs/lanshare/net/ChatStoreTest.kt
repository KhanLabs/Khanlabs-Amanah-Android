package dev.khanlabs.lanshare.net

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatStoreTest {
    private lateinit var dir: File
    private lateinit var file: File
    private val a = "A".repeat(64)
    private val b = "B".repeat(64)

    @BeforeTest
    fun start() {
        dir = Files.createTempDirectory("klshare-chats-").toFile()
        file = File(dir, "chats.jsonl")
    }

    @AfterTest
    fun stop() {
        dir.deleteRecursively()
    }

    @Test
    fun addsAndKeepsTheOrder() {
        val s = ChatStore(file)
        s.add(a, "Laptop", incoming = false, "one")
        s.add(a, "Laptop", incoming = true, "two")
        s.add(b, "Tablet", incoming = true, "three")
        assertEquals(listOf("one", "two"), s.thread(a).map { it.text })
        assertEquals(listOf(b, a), s.conversations().map { it.peer })
    }

    @Test
    fun comesBackAfterARestart() {
        val s = ChatStore(file)
        s.add(a, "Laptop", incoming = true, "gate \"4471\"\nsecond line ✓")
        val again = ChatStore(file)
        assertEquals("gate \"4471\"\nsecond line ✓", again.thread(a).single().text)
        assertEquals(1, again.unread())
    }

    @Test
    fun unreadAndMarkRead() {
        val s = ChatStore(file)
        s.add(a, "Laptop", incoming = true, "1")
        s.add(a, "Laptop", incoming = true, "2")
        s.add(b, "Tablet", incoming = false, "mine")
        assertEquals(2, s.unread())
        assertEquals(0, s.unread(b))
        assertTrue(s.markRead(a))
        assertFalse(s.markRead(a))
        assertEquals(0, ChatStore(file).unread())
    }

    @Test
    fun knowsOnlyAfterATextWentThrough() {
        val s = ChatStore(file)
        assertFalse(s.knows(a))
        val m = s.add(a, "Laptop", incoming = false, "hi", state = "sending")
        assertFalse(s.knows(a), "still waiting for them")
        s.setState(m.id, "declined")
        assertFalse(s.knows(a), "they said no")
        val n = s.add(a, "Laptop", incoming = false, "hello", state = "sending")
        s.setState(n.id, "failed", "Not nearby")
        assertFalse(s.knows(a), "never arrived")
        s.setState(n.id, "done")
        assertTrue(s.knows(a))
        assertTrue(ChatStore(file).knows(a))
    }

    @Test
    fun aReceivedTextMakesTheDeviceKnown() {
        val s = ChatStore(file)
        s.add(b, "Tablet", incoming = true, "hi")
        assertTrue(s.knows(b))
        assertFalse(s.knows(a))
    }

    @Test
    fun forgetRemovesTheConversationAndTheTrust() {
        val s = ChatStore(file)
        s.add(a, "Laptop", incoming = true, "x")
        s.add(b, "Tablet", incoming = true, "y")
        s.forget(a)
        assertFalse(s.knows(a))
        assertTrue(s.knows(b))
        assertEquals(listOf(b), ChatStore(file).conversations().map { it.peer })
    }

    @Test
    fun aTextThatWasSendingWhenTheAppClosedIsFailed() {
        val s = ChatStore(file)
        val m = s.add(a, "Laptop", incoming = false, "x", state = "sending")
        assertEquals("sending", s.all.single().state)
        assertEquals("failed", ChatStore(file).all.single { it.id == m.id }.state)
    }

    @Test
    fun setStateReportsChanges() {
        val s = ChatStore(file)
        val m = s.add(a, "Laptop", incoming = false, "x", state = "sending")
        assertEquals("done", s.setState(m.id, "done")?.state)
        assertNull(s.setState(m.id, "done"))
        assertNull(s.setState("nope", "done"))
    }

    @Test
    fun conversationsUseThePositionNotTheClock() {
        val s = ChatStore(file, clock = { 1000L }) // every text in the same millisecond
        s.add(a, "Laptop", incoming = true, "first")
        s.add(b, "Tablet", incoming = true, "second")
        s.add(a, "Laptop", incoming = true, "third")
        val c = s.conversations()
        assertEquals(listOf(a, b), c.map { it.peer })
        assertEquals("third", c[0].last.text)
        assertEquals(2, c[0].unread)
    }

    @Test
    fun theLatestNameWins() {
        val s = ChatStore(file)
        s.add(a, "Old name", incoming = true, "1")
        s.add(a, "New name", incoming = true, "2")
        assertEquals("New name", s.conversations().single().name)
    }

    @Test
    fun keepsOnlyTheLastOnes() {
        val s = ChatStore(file, keep = 3)
        repeat(5) { s.add(a, "Laptop", incoming = true, "m$it") }
        assertEquals(listOf("m2", "m3", "m4"), ChatStore(file, keep = 3).all.map { it.text })
    }

    @Test
    fun skipsBrokenLines() {
        file.writeText("not json\n{\"id\":\"1\"}\n")
        val s = ChatStore(file)
        assertTrue(s.all.isEmpty())
        s.add(a, "Laptop", incoming = true, "ok")
        assertEquals(listOf("ok"), ChatStore(file).all.map { it.text })
    }

    @Test
    fun worksWithoutAFile() {
        val s = ChatStore(null)
        s.add(a, "Laptop", incoming = true, "x")
        assertEquals(1, s.all.size)
    }

    private fun device(id: String, vararg hosts: String) = Device(id, "D", "android", hosts.toList(), 47820)

    @Test
    fun peerIsTheDeviceAtThatAddress() {
        val devices = listOf(device(a, "10.0.0.5"), device(b, "10.0.0.6"))
        assertEquals(a, ChatStore.peerOf(b, "10.0.0.5", devices), "the ID in an offer proves nothing")
        assertEquals(b, ChatStore.peerOf("", "10.0.0.6", devices))
    }

    @Test
    fun anAnnouncedIdOfADeviceAtAnotherAddressIsNotUsed() {
        val devices = listOf(device(a, "10.0.0.5"))
        assertEquals("ip:10.0.0.9", ChatStore.peerOf(a, "10.0.0.9", devices))
    }

    @Test
    fun anAnnouncedIdIsUsedWhenNothingContradictsIt() {
        assertEquals(b, ChatStore.peerOf(b.lowercase(), "10.0.0.9", emptyList()))
        assertEquals("ip:10.0.0.9", ChatStore.peerOf("short", "10.0.0.9", emptyList()))
    }
}
