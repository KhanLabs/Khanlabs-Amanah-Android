package dev.khanlabs.lanshare.transfer

import dev.khanlabs.lanshare.net.SendJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServiceNeedTest {
    private fun transferring(vararg sends: SendJob.State, receives: List<String> = emptyList()) =
        ServiceNeed.transferring(sends.toList(), receives)

    @Test
    fun aSendThatHasNotEndedKeepsTheServiceAlive() {
        assertTrue(transferring(SendJob.State.CONNECTING))
        assertTrue(transferring(SendJob.State.WAITING))
        assertTrue(transferring(SendJob.State.SENDING))
        assertTrue(transferring(SendJob.State.DONE, SendJob.State.SENDING), "one running send is enough")
    }

    @Test
    fun aSendThatEndedDoesNot() {
        for (state in listOf(SendJob.State.DONE, SendJob.State.DECLINED, SendJob.State.FAILED, SendJob.State.CANCELLED)) {
            assertFalse(transferring(state), "$state")
        }
        assertFalse(transferring())
    }

    @Test
    fun anAcceptedReceiveKeepsItAliveButOneNobodyAnsweredDoesNot() {
        assertTrue(transferring(receives = listOf("done", "accepted")))
        for (state in listOf("waiting", "declined", "expired", "cancelled", "done")) {
            assertFalse(transferring(receives = listOf(state)), state)
        }
    }

    @Test
    fun theServiceRunsForAnyOneReason() {
        assertFalse(ServiceNeed.needed(receiving = false, sharing = false, ownNetwork = false, transferring = false))
        assertTrue(ServiceNeed.needed(receiving = true, sharing = false, ownNetwork = false, transferring = false))
        assertTrue(ServiceNeed.needed(receiving = false, sharing = true, ownNetwork = false, transferring = false))
        assertTrue(ServiceNeed.needed(receiving = false, sharing = false, ownNetwork = true, transferring = false))
        assertTrue(ServiceNeed.needed(receiving = false, sharing = false, ownNetwork = false, transferring = true),
            "a send with receiving off")
    }

    @Test
    fun everySendStateIsDecided() {
        // a new state must be put in one list or the other on purpose
        val running = SendJob.State.entries.filter { ServiceNeed.running(it) }
        assertEquals(listOf(SendJob.State.CONNECTING, SendJob.State.WAITING, SendJob.State.SENDING), running)
    }
}
