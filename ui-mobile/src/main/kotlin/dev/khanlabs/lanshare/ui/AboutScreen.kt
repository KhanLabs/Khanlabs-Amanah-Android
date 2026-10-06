package dev.khanlabs.lanshare.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.khanlabs.lanshare.transfer.Engine

/** The app is free with no ads, tracking or account. Support is optional and goes through the website, so the app
 *  itself never talks to a payment service or the internet. Same links as the Windows app (brand.py). */
private const val SUPPORT_URL = "https://khanlabs.dev/support"
private val AMOUNTS = listOf(2, 5, 10, 25)
private val PROMISES = listOf("No ads.", "No tracking.", "No account.", "No cloud needed.", "Free forever.")
private const val AUTHOR = "Khan"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember { versionName(context) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Back, contentDescription = "Back") } },
            )
        },
        bottomBar = { MadeWithBar() },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Amanah", style = MaterialTheme.typography.titleLarge)
            Muted("File sharing by KhanLabs")
            Muted("Version $version")
            Spacer(Modifier.height(16.dp))
            Text("Built for people, not advertisers.", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
            PROMISES.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
            Spacer(Modifier.height(4.dp))
            Muted("Files go straight from one device to the other on your Wi-Fi. Nothing is sent to KhanLabs or anyone else.")
            Spacer(Modifier.height(16.dp))
            Text("Something not working?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Muted("Copy a short report to paste into a message to the developer. Nothing is sent anywhere. It has the app version, your phone model, device names and the latest errors, so read it first.")
            OutlinedButton(onClick = { copyText(context, Engine.errorReport(), "Copied. Read it before you send it.") }) {
                Text("Copy error report")
            }
            Spacer(Modifier.height(16.dp))
            SupportCard(context)
        }
    }
}

@Composable
private fun SupportCard(context: Context) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Enjoying the app?", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text("This app is free, private and supported by people like you. If it has been useful, you can support development.")
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AMOUNTS.forEach { amount ->
                    OutlinedButton(onClick = { openLink(context, "$SUPPORT_URL?amount=$amount") }, Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
                        Text("$$amount", maxLines = 1, softWrap = false)
                    }
                }
            }
            OutlinedButton(onClick = { openLink(context, SUPPORT_URL) }, Modifier.fillMaxWidth()) { Text("Custom amount") }
            Spacer(Modifier.height(8.dp))
            Muted("Opens khanlabs.dev in your browser. The app itself never connects to the internet.")
        }
    }
}

/** "Made with (heart) by Khan", pinned to the bottom of every screen. */
@Composable
fun MadeWithBar() {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Made with ", style = MaterialTheme.typography.bodySmall, color = muted)
            Icon(Icons.Heart, contentDescription = "love", tint = HeartRed, modifier = Modifier.size(14.dp))
            Text(" by $AUTHOR", style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
}

internal fun versionName(context: Context): String = try {
    val pm = context.packageManager
    val info = if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
               else @Suppress("DEPRECATION") pm.getPackageInfo(context.packageName, 0)
    info.versionName ?: ""
} catch (e: PackageManager.NameNotFoundException) {
    ""
}

private val HeartRed = Color(0xFFFF5F57)
