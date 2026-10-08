package dev.khanlabs.lanshare.transfer

import dev.khanlabs.lanshare.net.SendJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NowListTest {
    @Test
    fun aSentOrDeclinedSendLeavesNowByItself() {
        assertTrue(NowList.outgoingLeaves(SendJob.State.DONE, unsent = 0))
        assertTrue(NowList.outgoingLeaves(SendJob.State.DECLINED, unsent = 3), "declined, nothing to retry on this card")
    }

    @Test
    fun aFailedOrCancelledSendStaysWhileThereAreFilesToTryAgain() {
        assertFalse(NowList.outgoingLeaves(SendJob.State.FAILED, unsent = 2))
        assertFalse(NowList.outgoingLeaves(SendJob.State.CANCELLED, unsent = 1))
    }

    @Test
    fun aFailedTextOrAFailedSendWithNothingLeftHasNothingToDoOnNowAndLeaves() {
        assertTrue(NowList.outgoingLeaves(SendJob.State.FAILED, unsent = 0))
        assertTrue(NowList.outgoingLeaves(SendJob.State.CANCELLED, unsent = 0))
    }

    @Test
    fun aSendThatIsStillGoingStays() {
        for (s in listOf(SendJob.State.CONNECTING, SendJob.State.WAITING, SendJob.State.SENDING)) {
            assertFalse(NowList.outgoingLeaves(s, unsent = 0), s.name)
        }
    }

    @Test
    fun everyFinishedIncomingStateLeavesAndAWaitingOrRunningOneDoesNot() {
        for (s in listOf("done", "declined", "expired", "cancelled")) assertTrue(NowList.incomingLeaves(s), s)
        for (s in listOf("waiting", "accepted")) assertFalse(NowList.incomingLeaves(s), s)
    }

    @Test
    fun aFinishedCardStaysLongEnoughToReadAndNotLongEnoughToPileUp() {
        assertTrue(NowList.STAYS_MS in 3_000..15_000, "${NowList.STAYS_MS} ms")
        assertEquals(8_000L, NowList.STAYS_MS)
    }
}
