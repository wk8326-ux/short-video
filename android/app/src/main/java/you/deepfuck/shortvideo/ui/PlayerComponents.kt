@file:androidx.annotation.OptIn(
    markerClass = [androidx.media3.common.util.UnstableApi::class],
)

package you.deepfuck.shortvideo.ui

import android.graphics.Color as AndroidColor
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.util.Locale
import kotlinx.coroutines.delay
import you.deepfuck.shortvideo.media.PlayerSnapshot

@Composable
internal fun PlayerHost(player: ExoPlayer, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                keepScreenOn = true
                setKeepContentOnPlayerReset(true)
                setShutterBackgroundColor(AndroidColor.TRANSPARENT)
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                this.player = player
            }
        },
        update = { it.player = player },
        onRelease = { it.player = null },
    )
}

@Composable
internal fun BufferSpinner(visible: Boolean, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        DelayedSpinner(visible)
    }
}

@Composable
internal fun DelayedSpinner(visible: Boolean, modifier: Modifier = Modifier) {
    var delayedVisible by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        delayedVisible = false
        if (visible) {
            delay(220)
            delayedVisible = true
        }
    }
    if (!delayedVisible) return
    CircularProgressIndicator(
        modifier = modifier.size(24.dp),
        color = Color.White,
        strokeWidth = 2.dp,
    )
}

@Composable
internal fun FineProgressBar(
    player: PlayerSnapshot,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val duration = player.durationMs.coerceAtLeast(1L)
    val played = if (dragging) {
        dragFraction
    } else {
        (player.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    }
    val buffered = (player.bufferedMs.toFloat() / duration).coerceIn(played, 1f)
    val pulseTransition = rememberInfiniteTransition(label = "progress pulse")
    val pulse by pulseTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "progress pulse radius",
    )
    val enabled = player.durationMs > 0L

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .semantics {
                contentDescription = "播放进度"
                progressBarRangeInfo = ProgressBarRangeInfo(played, 0f..1f)
                setProgress { target ->
                    if (!enabled) return@setProgress false
                    onSeek((target.coerceIn(0f, 1f) * duration).toLong())
                    true
                }
            }
            .pointerInput(duration, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    dragging = true
                    dragFraction = (down.position.x / size.width).coerceIn(0f, 1f)
                    var pressed = true
                    while (pressed) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        pressed = change.pressed
                        change.consume()
                    }
                    onSeek((dragFraction * duration).toLong())
                    dragging = false
                }
            },
    ) {
        val inset = 6.dp.toPx()
        val start = Offset(inset, center.y)
        val end = Offset(size.width - inset, center.y)
        val width = (end.x - start.x).coerceAtLeast(1f)
        val bufferedEnd = Offset(start.x + width * buffered, center.y)
        val playedEnd = Offset(start.x + width * played, center.y)
        val trackWidth = 2.dp.toPx()

        drawLine(Color.White.copy(alpha = 0.22f), start, end, trackWidth, StrokeCap.Round)
        drawLine(Color.White.copy(alpha = 0.38f), start, bufferedEnd, trackWidth, StrokeCap.Round)
        drawLine(Color.White, start, playedEnd, trackWidth, StrokeCap.Round)
        if (player.isPlaying && !dragging) {
            drawCircle(
                color = Color.White.copy(alpha = (1f - pulse) * 0.18f),
                radius = (4.5.dp + 4.dp * pulse).toPx(),
                center = playedEnd,
            )
        }
        drawCircle(Color.White, radius = 4.5.dp.toPx(), center = playedEnd)
    }
}

internal fun formatDuration(milliseconds: Long): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0L) / 1_000L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "--"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit += 1
    }
    return String.format(Locale.ROOT, if (value >= 10 || unit == 0) "%.0f %s" else "%.1f %s", value, units[unit])
}
