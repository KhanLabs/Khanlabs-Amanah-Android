package dev.khanlabs.lanshare.ui

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.khanlabs.lanshare.transfer.Engine

/**
 * One accent and three surface levels, never pure black unless the person picks Black: the paper behind everything, the
 * card on it, and the raised surface for sheets, selected items and the lightest part of a tile. The ink and muted text
 * keep at least 4.5:1 on all three.
 */
private val Paper = Color(0xFF0B0D0E)
private val Card = Color(0xFF151A1C)
private val Raised = Color(0xFF1D2427)
private val Ink = Color(0xFFE8EEF0)
private val Muted = Color(0xFFA9B4B8)
private val Border = Color(0xFF2E3639)
private val Brick = Color(0xFFFF5F57)

/** The same three levels for the Black theme: the paper is true black, and the cards stay just above it. */
private val BlackPaper = Color(0xFF000000)
private val BlackCard = Color(0xFF0E1011)
private val BlackRaised = Color(0xFF181C1E)

/** An accent the person can pick in Settings: its key as saved, its name, and its dark and light versions. */
internal class AccentChoice(val key: String, val label: String, val dark: Color, val light: Color)

internal val AccentChoices = listOf(
    AccentChoice("teal", "Teal", Color(0xFF2DD4BF), Color(0xFF0F766E)),
    AccentChoice("blue", "Blue", Color(0xFF60A5FA), Color(0xFF1D4ED8)),
    AccentChoice("violet", "Violet", Color(0xFFA78BFA), Color(0xFF6D28D9)),
    AccentChoice("pink", "Pink", Color(0xFFF472B6), Color(0xFFBE185D)),
    AccentChoice("mono", "Mono", Color(0xFFE8EEF0), Color(0xFF1F2933)),
)

internal fun accentFor(key: String) = AccentChoices.firstOrNull { it.key == key } ?: AccentChoices.first()

/** The color this accent has on the screen right now (the light or the dark version), for the swatches in Settings. */
@Composable
internal fun AccentChoice.swatch(): Color = if (LocalDark.current) dark else light

private fun darkScheme(accent: Color, black: Boolean): ColorScheme {
    val paper = if (black) BlackPaper else Paper
    val card = if (black) BlackCard else Card
    val raised = if (black) BlackRaised else Raised
    return darkColorScheme(
        primary = accent,
        onPrimary = paper,
        primaryContainer = raised,
        onPrimaryContainer = Ink,
        secondary = accent,
        onSecondary = paper,
        secondaryContainer = raised,
        onSecondaryContainer = accent,
        background = paper,
        onBackground = Ink,
        surface = paper,
        onSurface = Ink,
        surfaceVariant = raised,
        onSurfaceVariant = Muted,
        surfaceContainerLowest = paper,
        surfaceContainerLow = card,
        surfaceContainer = card,
        surfaceContainerHigh = raised,
        surfaceContainerHighest = raised,
        outline = Border,
        outlineVariant = Border,
        error = Brick,
        onError = paper,
    )
}

private fun lightScheme(accent: Color) = lightColorScheme(
    primary = accent,
    onPrimary = Color.White,
    secondary = accent,
    secondaryContainer = accent.copy(alpha = 0.16f).compositeOverWhite(),
    onSecondaryContainer = accent,
    error = Color(0xFFC62828),
)

private fun Color.compositeOverWhite(): Color = Color(
    red = red * alpha + (1 - alpha), green = green * alpha + (1 - alpha), blue = blue * alpha + (1 - alpha),
)

/** One family of corners: 12 dp for small things, 20 dp for rows and cards, 28 dp for big tiles, sheets and dialogs. */
private val Rounded = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Whether the screens are drawn dark right now: the person's choice in Settings, or else the phone's. */
private val LocalDark = compositionLocalOf { true }

/**
 * The colors of the icons on the Send tab's tiles: one soft family, all the same lightness and strength, only the hue
 * changes, and none is near the teal accent (that one is for switches, buttons and the open tab). Each has a light and a
 * dark version, so the icon reads on both. Amber also marks a warning.
 */
internal enum class Accent(private val light: Long, private val dark: Long) {
    Blue(0xFF1D5FA8, 0xFF7DB4F0),
    Violet(0xFF5B3FB5, 0xFFB29CF2),
    Amber(0xFF8F5A0B, 0xFFE6BA6B),
    Pink(0xFF9D2D6B, 0xFFEC9CC6);

    @Composable
    fun color(): Color = Color(if (LocalDark.current) dark else light)
}

@Composable
fun LanShareTheme(content: @Composable () -> Unit) {
    val s by Engine.state.collectAsStateWithLifecycle()
    val dark = when (s.theme) {
        "light" -> false
        "dark", "black" -> true
        else -> isSystemInDarkTheme()
    }
    val accent = accentFor(s.accent)
    val scheme = if (dark) darkScheme(accent.dark, black = s.theme == "black") else lightScheme(accent.light)
    // the status and navigation bars follow the theme the person picked, not only the phone's: dark icons on a light screen
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        var c = view.context
        while (c is ContextWrapper && c !is Activity) c = c.baseContext
        (c as? Activity)?.window?.let { w ->
            WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalDark provides dark) {
        MaterialTheme(colorScheme = scheme, shapes = Rounded, content = content)
    }
}
