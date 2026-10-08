package dev.khanlabs.lanshare.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.khanlabs.lanshare.net.Device
import dev.khanlabs.lanshare.net.SendItem
import dev.khanlabs.lanshare.protocol.ConnectCode
import dev.khanlabs.lanshare.protocol.DeviceId
import dev.khanlabs.lanshare.protocol.WifiCode
import dev.khanlabs.lanshare.transfer.Engine
import dev.khanlabs.lanshare.transfer.Format
import dev.khanlabs.lanshare.transfer.HeldFiles
import dev.khanlabs.lanshare.transfer.Items
import dev.khanlabs.lanshare.transfer.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Files or text another app shared with this one, waiting for the person to pick a device. [deviceId] is set when
 * the person already picked one, in the Share menu or the app icon's menu.
 */
data class Shared(
    val uris: List<Uri> = emptyList(),
    val text: String = "",
    val deviceId: String = "",
    /** The files in [uris], being opened already (see Engine.holdFiles). */
    val held: HeldFiles? = null,
) {
    val isEmpty: Boolean get() = uris.isEmpty() && text.isEmpty()
}

internal enum class Tab(val label: String, val icon: ImageVector) {
    Send("Send", Icons.Send), Messages("Messages", Icons.Chat), Activity("Activity", Icons.Activity),
    Settings("Settings", Icons.Settings)
}

