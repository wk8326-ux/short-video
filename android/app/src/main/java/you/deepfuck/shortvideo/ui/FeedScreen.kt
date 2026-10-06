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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.movableContentOf
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
import you.deepfuck.shortvideo.shouldShowInitialFeedLoading
import you.deepfuck.shortvideo.shouldAttachFeedPlayer
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
    pipMode: Boolean,
    onSurface: (MediaSurface) -> Unit,
    onMode: (FeedMode) -> Unit,
    onToggleFavorite: (MediaEntry) -> Unit,
    onActive: (Int) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onTogglePlayback: () -> Unit,
    onMuted: (Boolean) -> Unit,
    onVolume: (Float) -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onFullscreen: (Boolean) -> Unit,
    onPip: () -> Unit,
    onManage: () -> Unit,
    onCheckUpdate: () -> Unit,
    onLogout: () -> Unit,
) {
    if (state.feedItems.isEmpty()) {
        FeedState(
            shouldShowInitialFeedLoading(state.feedItems.size, state.feedLoading),
            state.feedError,
            onRetry,
        )
        return
    }
    val pagerState = rememberPagerState(
        initialPage = state.activeIndex.coerceIn(0, state.feedItems.lastIndex),
        pageCount = { state.feedItems.size },
    )
    var fullscreenChromeVisible by remember(fullscreen) { mutableStateOf(true) }
    val movablePlayerHost = remember(exoPlayer) {
        movableContentOf { PlayerHost(exoPlayer, Modifier.fillMaxSize()) }
    }

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
                playerHost = movablePlayerHost,
                muted = state.muted,
                fullscreen = fullscreen,
                pipMode = pipMode,
                mode = state.mode,
                favorite = "${state.surface.apiValue}:${state.feedItems[index].id}" in state.favoriteMediaIds,
                onMode = onMode,
                onToggleFavorite = { onToggleFavorite(state.feedItems[index]) },
                onTogglePlayback = onTogglePlayback,
                onMuted = onMuted,
                onVolume = onVolume,
                onSeek = onSeek,
                onSeekBy = onSeekBy,
                onFullscreen = onFullscreen,
                onPip = onPip,
                onChromeVisible = { visible ->
                    if (index == pagerState.currentPage) fullscreenChromeVisible = visible
                },
            )
        }
        if (fullscreen && fullscreenChromeVisible) {
            Box(Modifier.padding(top = 2.dp)) {
                AppNavigation(
                    state = state,
                    compact = false,
                    onSurface = onSurface,
                    onMode = onMode,
                    onManage = onManage,
                    onCheckUpdate = onCheckUpdate,
                    onLogout = onLogout,
                )
            }
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
    playerHost: @Composable () -> Unit,
    muted: Boolean,
    fullscreen: Boolean,
    pipMode: Boolean,
    mode: FeedMode,
    favorite: Boolean,
    onMode: (FeedMode) -> Unit,
    onToggleFavorite: () -> Unit,
    onTogglePlayback: () -> Unit,
    onMuted: (Boolean) -> Unit,
    onVolume: (Float) -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onFullscreen: (Boolean) -> Unit,
    onPip: () -> Unit,
    onChromeVisible: (Boolean) -> Unit,
) {
    var chromeVisible by remember(fullscreen, entry.id) { mutableStateOf(true) }
    var playbackFeedbackVisible by remember(fullscreen, entry.id) { mutableStateOf(false) }
    var playbackFeedbackShowsPause by remember(fullscreen, entry.id) { mutableStateOf(false) }
    var chromeInteractionToken by remember(fullscreen, entry.id) { mutableStateOf(0) }
    var dragPixels by remember { mutableFloatStateOf(0f) }
    var seekPreviewMs by remember { mutableStateOf<Long?>(null) }
    var seekBaseMs by remember { mutableStateOf(0L) }
    val latestPositionMs = rememberUpdatedState(player.positionMs)

    LaunchedEffect(chromeVisible, entry.id, pipMode, chromeInteractionToken) {
        if (chromeVisible && !pipMode && active) {
            delay(5_000)
            chromeVisible = false
        }
    }
    LaunchedEffect(playbackFeedbackVisible, entry.id) {
        if (playbackFeedbackVisible) {
            delay(900)
            playbackFeedbackVisible = false
        }
    }
    LaunchedEffect(active, chromeVisible) {
        if (active) onChromeVisible(chromeVisible)
    }

    fun showChrome() {
        chromeVisible = true
        chromeInteractionToken += 1
    }

    // Scrubbing works in both orientations; the vertical pager keeps owning the
    // up and down gesture, so only the horizontal drag is claimed here.
    val gestureModifier = if (pipMode) {
        Modifier
    } else {
        Modifier.pointerInput(player.durationMs, entry.id) {
            detectHorizontalDragGestures(
                onDragStart = {
                    dragPixels = 0f
                    seekBaseMs = latestPositionMs.value
                    seekPreviewMs = seekBaseMs
                    showChrome()
                },
                onHorizontalDrag = { change, amount ->
                    change.consume()
                    dragPixels += amount
                    val duration = player.durationMs
                    if (duration > 0L) {
                        seekPreviewMs = calculateSeekTarget(
                            basePositionMs = seekBaseMs,
                            accumulatedDragPx = dragPixels,
                            widthPx = size.width,
                            durationMs = duration,
                            maxDeltaMs = 90_000L,
                        )
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
                    showChrome()
                    playbackFeedbackShowsPause = player.playWhenReady
                    playbackFeedbackVisible = true
                    onTogglePlayback()
                },
            ),
    ) {
        if (shouldAttachFeedPlayer(active, entry.id, player.mediaId)) playerHost()
        BufferSpinner(active && player.isBuffering && player.mediaId == entry.id)

        val controlsVisible = !pipMode && chromeVisible
        if (!pipMode) {
            PlayerSideControls(
                volume = player.volume,
                onVolumeChanged = onVolume,
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            )
        }
        if (controlsVisible) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(if (fullscreen) 150.dp else 190.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0xA8000000)),
                        ),
                    ),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .then(if (!fullscreen) Modifier.navigationBarsPadding() else Modifier)
                    .padding(start = 12.dp, bottom = if (fullscreen) 18.dp else UiDimens.PlayerBottomContentInset),
            ) {
                Text(
                    entry.title,
                    modifier = Modifier.widthIn(max = 260.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                Text(
                    "${index + 1} / ${total.coerceAtLeast(index + 1)}",
                    color = Color.White.copy(alpha = 0.72f),
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .then(if (!fullscreen) Modifier.navigationBarsPadding() else Modifier)
                    .padding(end = 8.dp, bottom = if (fullscreen) 18.dp else UiDimens.PlayerBottomContentInset),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val modeIcon = when (mode) {
                    FeedMode.SHUFFLE -> Icons.Outlined.Shuffle
                    FeedMode.NEWEST -> Icons.Outlined.Schedule
                    FeedMode.OLDEST -> Icons.Outlined.History
                }
                val modeLabel = when (mode) {
                    FeedMode.SHUFFLE -> "随机播放"
                    FeedMode.NEWEST -> "最新优先"
                    FeedMode.OLDEST -> "最早优先"
                }
                OverlayIconControl(
                    label = "播放顺序：$modeLabel",
                    size = 44,
                    tint = Color.White,
                    onClick = {
                        showChrome()
                        onMode(FeedMode.entries[(FeedMode.entries.indexOf(mode) + 1) % FeedMode.entries.size])
                    },
                ) { Icon(modeIcon, contentDescription = null) }
                OverlayIconControl(
                    label = if (fullscreen) "退出横屏" else "横屏",
                    size = 44,
                    tint = Color.White,
                    onClick = {
                        showChrome()
                        onFullscreen(!fullscreen)
                    },
                ) {
                    Icon(if (fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen, contentDescription = null)
                }
                OverlayIconControl(
                    label = "浮窗播放",
                    size = 44,
                    tint = Color.White,
                    onClick = {
                        showChrome()
                        onPip()
                    },
                ) { Icon(Icons.Outlined.PictureInPictureAlt, contentDescription = null) }
                OverlayIconControl(
                    label = if (favorite) "取消收藏" else "收藏",
                    size = 44,
                    tint = Color.White,
                    onClick = {
                        showChrome()
                        onToggleFavorite()
                    },
                ) {
                    Icon(if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, contentDescription = null)
                }
            }
            ProgressControl(
                player = player,
                onSeek = onSeek,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .then(if (!fullscreen) Modifier.navigationBarsPadding() else Modifier)
                    .padding(bottom = if (fullscreen) 14.dp else UiDimens.PlayerBottomContentInset),
            )
        }

        val showPlaybackFeedback = !pipMode && playbackFeedbackVisible && !player.isBuffering
        if (active && !fullscreen && showPlaybackFeedback && player.mediaId == entry.id) {
            OverlayIconControl(
                label = if (player.playWhenReady) "暂停" else "播放",
                size = 64,
                modifier = Modifier.align(Alignment.Center),
                onClick = {
                    playbackFeedbackShowsPause = player.playWhenReady
                    playbackFeedbackVisible = true
                    showChrome()
                    onTogglePlayback()
                },
            ) {
                Icon(
                    if (playbackFeedbackShowsPause) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp),
                )
            }
        }

        if (active && fullscreen && showPlaybackFeedback && player.mediaId == entry.id) {
            Row(
                modifier = Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(36.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OverlayIconControl(label = "后退 10 秒", onClick = { onSeekBy(-10_000L) }) {
                    Icon(Icons.Outlined.Replay10, contentDescription = null)
                }
                OverlayIconControl(
                    label = if (player.playWhenReady) "暂停" else "播放",
                    size = 64,
                    tint = Color.White,
                    onClick = {
                        playbackFeedbackShowsPause = player.playWhenReady
                        playbackFeedbackVisible = true
                        showChrome()
                        onTogglePlayback()
                    },
                ) {
                    Icon(
                        if (playbackFeedbackShowsPause) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                    )
                }
                OverlayIconControl(label = "快进 10 秒", onClick = { onSeekBy(10_000L) }) {
                    Icon(Icons.Outlined.Forward10, contentDescription = null)
                }
            }
        }

        seekPreviewMs?.let { target ->
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .glassSurface(fill = GlassFillSoft)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    if (target >= player.positionMs) Icons.Outlined.Forward10 else Icons.Outlined.Replay10,
                    contentDescription = null,
                    tint = Color.White,
                )
                Text(
                    "${formatDuration(target)} / ${formatDuration(player.durationMs)}",
                    color = Color.White,
                )
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
    FineProgressBar(
        player = player,
        onSeek = onSeek,
        modifier = modifier.padding(horizontal = 8.dp),
    )
}

@Composable
internal fun OverlayIconControl(
    label: String,
    modifier: Modifier = Modifier,
    size: Int = 48,
    tint: Color = Color.White,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    GlassIconButton(
        label = label,
        onClick = onClick,
        modifier = modifier
            .size(size.dp),
        size = size.dp,
        tint = tint,
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
                TextButton(onClick = onRetry, modifier = Modifier.widthIn(min = 96.dp).height(48.dp)) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("重试")
                }
            }
        }
    }
}
