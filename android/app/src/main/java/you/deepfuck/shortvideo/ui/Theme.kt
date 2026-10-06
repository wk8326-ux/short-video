package you.deepfuck.shortvideo.ui

import android.graphics.Typeface
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

internal data class AppSkinOption(
    val id: String,
    val label: String,
    val accent: Color,
)

internal val AppSkinOptions = listOf(
    AppSkinOption("obsidian-coral", "沉浸暗黑 · Immersive Dark", Color(0xFF7C3AED)),
    AppSkinOption("graphite-cyan", "极简留白 · Minimalist Light", Color(0xFF2563EB)),
    AppSkinOption("midnight-amber", "OLED 极致对比 · Black & Mint", Color(0xFF10B981)),
    AppSkinOption("forest-mint", "赛博霓虹 · Cyberpunk Neon", Color(0xFFEC4899)),
    AppSkinOption("paper-ink", "温暖黑胶 · Warm Vinyl", Color(0xFFC05621)),
)

private val RobotoFlex = FontFamily(Typeface.create("sans-serif-flex", Typeface.NORMAL))

private val AppTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = RobotoFlex,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 31.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = RobotoFlex,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 27.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = RobotoFlex,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 23.sp,
        letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = RobotoFlex,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = RobotoFlex,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = RobotoFlex,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = RobotoFlex,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = RobotoFlex,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = RobotoFlex,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
    ),
)

private data class SkinColors(
    val primary: Color,
    val primaryOn: Color,
    val primaryContainer: Color,
    val secondary: Color,
    val secondaryContainer: Color,
    val tertiaryContainer: Color,
    val lightSurface: Color,
    val lightLow: Color,
    val light: Color,
    val lightHigh: Color,
    val lightHighest: Color,
    val darkSurface: Color,
    val darkLow: Color,
    val dark: Color,
    val darkHigh: Color,
    val darkHighest: Color,
    val lightOn: Color = Color(0xFF020000),
    val lightVariant: Color = Color(0xFF25181C),
    val darkOn: Color = Color.White,
    val darkVariant: Color = Color(0xFFFEECF1),
    val lightOnPrimaryContainer: Color = Color.White,
    val lightOnSecondaryContainer: Color = Color.White,
    val lightOnTertiaryContainer: Color = Color.White,
    val outlineLight: Color = Color(0xFF3A2D31),
    val outlineDark: Color = Color(0xFFF0DEE3),
    val darkPrimary: Color = primary,
    val darkPrimaryContainer: Color = primaryContainer,
    val darkSecondary: Color = secondary,
    val darkSecondaryContainer: Color = secondaryContainer,
    val darkTertiary: Color = tertiaryContainer,
    val darkTertiaryContainer: Color = darkTertiary,
    val darkOnPrimaryContainer: Color = Color.White,
    val darkOnSecondaryContainer: Color = Color.White,
)

