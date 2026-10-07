package dev.khanlabs.lanshare.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.khanlabs.lanshare.transfer.UiState

/** What the status card says. The word and the icon always change with the color, so color is never the only sign. */
private enum class RxState { Receiving, Starting, Off, NoWifi, Problem }

private fun rxState(s: UiState) = when {
    s.listenError != null -> RxState.Problem
    !s.receiving -> RxState.Off
    s.addresses.isEmpty() -> RxState.NoWifi
    s.port == null -> RxState.Starting
    else -> RxState.Receiving
}

/** True when text is large or the screen is narrow: the screens drop the second lines that are only nice to have. */
@Composable
internal fun compactScreen(): Boolean =
    LocalDensity.current.fontScale >= 1.3f || LocalConfiguration.current.screenWidthDp < 360

/**
 * The top of the Send tab: whether this phone can be sent to, in words, with the switch beside it. The left part opens
 * the details sheet (name, address, QR code); the right part turns receiving on and off.
 */
@Composable
internal fun ReceivingCard(
    s: UiState, onToggle: (Boolean) -> Unit, onOpen: () -> Unit, onMakeLink: () -> Unit,
) {
    val state = rxState(s)
    val scheme = MaterialTheme.colorScheme
    val tint = when (state) {
        RxState.Receiving, RxState.Starting -> scheme.primary
        RxState.Off -> scheme.onSurfaceVariant
        RxState.NoWifi -> Accent.Amber.color()
        RxState.Problem -> scheme.error
    }
    val fill = if (state == RxState.Off) scheme.surfaceContainerHigh else tint.copy(alpha = 0.14f).compositeOver(scheme.surfaceContainerLow)
    val edge = if (state == RxState.Off) scheme.outlineVariant else tint.copy(alpha = 0.40f)
    val (icon, title) = when (state) {
        RxState.Receiving -> Icons.CheckCircle to "Receiving"
        RxState.Starting -> Icons.CheckCircle to "Starting"
        RxState.Off -> Icons.Power to "Not receiving"
        RxState.NoWifi -> Icons.WifiOff to "No Wi-Fi"
        RxState.Problem -> Icons.Error to "Can't receive"
    }
    val detail = when (state) {
        RxState.Receiving -> "Visible as ${s.name}"
        RxState.Starting -> "Getting ready"
        RxState.Off -> "Others can't send to you. You can still send."
        RxState.NoWifi -> "Connect to Wi-Fi, or make a direct link"
        RxState.Problem -> s.listenError ?: ""
    }
    // the cause of a problem always shows; the nice-to-have line goes first when space is short
    val showDetail = !compactScreen() || state == RxState.NoWifi || state == RxState.Problem
    Surface(shape = MaterialTheme.shapes.large, color = fill, border = BorderStroke(1.dp, edge), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clickable(onClickLabel = "Receiving details", onClick = onOpen)
                    .semantics(mergeDescendants = true) { contentDescription = "$title. $detail" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp).background(tint.copy(alpha = 0.16f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Glyph(icon, tint, 24)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (showDetail && detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            when (state) {
                RxState.NoWifi -> FilledTonalButton(onClick = onMakeLink) { Text("Make link", maxLines = 1) }
                RxState.Problem -> Button(onClick = onOpen) { Text("Fix", maxLines = 1) }
                else -> Switch(checked = s.receiving, onCheckedChange = onToggle)
            }
        }
    }
}
