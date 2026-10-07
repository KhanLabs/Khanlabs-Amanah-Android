package dev.khanlabs.lanshare.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.khanlabs.lanshare.net.SendItem
import dev.khanlabs.lanshare.net.ShareServer
import dev.khanlabs.lanshare.protocol.WifiCode
import dev.khanlabs.lanshare.transfer.Engine
import dev.khanlabs.lanshare.transfer.Format
import dev.khanlabs.lanshare.transfer.Items
import dev.khanlabs.lanshare.transfer.OwnWifiView
import dev.khanlabs.lanshare.transfer.ShareView
import dev.khanlabs.lanshare.transfer.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Share to anyone: hands files to a person who does not have Amanah. First pick what to share, for how long and
 * whether to ask for a PIN; then the screen shows a QR code that opens a plain page with only those files.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShareScreen(s: UiState, onBack: () -> Unit, onOwnWifi: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Share to anyone") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Back, contentDescription = "Back") } },
            )
        },
        bottomBar = { MadeWithBar() },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val v = s.share
            if (v == null) Setup(s, onOwnWifi) else Live(v, s.ownWifi)
        }
    }
}

// --- step 1: pick what to share

/** The three ways to pick things to share. [reading] is true while a folder or a long list is being counted. */
private class Pickers(val files: () -> Unit, val media: () -> Unit, val folder: () -> Unit, val reading: Boolean)

/** Pickers for files, photos and videos, and a folder. [onPicked] gets the items and how many could not be read. */
@Composable
private fun rememberPickers(onPicked: (List<SendItem>, Int) -> Unit): Pickers {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reading by remember { mutableStateOf(false) }

    fun addUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            reading = true
            val (items, skipped) = withContext(Dispatchers.IO) { Items.fromUris(context, uris) }
            reading = false
            onPicked(items, skipped)
        }
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), ::addUris)
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(), ::addUris)
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) return@rememberLauncherForActivityResult
        scope.launch {
            reading = true
            val t = withContext(Dispatchers.IO) { Items.fromTree(context, tree) }
            reading = false
            when {
                t.tooMany -> toast(context, "That folder has more than 10,000 files. Share it in parts.")
                t.items.isEmpty() -> toast(context, "That folder has no files to share.")
                else -> onPicked(t.items, t.skipped)
            }
        }
    }
    return Pickers(
        files = { pickFiles.launch(arrayOf("*/*")) },
        media = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
        folder = { pickFolder.launch(null) },
        reading = reading,
    )
}

