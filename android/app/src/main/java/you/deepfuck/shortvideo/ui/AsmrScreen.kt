package you.deepfuck.shortvideo.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.PI
import kotlin.math.sin
import you.deepfuck.shortvideo.AppUiState
import you.deepfuck.shortvideo.ListPosition
import you.deepfuck.shortvideo.filterAsmrAuthors
import you.deepfuck.shortvideo.filterAsmrEntries
import you.deepfuck.shortvideo.shouldLoadMore
import you.deepfuck.shortvideo.data.AsmrAuthor
import you.deepfuck.shortvideo.data.AsmrFilter
import you.deepfuck.shortvideo.data.MediaEntry
import you.deepfuck.shortvideo.media.PlayerSnapshot

@Composable
internal fun AsmrScreen(
    state: AppUiState,
    player: PlayerSnapshot,
    exoPlayer: ExoPlayer,
    muted: Boolean,
    fullscreen: Boolean,
    authorListPosition: ListPosition,
    mediaListPosition: ListPosition,
    onAuthor: (String) -> Unit,
    onBackAuthor: () -> Unit,
    onLoadMoreItems: () -> Unit,
    onFilter: (AsmrFilter) -> Unit,
    onQuery: (String) -> Unit,
    onPlay: (MediaEntry) -> Unit,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onClose: () -> Unit,
    onToggle: () -> Unit,
    onMuted: (Boolean) -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onVideoBackgroundPlayback: (Boolean) -> Unit,
    onFullscreen: (Boolean) -> Unit,
    onAuthorListPosition: (ListPosition) -> Unit,
    onMediaListPosition: (String, ListPosition) -> Unit,
) {
    val miniPlayerVisible = state.nowPlaying != null && state.expandedMedia == null
    Box(Modifier.fillMaxSize().background(Canvas)) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Spacer(Modifier.height(52.dp))
            Box(Modifier.weight(1f)) {
                val selectedAuthor = state.selectedAuthor
                if (selectedAuthor == null) {
                    AuthorLibrary(
                        authors = state.asmrAuthors,
                        total = state.asmrAuthorsTotal,
                        loading = state.asmrLoading,
                        error = state.asmrError,
                        initialPosition = authorListPosition,
                        onPosition = onAuthorListPosition,
                        filter = state.asmrFilter,
                        onFilter = onFilter,
                        onAuthor = onAuthor,
                        bottomContentPadding = 16.dp,
                    )
                } else {
                    key(selectedAuthor) {
                        AuthorMedia(
                            state = state,
                            player = player,
                            initialPosition = mediaListPosition,
                            onPosition = { onMediaListPosition(selectedAuthor, it) },
                            onBack = onBackAuthor,
                            onLoadMore = onLoadMoreItems,
                            onFilter = onFilter,
                            onQuery = onQuery,
                            onPlay = onPlay,
                            bottomContentPadding = 16.dp,
                        )
                    }
                }
            }

            if (miniPlayerVisible) {
                MiniPlayer(
                    entry = requireNotNull(state.nowPlaying),
                    player = player,
                    onExpand = onExpand,
                    onToggle = onToggle,
                    onClose = onClose,
                    onSeek = onSeek,
                    modifier = Modifier.zIndex(1f),
                )
            }
        }

        state.expandedMedia?.let { entry ->
            ExpandedAsmrPlayer(
                entry = entry,
                player = player,
                exoPlayer = exoPlayer,
                muted = muted,
                fullscreen = fullscreen,
                backgroundPlayback = state.asmrVideoBackgroundPlayback,
                onCollapse = onCollapse,
                onClose = onClose,
                onToggle = onToggle,
                onMuted = onMuted,
                onSeek = onSeek,
                onSeekBy = onSeekBy,
                onBackgroundPlayback = onVideoBackgroundPlayback,
                onFullscreen = onFullscreen,
            )
        }
    }
}

