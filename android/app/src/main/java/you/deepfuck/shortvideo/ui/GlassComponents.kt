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

internal val GlassPanelShape = RoundedCornerShape(8.dp)
internal val ControlShape = RoundedCornerShape(6.dp)

internal fun Modifier.glassSurface(
    shape: Shape = GlassPanelShape,
    fill: Color = GlassFill,
    line: Color = GlassLine,
): Modifier = this
    .clip(shape)
    .background(fill, shape)
    .drawWithCache {
        val highlight = Brush.verticalGradient(
            colors = listOf(GlassHighlight, Color.Transparent),
            endY = 18.dp.toPx(),
        )
        onDrawBehind { drawRect(highlight) }
    }
    .border(width = 1.dp, color = line, shape = shape)

@Composable
internal fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = GlassPanelShape,
    fill: Color = GlassFill,
    line: Color = GlassLine,
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
    tint: Color = Color.White,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = tween(120),
        label = "$label press",
    )
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .semantics { contentDescription = label },
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(contentColor = tint),
        interactionSource = interactionSource,
        content = content,
    )
}
