package dev.khanlabs.lanshare.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.khanlabs.lanshare.transfer.Engine

/**
 * Shown once, the first time the app opens. It says what the app is and why Android is about to ask
 * for permission, so the permission prompt is not a surprise. [permissionNote] is empty when none is needed.
 */
@Composable
fun WelcomeScreen(permissionNote: String, onContinue: () -> Unit) {
    val current = remember { Engine.state.value.name }
    var name by remember { mutableStateOf(current) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text("LAN Share", fontFamily = FontFamily.Serif, fontSize = 40.sp, color = MaterialTheme.colorScheme.onBackground)
                Text("Send files and text between your phone and your computer, over your own Wi-Fi.",
                    style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Point(Icons.Wifi, "Works on the same Wi-Fi", "No internet is needed. Files go straight from one device to the other and never pass through a server.")
                Point(Icons.Heart, "No ads. No tracking. No account.", "Free. The app never sends anything about you anywhere.")
                Point(Icons.Check, "You choose what comes in", "Every incoming offer asks first. Nothing is saved unless you accept.")
                Column {
                    Text("Name this phone", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(name, { name = it.take(63) }, Modifier.fillMaxWidth(), singleLine = true)
                    Muted("Other devices on this Wi-Fi see this name. You can change it later in Settings.", modifier = Modifier.padding(top = 4.dp))
                }
                if (permissionNote.isNotEmpty()) Muted(permissionNote)
            }
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 12.dp)) {
                Button(
                    onClick = {
                        if (name.isNotBlank() && name.trim() != current) Engine.setName(name)
                        onContinue()
                    },
                    Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Get started") }
            }
            MadeWithBar()
        }
    }
}

@Composable
private fun Point(icon: ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Glyph(icon, MaterialTheme.colorScheme.primary, size = 28)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Muted(body)
        }
    }
}
