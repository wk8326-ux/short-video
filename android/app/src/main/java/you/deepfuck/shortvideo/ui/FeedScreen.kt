package you.deepfuck.shortvideo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import you.deepfuck.shortvideo.AppUiState
import you.deepfuck.shortvideo.data.FeedMode
import you.deepfuck.shortvideo.data.MediaEntry
import you.deepfuck.shortvideo.data.MediaSurface
import you.deepfuck.shortvideo.media.PlayerSnapshot

@Composable
internal fun FeedScreen(
    state: AppUiState,
    player: PlayerSnapshot,
    exoPlayer: ExoPlayer,
    fullscreen: Boolean,
    onSurface: (MediaSurface) -> Unit,
    onMode: (FeedMode) -> Unit,
    onActive: (Int) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onTogglePlayback: () -> Unit,
    onMuted: (Boolean) -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onFullscreen: (Boolean) -> Unit,
    onManage: () -> Unit,
    onLogout: () -> Unit,
) {
    if (state.feedItems.isEmpty()) {
        FeedState(state.feedLoading, state.feedError, onRetry)
        return
    }
    val pagerState = rememberPagerState(
        initialPage = state.activeIndex.coerceIn(0, state.feedItems.lastIndex),
        pageCount = { state.feedItems.size },
    )
    var fullscreenChromeVisible by remember(fullscreen) { mutableStateOf(true) }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { page -> onActive(page) }
    }
    LaunchedEffect(state.activeIndex, state.feedItems.size) {
        if (!pagerState.isScrollInProgress && pagerState.currentPage != state.activeIndex) {
            pagerState.scrollToPage(state.activeIndex.coerceIn(0, state.feedItems.lastIndex))
        }
    }
    LaunchedEffect(pagerState.currentPage, state.feedItems.size) {
        if (pagerState.currentPage >= state.feedItems.lastIndex - 4) onLoadMore()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            key = { state.feedItems[it].id },
        ) { index ->
            FeedPage(
                entry = state.feedItems[index],
                index = index,
                total = state.feedTotal,
                active = index == pagerState.currentPage,
                player = player,
                exoPlayer = exoPlayer,
                muted = state.muted,
                fullscreen = fullscreen,
                onTogglePlayback = onTogglePlayback,
                onMuted = onMuted,
                onSeek = onSeek,
                onSeekBy = onSeekBy,
                onFullscreen = onFullscreen,
                onChromeVisible = { visible ->
                    if (index == pagerState.currentPage) fullscreenChromeVisible = visible
                },
            )
        }
        if (!fullscreen || fullscreenChromeVisible) {
            Box(Modifier.statusBarsPadding().padding(top = 8.dp)) {
                AppNavigation(
                    state = state,
                    compact = false,
                    onSurface = onSurface,
                    onMode = onMode,
                    onManage = onManage,
                    onLogout = onLogout,
                )
            }
        }
        if (state.feedLoading && state.feedItems.isNotEmpty()) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(18.dp).size(20.dp),
                color = TextSecondary,
                strokeWidth = 2.dp,
            )
        }
    }
}

