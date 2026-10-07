package dev.khanlabs.lanshare.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoOffTest {
    private val hour = AutoOff.HOUR_MS

    @Test
    fun anUnknownChoiceMeansNever() {
        assertEquals("never", AutoOff.parse(null))
        assertEquals("never", AutoOff.parse("sometimes"))
        assertEquals("hour", AutoOff.parse("hour"))
        assertEquals("network", AutoOff.parse("network"))
        assertEquals(listOf("never", "hour", "network"), AutoOff.CHOICES.map { it.first })
    }

    @Test
    fun theHourCountsFromWhenReceivingWasTurnedOn() {
        val on = 1_000_000L
        assertFalse(AutoOff.hourUp(AutoOff.HOUR, on, on + hour - 1))
        assertTrue(AutoOff.hourUp(AutoOff.HOUR, on, on + hour))
        assertTrue(AutoOff.hourUp(AutoOff.HOUR, on, on + 5 * hour))
    }

    @Test
    fun theHourDoesNothingForOtherChoicesOrWithoutAStartTime() {
        assertFalse(AutoOff.hourUp(AutoOff.NEVER, 1L, 10 * hour))
        assertFalse(AutoOff.hourUp(AutoOff.NETWORK, 1L, 10 * hour))
        assertFalse(AutoOff.hourUp(AutoOff.HOUR, 0L, 10 * hour))
    }

    @Test
    fun aClockSetBackwardsIsNotAnHour() {
        assertFalse(AutoOff.hourUp(AutoOff.HOUR, 10 * hour, hour))
    }

    @Test
    fun theTimeLeftIsBetweenASecondAndAnHour() {
        assertEquals(hour, AutoOff.hourLeft(5_000L, 5_000L))
        assertEquals(hour / 2, AutoOff.hourLeft(0L, hour / 2))
        assertEquals(1_000L, AutoOff.hourLeft(0L, hour + 5))
        assertEquals(hour, AutoOff.hourLeft(10 * hour, 0L)) // a clock set backwards waits at most an hour
    }

    @Test
    fun aChangedNetworkTurnsItOffOnlyForTheNetworkChoice() {
        val home = listOf("192.168.1.20")
        val cafe = listOf("10.0.0.7")
        assertTrue(AutoOff.networkMoved(AutoOff.NETWORK, home, cafe))
        assertFalse(AutoOff.networkMoved(AutoOff.NEVER, home, cafe))
        assertFalse(AutoOff.networkMoved(AutoOff.HOUR, home, cafe))
    }

    @Test
    fun losingTheWifiCountsAsAChangeButJoiningTheFirstOneDoesNot() {
        assertTrue(AutoOff.networkMoved(AutoOff.NETWORK, listOf("192.168.1.20"), emptyList()))
        assertFalse(AutoOff.networkMoved(AutoOff.NETWORK, emptyList(), listOf("192.168.1.20")))
        assertFalse(AutoOff.networkMoved(AutoOff.NETWORK, emptyList(), emptyList()))
    }

    @Test
    fun theSameAddressesInAnotherOrderAreNoChange() {
        assertFalse(AutoOff.networkMoved(AutoOff.NETWORK, listOf("192.168.1.20", "192.168.49.1"), listOf("192.168.49.1", "192.168.1.20")))
    }

    @Test
    fun everyChoiceHasWordsForTheScreen() {
        for ((key, label) in AutoOff.CHOICES) {
            assertTrue(label.isNotBlank())
            assertTrue(AutoOff.describe(key).isNotBlank())
        }
        assertTrue(AutoOff.reason(AutoOff.HOUR).contains("hour"))
        assertTrue(AutoOff.reason(AutoOff.NETWORK).contains("Wi-Fi"))
    }
}
