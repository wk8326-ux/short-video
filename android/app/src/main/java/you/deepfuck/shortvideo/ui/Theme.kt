package you.deepfuck.shortvideo.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Canvas = Color(0xFF090A0B)
val Raised = Color(0xFF111214)
val TextPrimary = Color(0xFFF4F1ED)
val TextSecondary = Color(0xFFB7B2AC)
val TextFaint = Color(0xFF7E7B77)
val Accent = Color(0xFFE7464F)
val Line = Color(0x1FFFFFFF)
val FrostedChromeSurface = Color(0xDDF7F7F5)
val FrostedChromeContent = Color(0xFF18191B)
val FrostedChromeMuted = Color(0xFF5D6064)
val FrostedChromeOutline = Color(0xB3FFFFFF)

private val ShortVideoColors = darkColorScheme(
    primary = Accent,
    onPrimary = TextPrimary,
    background = Canvas,
    onBackground = TextPrimary,
    surface = Raised,
    onSurface = TextPrimary,
    surfaceVariant = Color(0xFF1A1B1D),
    onSurfaceVariant = TextSecondary,
    outline = Color(0x33FFFFFF),
    error = Color(0xFFFF727A),
)

@Composable
fun ShortVideoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ShortVideoColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
