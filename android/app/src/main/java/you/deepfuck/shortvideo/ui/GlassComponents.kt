package you.deepfuck.shortvideo.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val GlassPanelShape = RoundedCornerShape(UiDimens.CardRadius)
internal val ControlShape = RoundedCornerShape(UiDimens.ControlRadius)

@Composable
internal fun Modifier.glassSurface(
    shape: Shape = GlassPanelShape,
    fill: Color = Color.Unspecified,
    line: Color = Color.Unspecified,
): Modifier {
    val colors = MaterialTheme.colorScheme
    val resolvedFill = if (fill == Color.Unspecified) colors.surfaceContainerLow else fill
    val resolvedLine = if (line == Color.Unspecified) colors.outlineVariant else line
    val highlightColor = colors.surfaceContainerHighest.copy(alpha = 0.18f)
    return this
        .clip(shape)
        .background(resolvedFill, shape)
        .drawWithCache {
            val highlight = Brush.verticalGradient(
                colors = listOf(highlightColor, Color.Transparent),
                endY = 18.dp.toPx(),
            )
            onDrawBehind { drawRect(highlight) }
        }
        .border(width = 1.dp, color = resolvedLine, shape = shape)
}

@Composable
internal fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = GlassPanelShape,
    fill: Color = Color.Unspecified,
    line: Color = Color.Unspecified,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.glassSurface(shape = shape, fill = fill, line = line),
        content = content,
    )
}

@Composable
internal fun GlassIconButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    enabled: Boolean = true,
    tint: Color = Color.Unspecified,
    content: @Composable () -> Unit,
) {
    val resolvedSize = size.coerceAtLeast(UiDimens.TouchTarget)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val contentColor = if (tint == Color.Unspecified) {
        MaterialTheme.colorScheme.onSurface
    } else {
        tint
    }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = tween(120),
        label = "$label press",
    )
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(resolvedSize)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .semantics { contentDescription = label },
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(contentColor = contentColor),
        interactionSource = interactionSource,
        content = content,
    )
}