@Composable
private fun ReadingDialog() {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        title = { Text("Reading") },
        text = { Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(28.dp))
            Spacer(Modifier.width(16.dp))
            Text("Counting the files.")
        } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Setup(s: UiState, onOwnWifi: () -> Unit) {
    val context = LocalContext.current
    var picked by remember { mutableStateOf<List<SendItem>>(emptyList()) }
    var minutes by rememberSaveable { mutableIntStateOf(ShareServer.DEFAULT_MINUTES) }
    var usePin by rememberSaveable { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val pick = rememberPickers { items, skipped ->
        if (skipped > 0) toast(context, "$skipped item(s) couldn't be read and were left out")
        picked = picked + items
        error = null
    }

    Text("Someone without Amanah scans a QR code with their camera and downloads only what you pick here. " +
        "They need to be on the same Wi-Fi as this phone. Nothing goes through the internet.",
        style = MaterialTheme.typography.bodyMedium)

    SectionHeader("What to share")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = pick.files) { Text("Files") }
        OutlinedButton(onClick = pick.media) { Text("Photos and videos") }
        OutlinedButton(onClick = pick.folder) { Text("Folder") }
    }
    if (picked.isEmpty()) {
        Muted("Nothing picked yet.")
    } else {
        Card {
            Column(Modifier.padding(16.dp)) {
                Text("${itemsText(picked.size, "file")}, ${Format.size(picked.sumOf { it.size })}", style = MaterialTheme.typography.titleMedium)
                val tops = picked.map { it.name.substringBefore('/') }.distinct()
                for (name in tops.take(5)) Text(name, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                if (tops.size > 5) Muted("and ${tops.size - 5} more")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { picked = emptyList() }) { Text("Clear") }
                }
            }
        }
    }

    SectionHeader("How long")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((label, m) in ShareServer.TIME_LIMITS) {
            FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text(label) })
        }
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Ask for a 6-digit PIN", style = MaterialTheme.typography.bodyLarge)
            Muted("You tell them the PIN. It is not in the QR code.")
        }
        Switch(checked = usePin, onCheckedChange = { usePin = it })
    }

    val reachable = s.addresses.isNotEmpty() || s.ownWifi.addresses.isNotEmpty()
    if (!reachable) {
        Text("This phone is not on Wi-Fi right now. Connect to the Wi-Fi the other person is on, then start.",
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        Muted("No Wi-Fi anywhere? This phone can make its own network for the other person to join.")
        OutlinedButton(onClick = onOwnWifi) { Text("Make a network") }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    Button(
        enabled = picked.isNotEmpty() && reachable && !starting,
        onClick = {
            starting = true
            error = null
            Engine.startShare(picked, usePin, minutes) { e ->
                starting = false
                error = e
                if (e == null) picked = emptyList()
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (starting) "Starting" else "Start sharing") }

    if (pick.reading) ReadingDialog()
}

// --- step 2: the QR code, while it is on

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Live(v: ShareView, own: OwnWifiView) {
    val context = LocalContext.current
    val addMore = rememberPickers { items, skipped ->
        if (skipped > 0) toast(context, "$skipped item(s) couldn't be read and were left out")
        if (items.isNotEmpty()) Engine.addToShare(items) { e ->
            toast(context, e ?: "Added ${itemsText(items.size, "file")}. They show at the top of the page.")
        }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(v.live, v.endsAt) {
        while (v.live && v.endsAt != null) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    if (!v.live) {
        Text("This share has ended", style = MaterialTheme.typography.titleLarge)
        Muted("The address now says so. Nothing more can be downloaded.")
        Counts(v)
        Button(onClick = { Engine.stopShare() }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        return
    }

    Text("Sharing ${itemsText(v.items, "item")}, ${Format.size(v.bytes)}", style = MaterialTheme.typography.titleMedium)
    if (v.url.isEmpty()) {
        Text("This phone has no Wi-Fi address right now, so there is no code to scan. Connect to Wi-Fi and it appears here.",
            color = MaterialTheme.colorScheme.error)
    } else {
        if (own.hosting) {
            // the other person is not on any network yet: first join this phone's, then open the page
            SectionHeader("Step 1: join this phone's network")
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                QrCode(WifiCode.toText(own.ssid, own.psk), Modifier.widthIn(max = 240.dp).fillMaxWidth(), "QR code that joins this phone's Wi-Fi network")
                Spacer(Modifier.height(8.dp))
                Text("Ask them to scan this with their camera and join. The network has no internet, and their phone may say so.",
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                Muted("${own.ssid}  ·  ${own.psk}", mono = true)
            }
            SectionHeader("Step 2: open the page")
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            QrCode(v.url, Modifier.widthIn(max = 300.dp).fillMaxWidth(), "QR code that opens the shared files on a web page")
            Spacer(Modifier.height(8.dp))
            Text("Ask them to scan this with their camera.", textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        }
        SelectionContainer {
            Text(v.url, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { copyText(context, v.url) }, Modifier.weight(1f)) { Text("Copy link") }
            OutlinedButton(onClick = { shareLink(context, v.url) }, Modifier.weight(1f)) { Text("Send link") }
        }
    }
    v.pin?.let { pin ->
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Muted("PIN")
                Text(pin, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 32.sp, letterSpacing = 4.sp)
                Muted("Tell them the PIN. It is not in the code.")
            }
        }
    }
    SectionHeader("Add more")
    Muted("New files show at the top of the page for people who already opened it. Nothing already shared changes.")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = addMore.files) { Text("Files") }
        OutlinedButton(onClick = addMore.media) { Text("Photos and videos") }
        OutlinedButton(onClick = addMore.folder) { Text("Folder") }
    }
    FileStatus(v)
    SpeedHint(v, own.hosting)
    Counts(v)
    Muted(v.endsAt?.let { "Ends in ${remainingText(it - now)}." } ?: "Stays on until you stop it.")
    if (v.installer) Muted("The page also offers Amanah for Android, so they can install it from this phone.")
    Muted(if (own.hosting) "Keep this phone's network on. Sharing stops when you stop it here, or when the time is up."
          else "Keep this phone on the same Wi-Fi. Sharing stops when you stop it here, or when the time is up.")
    Button(
        onClick = { Engine.stopShare() }, modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError),
    ) { Text("Stop sharing") }
    if (addMore.reading) ReadingDialog()
}

/** Which files are ready for people to download, which are still being read, and which could not be read at all. */
@Composable
private fun FileStatus(v: ShareView) {
    if (v.preparing > 0 && v.files > 0) {
        val done = v.files - v.preparing
        Text("Getting files ready: $done of ${v.files}", style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(progress = { done.toFloat() / v.files }, modifier = Modifier.fillMaxWidth())
        Muted("Each file is read once and checked. People can download a file as soon as it is ready.")
    }
    if (v.failed > 0) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("${itemsText(v.failed, "file")} couldn't be read", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer)
                for (f in v.failedFiles.take(5)) {
                    Text("${f.name}: ${f.reason}", style = MaterialTheme.typography.bodySmall, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(top = 2.dp))
                }
                if (v.failed > 5) Muted("and ${v.failed - 5} more")
                Muted("The page tells people they are missing. Remove them from the list, then pick them again with Add more.")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { Engine.removeUnreadableFromShare() }) { Text("Remove them") }
                }
            }
        }
    }
    if (v.problems > 0) {
        Muted("${itemsText(v.problems, "file")} changed while it was being sent, so that download was stopped. People can try it again.")
    }
}

