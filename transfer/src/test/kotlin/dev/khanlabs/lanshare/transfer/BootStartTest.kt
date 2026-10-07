package dev.khanlabs.lanshare.transfer

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BootStartTest {
    @Test
    fun theSwitchIsOffUntilThePersonTurnsItOn() {
        assertFalse(BootStart.DEFAULT)
        assertFalse(UiState().bootStart)
    }

    @Test
    fun startsAfterBootOnlyWhenAskedForAndReceivingIsOn() {
        assertTrue(BootStart.shouldStart(optedIn = true, receiving = true))
        assertFalse(BootStart.shouldStart(optedIn = true, receiving = false), "receiving is off: nothing to start")
        assertFalse(BootStart.shouldStart(optedIn = false, receiving = true), "never without being asked")
        assertFalse(BootStart.shouldStart(optedIn = false, receiving = false))
    }

    // The manifest decides what Android allows, so a slip there fails on the phone and not here. These lines guard it.
    private val manifest: String by lazy { File("src/main/AndroidManifest.xml").readText() }

    @Test
    fun theServiceIsConnectedDeviceWithDataSyncAsTheFallback() {
        assertTrue("android:foregroundServiceType=\"connectedDevice|dataSync\"" in manifest)
        assertTrue("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" in manifest)
        assertTrue("android.permission.FOREGROUND_SERVICE_DATA_SYNC" in manifest)
        // connectedDevice needs one of these on Android 14 and newer
        assertTrue(listOf("CHANGE_WIFI_MULTICAST_STATE", "CHANGE_WIFI_STATE", "CHANGE_NETWORK_STATE").any { "android.permission.$it" in manifest })
    }

    @Test
    fun theBootReceiverIsNotExportedAndOnlyListensForBoot() {
        assertTrue("android.permission.RECEIVE_BOOT_COMPLETED" in manifest)
        val receiver = manifest.substringAfter("android:name=\".BootReceiver\"").substringBefore("</receiver>")
        assertTrue("android:exported=\"false\"" in receiver)
        assertEquals(listOf("android.intent.action.BOOT_COMPLETED"),
            Regex("<action android:name=\"([^\"]+)\"").findAll(receiver).map { it.groupValues[1] }.toList())
    }
}
