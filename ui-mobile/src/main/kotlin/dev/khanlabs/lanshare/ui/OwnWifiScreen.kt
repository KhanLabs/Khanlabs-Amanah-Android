package dev.khanlabs.lanshare.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.khanlabs.lanshare.protocol.WifiCode
import dev.khanlabs.lanshare.transfer.Engine
import dev.khanlabs.lanshare.transfer.OwnBand
import dev.khanlabs.lanshare.transfer.OwnWifiView
import dev.khanlabs.lanshare.transfer.UiState

/**
 * A Wi-Fi network with no router: this phone makes one for the other device to join, or joins one. The network has no
 * internet. Files still go straight between the two devices.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OwnWifiScreen(s: UiState, onBack: () -> Unit, onScan: () -> Unit, onReceivingChange: (Boolean) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wi-Fi network") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Back, contentDescription = "Back") } },
            )
        },
        bottomBar = { MadeWithBar() },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("No router or Wi-Fi around? Make a network on this phone for the other device to join, or join one made by " +
                "another device. The network has no internet. Files still go straight between the two devices.",
                style = MaterialTheme.typography.bodyMedium)
            val o = s.ownWifi
            when (o.mode) {
                OwnWifiView.Mode.Off -> Off(o)
                OwnWifiView.Mode.Starting -> Busy("Making the network")
                OwnWifiView.Mode.Hosting -> Hosting(s, o, onReceivingChange)
                OwnWifiView.Mode.Joining -> Busy("Joining ${o.ssid}. Android may ask you to allow it.")
                OwnWifiView.Mode.Joined -> Joined(o)
            }
            if (o.mode == OwnWifiView.Mode.Off || o.mode == OwnWifiView.Mode.Joined) JoinSection(onScan)
        }
    }
}

@Composable
private fun Busy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(28.dp))
        Spacer(Modifier.padding(start = 16.dp))
        Text(text)
    }
}

// --- make a network

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Off(o: OwnWifiView) {
    val context = LocalContext.current
    var denied by remember { mutableStateOf(false) }
    // the choice is shown only when this phone can both pick a band and use 5 GHz
    val support = remember { Engine.bandSupport }
    var pick by rememberSaveable { mutableIntStateOf(0) } // an OwnBand, by position
    val band = if (support.offer) OwnBand.values()[pick] else OwnBand.Auto
    // Android 13 and newer: Nearby devices. Android 8 to 12: Location (the app never reads the location).
    val needed = remember {
        when {
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            Build.VERSION.SDK_INT >= 26 -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            else -> emptyArray()
        }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) Engine.startOwnNetwork(band) else denied = true
    }

    SectionHeader("Make a network on this phone")
    Muted("Your phone's own Wi-Fi may disconnect while the network is on. Mobile data keeps working for other apps.")
    if (support.offer) {
        Text("Wi-Fi band", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = band == OwnBand.Auto, onClick = { pick = OwnBand.Auto.ordinal }, label = { Text("Automatic") })
            FilterChip(selected = band == OwnBand.Ghz24, onClick = { pick = OwnBand.Ghz24.ordinal }, label = { Text("2.4 GHz") })
            FilterChip(selected = band == OwnBand.Ghz5, onClick = { pick = OwnBand.Ghz5.ordinal }, label = { Text("5 GHz") })
        }
        Muted(when (band) {
            OwnBand.Auto -> "Android picks the band. Works with every phone, but it may be the slow one."
            OwnBand.Ghz24 -> "Slower, about 7 MB/s at best. Every phone and PC can join."
            OwnBand.Ghz5 -> "3 to 5 times faster for big files. Some older phones can't see it. If the other device can't find the network, make it again on 2.4 GHz."
        })
    }
    if (Build.VERSION.SDK_INT < 26) {
        Text("This Android version is too old to make a network from an app. It needs Android 8 or newer. You can still join one.",
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
    o.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    if (denied) {
        Text(
            if (Build.VERSION.SDK_INT >= 33) "Allow \"Nearby devices\" for Amanah in the phone's settings, then try again."
            else "Allow Location for Amanah in the phone's settings, and turn Location on, then try again.",
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
    Button(
        enabled = Build.VERSION.SDK_INT >= 26,
        onClick = {
            denied = false
            val missing = needed.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isEmpty()) Engine.startOwnNetwork(band) else ask.launch(missing.toTypedArray())
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Make a network") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Hosting(s: UiState, o: OwnWifiView, onReceivingChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    var kind by rememberSaveable { mutableStateOf(0) } // 0: Amanah code, 1: Wi-Fi code
    val code = remember(s.ownWifi, s.port, s.name, s.addresses) { Engine.myCode() }

    SectionHeader("The network is on")
    bandLine(o)?.let { Muted(it) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = kind == 0, onClick = { kind = 0 }, label = { Text("Amanah code") })
        FilterChip(selected = kind == 1, onClick = { kind = 1 }, label = { Text("Wi-Fi code") })
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (kind == 0) {
            if (code == null || !code.hasWifi) {
                Text(
                    if (!s.receiving) "Turn on receiving to get a code that joins the network and connects in one scan."
                    else "Waiting for the phone's address on the network.",
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                if (!s.receiving) TextButton(onClick = { onReceivingChange(true) }) { Text("Turn on receiving") }
            } else {
                QrCode(code.toText(), Modifier.widthIn(max = 300.dp).fillMaxWidth(), "QR code that joins this phone's network and connects to it")
                Spacer(Modifier.height(8.dp))
                Text("On another phone with Amanah: Send tab, Scan QR code. It joins the network and connects.",
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            QrCode(WifiCode.toText(o.ssid, o.psk), Modifier.widthIn(max = 300.dp).fillMaxWidth(), "QR code that joins this phone's Wi-Fi network")
            Spacer(Modifier.height(8.dp))
            Text("Any phone's camera can scan this to join. A PC can pick the network from its Wi-Fi list.",
                textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        }
    }
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Muted("Network name")
            Text(o.ssid, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            Muted("Password")
            Text(o.psk, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { copyText(context, o.ssid) }) { Text("Copy name") }
                OutlinedButton(onClick = { copyText(context, o.psk) }) { Text("Copy password") }
            }
        }
    }
    o.addresses.firstOrNull()?.let {
        Muted("This phone's address on the network: $it. On a PC, join the network, then in Amanah use Add by IP with this address.")
    }
    Muted("Android keeps this network on only while Amanah runs. Turn it off when you are done.")
    Button(
        onClick = { Engine.stopOwnNetwork() }, modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
    ) { Text("Turn off the network") }
}

/** Which band the network is on, from what Android reports; when it reports nothing, what was asked for. Null for Automatic with no report. */
private fun bandLine(o: OwnWifiView): String? = when {
    o.slowBand == true -> "On 2.4 GHz." + if (o.band == OwnBand.Ghz5) {
        " You asked for 5 GHz, but Android used 2.4 GHz. This can happen when the phone is also on a 2.4 GHz Wi-Fi. " +
            "Turn that Wi-Fi off and make the network again."
    } else ""
    o.slowBand == false -> "On 5 GHz, the fast kind. A device that can't find the network may not support 5 GHz."
    o.band == OwnBand.Ghz5 -> "You asked for 5 GHz."
    o.band == OwnBand.Ghz24 -> "You asked for 2.4 GHz."
    else -> null
}

