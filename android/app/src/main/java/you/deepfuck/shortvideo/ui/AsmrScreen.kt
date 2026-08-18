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
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.PI
import kotlin.math.sin
import you.deepfuck.shortvideo.AppUiState
import you.deepfuck.shortvideo.shouldLoadMore
import you.deepfuck.shortvideo.data.AsmrAuthor
import you.deepfuck.shortvideo.data.MediaEntry
import you.deepfuck.shortvideo.media.PlayerSnapshot

@Composable
internal fun AsmrScreen(
    state: AppUiState,
    player: PlayerSnapshot,
    exoPlayer: ExoPlayer,
    muted: Boolean,
    fullscreen: Boolean,
    onAuthor: (String) -> Unit,
    onBackAuthor: () -> Unit,
    onLoadMoreItems: () -> Unit,
    onFilter: (String) -> Unit,
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
) {
    val screenStates = rememberSaveableStateHolder()
    Box(Modifier.fillMaxSize().background(Canvas)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Spacer(Modifier.height(52.dp))
            val selectedAuthor = state.selectedAuthor
            if (selectedAuthor == null) {
                screenStates.SaveableStateProvider("asmr-authors") {
                    AuthorLibrary(
                        authors = state.asmrAuthors,
                        total = state.asmrAuthorsTotal,
                        loading = state.asmrLoading,
                        error = state.asmrError,
                        listState = rememberLazyListState(),
                        onAuthor = onAuthor,
                    )
                }
            } else {
                screenStates.SaveableStateProvider("asmr-author:$selectedAuthor") {
                    AuthorMedia(
                        state = state,
                        player = player,
                        listState = rememberLazyListState(),
                        onBack = onBackAuthor,
                        onLoadMore = onLoadMoreItems,
                        onFilter = onFilter,
                        onQuery = onQuery,
                        onPlay = onPlay,
                    )
                }
            }
        }

        if (state.nowPlaying != null && state.expandedMedia == null) {
            MiniPlayer(
                entry = state.nowPlaying,
                player = player,
                onExpand = onExpand,
                onToggle = onToggle,
                onClose = onClose,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
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
    listState: LazyListState,
    onAuthor: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(authors, query) {
        if (query.isBlank()) authors else authors.filter { it.name.contains(query, ignoreCase = true) }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 18.dp)) {
            Text("ASMR 媒体库", fontWeight = FontWeight.SemiBold, style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(5.dp))
            Text(
                if (total > authors.size) "已载入 ${authors.size} / $total 位作者" else "${authors.size} 位作者",
                color = TextFaint,
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("搜索作者") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = Color.White) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = RoundedCornerShape(6.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = Color.White.copy(alpha = 0.46f),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.18f),
                    cursorColor = Color.White,
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
                    bottom = 94.dp,
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
                        }
                    }
                    HorizontalDivider(color = Line)
                }
            }
        }
    }
}

@Composable
private fun AuthorMedia(
    state: AppUiState,
    player: PlayerSnapshot,
    listState: LazyListState,
    onBack: () -> Unit,
    onLoadMore: () -> Unit,
    onFilter: (String) -> Unit,
    onQuery: (String) -> Unit,
    onPlay: (MediaEntry) -> Unit,
) {
    val filtered = remember(state.asmrItems, state.asmrFilter, state.asmrQuery) {
        state.asmrItems.filter { item ->
            val kindMatches = state.asmrFilter == "all" ||
                state.asmrFilter == "video" && !item.isAudio ||
                state.asmrFilter == "audio" && item.isAudio
            kindMatches && (state.asmrQuery.isBlank() || item.title.contains(state.asmrQuery, ignoreCase = true))
        }
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
            IconButton(
                onClick = onBack,
                colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回作者列表")
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
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            listOf("all" to "全部", "video" to "视频", "audio" to "音频").forEach { (key, label) ->
                val selected = state.asmrFilter == key
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    TextButton(
                        onClick = { onFilter(key) },
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = Color.White.copy(alpha = if (selected) 1f else 0.62f),
                        ),
                    ) { Text(label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) }
                    Box(
                        Modifier
                            .width(24.dp)
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(if (selected) Accent else Color.Transparent),
                    )
                }
            }
        }
        OutlinedTextField(
            value = state.asmrQuery,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            singleLine = true,
            placeholder = { Text("搜索媒体") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = Color.White) },
            shape = RoundedCornerShape(6.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedBorderColor = Color.White.copy(alpha = 0.46f),
                unfocusedBorderColor = Color.White.copy(alpha = 0.18f),
                cursorColor = Color.White,
            ),
        )
        when {
            state.asmrLoading && state.asmrItems.isEmpty() -> ListLoadingPlaceholder()
            state.asmrError != null && state.asmrItems.isEmpty() -> CenterMessage(state.asmrError)
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 94.dp),
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
                            Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
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
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.78f))
            .navigationBarsPadding()
            .height(72.dp)
            .then(if (entry.isAudio) Modifier else Modifier.clickable(onClick = onExpand))
            .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(5.dp))
            Text(
                "${entry.author.orEmpty()}  ·  ${formatDuration(player.positionMs)}",
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
                IconButton(
                    onClick = onToggle,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
                ) {
                    Icon(
                        if (player.playWhenReady) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (player.playWhenReady) "暂停" else "播放",
                    )
                }
            }
        }
        IconButton(
            onClick = onClose,
            colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
        ) { Icon(Icons.Outlined.Close, contentDescription = "关闭播放器") }
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
            .background(Color(0xFF070809))
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
                    .background(Brush.verticalGradient(listOf(Color(0xCC070809), Color.Transparent)))
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onCollapse,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
                ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "收起播放器") }
                Column(Modifier.weight(1f)) {
                    Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(entry.author.orEmpty(), maxLines = 1, color = TextFaint, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                }
                IconButton(
                    onClick = { onBackgroundPlayback(!backgroundPlayback) },
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = if (backgroundPlayback) Accent else Color.White,
                    ),
                ) {
                    Icon(
                        Icons.Outlined.Headphones,
                        contentDescription = if (backgroundPlayback) "关闭视频后台播放" else "开启视频后台播放",
                    )
                }
                IconButton(
                    onClick = { onFullscreen(!fullscreen) },
                    colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
                ) {
                    Icon(if (fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen, contentDescription = if (fullscreen) "退出横屏" else "横屏")
                }
                IconButton(
                    onClick = onClose,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
                ) { Icon(Icons.Outlined.Close, contentDescription = "关闭播放器") }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD8000000))))
                    .navigationBarsPadding()
                    .padding(start = 12.dp, end = 12.dp, top = 34.dp, bottom = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!fullscreen) {
                        IconButton(
                            onClick = onToggle,
                            modifier = Modifier.size(48.dp),
                            colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
                        ) {
                            Icon(
                                if (player.playWhenReady) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (player.playWhenReady) "暂停" else "播放",
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
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OverlayIconControl(
                    label = if (muted) "打开声音" else "静音",
                    onClick = { onMuted(!muted) },
                ) {
                    Icon(if (muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = null)
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
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0x72000000))
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
