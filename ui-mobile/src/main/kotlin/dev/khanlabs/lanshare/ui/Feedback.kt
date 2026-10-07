package dev.khanlabs.lanshare.ui

import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import dev.khanlabs.lanshare.transfer.Engine

/*
 * Small answers when a transfer ends: one short buzz and an icon that springs in. Short spring motion, no blur (the
 * owner's design rules). Nothing here runs for a card that was already over when the screen opened, so scrolling back or
 * coming back to the app never buzzes again.
 */

/** How a transfer ended, for the buzz and the icon. */
internal enum class Outcome { Done, Failed }

/** True unless the person turned animations off in the phone's settings (Accessibility, Remove animations). */
@Composable
internal fun animationsOn(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
}

/**
 * A buzz for [result], when the Settings switch is on. The phone's own touch feedback setting still applies: with it off,
 * Android does nothing here.
 */
@Composable
internal fun rememberBuzz(): (Outcome) -> Unit {
    val view = LocalView.current
    return remember(view) {
        { result ->
            if (Engine.haptics) {
                val modern = Build.VERSION.SDK_INT >= 30
                view.performHapticFeedback(
                    when (result) {
                        Outcome.Done -> if (modern) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CLOCK_TICK
                        Outcome.Failed -> if (modern) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
                    },
                )
            }
        }
    }
}

/**
 * True for a card that ended while it was on screen. A card that was already over when it first appeared is not "just"
 * finished, so it does not buzz or animate.
 */
@Composable
internal fun justEnded(ended: Boolean): Boolean {
    val endedAtStart = remember { ended }
    return ended && !endedAtStart
}

/**
 * The icon at the left of a transfer card: [arrow] while it runs. When it ends well it becomes a check, and when a send
 * fails a warning; the new icon springs in once and the phone buzzes once. [result] is null while it runs and for an end
 * that needs no cheer (declined, cancelled).
 */
@Composable
internal fun TransferGlyph(arrow: ImageVector, result: Outcome?) {
    val just = justEnded(result != null)
    val animate = animationsOn()
    val buzz = rememberBuzz()
    val scale = remember { Animatable(1f) }
    LaunchedEffect(just) {
        if (just && result != null) {
            buzz(result)
            if (animate) {
                scale.snapTo(0.5f)
                scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
            }
        }
    }
    val scheme = MaterialTheme.colorScheme
    val (icon, tint, words) = when (result) {
        Outcome.Done -> Triple(Icons.CheckCircle, scheme.primary, "Finished")
        Outcome.Failed -> Triple(Icons.Error, scheme.error, "Failed")
        null -> Triple(arrow, scheme.primary, null)
    }
    Box(Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value }) { Glyph(icon, tint, description = words) }
}
