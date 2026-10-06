package dev.khanlabs.lanshare

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import dev.khanlabs.lanshare.transfer.Engine
import dev.khanlabs.lanshare.transfer.TransferService
import dev.khanlabs.lanshare.ui.HomeScreen
import dev.khanlabs.lanshare.ui.LanShareTheme
import dev.khanlabs.lanshare.ui.Shared
import dev.khanlabs.lanshare.ui.WelcomeScreen

class MainActivity : ComponentActivity() {
    private var shared by mutableStateOf(Shared())
    private var link by mutableStateOf("")  // a klshare:// connect link opened from a camera app

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    /** Debug builds only: a text size and screen width picked in Settings, so layouts can be tested on any phone. */
    override fun attachBaseContext(newBase: Context) {
        val debuggable = newBase.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val prefs = newBase.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val scale = if (debuggable) prefs.getFloat("debug_font_scale", 1f) else 1f
        val small = debuggable && prefs.getBoolean("debug_small_screen", false)
        if (scale == 1f && !small) return super.attachBaseContext(newBase)
        val config = Configuration(newBase.resources.configuration)
        config.fontScale = scale
        if (small) {
            val widthPx = newBase.resources.displayMetrics.widthPixels
            config.densityDpi = widthPx * 160 / 320 // 320 dp wide
        }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) readShare(intent)
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        var welcomed by mutableStateOf(prefs.getBoolean("welcomed", false))
        setContent {
            LanShareTheme {
                if (welcomed) {
                    HomeScreen(shared, link, onLinkHandled = { link = "" }, onShareDone = { shared = Shared() },
                        onReceivingChange = ::setReceiving)
                } else {
                    WelcomeScreen(permissionNote = permissionNote(), onContinue = {
                        prefs.edit().putBoolean("welcomed", true).apply()
                        welcomed = true
                        askForPermissions()
                    })
                }
            }
        }
        // Asked once, on "Get started". After that the Receiving sheet explains and links to Settings, so a "no" is respected.
    }

    /** Runtime permissions this phone needs, in plain words for the welcome screen. */
    private fun permissionNote(): String = when {
        Build.VERSION.SDK_INT >= ANDROID_17 ->
            "Next, Android will ask to show notifications and to find devices on your Wi-Fi. " +
                "Notifications are how you accept a file when the app is closed."
        Build.VERSION.SDK_INT >= 33 ->
            "Next, Android will ask to show notifications. That is how you accept a file when the app is closed."
        else -> ""
    }

    private fun askForPermissions() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= ANDROID_17) add(ACCESS_LOCAL_NETWORK)
            if (Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) askPermissions.launch(wanted.toTypedArray())
    }

    override fun onStart() {
        super.onStart()
        if (Engine.receiving) TransferService.start(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readShare(intent)
    }

    private fun setReceiving(on: Boolean) {
        Engine.setReceiving(on)
        if (on) TransferService.start(this) else TransferService.stop(this)
    }

    /** Files or text from another app's Share button, or a connect link from a camera app. */
    private fun readShare(intent: Intent?) {
        intent ?: return
        if (intent.action == Intent.ACTION_VIEW) {
            intent.data?.let { link = it.toString() }
            return
        }
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.toList() ?: emptyList()
            else -> return
        }
        val text = if (uris.isEmpty()) intent.getStringExtra(Intent.EXTRA_TEXT) ?: "" else ""
        shared = Shared(uris, text)
    }

    private companion object {
        /** Android 17 (API 37) makes apps ask before they talk to devices on the local network. Not tested on a real device yet. */
        const val ANDROID_17 = 37
        const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
