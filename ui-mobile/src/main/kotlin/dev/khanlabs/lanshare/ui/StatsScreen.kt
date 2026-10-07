package dev.khanlabs.lanshare.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.khanlabs.lanshare.transfer.Format
import dev.khanlabs.lanshare.transfer.HistoryEntry
import dev.khanlabs.lanshare.transfer.Stats
import dev.khanlabs.lanshare.transfer.StatsData
import dev.khanlabs.lanshare.transfer.StatsRange

/** Numbers that line up: every digit is the same width, so a figure does not jiggle as it changes. */
private val Figures = TextStyle(fontFeatureSettings = "tnum")

/**
 * What this phone has moved: the total, a bar for each day (or month), how much was sent and received, the best speed,
 * which devices, which kinds of file, and how often the connection had to be picked up again. It is all worked out here
 * from the Activity list, so nothing leaves the phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatsScreen(history: List<HistoryEntry>, onBack: () -> Unit) {
    var range by rememberSaveable { mutableStateOf(StatsRange.Week) }
    val data = remember(history, range) { Stats.compute(history, range) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Stats") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Back, contentDescription = "Back") } },
            )
        },
        bottomBar = { MadeWithBar() },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (r in StatsRange.entries) FilterChip(selected = range == r, onClick = { range = r }, label = { Text(r.label) })
                }
            }
            if (data.isEmpty) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Nothing in this time yet")
                            Spacer(Modifier.height(4.dp))
                            Muted("Stats appear after your first transfer. Send or receive something and come back.")
                        }
                    }
                }
            } else {
                item { Hero(data) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Figure("Sent", Format.size(data.sent), Modifier.weight(1f))
                        Figure("Received", Format.size(data.received), Modifier.weight(1f))
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Figure("Fastest", if (data.fastestBps > 0) Format.speed(data.fastestBps.toDouble()) else "Not timed", Modifier.weight(1f))
                        Figure("Finished whole", "${data.finished} of ${data.transfers}", Modifier.weight(1f))
                    }
                }
                if (data.topDevices.isNotEmpty()) {
                    item { SectionHeader("Devices") }
                    item { Devices(data) }
                }
                if (data.types.isNotEmpty()) {
                    item { SectionHeader("Files") }
                    item { Kinds(data) }
                }
                item { SectionHeader("Reliability") }
                item { Reliability(data) }
            }
            item { Muted("Stats are worked out on this phone from your Activity list. They never leave it.") }
        }
    }
}

@Composable
private fun Hero(d: StatsData) {
    SoftCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(Format.size(d.total) + " moved", style = MaterialTheme.typography.headlineMedium.merge(Figures))
            Muted("${plural(d.files, "file")}  ·  ${plural(d.transfers, "transfer")}")
            Spacer(Modifier.height(16.dp))
            BarChart(d, Modifier.fillMaxWidth().height(96.dp))
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                for (l in d.barLabels) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(l, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, softWrap = false, overflow = TextOverflow.Visible, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** One bar for each day (or month). The last bar, today, is the accent color; the rest are the same color, softer. */
@Composable
private fun BarChart(d: StatsData, modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val soft = accent.copy(alpha = 0.35f)
    val stub = MaterialTheme.colorScheme.outlineVariant
    val best = (d.bars.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val summary = "Bytes moved per ${if (d.range == StatsRange.All) "month" else "day"}, the highest is ${Format.size(best)}"
    Canvas(modifier.semantics { contentDescription = summary }) {
        val n = d.bars.size
        val gap = if (n > 12) 2.dp.toPx() else 8.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        d.bars.forEachIndexed { i, v ->
            // a day with nothing still gets a small stub, so the row reads as days and not as a gap
            val h = if (v <= 0) 4.dp.toPx() else (size.height * v / best).toFloat().coerceAtLeast(6.dp.toPx())
            drawRoundRect(
                color = if (v <= 0) stub else if (i == n - 1) accent else soft,
                topLeft = Offset(i * (w + gap), size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(minOf(w / 2, 6.dp.toPx())),
            )
        }
    }
}

@Composable
private fun Figure(label: String, value: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Muted(label)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleLarge.merge(Figures), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Devices(d: StatsData) {
    val top = d.topDevices.firstOrNull()?.bytes?.coerceAtLeast(1L) ?: 1L
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (x in d.topDevices) Column {
                Row {
                    Text(x.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(8.dp))
                    Text(Format.size(x.bytes), style = MaterialTheme.typography.bodyMedium.merge(Figures),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(6.dp))
                Meter(x.bytes.toFloat() / top)
            }
        }
    }
}

@Composable
private fun Meter(fraction: Float) {
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(Modifier.fillMaxWidth().height(8.dp)) {
        drawRoundRect(track, size = size, cornerRadius = CornerRadius(size.height / 2))
        drawRoundRect(accent, size = Size((size.width * fraction.coerceIn(0.02f, 1f)), size.height), cornerRadius = CornerRadius(size.height / 2))
    }
}

/** One bar split by kind of file, with a line under it for each kind. */
@Composable
private fun Kinds(d: StatsData) {
    val total = d.types.sumOf { it.files }.coerceAtLeast(1)
    val shades = listOf(1f, 0.7f, 0.5f, 0.35f, 0.22f)
    val accent = MaterialTheme.colorScheme.primary
    val kinds = d.types.take(5)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Canvas(Modifier.fillMaxWidth().height(12.dp)) {
                var x = 0f
                kinds.forEachIndexed { i, t ->
                    val w = size.width * t.files / total
                    drawRoundRect(accent.copy(alpha = shades[i]), topLeft = Offset(x, 0f), size = Size((w - 2.dp.toPx()).coerceAtLeast(2f), size.height),
                        cornerRadius = CornerRadius(size.height / 2))
                    x += w
                }
            }
            Spacer(Modifier.height(12.dp))
            kinds.forEachIndexed { i, t ->
                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.width(10.dp).height(10.dp)) { drawCircle(accent.copy(alpha = shades[i])) }
                    Spacer(Modifier.width(10.dp))
                    Text(t.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(plural(t.files, "file"), style = MaterialTheme.typography.bodyMedium.merge(Figures), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Reliability(d: StatsData) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Line(if (d.resumed == 0) "No transfer lost its connection"
                 else "${plural(d.resumed, "transfer")} lost the connection and went on")
            Line(if (d.filesSkipped == 0) "No file was sent twice"
                 else "${plural(d.filesSkipped, "file")} already there, ${Format.size(d.bytesSkipped)} not sent again")
            Line(if (d.verified == 0) "No send has been checked yet" else "${plural(d.verified, "file")} checked, they arrived whole")
        }
    }
}

@Composable
private fun Line(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

private fun plural(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

/** The small card at the top of Activity: this week at a glance. Tapping it opens the full Stats screen. */
@Composable
internal fun StatsSummaryCard(history: List<HistoryEntry>, onClick: () -> Unit) {
    val d = remember(history) { Stats.compute(history, StatsRange.Week) }
    SoftCard(Modifier.fillMaxWidth(), onClick) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Muted("This week")
                Text(if (d.isEmpty) "Nothing yet" else Format.size(d.total), style = MaterialTheme.typography.titleLarge.merge(Figures))
                Muted(if (d.isEmpty) "Send or receive something" else "moved  ·  " + plural(d.transfers, "transfer"))
            }
            if (!d.isEmpty) {
                Spacer(Modifier.width(12.dp))
                BarChart(d, Modifier.width(96.dp).height(40.dp))
                Spacer(Modifier.width(8.dp))
            }
            Glyph(Icons.ChevronRight)
        }
    }
}
