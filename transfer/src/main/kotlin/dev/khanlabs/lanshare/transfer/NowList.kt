package dev.khanlabs.lanshare.transfer

import dev.khanlabs.lanshare.net.SendJob

/**
 * Which finished transfers stay on the Send screen's "Now" list. Activity keeps the record of every one, so Now holds only
 * what is happening, and what still needs the person: a send that failed or was cancelled with files left to retry.
 */
object NowList {
    /** How long a finished card stays, so the outcome ("Sent", "Verified", "Declined") can be read before it goes. */
    const val STAYS_MS = 8_000L

    /** Does a finished send leave Now by itself? [unsent] is how many of its files did not get across. */
    fun outgoingLeaves(state: SendJob.State, unsent: Int): Boolean = when (state) {
        SendJob.State.DONE, SendJob.State.DECLINED -> true
        SendJob.State.FAILED, SendJob.State.CANCELLED -> unsent == 0 // nothing to try again: a text, or all files were there
        else -> false // still going
    }

    /** Does a finished incoming send leave Now by itself? The same actions (Open, Share, Copy text) are in Activity. */
    fun incomingLeaves(state: String): Boolean = state in setOf("done", "declined", "expired", "cancelled")
}
