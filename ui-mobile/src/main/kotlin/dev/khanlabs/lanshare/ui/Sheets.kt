package dev.khanlabs.lanshare.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.khanlabs.lanshare.net.Device
import dev.khanlabs.lanshare.transfer.Engine
import dev.khanlabs.lanshare.transfer.IncomingView
import dev.khanlabs.lanshare.transfer.UiState
import kotlinx.coroutines.delay

/**
 * A bottom sheet. The "Made with love by Khan" line sits at its foot, so it stays in view on every screen.
 * A [locked] sheet can't be swiped or tapped away: the person has to answer it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Sheet(
    onDismiss: () -> Unit,
    locked: Boolean = false,
    /** Stays below the scrolling part, so its buttons are always in reach. */
    footer: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { !locked || it != SheetValue.Hidden },
    )
    val scroll = rememberScrollState()
    ModalBottomSheet(onDismissRequest = { if (!locked) onDismiss() }, sheetState = state) {
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(scroll).padding(horizontal = 20.dp)
                .padding(bottom = 12.dp),
            content = content,
        )
        // when the content is taller than the room (large text, small screens) it scrolls under this line, so a cut-off row reads as scrolling
        if (scroll.maxValue > 0) HorizontalDivider(Modifier.padding(bottom = 8.dp))
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp), content = footer)
        MadeWithBar()
    }
}

@Composable
private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String = "", onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(icon, MaterialTheme.colorScheme.primary, 26)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) Muted(subtitle)
        }
    }
}

// --- offer