@Composable
private fun FeedPage(
    entry: MediaEntry,
    index: Int,
    total: Int,
    active: Boolean,
    player: PlayerSnapshot,
    exoPlayer: ExoPlayer,
    muted: Boolean,
    fullscreen: Boolean,
    onTogglePlayback: () -> Unit,
    onMuted: (Boolean) -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onFullscreen: (Boolean) -> Unit,
    onChromeVisible: (Boolean) -> Unit,
) {
    var chromeVisible by remember(fullscreen, entry.id) { mutableStateOf(true) }
    var dragPixels by remember { mutableFloatStateOf(0f) }
    var seekPreviewMs by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(fullscreen, chromeVisible, player.isPlaying, player.mediaId) {
        if (fullscreen && chromeVisible && player.isPlaying && player.mediaId == entry.id) {
            delay(5_000)
            chromeVisible = false
        }
    }
    LaunchedEffect(active, chromeVisible) {
        if (active) onChromeVisible(chromeVisible)
    }

    val gestureModifier = if (fullscreen) {
        Modifier.pointerInput(player.durationMs, entry.id) {
            detectHorizontalDragGestures(
                onDragStart = {
                    dragPixels = 0f
                    seekPreviewMs = player.positionMs
                    chromeVisible = true
                },
                onHorizontalDrag = { change, amount ->
                    change.consume()
                    dragPixels += amount
                    val duration = player.durationMs
                    if (duration > 0L) {
                        val delta = (dragPixels / size.width * duration * 0.45f).toLong()
                            .coerceIn(-90_000L, 90_000L)
                        seekPreviewMs = (player.positionMs + delta).coerceIn(0L, duration)
                    }
                },
                onDragEnd = {
                    seekPreviewMs?.let(onSeek)
                    seekPreviewMs = null
                    dragPixels = 0f
                },
                onDragCancel = {
                    seekPreviewMs = null
                    dragPixels = 0f
                },
            )
        }
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(gestureModifier)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    if (fullscreen) chromeVisible = !chromeVisible else onTogglePlayback()
                },
            ),
    ) {
        if (active) PlayerHost(exoPlayer, Modifier.fillMaxSize())
        BufferSpinner(active && player.isBuffering && player.mediaId == entry.id)

        val controlsVisible = !fullscreen || chromeVisible
        if (controlsVisible) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(if (fullscreen) 150.dp else 190.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0xC7000000)),
                        ),
                    ),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 82.dp, bottom = 38.dp),
            ) {
                Text(
                    entry.title,
                    maxLines = if (fullscreen) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${index + 1} / ${total.coerceAtLeast(index + 1)}",
                    color = TextSecondary,
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 12.dp, bottom = 38.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircleControl(
                    label = if (muted) "打开声音" else "静音",
                    onClick = { onMuted(!muted) },
                ) {
                    Icon(if (muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = null)
                }
                CircleControl(
                    label = if (fullscreen) "退出横屏" else "横屏",
                    onClick = { onFullscreen(!fullscreen) },
                ) {
                    Icon(if (fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen, contentDescription = null)
                }
            }
            ProgressControl(
                player = player,
                onSeek = onSeek,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
            )
        }

        val showCenter = fullscreen && chromeVisible || !fullscreen && !player.isPlaying
        if (active && showCenter && player.mediaId == entry.id) {
            CircleControl(
                label = if (player.isPlaying) "暂停" else "播放",
                size = 64,
                modifier = Modifier.align(Alignment.Center),
                onClick = onTogglePlayback,
            ) {
                Icon(
                    if (player.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                )
            }
        }

        if (fullscreen && chromeVisible) {
            Row(
                modifier = Modifier.align(Alignment.Center).padding(top = 104.dp),
                horizontalArrangement = Arrangement.spacedBy(84.dp),
            ) {
                IconButton(onClick = { onSeekBy(-10_000L) }) {
                    Icon(Icons.Outlined.Replay10, contentDescription = "后退 10 秒")
                }
                IconButton(onClick = { onSeekBy(10_000L) }) {
                    Icon(Icons.Outlined.FastForward, contentDescription = "快进 10 秒")
                }
            }
        }

        seekPreviewMs?.let { target ->
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xC2090A0B))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    if (target >= player.positionMs) Icons.Outlined.FastForward else Icons.Outlined.Replay10,
                    contentDescription = null,
                )
                Text("${formatDuration(target)} / ${formatDuration(player.durationMs)}")
            }
        }
    }
}

@Composable
private fun ProgressControl(
    player: PlayerSnapshot,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf(false) }
    var value by remember { mutableFloatStateOf(0f) }
    val duration = player.durationMs.coerceAtLeast(1L)
    val shown = if (dragging) value else (player.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    Row(
        modifier = modifier.fillMaxWidth().height(38.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Slider(
            value = shown,
            onValueChange = { dragging = true; value = it },
            onValueChangeFinished = {
                onSeek((value * duration).toLong())
                dragging = false
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = player.durationMs > 0L,
            colors = SliderDefaults.colors(
                thumbColor = TextPrimary,
                activeTrackColor = Accent,
                inactiveTrackColor = Color(0x55FFFFFF),
            ),
        )
    }
}

@Composable
internal fun CircleControl(
    label: String,
    modifier: Modifier = Modifier,
    size: Int = 48,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(Color(0xA6090A0B))
            .semantics { contentDescription = label },
    ) {
        content()
    }
}

@Composable
private fun FeedState(loading: Boolean, error: String?, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Canvas), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(28.dp), color = TextSecondary, strokeWidth = 2.dp)
            } else {
                Text(error ?: "暂无视频", color = TextSecondary)
                TextButton(onClick = onRetry, modifier = Modifier.widthIn(min = 96.dp).height(44.dp)) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("重试")
                }
            }
        }
    }
}
