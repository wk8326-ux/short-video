@file:androidx.annotation.OptIn(
    markerClass = [androidx.media3.common.util.UnstableApi::class],
)

package you.deepfuck.shortvideo.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.util.Locale
import kotlin.math.roundToInt
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
                setKeepContentOnPlayerReset(false)
                setShutterBackgroundColor(AndroidColor.BLACK)
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
    val transition = rememberInfiniteTransition(label = "loading ring")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(850, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "loading ring rotation",
    )
    Canvas(
        modifier = modifier
            .size(24.dp)
            .semantics { contentDescription = "正在加载" },
    ) {
        val stroke = 2.dp.toPx()
        drawArc(
            color = Color.White,
            startAngle = rotation,
            sweepAngle = 252f,
            useCenter = false,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

@Composable
internal fun PlayerSideControls(
    volume: Float,
    onVolumeChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val window = remember(context) { findActivityWindow(context) }
    var brightness by remember(window) { mutableFloatStateOf(windowBrightness(window)) }
    var feedback by remember { mutableStateOf<SideControlFeedback?>(null) }

    LaunchedEffect(feedback) {
        if (feedback != null) {
            delay(900)
            feedback = null
        }
    }

    Box(modifier) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VerticalPlayerControl(
                label = "亮度",
                value = brightness,
                onValueChanged = { value ->
                    brightness = value
                    window?.let { target ->
                        target.attributes = target.attributes.apply { screenBrightness = value }
                    }
                    feedback = SideControlFeedback(SideControl.BRIGHTNESS, value)
                },
            )
            VerticalPlayerControl(
                label = "音量",
                value = volume,
                onValueChanged = { value ->
                    onVolumeChanged(value)
                    feedback = SideControlFeedback(SideControl.VOLUME, value)
                },
            )
        }
        feedback?.let { current ->
            val alignment = if (current.side == SideControl.BRIGHTNESS) {
                Alignment.CenterStart
            } else {
                Alignment.CenterEnd
            }
            Column(
                modifier = Modifier
                    .align(alignment)
                    .padding(horizontal = 14.dp)
                    .width(44.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    if (current.side == SideControl.BRIGHTNESS) {
                        androidx.compose.material.icons.Icons.Outlined.Brightness6
                    } else {
                        androidx.compose.material.icons.Icons.AutoMirrored.Outlined.VolumeUp
                    },
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    "${(current.value * 100).roundToInt()}%",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(44.dp),
                )
            }
        }
    }
}

private enum class SideControl {
    BRIGHTNESS,
    VOLUME,
}

private data class SideControlFeedback(
    val side: SideControl,
    val value: Float,
)

@Composable
private fun VerticalPlayerControl(
    label: String,
    value: Float,
    onValueChanged: (Float) -> Unit,
) {
    val latestValue = rememberUpdatedState(value.coerceIn(0f, 1f))
    val latestOnValueChanged = rememberUpdatedState(onValueChanged)
    var gestureValue by remember { mutableFloatStateOf(value.coerceIn(0f, 1f)) }
    LaunchedEffect(value) {
        gestureValue = value.coerceIn(0f, 1f)
    }
    Box(
        modifier = Modifier
            .width(88.dp)
            .fillMaxHeight()
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(0f, 1f), 0f..1f)
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { gestureValue = latestValue.value },
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        val next = (gestureValue - amount / size.height).coerceIn(0f, 1f)
                        gestureValue = next
                        latestOnValueChanged.value(next)
                    },
                )
            },
    )
}

private fun findActivityWindow(context: Context): android.view.Window? {
    var current = context
    while (current is ContextWrapper) {
        if (current is Activity) return current.window
        val next = current.baseContext
        if (next === current) break
        current = next
    }
    return null
}

private fun windowBrightness(window: android.view.Window?): Float =
    window?.attributes?.screenBrightness?.takeIf { it in 0f..1f } ?: 0.5f

@Composable
internal fun FineProgressBar(
    player: PlayerSnapshot,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
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
            .height(if (compact) 24.dp else 44.dp)
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
        val inset = if (compact) 0f else 6.dp.toPx()
        val start = Offset(inset, center.y)
        val end = Offset(size.width - inset, center.y)
        val width = (end.x - start.x).coerceAtLeast(1f)
        val bufferedEnd = Offset(start.x + width * buffered, center.y)
        val playedEnd = Offset(start.x + width * played, center.y)
        val trackWidth = 2.dp.toPx()

        drawLine(Color.White.copy(alpha = 0.22f), start, end, trackWidth, StrokeCap.Round)
        drawLine(Color.White.copy(alpha = 0.38f), start, bufferedEnd, trackWidth, StrokeCap.Round)
        drawLine(Color.White, start, playedEnd, trackWidth, StrokeCap.Round)
        if (player.isPlaying && !dragging && !compact) {
            drawCircle(
                color = Color.White.copy(alpha = (1f - pulse) * 0.18f),
                radius = (4.5.dp + 4.dp * pulse).toPx(),
                center = playedEnd,
            )
        }
        drawCircle(Color.White, radius = (if (compact) 3.dp else 4.5.dp).toPx(), center = playedEnd)
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