private val SkinPalette = mapOf(
    // Immersive Dark: charcoal canvas with a restrained violet focus color.
    "obsidian-coral" to SkinColors(
        primary = Color(0xFF7C3AED), primaryOn = Color.White,
        primaryContainer = Color(0xFF5B21B6), secondary = Color(0xFFA1A1AA),
        secondaryContainer = Color(0xFF2B2D35), tertiaryContainer = Color(0xFF3F3A52),
        lightSurface = Color(0xFF0F1115), lightLow = Color(0xFF15171D),
        light = Color(0xFF1C1E24), lightHigh = Color(0xFF252832),
        lightHighest = Color(0xFF30343D), darkSurface = Color(0xFF0F1115),
        darkLow = Color(0xFF15171D), dark = Color(0xFF1C1E24),
        darkHigh = Color(0xFF252832), darkHighest = Color(0xFF30343D),
        lightOn = Color.White, lightVariant = Color(0xFFA1A1AA),
        darkOn = Color.White, darkVariant = Color(0xFFA1A1AA),
        outlineLight = Color(0xFF454852), outlineDark = Color(0xFF454852),
        darkPrimary = Color(0xFF7C3AED), darkPrimaryContainer = Color(0xFF5B21B6),
        darkSecondary = Color(0xFFA1A1AA), darkSecondaryContainer = Color(0xFF2B2D35),
        darkTertiary = Color(0xFFC4B5FD), darkOnPrimaryContainer = Color.White,
        darkOnSecondaryContainer = Color.White,
    ),
    // Minimalist Light: cool white canvas, blue actions, slate text.
    "graphite-cyan" to SkinColors(
        primary = Color(0xFF2563EB), primaryOn = Color.White,
        primaryContainer = Color(0xFFDBEAFE), secondary = Color(0xFF64748B),
        secondaryContainer = Color(0xFFE2E8F0), tertiaryContainer = Color(0xFFE0E7FF),
        lightSurface = Color(0xFFF8F9FA), lightLow = Color(0xFFF1F5F9),
        light = Color.White, lightHigh = Color(0xFFEFF3F8),
        lightHighest = Color(0xFFE2E8F0), darkSurface = Color(0xFFF8F9FA),
        darkLow = Color(0xFFF1F5F9), dark = Color.White,
        darkHigh = Color(0xFFEFF3F8), darkHighest = Color(0xFFE2E8F0),
        lightOn = Color(0xFF1E293B), lightVariant = Color(0xFF64748B),
        darkOn = Color(0xFF1E293B), darkVariant = Color(0xFF64748B),
        outlineLight = Color(0xFFCBD5E1), outlineDark = Color(0xFFCBD5E1),
        darkPrimary = Color(0xFF2563EB), darkPrimaryContainer = Color(0xFFDBEAFE),
        darkSecondary = Color(0xFF64748B), darkSecondaryContainer = Color(0xFFE2E8F0),
        darkTertiary = Color(0xFF4F46E5), darkOnPrimaryContainer = Color(0xFF1E3A8A),
        darkOnSecondaryContainer = Color(0xFF334155),
        lightOnPrimaryContainer = Color(0xFF1E3A8A),
        lightOnSecondaryContainer = Color(0xFF334155),
        lightOnTertiaryContainer = Color(0xFF312E81),
    ),
    // OLED Black & Mint: pure black canvas with high-saturation mint actions.
    "midnight-amber" to SkinColors(
        primary = Color(0xFF10B981), primaryOn = Color(0xFF03251B),
        primaryContainer = Color(0xFF047857), secondary = Color(0xFF94A3B8),
        secondaryContainer = Color(0xFF1E293B), tertiaryContainer = Color(0xFF134E4A),
        lightSurface = Color.Black, lightLow = Color(0xFF080808),
        light = Color(0xFF121212), lightHigh = Color(0xFF1D1D1D),
        lightHighest = Color(0xFF292929), darkSurface = Color.Black,
        darkLow = Color(0xFF080808), dark = Color(0xFF121212),
        darkHigh = Color(0xFF1D1D1D), darkHighest = Color(0xFF292929),
        lightOn = Color(0xFFF8FAFC), lightVariant = Color(0xFF94A3B8),
        darkOn = Color(0xFFF8FAFC), darkVariant = Color(0xFF94A3B8),
        outlineLight = Color(0xFF334155), outlineDark = Color(0xFF334155),
        darkPrimary = Color(0xFF10B981), darkPrimaryContainer = Color(0xFF047857),
        darkSecondary = Color(0xFF94A3B8), darkSecondaryContainer = Color(0xFF1E293B),
        darkTertiary = Color(0xFF5EEAD4), darkOnPrimaryContainer = Color.White,
        darkOnSecondaryContainer = Color(0xFFE2E8F0),
    ),
    // Cyberpunk Neon: pink primary and cyan secondary over midnight indigo.
    "forest-mint" to SkinColors(
        primary = Color(0xFFEC4899), primaryOn = Color.White,
        primaryContainer = Color(0xFF9D174D), secondary = Color(0xFF06B6D4),
        secondaryContainer = Color(0xFF164E63), tertiaryContainer = Color(0xFF312E81),
        lightSurface = Color(0xFF09090B), lightLow = Color(0xFF10101A),
        light = Color(0xFF181825), lightHigh = Color(0xFF24243A),
        lightHighest = Color(0xFF31314B), darkSurface = Color(0xFF09090B),
        darkLow = Color(0xFF10101A), dark = Color(0xFF181825),
        darkHigh = Color(0xFF24243A), darkHighest = Color(0xFF31314B),
        lightOn = Color.White, lightVariant = Color(0xFFA6ADC8),
        darkOn = Color.White, darkVariant = Color(0xFFA6ADC8),
        outlineLight = Color(0xFF464663), outlineDark = Color(0xFF464663),
        darkPrimary = Color(0xFFEC4899), darkPrimaryContainer = Color(0xFF9D174D),
        darkSecondary = Color(0xFF06B6D4), darkSecondaryContainer = Color(0xFF164E63),
        darkTertiary = Color(0xFF67E8F9), darkOnPrimaryContainer = Color.White,
        darkOnSecondaryContainer = Color.White,
    ),
    // Warm Vinyl: cream paper, sand surfaces, and terracotta controls.
    "paper-ink" to SkinColors(
        primary = Color(0xFFC05621), primaryOn = Color.White,
        primaryContainer = Color(0xFF9C3D16), secondary = Color(0xFF718096),
        secondaryContainer = Color(0xFFE2D9C9), tertiaryContainer = Color(0xFFE8C7A8),
        lightSurface = Color(0xFFFDFBF7), lightLow = Color(0xFFF8F4EC),
        light = Color(0xFFF3EFE6), lightHigh = Color(0xFFEAE2D5),
        lightHighest = Color(0xFFE0D5C5), darkSurface = Color(0xFFFDFBF7),
        darkLow = Color(0xFFF8F4EC), dark = Color(0xFFF3EFE6),
        darkHigh = Color(0xFFEAE2D5), darkHighest = Color(0xFFE0D5C5),
        lightOn = Color(0xFF2D3748), lightVariant = Color(0xFF718096),
        darkOn = Color(0xFF2D3748), darkVariant = Color(0xFF718096),
        outlineLight = Color(0xFFB7A995), outlineDark = Color(0xFFB7A995),
        darkPrimary = Color(0xFFC05621), darkPrimaryContainer = Color(0xFF9C3D16),
        darkSecondary = Color(0xFF718096), darkSecondaryContainer = Color(0xFFE2D9C9),
        darkTertiary = Color(0xFF8B5E3C), darkOnPrimaryContainer = Color.White,
        darkOnSecondaryContainer = Color(0xFF2D3748),
        lightOnSecondaryContainer = Color(0xFF2D3748),
        lightOnTertiaryContainer = Color(0xFF5B3A22),
    ),
)