/** Something picked, waiting for a device to be chosen. */
private class Pending(
    val what: String,
    val uris: List<Uri> = emptyList(),
    val items: List<SendItem> = emptyList(),
    val text: String = "",
    val fromShare: Boolean = false,
    /** Opened already, in place of [uris]. */
    val held: HeldFiles? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    shared: Shared,
    link: String,
    onLinkHandled: () -> Unit,
    /** A message notification was tapped: the other device's ID. */
    openChat: String,
    onChatHandled: () -> Unit,
    onShareDone: () -> Unit,
    onReceivingChange: (Boolean) -> Unit,
) {
    val s by Engine.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // the Settings choice decides the tab at a fresh start; a recreated screen (turning the phone) keeps the tab it was on
    var tab by rememberSaveable { mutableStateOf(Tab.entries.firstOrNull { it.name == s.startTab } ?: Tab.Send) }
    var aboutOpen by rememberSaveable { mutableStateOf(false) }
    var statsOpen by rememberSaveable { mutableStateOf(false) }  // the Stats screen
    var shareOpen by rememberSaveable { mutableStateOf(false) }  // the Share to anyone screen
    var wifiOpen by rememberSaveable { mutableStateOf(false) }   // the Wi-Fi network screen
    var receivingOpen by remember { mutableStateOf(false) }
    var helpOpen by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    var scanOpen by remember { mutableStateOf(false) }
    var qrOpen by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var connectingText by remember { mutableStateOf("Checking the device.") }
    var joinNet by remember { mutableStateOf<Pair<String, String>?>(null) }  // asking whether to join this network
    var linkCode by remember { mutableStateOf<ConnectCode?>(null) }  // asking whether to add this device
    var connectMsg by remember { mutableStateOf<String?>(null) }  // the result of a scan, shown until dismissed
    var pairOpen by remember { mutableStateOf(false) }   // showing a code for another device to pair with
    var renameOpen by remember { mutableStateOf(false) }
    var clearOpen by remember { mutableStateOf(false) }
    var detailsFor by remember { mutableStateOf<Device?>(null) }
    var removeFor by remember { mutableStateOf<Device?>(null) }   // asking whether to remove this saved device
    var removeAllOpen by remember { mutableStateOf(false) }        // asking whether to remove every saved device that is not nearby
    var chooseFor by remember { mutableStateOf<Device?>(null) }   // a device was tapped: what to send it?
    var chatPeer by rememberSaveable { mutableStateOf<String?>(null) }   // the conversation on screen, if any
    var dest by remember { mutableStateOf<Device?>(null) }        // the device a running pick will go to, if known
    var pending by remember { mutableStateOf<Pending?>(null) }
    var reading by remember { mutableStateOf(false) }

    /** Content is ready: send it to the device already chosen, or ask which device. */
    fun ready(p: Pending) {
        val d = dest
        dest = null
        if (d != null) sendPending(context, scope, listOf(d), p) else pending = p
    }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) dest = null else ready(Pending(itemsText(uris.size, "file"), uris = uris))
    }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isEmpty()) dest = null else ready(Pending(itemsText(uris.size, "photo or video"), uris = uris))
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) {
            dest = null
        } else scope.launch {
            reading = true
            val t = withContext(Dispatchers.IO) { Items.fromTree(context, tree) }
            reading = false
            when {
                t.tooMany -> toast(context, "That folder has more than 10,000 files. Send it in parts.")
                t.items.isEmpty() -> toast(context, "That folder has no files to send.")
                else -> {
                    if (t.skipped > 0) toast(context, "${t.skipped} item(s) couldn't be read and were left out")
                    ready(Pending("folder ${t.folderName} (${itemsText(t.items.size, "file")}, ${Format.size(t.bytes)})", items = t.items))
                }
            }
        }
    }

    fun startPick(kind: Kind) {
        when (kind) {
            Kind.Files -> pickFiles.launch(arrayOf("*/*"))
            Kind.Media -> pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            Kind.Folder -> pickFolder.launch(null)
            Kind.Text -> {
                val d = dest
                dest = null
                if (d != null) chatPeer = d.id else tab = Tab.Messages
            }
        }
    }

    // coming back to the app: ask the saved devices again, so a device that went away leaves the nearby list
    LifecycleResumeEffect(Unit) {
        Engine.checkSaved(minGapMs = 10_000)
        onPauseOrDispose {}
    }

    // something shared from another app: go to the device list at once
    LaunchedEffect(shared) {
        if (shared.isEmpty && shared.deviceId.isEmpty()) return@LaunchedEffect
        tab = Tab.Send
        aboutOpen = false
        shareOpen = false
        wifiOpen = false
        chatPeer = null
        val content = Pending(
            if (shared.uris.isEmpty()) "text" else itemsText(shared.uris.size, "item"),
            uris = shared.uris, text = shared.text, fromShare = true, held = shared.held,
        )
        pending?.held?.release() // an earlier share nobody picked a device for
        if (shared.deviceId.isEmpty()) {
            pending = content
            return@LaunchedEffect
        }
        // the device was already picked: wait a moment for discovery if the phone just started, then go
        fun find() = Engine.state.value.let { st -> (st.nearby + st.added).firstOrNull { it.id == shared.deviceId } }
        var device = find()
        if (device == null) {
            connecting = true
            device = withTimeoutOrNull(4_000) { Engine.state.first { find() != null } }?.let { find() }
            connecting = false
        }
        when {
            device == null -> {
                toast(context, "That device is not reachable right now. Pick another.")
                if (shared.isEmpty) onShareDone() else pending = content
            }
            shared.isEmpty -> { chooseFor = device; onShareDone() }   // from the app icon's menu: what to send it?
            else -> { sendPending(context, scope, listOf(device), content); onShareDone() }   // progress shows under Now on the Send tab
        }
    }
    /** Add the device a code names, with its full ID pinned. A code that names a Wi-Fi network joins it first. */
    fun connect(code: ConnectCode) {
        connecting = true
        if (code.isPair) {
            connectingText = "Pairing with ${code.name.ifEmpty { "the device" }}."
            Engine.pairWith(code) { name, error ->
                connecting = false
                connectMsg = if (name != null) "Paired with $name. It is in your device list. To take its files without asking, " +
                    "turn that on under Settings, Paired devices." else error
            }
            return
        }
        connectingText = if (code.hasWifi) "Joining the network, then checking the device. Android may ask you to allow it." else "Checking the device."
        val done = { device: Device?, error: String? ->
            connecting = false
            connectMsg = if (device != null) "Connected to ${device.name}. It is now in your Nearby list." else error
        }
        if (code.hasWifi) Engine.joinAndConnect(code, done) else Engine.addByCode(code, done)
    }
    /** Join a Wi-Fi network from a plain Wi-Fi QR code. */
    fun join(ssid: String, psk: String) {
        connecting = true
        connectingText = "Joining the network. Android may ask you to allow it."
        Engine.joinNetwork(ssid, psk) { error ->
            connecting = false
            connectMsg = error ?: "Joined $ssid. Other Amanah devices on it should show up under Nearby, or use Add by IP."
        }
    }
    /** A QR code was read by the scanner in this app. A code that joins a network asks first. */
    fun scanned(text: String) {
        scanOpen = false
        val code = ConnectCode.parse(text)
        val net = WifiCode.parse(text)
        when {
            code != null && (code.hasWifi || code.isPair) -> linkCode = code
            code != null -> connect(code)
            net != null -> joinNet = net
            else -> connectMsg = "That QR code is not from Amanah, and it is not a Wi-Fi code."
        }
    }

    // a message notification was tapped: open that conversation
    LaunchedEffect(openChat) {
        if (openChat.isNotEmpty()) {
            chatPeer = openChat
            aboutOpen = false
            shareOpen = false
            wifiOpen = false
            onChatHandled()
        }
    }

    // a connect link opened from the phone's camera app: ask before adding anything
    LaunchedEffect(link) {
        if (link.isNotEmpty()) {
            val code = ConnectCode.parse(link)
            if (code == null) connectMsg = "That link is not an Amanah connect code." else linkCode = code
            onLinkHandled()
        }
    }
    /** Closes the device list. Files already opened for it are given back unless a send took them. */
    fun closePending(sent: Boolean = false) {
        if (pending?.fromShare == true) onShareDone()
        if (!sent) pending?.held?.release()
        pending = null
    }

    val openThread = chatPeer
    if (openThread != null) {
        BackHandler { chatPeer = null }
        ThreadScreen(openThread, s, onBack = { chatPeer = null })
    } else if (shareOpen) {
        BackHandler { shareOpen = false }
        ShareScreen(s, onBack = { shareOpen = false }, onOwnWifi = { shareOpen = false; wifiOpen = true })
    } else if (wifiOpen) {
        BackHandler { wifiOpen = false }
        OwnWifiScreen(s, onBack = { wifiOpen = false }, onScan = { scanOpen = true }, onReceivingChange = onReceivingChange)
    } else if (statsOpen) {
        BackHandler { statsOpen = false }
        StatsScreen(s.history, onBack = { statsOpen = false })
    } else if (aboutOpen) {
        BackHandler { aboutOpen = false }
        AboutScreen(onBack = { aboutOpen = false })
    } else {
        BackHandler(enabled = tab != Tab.Send) { tab = Tab.Send }
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    // only the name, in spaced capitals, in the middle: the receiving status lives on the Send tab
                    title = {
                        // the name is a mark, not reading text: it stops growing at 130% so the bar keeps its height
                        val d = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(d.density, minOf(d.fontScale, 1.3f))) {
                            Text("AMANAH", fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = 5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Clip)
                        }
                    },
                )
            },
            bottomBar = {
                // a rounded sheet under the tabs: its top corners are round, and the tab that is open gets the accent color
                Column(Modifier.clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                    // the labels stop growing at 130%: the bar is a fixed height, and the words are short
                    val d = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(d.density, minOf(d.fontScale, 1.3f))) {
                        NavigationBar(Modifier.height(64.dp), containerColor = Color.Transparent, windowInsets = WindowInsets(0, 0, 0, 0)) {
                            val unread = s.messages.count { !it.read }
                            for (t in Tab.entries) NavigationBarItem(
                                selected = tab == t, onClick = { tab = t }, alwaysShowLabel = true,
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = Color.Transparent,
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                                icon = {
                                    if (t == Tab.Messages && unread > 0) {
                                        BadgedBox(badge = { Badge { Text(if (unread > 99) "99+" else unread.toString()) } }) {
                                            Icon(t.icon, contentDescription = "${t.label}, $unread unread")
                                        }
                                    } else {
                                        Icon(t.icon, contentDescription = null)
                                    }
                                },
                                // the smallest label style with no letter spacing, and free to be a little wider than its
                                // slot, so "Messages" is not clipped on a 320 dp screen or with large text
                                label = {
                                    Text(t.label, maxLines = 1, softWrap = false, modifier = Modifier.wrapContentWidth(unbounded = true),
                                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp))
                                },
                            )
                        }
                    }
                    MadeWithBar(background = Color.Transparent, divider = false)
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad)) {
                when (tab) {
                    Tab.Send -> SendTab(
                        s,
                        onKind = { dest = null; startPick(it) },
                        onDevice = { chooseFor = it },
                        onDetails = { detailsFor = it },
                        onScan = { scanOpen = true },
                        onAddByIp = { addOpen = true },
                        onHelp = { helpOpen = true },
                        onShareAnyone = { shareOpen = true },
                        onOwnWifi = { wifiOpen = true },
                        onRemove = { removeFor = it },
                        onRemoveAll = { removeAllOpen = true },
                        onReceivingChange = onReceivingChange,
                        onOpenReceiving = { receivingOpen = true },
                    )
                    Tab.Messages -> MessagesTab(s, onOpen = { chatPeer = it })
                    Tab.Activity -> ActivityTab(s.history, onStats = { statsOpen = true })
                    Tab.Settings -> SettingsTab(s, onReceivingChange, { renameOpen = true }, { qrOpen = true }, { clearOpen = true }, { aboutOpen = true },
                        onPair = { pairOpen = true })
                }
            }
        }
    }

    // --- sheets and dialogs

    if (!s.conformance) s.incoming.lastOrNull { it.state == "waiting" }?.let { OfferSheet(it) }

    pending?.let { p ->
        DevicePickerSheet(p.what, s, onPick = { picked ->
            sendPending(context, scope, picked, p)
            closePending(sent = true)
        }, onScan = { scanOpen = true }, onAddByIp = { addOpen = true }, onHelp = { helpOpen = true }, onDismiss = { closePending() })
    }
    chooseFor?.let { d ->
        WhatToSendSheet(d, onChoose = { kind -> dest = d; chooseFor = null; startPick(kind) }, onDismiss = { chooseFor = null })
    }
    if (receivingOpen) ReceivingSheet(s, onReceivingChange, onRename = { renameOpen = true },
        onShowQr = { receivingOpen = false; qrOpen = true }, onDismiss = { receivingOpen = false })
    if (qrOpen) QrSheet(remember(s.addresses, s.port, s.name) { Engine.myCode() }, onDismiss = { qrOpen = false })
    if (pairOpen) {
        // the code is made when the sheet opens and taken off the screen when it closes (or after 10 minutes, or after one use)
        LaunchedEffect(Unit) { if (!Engine.startPairing()) { pairOpen = false; toast(context, "Connect to Wi-Fi and turn Receiving on first.") } }
        QrSheet(s.pairCode.takeIf { it.isNotEmpty() }?.let { ConnectCode.parse(it) }, onDismiss = { pairOpen = false; Engine.stopPairing() }, pairing = true)
    }
    // another device paired with this one: say so, and close the code
    val firstPairing = remember { s.lastPairedAt }
    LaunchedEffect(s.lastPairedAt) {
        if (s.lastPairedAt > firstPairing) {
            pairOpen = false
            toast(context, "Paired with ${s.lastPaired}")
        }
    }
    if (helpOpen) HelpSheet(s, onScan = { helpOpen = false; scanOpen = true },
        onAddByIp = { helpOpen = false; addOpen = true }, onDismiss = { helpOpen = false })
    detailsFor?.let { d ->
        DeviceSheet(d, added = s.added.any { it.id == d.id }, onForget = { Engine.forget(d); detailsFor = null }, onDismiss = { detailsFor = null })
    }

    if (addOpen) AddByIpDialog(onDone = { addOpen = false })
    if (scanOpen) ScanScreen(onCode = ::scanned, onAddByIp = { scanOpen = false; addOpen = true }, onDismiss = { scanOpen = false })
    if (connecting) AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        title = { Text("Connecting") },
        text = { Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(28.dp))
            Spacer(Modifier.width(16.dp))
            Text(connectingText)
        } },
    )
    linkCode?.let { code ->
        AlertDialog(
            onDismissRequest = { linkCode = null },
            title = { Text(if (code.isPair) "Pair with this device?" else if (code.hasWifi) "Join the network and add this device?" else "Add this device?") },
            text = { Column {
                Text(code.name.ifEmpty { "Unnamed device" }, style = MaterialTheme.typography.titleMedium)
                Muted("${code.hosts.first()}:${code.port}  ·  ID ${DeviceId.short(code.id)}", mono = true)
                if (code.hasWifi) {
                    Spacer(Modifier.height(8.dp))
                    Muted("Wi-Fi network: ${code.ssid}. It has no internet. Your phone's other apps keep using their own connection.")
                }
                if (code.isPair) {
                    Spacer(Modifier.height(8.dp))
                    Muted("Pairing proves each device to the other, and lets you take its files without asking if you turn that on for it later.")
                }
                Spacer(Modifier.height(8.dp))
                Muted(if (code.isPair) "Only pair if you are looking at that device's screen." else "Only add it if you scanned this code yourself.")
            } },
            confirmButton = { Button(onClick = { linkCode = null; connect(code) }) { Text(if (code.isPair) "Pair" else if (code.hasWifi) "Join and add" else "Add") } },
            dismissButton = { TextButton(onClick = { linkCode = null }) { Text("Cancel") } },
        )
    }
    joinNet?.let { (ssid, psk) ->
        AlertDialog(
            onDismissRequest = { joinNet = null },
            title = { Text("Join this Wi-Fi network?") },
            text = { Column {
                Text(ssid, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Muted("Only this app uses it. Your phone's other apps keep their own connection.")
            } },
            confirmButton = { Button(onClick = { joinNet = null; join(ssid, psk) }) { Text("Join") } },
            dismissButton = { TextButton(onClick = { joinNet = null }) { Text("Cancel") } },
        )
    }
    connectMsg?.let { msg ->
        AlertDialog(onDismissRequest = { connectMsg = null }, text = { Text(msg) },
            confirmButton = { Button(onClick = { connectMsg = null }) { Text("OK") } })
    }
    if (renameOpen) RenameDialog(s.name, onDone = { renameOpen = false })
    removeFor?.let { d ->
        ConfirmDialog(
            "Remove ${d.name}?", "It is taken off this list. Add it again with its QR code or its address. Messages with it are kept.",
            "Remove", onConfirm = { Engine.forget(d) }, onDone = { removeFor = null },
        )
    }
    if (removeAllOpen) ConfirmDialog(
        "Remove all not nearby?", "Every saved device in the Not nearby list is taken off. Add one again with its QR code or its address. Messages are kept.",
        "Remove all", onConfirm = { Engine.forgetInactive() }, onDone = { removeAllOpen = false },
    )
    if (clearOpen) ConfirmDialog(
        "Clear activity?", "This removes the list of what you sent and received. The files themselves stay where they are.",
        "Clear", onConfirm = { Engine.clearHistory() }, onDone = { clearOpen = false },
    )
    if (reading) AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        title = { Text("Reading the folder") },
        text = { Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(28.dp))
            Spacer(Modifier.width(16.dp))
            Text("Counting the files.")
        } },
    )
}