@Composable
private fun AuthorLibrary(
    authors: List<AsmrAuthor>,
    total: Int,
    loading: Boolean,
    error: String?,
    initialPosition: ListPosition,
    onPosition: (ListPosition) -> Unit,
    filter: AsmrFilter,
    onFilter: (AsmrFilter) -> Unit,
    onAuthor: (String) -> Unit,
    bottomContentPadding: Dp,
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialPosition.index.coerceAtLeast(0),
        initialFirstVisibleItemScrollOffset = initialPosition.offset.coerceAtLeast(0),
    )
    DisposableEffect(listState) {
        onDispose {
            onPosition(ListPosition(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset))
        }
    }
    var query by remember { mutableStateOf("") }
    val filtered = remember(authors, filter, query) {
        filterAsmrAuthors(authors, filter, query)
    }
    LaunchedEffect(filtered.size) {
        if (filtered.isEmpty()) return@LaunchedEffect
        val current = ListPosition(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        val safe = current.clamp(filtered.size)
        if (safe != current) listState.scrollToItem(safe.index, safe.offset)
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "ASMR 媒体库",
                        fontWeight = FontWeight.SemiBold,
                        style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        if (total > authors.size) "已载入 ${authors.size} / $total 位作者" else "${authors.size} 位作者",
                        color = TextFaint,
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    )
                }
                Icon(
                    Icons.Outlined.LibraryMusic,
                    contentDescription = null,
                    tint = TextFaint,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            AsmrFilterTabs(selected = filter, onFilter = onFilter)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("搜索全部作者") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = Color.White) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = ControlShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedContainerColor = GlassFillSoft,
                    unfocusedContainerColor = CanvasSoft.copy(alpha = 0.7f),
                    focusedBorderColor = GlassLine,
                    unfocusedBorderColor = Line,
                    cursorColor = AccentSoft,
                ),
            )
        }
        when {
            loading && authors.isEmpty() -> ListLoadingPlaceholder()
            error != null && authors.isEmpty() -> CenterMessage(error)
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = bottomContentPadding,
                ),
            ) {
                items(filtered, key = AsmrAuthor::name) { author ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onAuthor(author.name) }
                            .padding(vertical = 14.dp),
                    ) {
                        Text(
                            author.name,
                            fontWeight = FontWeight.Medium,
                            style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                            color = TextPrimary,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${author.videoCount} 视频  ·  ${author.audioCount} 音频",
                                color = TextFaint,
                                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                "${author.itemCount} 项",
                                color = TextSecondary,
                                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.Outlined.ChevronRight,
                                contentDescription = null,
                                tint = TextFaint,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    HorizontalDivider(color = Line)
                }
            }
        }
    }
}

@Composable
private fun AsmrFilterTabs(
    selected: AsmrFilter,
    onFilter: (AsmrFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        AsmrFilter.entries.forEach { filter ->
            val active = selected == filter
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                TextButton(
                    onClick = { onFilter(filter) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .semantics { this.selected = active },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = Color.White.copy(alpha = if (active) 1f else 0.62f),
                    ),
                ) {
                    Text(
                        filter.label,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
                Box(
                    Modifier
                        .width(24.dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(if (active) Accent else Color.Transparent),
                )
            }
        }
    }
}

@Composable
private fun AuthorMedia(
    state: AppUiState,
    player: PlayerSnapshot,
    initialPosition: ListPosition,
    onPosition: (ListPosition) -> Unit,
    onBack: () -> Unit,
    onLoadMore: () -> Unit,
    onFilter: (AsmrFilter) -> Unit,
    onQuery: (String) -> Unit,
    onPlay: (MediaEntry) -> Unit,
    bottomContentPadding: Dp,
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialPosition.index.coerceAtLeast(0),
        initialFirstVisibleItemScrollOffset = initialPosition.offset.coerceAtLeast(0),
    )
    DisposableEffect(listState) {
        onDispose {
            onPosition(ListPosition(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset))
        }
    }
    val filtered = remember(state.asmrItems, state.asmrFilter, state.asmrQuery) {
        filterAsmrEntries(state.asmrItems, state.asmrFilter, state.asmrQuery)
    }
    LaunchedEffect(filtered.size) {
        if (filtered.isEmpty()) return@LaunchedEffect
        val current = ListPosition(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        val safe = current.clamp(filtered.size)
        if (safe != current) listState.scrollToItem(safe.index, safe.offset)
    }
    LaunchedEffect(listState, filtered.size, state.asmrLoading, state.asmrItemsHasMore, state.asmrQuery) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (
                    state.asmrQuery.isBlank() &&
                    shouldLoadMore(lastVisible, filtered.size, state.asmrLoading, state.asmrItemsHasMore)
                ) {
                    onLoadMore()
                }
            }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(
                label = "返回作者列表",
                onClick = onBack,
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    state.selectedAuthor.orEmpty(),
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (state.asmrLoading || state.asmrItems.size < state.asmrTotal) {
                        "已载入 ${state.asmrItems.size} / ${state.asmrTotal}"
                    } else {
                        "${state.asmrItems.size} 个媒体"
                    },
                    color = TextFaint,
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                )
            }
        }
        AsmrFilterTabs(
            selected = state.asmrFilter,
            onFilter = onFilter,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        OutlinedTextField(
            value = state.asmrQuery,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            singleLine = true,
            placeholder = { Text("搜索媒体") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = Color.White) },
            shape = ControlShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedContainerColor = GlassFillSoft,
                unfocusedContainerColor = CanvasSoft.copy(alpha = 0.7f),
                focusedBorderColor = GlassLine,
                unfocusedBorderColor = Line,
                cursorColor = AccentSoft,
            ),
        )
        when {
            state.asmrLoading && state.asmrItems.isEmpty() -> ListLoadingPlaceholder()
            state.asmrError != null && state.asmrItems.isEmpty() -> CenterMessage(state.asmrError)
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = bottomContentPadding,
                ),
            ) {
                items(filtered, key = MediaEntry::id) { entry ->
                    val active = state.nowPlaying?.id == entry.id && player.mediaId == entry.id
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onPlay(entry) }.padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (entry.isAudio) Icons.Outlined.Audiotrack else Icons.Outlined.Movie,
                            contentDescription = null,
                            tint = if (active) Accent else Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(
                                entry.title,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                listOfNotNull(entry.format?.uppercase(), formatBytes(entry.size)).joinToString("  ·  "),
                                color = TextFaint,
                                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            )
                        }
                        when {
                            active && player.isBuffering -> DelayedSpinner(
                                visible = true,
                                modifier = Modifier.size(20.dp),
                            )
                            active && player.playWhenReady -> PlayingEqualizer()
                            else -> Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = if (active) "继续播放" else "播放",
                                tint = if (active) Accent else Color.White,
                            )
                        }
                    }
                    HorizontalDivider(color = Line)
                }
                if (state.asmrLoading) {
                    item(key = "loading-more-media") { LoadingMoreRow() }
                }
            }
        }
    }
}

