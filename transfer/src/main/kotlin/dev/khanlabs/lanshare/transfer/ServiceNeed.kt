package dev.khanlabs.lanshare.transfer

import dev.khanlabs.lanshare.net.SendJob

/**
 * When the foreground service must run. A send that has begun or a receive that was accepted counts, so Android keeps
 * the process when the app goes to the background; before, only receiving, a share or a Wi-Fi network did.
 * Kept apart from [Engine] so the rules can be tested.
 */
object ServiceNeed {
    /** A send that has not ended: connecting, waiting for the other side to answer, or sending. */
    fun running(state: SendJob.State): Boolean =
        state == SendJob.State.CONNECTING || state == SendJob.State.WAITING || state == SendJob.State.SENDING

    /** A receive the person accepted that is still coming in. [state] is [IncomingView.state]. */
    fun accepted(state: String): Boolean = state == "accepted"

    fun transferring(sends: List<SendJob.State>, receives: List<String>): Boolean =
        sends.any(::running) || receives.any(::accepted)

    fun needed(receiving: Boolean, sharing: Boolean, ownNetwork: Boolean, transferring: Boolean): Boolean =
        receiving || sharing || ownNetwork || transferring
}
