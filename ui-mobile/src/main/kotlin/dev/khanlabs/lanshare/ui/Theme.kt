package dev.khanlabs.lanshare.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The Windows app's colors (brand.py): near-black paper, dark cards, a teal accent. */
private val Paper = Color(0xFF080A0E)
private val Card = Color(0xFF12151B)
private val Card2 = Color(0xFF181C24)
private val Hover = Color(0xFF1F2530)
private val Ink = Color(0xFFE7EAEF)
private val Muted = Color(0xFF97A0AD)
private val Border = Color(0xFF2F3742)
private val Teal = Color(0xFF2DD4BF)
private val TealDark = Color(0xFF0F766E)
private val Sage = Color(0xFF34D399)
private val Brick = Color(0xFFFF5F57)

private val Dark = darkColorScheme(
    primary = Teal,
    onPrimary = Paper,
    primaryContainer = Hover,
    onPrimaryContainer = Ink,
    secondary = Sage,
    onSecondary = Paper,
    secondaryContainer = Hover,
    onSecondaryContainer = Teal,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Card2,
    onSurfaceVariant = Muted,
    surfaceContainerLowest = Paper,
    surfaceContainerLow = Card,
    surfaceContainer = Card,
    surfaceContainerHigh = Card2,
    surfaceContainerHighest = Hover,
    outline = Border,
    outlineVariant = Border,
    error = Brick,
    onError = Paper,
)

private val Light = lightColorScheme(
    primary = TealDark,
    onPrimary = Color.White,
    secondary = Color(0xFF047857),
    secondaryContainer = Color(0xFFCCF0EA),
    onSecondaryContainer = TealDark,
    error = Color(0xFFC62828),
)

@Composable
fun LanShareTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