private fun SkinColors.toScheme(dark: Boolean): ColorScheme {
    val surfaceColor = if (dark) darkSurface else lightSurface
    val low = if (dark) darkLow else lightLow
    val base = if (dark) this.dark else light
    val high = if (dark) darkHigh else lightHigh
    val highest = if (dark) darkHighest else lightHighest
    val on = if (dark) darkOn else lightOn
    val variant = if (dark) darkVariant else lightVariant
    val outline = if (dark) outlineDark else outlineLight
    val errorColor = if (dark) Color(0xFFF2B8B5) else Color(0xFFB3261E)
    val onErrorColor = if (dark) Color(0xFF601410) else Color.White
    val errorContainerColor = if (dark) Color(0xFF8C1D18) else Color(0xFFF9DEDC)
    val onErrorContainerColor = if (dark) Color(0xFFF9DEDC) else Color(0xFF410E0B)
    return if (dark) {
        darkColorScheme(
            primary = darkPrimary,
            onPrimary = Color(0xFF020000),
            primaryContainer = darkPrimaryContainer,
            onPrimaryContainer = darkOnPrimaryContainer,
            secondary = darkSecondary,
            onSecondary = Color(0xFF020000),
            secondaryContainer = darkSecondaryContainer,
            onSecondaryContainer = darkOnSecondaryContainer,
            tertiary = darkTertiary,
            onTertiary = Color(0xFF020000),
            tertiaryContainer = darkTertiaryContainer,
            onTertiaryContainer = Color(0xFF010000),
            error = errorColor, onError = onErrorColor,
            errorContainer = errorContainerColor, onErrorContainer = onErrorContainerColor,
            background = surfaceColor, onBackground = on,
            surface = surfaceColor, onSurface = on,
            surfaceVariant = high, onSurfaceVariant = variant,
            surfaceContainerLowest = surfaceColor, surfaceContainerLow = low,
            surfaceContainer = base, surfaceContainerHigh = high,
            surfaceContainerHighest = highest, inverseSurface = lightHighest,
            inverseOnSurface = Color(0xFF352F30), inversePrimary = Color(0xFF984061),
            outline = outline, outlineVariant = outline,
            scrim = Color.Black, surfaceTint = primary,
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = primaryOn,
            primaryContainer = primaryContainer,
            onPrimaryContainer = lightOnPrimaryContainer,
            secondary = secondary,
            onSecondary = Color.White,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = lightOnSecondaryContainer,
            tertiary = tertiaryContainer,
            onTertiary = Color.White,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = lightOnTertiaryContainer,
            error = errorColor, onError = onErrorColor,
            errorContainer = errorContainerColor, onErrorContainer = onErrorContainerColor,
            background = surfaceColor, onBackground = on,
            surface = surfaceColor, onSurface = on,
            surfaceVariant = high, onSurfaceVariant = variant,
            surfaceContainerLowest = Color.White, surfaceContainerLow = low,
            surfaceContainer = base, surfaceContainerHigh = high,
            surfaceContainerHighest = highest, inverseSurface = Color(0xFF352F30),
            inverseOnSurface = Color(0xFFF7EFF1), inversePrimary = Color(0xFFFEB1C9),
            outline = outline, outlineVariant = outline,
            scrim = Color.Black, surfaceTint = primary,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ShortVideoTheme(
    skin: String = "obsidian-coral",
    content: @Composable () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val palette = SkinPalette[skin] ?: SkinPalette.getValue("obsidian-coral")
    val colors = palette.toScheme(dark)
    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        motionScheme = MotionScheme.standard(),
    ) {
        CompositionLocalProvider(LocalContentColor provides colors.onSurface, content = content)
    }
}

val Canvas: Color
    @Composable get() = MaterialTheme.colorScheme.background
val CanvasSoft: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainerLow
val Raised: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainer
val RaisedStrong: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainerHigh
val TextPrimary: Color
    @Composable get() = MaterialTheme.colorScheme.onSurface
val TextSecondary: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
val TextFaint: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
val Accent: Color
    @Composable get() = MaterialTheme.colorScheme.primary
val AccentSoft: Color
    @Composable get() = MaterialTheme.colorScheme.secondary
val Line: Color
    @Composable get() = MaterialTheme.colorScheme.outlineVariant
val GlassFill: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainer
val GlassFillSoft: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainerLow
val MiniPlayerGlassFill: Color
    @Composable get() = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
val GlassLine: Color
    @Composable get() = MaterialTheme.colorScheme.outline
val GlassHighlight: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.18f)