@Composable
private fun MiniPlayer(
    entry: MediaEntry,
    player: PlayerSnapshot,
    onExpand: () -> Unit,
    onToggle: () -> Unit,
    onClose: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .glassSurface(fill = MiniPlayerGlassFill)
            .pointerInput(Unit) {
                // Keep unhandled touches on the player plane instead of the media list below it.
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Final).changes.forEach { change ->
                            if (!change.isConsumed) change.consume()
                        }
                    }
                }
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(62.dp)
                .then(if (entry.isAudio) Modifier else Modifier.clickable(onClick = onExpand))
                .padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    entry.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    "${entry.author.orEmpty()}  ·  ${formatDuration(player.positionMs)} / ${formatDuration(player.durationMs)}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = TextFaint,
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                )
            }
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (player.isBuffering) {
                    DelayedSpinner(visible = true, modifier = Modifier.size(22.dp))
                } else {
                    GlassIconButton(
                        label = if (player.playWhenReady) "暂停" else "播放",
                        onClick = onToggle,
                    ) {
                        Icon(
                            if (player.playWhenReady) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = null,
                        )
                    }
                }
            }
            GlassIconButton(
                label = "关闭播放器",
                onClick = onClose,
            ) {
                Icon(Icons.Outlined.Close, contentDescription = null)
            }
        }
        FineProgressBar(
            player = player,
            onSeek = onSeek,
            compact = true,
        )
    }
}