/**
 * For a big share, how long it takes and why. Most of the wait is the Wi-Fi band: 2.4 GHz moves about 7 MB/s here, 5 GHz
 * three to five times that. Skipped for small shares, where the band makes no difference to anyone.
 */
@Composable
private fun SpeedHint(v: ShareView, hosting: Boolean) {
    val link = v.link ?: return
    if (v.bytes < BIG_SHARE) return
    // a link rate is not what a transfer gets: about 55 per cent of it reaches the file
    val perSecond = link.mbps * 1_000_000L / 8 * 55 / 100
    val eta = if (!hosting && perSecond > 0) etaText(v.bytes / perSecond) else null
    val text = when {
        hosting && link.slowBand ->
            "This phone is also connected to a 2.4 GHz Wi-Fi, so the network it makes is 2.4 GHz too, the slow kind. " +
                "A big share can take many minutes. Turn that Wi-Fi off and start the network again, and it may use 5 GHz."
        link.slowBand ->
            "This phone is on 2.4 GHz Wi-Fi, the slow kind.${eta?.let { " ${Format.size(v.bytes)} takes $it." } ?: ""} " +
                "On 5 GHz it is 3 to 5 times faster. Put both phones on the 5 GHz name of your router, often ending in 5G."
        hosting -> return
        else -> "This phone is on 5 GHz Wi-Fi.${eta?.let { " ${Format.size(v.bytes)} takes $it." } ?: ""}"
    }
    Muted(text)
}

private const val BIG_SHARE = 500L * 1024 * 1024

/** "about 40 s", "about 6 min", "about 1 h 10 min". */
private fun etaText(seconds: Long): String = when {
    seconds < 90 -> "about ${maxOf(seconds, 10)} s"
    seconds < 5400 -> "about ${(seconds + 30) / 60} min"
    else -> "about ${seconds / 3600} h ${(seconds % 3600) / 60} min"
}

@Composable
private fun Counts(v: ShareView) {
    val people = if (v.visitors == 1) "1 person has opened it" else "${v.visitors} people have opened it"
    val files = if (v.downloads == 1) "1 download" else "${v.downloads} downloads"
    Muted("$people  ·  $files  ·  ${Format.size(v.sent)} sent")
}

/** "42 min", "3 min 20 s", "45 s". */
internal fun remainingText(ms: Long): String {
    val total = maxOf(0L, ms) / 1000
    return when {
        total >= 3600 -> "${total / 3600} h ${(total % 3600) / 60} min"
        total >= 600 -> "${total / 60} min"
        total >= 60 -> "${total / 60} min ${total % 60} s"
        else -> "$total s"
    }
}

private fun itemsText(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

private fun shareLink(context: Context, url: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
    try {
        context.startActivity(Intent.createChooser(send, "Send link").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        toast(context, "No app can send the link")
    }
}

// --- the entry on the Send tab

/** "Share to anyone" on the Send tab; says "Sharing now" and the counts while a share is on. */
@Composable
internal fun ShareAnyoneCard(v: ShareView?, onClick: () -> Unit) {
    Card(onClick = onClick, Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph(Icons.Share, MaterialTheme.colorScheme.primary, 28)
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                if (v == null) {
                    Text("Share to anyone", style = MaterialTheme.typography.titleMedium)
                    Muted("A QR code for a person who doesn't have Amanah")
                } else {
                    Text(if (v.live) "Sharing now" else "Share ended", style = MaterialTheme.typography.titleMedium)
                    Muted("${itemsText(v.items, "item")}  ·  ${if (v.downloads == 1) "1 download" else "${v.downloads} downloads"}")
                }
            }
            Glyph(Icons.ChevronRight)
        }
    }
}