private fun itemsText(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

private fun sendPending(context: Context, scope: CoroutineScope, devices: List<Device>, p: Pending) {
    scope.launch {
        val held = p.held
        val (fromUris, skipped) = when {
            held != null -> held.await()
            p.uris.isEmpty() -> emptyList<SendItem>() to 0
            else -> withContext(Dispatchers.IO) { Items.fromUris(context, p.uris) }
        }
        if (skipped > 0) toast(context, "$skipped item(s) couldn't be read and were left out")
        val all = fromUris + p.items
        if (all.isNotEmpty() || p.text.isNotEmpty()) Engine.send(devices, all, p.text)
    }
}

// --- dialogs

@Composable
private fun AddByIpDialog(onDone: () -> Unit) {
    var address by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Add by IP address") },
        text = {
            Column {
                OutlinedTextField(address, { address = it.trim(); error = null }, Modifier.fillMaxWidth(),
                    placeholder = { Text("192.168.1.20") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                Spacer(Modifier.height(8.dp))
                Muted("The address is shown on the other device. Add :port only if it isn't 47820.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(enabled = address.isNotEmpty() && !busy, onClick = {
                busy = true
                Engine.addByIp(address) { e -> busy = false; if (e == null) onDone() else error = e }
            }) { Text(if (busy) "Looking" else "Add") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

@Composable
private fun RenameDialog(current: String, onDone: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Device name") },
        text = {
            Column {
                OutlinedTextField(name, { name = it.take(63) }, Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(8.dp))
                Muted("Other devices see this name.")
            }
        },
        confirmButton = { Button(enabled = name.isNotBlank(), onClick = { Engine.setName(name); onDone() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

@Composable
private fun ConfirmDialog(title: String, body: String, action: String, onConfirm: () -> Unit, onDone: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { Button(onClick = { onConfirm(); onDone() }) { Text(action) } },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}