@Composable
private fun PlayingEqualizer(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "playing equalizer")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "playing equalizer phase",
    )
    Canvas(
        modifier = modifier
            .size(22.dp)
            .semantics { contentDescription = "正在播放" },
    ) {
        val barWidth = size.width / 7f
        val gap = barWidth
        val phases = floatArrayOf(0f, 0.34f, 0.67f)
        phases.forEachIndexed { index, offset ->
            val wave = ((sin((phase + offset) * 2f * PI).toFloat() + 1f) / 2f)
            val barHeight = size.height * (0.28f + wave * 0.66f)
            val left = gap + index * (barWidth + gap)
            drawRoundRect(
                color = Accent,
                topLeft = Offset(left, (size.height - barHeight) / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}

@Composable
private fun ExpandedAsmrPlayer(
    entry: MediaEntry,
    player: PlayerSnapshot,
    exoPlayer: ExoPlayer,
    muted: Boolean,
    fullscreen: Boolean,
    backgroundPlayback: Boolean,
    onCollapse: () -> Unit,
    onClose: () -> Unit,
    onToggle: () -> Unit,
    onMuted: (Boolean) -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onBackgroundPlayback: (Boolean) -> Unit,
    onFullscreen: (Boolean) -> Unit,
) {
    var seekTarget by remember { mutableStateOf<Long?>(null) }
    var drag by remember { mutableFloatStateOf(0f) }
    var chromeVisible by remember(fullscreen, entry.id) { mutableStateOf(true) }
    val controlsVisible = !fullscreen || chromeVisible

    LaunchedEffect(fullscreen, chromeVisible, player.isPlaying, entry.id) {
        if (fullscreen && chromeVisible && player.isPlaying) {
            delay(5_000)
            chromeVisible = false
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Canvas)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = { if (fullscreen) chromeVisible = !chromeVisible else onToggle() },
            )
            .pointerInput(entry.id, player.durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { drag = 0f; seekTarget = player.positionMs },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        drag += amount
                        if (player.durationMs > 0L) {
                            val delta = (drag / size.width * player.durationMs * 0.45f).toLong()
                                .coerceIn(-90_000L, 90_000L)
                            seekTarget = (player.positionMs + delta).coerceIn(0L, player.durationMs)
                        }
                    },
                    onDragEnd = { seekTarget?.let(onSeek); seekTarget = null },
                    onDragCancel = { seekTarget = null },
                )
            },
    ) {
        PlayerHost(exoPlayer, Modifier.fillMaxSize())
        BufferSpinner(player.isBuffering)

        if (controlsVisible) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .background(Brush.verticalGradient(listOf(Canvas.copy(alpha = 0.8f), Color.Transparent)))
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIconButton(
                    label = "收起播放器",
                    onClick = onCollapse,
                ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null) }
                Column(Modifier.weight(1f)) {
                    Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(entry.author.orEmpty(), maxLines = 1, color = TextFaint, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                }
                if (fullscreen) {
                    GlassIconButton(
                        label = if (backgroundPlayback) "关闭视频后台播放" else "开启视频后台播放",
                        onClick = { onBackgroundPlayback(!backgroundPlayback) },
                        tint = if (backgroundPlayback) Accent else Color.White,
                    ) {
                        Icon(
                            Icons.Outlined.Headphones,
                            contentDescription = null,
                        )
                    }
                    GlassIconButton(
                        label = "退出横屏",
                        onClick = { onFullscreen(false) },
                    ) {
                        Icon(Icons.Outlined.FullscreenExit, contentDescription = null)
                    }
                }
                GlassIconButton(
                    label = "关闭播放器",
                    onClick = onClose,
                ) { Icon(Icons.Outlined.Close, contentDescription = null) }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000))))
                    .navigationBarsPadding()
                    .padding(start = 12.dp, end = 12.dp, top = 34.dp, bottom = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!fullscreen) {
                        GlassIconButton(
                            label = if (player.playWhenReady) "暂停" else "播放",
                            onClick = onToggle,
                        ) {
                            Icon(
                                if (player.playWhenReady) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = null,
                            )
                        }
                    }
                    FineProgressBar(
                        player = player,
                        onSeek = onSeek,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${formatDuration(player.positionMs)} / ${formatDuration(player.durationMs)}",
                        modifier = Modifier.padding(start = 8.dp),
                        color = TextSecondary,
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    )
                }
            }

            Column(
                modifier = if (fullscreen) {
                    Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)
                } else {
                    Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = 12.dp, bottom = 70.dp)
                },
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OverlayIconControl(
                    label = if (muted) "打开声音" else "静音",
                    onClick = { onMuted(!muted) },
                ) {
                    Icon(if (muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = null)
                }
                if (!fullscreen) {
                    OverlayIconControl(
                        label = if (backgroundPlayback) "关闭视频后台播放" else "开启视频后台播放",
                        onClick = { onBackgroundPlayback(!backgroundPlayback) },
                    ) {
                        Icon(
                            Icons.Outlined.Headphones,
                            contentDescription = null,
                            tint = if (backgroundPlayback) Accent else Color.White,
                        )
                    }
                    OverlayIconControl(
                        label = "横屏",
                        onClick = { onFullscreen(true) },
                    ) {
                        Icon(Icons.Outlined.Fullscreen, contentDescription = null)
                    }
                }
            }
        }

        if (fullscreen && controlsVisible) {
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
                    onClick = onToggle,
                ) {
                    Icon(
                        if (player.playWhenReady) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                    )
                }
                OverlayIconControl(label = "快进 10 秒", onClick = { onSeekBy(10_000L) }) {
                    Icon(Icons.Outlined.Forward10, contentDescription = null)
                }
            }
        }

        seekTarget?.let {
            Text(
                "${formatDuration(it)} / ${formatDuration(player.durationMs)}",
                modifier = Modifier
                    .align(Alignment.Center)
                    .glassSurface(fill = GlassFillSoft)
                    .padding(14.dp),
                color = Color.White,
            )
        }
    }
}

@Composable
private fun ListLoadingPlaceholder() {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        repeat(6) { index ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .width(if (index % 2 == 0) 210.dp else 156.dp)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White.copy(alpha = 0.10f)),
                )
                Box(
                    Modifier
                        .width(116.dp)
                        .height(9.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.White.copy(alpha = 0.06f)),
                )
            }
        }
    }
}

@Composable
private fun LoadingMoreRow() {
    Box(
        modifier = Modifier.fillMaxWidth().height(52.dp),
        contentAlignment = Alignment.Center,
    ) {
        DelayedSpinner(visible = true, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun CenterMessage(message: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Refresh, contentDescription = null, tint = TextFaint)
            Text(message, color = TextSecondary)
        }
    }
}
