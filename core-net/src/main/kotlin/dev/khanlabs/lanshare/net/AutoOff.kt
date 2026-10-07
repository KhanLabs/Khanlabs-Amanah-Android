package dev.khanlabs.lanshare.net

/**
 * "Turn receiving off by itself": a person who leaves receiving on in a cafe or at work is visible to everyone on that
 * network. The choice is [NEVER] (the default), [HOUR] (an hour after receiving was turned on) or [NETWORK] (when the
 * Wi-Fi changes). The rules live here, away from Android, so they can be tested without a phone. Same words and rules
 * as the Windows app.
 */
object AutoOff {
    const val NEVER = "never"
    const val HOUR = "hour"
    const val NETWORK = "network"

    const val HOUR_MS = 60L * 60 * 1000

    /** Key and the words on the chips, in the order they are shown. */
    val CHOICES = listOf(NEVER to "Never", HOUR to "After 1 hour", NETWORK to "When the Wi-Fi changes")

    /** The key for [key], or [NEVER] for anything this version does not know. */
    fun parse(key: String?): String = if (CHOICES.any { it.first == key }) key!! else NEVER

    fun describe(mode: String): String = when (mode) {
        HOUR -> "Receiving turns itself off an hour after you turn it on."
        NETWORK -> "Receiving turns itself off when this phone joins another Wi-Fi network or loses its Wi-Fi."
        else -> "Receiving stays on until you turn it off."
    }

    /** What the status card and the notification say after receiving turned itself off. */
    fun reason(mode: String): String = when (mode) {
        HOUR -> "Receiving turned off after an hour. Turn it on again when you want to receive."
        else -> "Receiving turned off because the Wi-Fi network changed. Turn it on again when you want to receive."
    }

    /** True when [mode] is [HOUR] and an hour has passed since receiving was turned on at [onSince] (milliseconds since 1970). */
    fun hourUp(mode: String, onSince: Long, now: Long): Boolean = mode == HOUR && onSince > 0 && now - onSince >= HOUR_MS

    /** Milliseconds until the hour is up, never less than a second, so a timer set from it fires after the hour. */
    fun hourLeft(onSince: Long, now: Long): Long = (HOUR_MS - (now - onSince)).coerceIn(1_000L, HOUR_MS)

    /**
     * True when [mode] is [NETWORK] and the phone's local addresses changed from [before] to [after]. Having no address
     * before (no Wi-Fi yet) is not a change: that is the phone joining its first network.
     */
    fun networkMoved(mode: String, before: List<String>, after: List<String>): Boolean =
        mode == NETWORK && before.isNotEmpty() && before.sorted() != after.sorted()
}