/** An incoming send. Stays until answered or until the time runs out. */
@Composable
internal fun OfferSheet(v: IncomingView) {
    val now by produceState(System.currentTimeMillis(), v.id) {
        while (true) {
            value = System.currentTimeMillis()
            delay(250)
        }
    }
    val left = ((v.expiresAt - now + 999) / 1000).coerceAtLeast(0)
    val fraction = ((v.expiresAt - now).toFloat() / (v.expiresAt - v.created).coerceAtLeast(1)).coerceIn(0f, 1f)
    var whyOpen by remember { mutableStateOf(false) }
    Sheet(onDismiss = {}, locked = true, footer = {
        Muted("Answer within ${clock(left)}")
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val tight = PaddingValues(horizontal = 8.dp, vertical = 12.dp)
            OutlinedButton(onClick = { Engine.answer(v.id, false) }, Modifier.weight(1f), contentPadding = tight) {
                Text("Decline", maxLines = 1, softWrap = false)
            }
            Button(onClick = { Engine.answer(v.id, true) }, Modifier.weight(1f), contentPadding = tight) {
                Text("Accept", maxLines = 1, softWrap = false)
            }
        }
    }) {
        Text("${v.senderName} wants to send you", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(countText(v.fileCount, v.total), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        if (v.fileCount > 0) {
            Spacer(Modifier.height(8.dp))
            for (n in v.firstNames) {
                Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph(Icons.File, size = 18)
                    Spacer(Modifier.width(8.dp))
                    Text(n, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (v.fileCount > v.firstNames.size) Muted("and ${v.fileCount - v.firstNames.size} more", modifier = Modifier.padding(top = 2.dp))
        }
        if (v.text.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(v.text.take(1000), style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.clickable { whyOpen = !whyOpen }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph(Icons.Info, size = 18)
            Spacer(Modifier.width(6.dp))
            Muted(if (v.senderShortId.isNotEmpty()) "New device  ·  ID ${v.senderShortId}" else "New device")
        }
        if (whyOpen) {
            Muted("From ${v.ip}. Anyone on this Wi-Fi can send an offer under any name, so accept only what you expect.")
        }
    }
}

// --- sending

/** Content is picked; now pick the device to send it to. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DevicePickerSheet(
    what: String, s: UiState, onPick: (Device) -> Unit, onScan: () -> Unit, onAddByIp: () -> Unit, onHelp: () -> Unit,
    onDismiss: () -> Unit,
) {
    val devices = s.nearby + s.added
    Sheet(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Send $what to", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { Engine.refreshNearby() }) { Glyph(Icons.Refresh, description = "Refresh") }
        }
        Spacer(Modifier.height(8.dp))
        if (devices.isEmpty()) {
            Text("No devices found yet.", style = MaterialTheme.typography.bodyLarge)
            Muted("Open KhanLabs LAN Share on the other device, on the same Wi-Fi.")
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onScan) { Text("Scan QR code") }
                OutlinedButton(onClick = onAddByIp) { Text("Add by IP") }
                TextButton(onClick = onHelp) { Text("Not showing up?") }
            }
        }
        for (d in devices) ActionRow(deviceIcon(d.model), d.name, d.kind()) { onPick(d) }
    }
}

/** The other way round: a device was tapped; now say what to send it. */
@Composable
internal fun WhatToSendSheet(d: Device, onChoose: (Kind) -> Unit, onDismiss: () -> Unit) {
    Sheet(onDismiss) {
        Text("Send to ${d.name}", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        ActionRow(Icons.File, "Files") { onChoose(Kind.Files) }
        ActionRow(Icons.Image, "Photos and videos") { onChoose(Kind.Media) }
        ActionRow(Icons.Folder, "Folder") { onChoose(Kind.Folder) }
        ActionRow(Icons.Text, "Text") { onChoose(Kind.Text) }
    }
}

internal enum class Kind { Files, Media, Folder, Text }

// --- receiving

@Composable
internal fun ReceivingSheet(
    s: UiState, onReceivingChange: (Boolean) -> Unit, onRename: () -> Unit, onShowQr: () -> Unit, onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    Sheet(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Receiving", style = MaterialTheme.typography.titleLarge)
                Muted(statusLine(s))
            }
            Switch(checked = s.receiving, onCheckedChange = onReceivingChange)
        }
        if (s.receiving && notificationsOff(context)) {
            Spacer(Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Notifications are off", style = MaterialTheme.typography.titleSmall)
                    Muted("You will not see an offer when the app is closed. Turn notifications on to accept files from the lock screen.")
                    TextButton(onClick = { openNotificationSettings(context); onDismiss() }) { Text("Turn on") }
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().clickable(onClick = onRename).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Muted("Other devices see you as")
                Text(s.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onRename) { Text("Rename") }
        }
        if (s.receiving && s.port != null && s.addresses.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Muted("Your address")
            Text(s.addresses.joinToString("\n") { "$it:${s.port}" }, style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            Muted("If this phone doesn't show up on another device, scan its QR code or type this address there (Add by IP).")
            OutlinedButton(onClick = onShowQr, Modifier.padding(top = 8.dp)) {
                Glyph(Icons.Qr, MaterialTheme.colorScheme.primary, 18)
                Spacer(Modifier.width(8.dp))
                Text("Show QR code")
            }
        }
        Spacer(Modifier.height(8.dp))
        Muted("Device ID ${s.shortId}")
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Muted("Files are saved in")
                Text(s.folder, style = MaterialTheme.typography.bodyLarge)
            }
            TextButton(onClick = { showFolder(context) }) { Text("Show") }
        }
    }
}

internal fun statusLine(s: UiState): String = when {
    s.listenError != null -> s.listenError ?: ""
    !s.receiving -> "Off. You can still send."
    s.port == null -> "Starting"
    s.addresses.isEmpty() -> "Connect to Wi-Fi to receive"
    else -> "Ready. Others on your Wi-Fi can send to you."
}

// --- help

@Composable
internal fun HelpSheet(s: UiState, onScan: () -> Unit, onAddByIp: () -> Unit, onDismiss: () -> Unit) {
    Sheet(onDismiss) {
        Text("Not showing up?", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Step("1", "Both devices on the same Wi-Fi", "Not a guest network, and not mobile data.")
        Step("2", "Open LAN Share on the other device", "Keep its window open and receiving turned on.")
        Step("3", "On a Windows PC, set the network to Private",
            "Settings, Network and internet, Wi-Fi, your network, then Private network. Allow LAN Share if Windows asks.")
        Step("4", "Still nothing? Scan its QR code",
            "Open LAN Share on the other device and show its QR code. Scan it here. This works whenever both devices can reach each other.")
        Step("5", "No camera? Use the address",
            "The other device shows its address, like 192.168.1.20. Type it with Add by IP.")
        if (s.receiving && s.port != null && s.addresses.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Muted("This phone's address: ${s.addresses.first()}:${s.port}")
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onScan, Modifier.fillMaxWidth()) { Text("Scan QR code") }
        OutlinedButton(onClick = onAddByIp, Modifier.fillMaxWidth()) { Text("Add by IP address") }
    }
}

@Composable
private fun Step(number: String, title: String, body: String) {
    Row(Modifier.padding(vertical = 6.dp)) {
        Text(number, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(24.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Muted(body)
        }
    }
}

// --- device details

@Composable
internal fun DeviceSheet(d: Device, added: Boolean, onForget: () -> Unit, onDismiss: () -> Unit) {
    Sheet(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(deviceIcon(d.model), MaterialTheme.colorScheme.primary, 32)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(d.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Muted(d.kind())
            }
        }
        Spacer(Modifier.height(12.dp))
        Muted("Device ID ${d.shortId}")
        Muted("Address ${d.hosts.firstOrNull() ?: "unknown"}:${d.port}")
        if (d.app.isNotEmpty()) Muted(d.app)
        if (added) {
            Spacer(Modifier.height(12.dp))
            Muted("You added this device by its address.")
            OutlinedButton(onClick = onForget, Modifier.padding(top = 4.dp)) { Text("Forget this device") }
        }
    }
}