// --- join a network

@Composable
private fun Joined(o: OwnWifiView) {
    SectionHeader("Joined a network")
    Text(o.ssid, style = MaterialTheme.typography.titleMedium)
    Muted(o.addresses.firstOrNull()?.let { "This phone's address: $it" } ?: "Waiting for an address")
    Muted("Amanah's traffic goes over this network while it is joined. Other apps are not changed.")
    OutlinedButton(onClick = { Engine.leaveNetwork() }, Modifier.fillMaxWidth()) { Text("Leave the network") }
}

@Composable
private fun JoinSection(onScan: () -> Unit) {
    var enterOpen by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    SectionHeader("Join a network")
    if (Build.VERSION.SDK_INT < 29) {
        Muted("This Android version can't join a network from an app. Join it in the phone's Wi-Fi settings instead.")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = Build.VERSION.SDK_INT >= 29, onClick = onScan) {
            Glyph(Icons.Qr, MaterialTheme.colorScheme.primary, 18)
            Spacer(Modifier.padding(start = 8.dp))
            Text("Scan QR code")
        }
        OutlinedButton(enabled = Build.VERSION.SDK_INT >= 29, onClick = { enterOpen = true }) { Text("Enter name and password") }
    }
    if (enterOpen) JoinDialog(onResult = { enterOpen = false; message = it }, onDismiss = { enterOpen = false })
    message?.let { m ->
        AlertDialog(onDismissRequest = { message = null }, text = { Text(m) },
            confirmButton = { Button(onClick = { message = null }) { Text("OK") } })
    }
}

@Composable
private fun JoinDialog(onResult: (String) -> Unit, onDismiss: () -> Unit) {
    var ssid by remember { mutableStateOf("") }
    var psk by remember { mutableStateOf("") }
    val valid = WifiCode.isValid(ssid, psk)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join a network") },
        text = {
            Column {
                OutlinedTextField(ssid, { ssid = it }, Modifier.fillMaxWidth(), label = { Text("Network name") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(psk, { psk = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Spacer(Modifier.height(8.dp))
                Muted("Android will ask once more before it joins.")
            }
        },
        confirmButton = {
            Button(enabled = valid, onClick = {
                Engine.joinNetwork(ssid, psk) { error ->
                    onResult(error ?: "Joined $ssid. Go back to the Send tab; the other device should appear, or use Add by IP.")
                }
            }) { Text("Join") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// --- the entry on the Send tab

/** "Wi-Fi network" on the Send tab; says what is on while a network is made or joined. */
@Composable
internal fun OwnWifiCard(o: OwnWifiView, onClick: () -> Unit) {
    SoftCard(Modifier.fillMaxWidth(), onClick) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph(Icons.Wifi, MaterialTheme.colorScheme.primary, 28)
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                when (o.mode) {
                    OwnWifiView.Mode.Off -> {
                        Text("No Wi-Fi around?", style = MaterialTheme.typography.titleMedium)
                        Muted("Make a network on this phone, or join one")
                    }
                    OwnWifiView.Mode.Hosting -> {
                        Text("Network on", style = MaterialTheme.typography.titleMedium)
                        Muted(o.ssid)
                    }
                    OwnWifiView.Mode.Starting -> Text("Making a network", style = MaterialTheme.typography.titleMedium)
                    OwnWifiView.Mode.Joining -> Text("Joining ${o.ssid}", style = MaterialTheme.typography.titleMedium)
                    OwnWifiView.Mode.Joined -> {
                        Text("Joined a network", style = MaterialTheme.typography.titleMedium)
                        Muted(o.ssid)
                    }
                }
            }
            Glyph(Icons.ChevronRight)
        }
    }
}
