package dev.khanlabs.lanshare.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Near-black paper, soft dark cards and the teal accent of the Windows app (brand.py). The cards are a little lighter
 *  than the paper so their rounded edges show. */
private val Paper = Color(0xFF0D0F12)
private val Card = Color(0xFF171A1F)
private val Card2 = Color(0xFF1D2128)
private val Hover = Color(0xFF262B33)
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

/** Round corners everywhere: cards, sheets and dialogs. A card is 24 dp, a big tile 28 dp. */
private val Rounded = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** The colors of the icons on the Send tab's tiles. Each pair is the light and dark version, so the icon reads on both. */
internal enum class Accent(private val light: Long, private val dark: Long) {
    Blue(0xFF0277BD, 0xFF4FC3F7),
    Green(0xFF047857, 0xFF34D399),
    Amber(0xFFB45309, 0xFFFBBF24),
    Pink(0xFFBE185D, 0xFFF472B6),
    Violet(0xFF6D28D9, 0xFFA78BFA);

    @Composable
    fun color(): Color = Color(if (isSystemInDarkTheme()) dark else light)
}

@Composable
fun LanShareTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, shapes = Rounded, content = content)
}
