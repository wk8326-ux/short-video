package you.deepfuck.shortvideo.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Canvas = Color(0xFF090A0B)
val CanvasSoft = Color(0xFF0E0F11)
val Raised = Color(0xFF15171A)
val RaisedStrong = Color(0xFF1B1D21)
val TextPrimary = Color(0xFFF4F1ED)
val TextSecondary = Color(0xFFBBB7B1)
val TextFaint = Color(0xFF85827E)
val Accent = Color(0xFFE7464F)
val AccentSoft = Color(0xFFFF727A)
val Line = Color(0x24FFFFFF)
val GlassFill = Color(0x8F0C0D0F)
val GlassFillSoft = Color(0x7015171A)
val GlassLine = Color(0x2EFFFFFF)
val GlassHighlight = Color(0x1FFFFFFF)

private val AppTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 31.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 27.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 23.sp,
        letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
    ),
)

private val ShortVideoColors = darkColorScheme(
    primary = Accent,
    onPrimary = TextPrimary,
    background = Canvas,
    onBackground = TextPrimary,
    surface = Raised,
    onSurface = TextPrimary,
    surfaceVariant = RaisedStrong,
    onSurfaceVariant = TextSecondary,
    outline = GlassLine,
    outlineVariant = Line,
    error = AccentSoft,
    onError = Color.White,
    scrim = Color.Black,
)

@Composable
fun ShortVideoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ShortVideoColors,
        typography = AppTypography,
    ) {
        CompositionLocalProvider(LocalContentColor provides TextPrimary, content = content)
    }
}
