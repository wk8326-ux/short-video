package you.deepfuck.shortvideo.ui

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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import you.deepfuck.shortvideo.AppUiState
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
    onFilter: (String) -> Unit,
    onQuery: (String) -> Unit,
    onPlay: (MediaEntry) -> Unit,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onClose: () -> Unit,
    onToggle: () -> Unit,
    onMuted: (Boolean) -> Unit,
    onSeek: (Long) -> Unit,
    onFullscreen: (Boolean) -> Unit,
) {
    val screenStates = rememberSaveableStateHolder()
    Box(Modifier.fillMaxSize().background(Canvas)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Spacer(Modifier.height(52.dp))
            if (state.selectedAuthor == null) {
                screenStates.SaveableStateProvider("asmr-authors") {
                    AuthorLibrary(
                        authors = state.asmrAuthors,
                        loading = state.asmrLoading,
                        error = state.asmrError,
                        onAuthor = onAuthor,
                    )
                }
            } else {
                screenStates.SaveableStateProvider("asmr-author:${state.selectedAuthor}") {
                    AuthorMedia(
                        state = state,
                        onBack = onBackAuthor,
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
                onCollapse = onCollapse,
                onClose = onClose,
                onToggle = onToggle,
                onMuted = onMuted,
                onSeek = onSeek,
                onFullscreen = onFullscreen,
            )
        }
    }
}

@Composable
private fun AuthorLibrary(
    authors: List<AsmrAuthor>,
    loading: Boolean,
    error: String?,
    onAuthor: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val filtered = remember(authors, query) {
        if (query.isBlank()) authors else authors.filter { it.name.contains(query, ignoreCase = true) }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 18.dp)) {
            Text("ASMR 媒体库", fontWeight = FontWeight.SemiBold, style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(5.dp))
            Text("${authors.size} 位作者", color = TextFaint, style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("搜索作者") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = RoundedCornerShape(6.dp),
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
                if (loading) {
                    item(key = "loading-more-authors") { LoadingMoreRow() }
                }
            }
        }
    }
}

@Composable
private fun AuthorMedia(
    state: AppUiState,
    onBack: () -> Unit,
    onFilter: (String) -> Unit,
    onQuery: (String) -> Unit,
    onPlay: (MediaEntry) -> Unit,
) {
    val listState = rememberLazyListState()
    val filtered = remember(state.asmrItems, state.asmrFilter, state.asmrQuery) {
        state.asmrItems.filter { item ->
            val kindMatches = state.asmrFilter == "all" ||
                state.asmrFilter == "video" && !item.isAudio ||
                state.asmrFilter == "audio" && item.isAudio
            kindMatches && (state.asmrQuery.isBlank() || item.title.contains(state.asmrQuery, ignoreCase = true))
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回作者列表")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    state.selectedAuthor.orEmpty(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
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
            modifier = Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x0FFFFFFF)).padding(3.dp),
        ) {
            listOf("all" to "全部", "video" to "视频", "audio" to "音频").forEach { (key, label) ->
                TextButton(
                    onClick = { onFilter(key) },
                    modifier = Modifier.weight(1f).height(40.dp),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (state.asmrFilter == key) TextPrimary else TextFaint,
                    ),
                ) { Text(label, fontWeight = if (state.asmrFilter == key) FontWeight.SemiBold else FontWeight.Normal) }
            }
        }
        OutlinedTextField(
            value = state.asmrQuery,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            singleLine = true,
            placeholder = { Text("搜索媒体") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            shape = RoundedCornerShape(6.dp),
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
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onPlay(entry) }.padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (entry.isAudio) Icons.Outlined.Audiotrack else Icons.Outlined.Movie,
                            contentDescription = null,
                            tint = if (state.nowPlaying?.id == entry.id) Accent else TextFaint,
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
                        Icon(Icons.Filled.PlayArrow, contentDescription = "播放", tint = TextSecondary)
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
            .background(Color(0xF2111214))
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
                IconButton(onClick = onToggle) {
                    Icon(if (player.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (player.isPlaying) "暂停" else "播放")
                }
            }
        }
        IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "关闭播放器") }
    }
}

@Composable
private fun ExpandedAsmrPlayer(
    entry: MediaEntry,
    player: PlayerSnapshot,
    exoPlayer: ExoPlayer,
    muted: Boolean,
    fullscreen: Boolean,
    onCollapse: () -> Unit,
    onClose: () -> Unit,
    onToggle: () -> Unit,
    onMuted: (Boolean) -> Unit,
    onSeek: (Long) -> Unit,
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
        if (entry.isAudio) {
            Column(
                modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(78.dp).background(Color(0x12FFFFFF), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Headphones, contentDescription = null, modifier = Modifier.size(34.dp), tint = TextSecondary)
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    entry.title,
                    modifier = Modifier.padding(horizontal = 32.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(entry.author.orEmpty(), color = TextFaint)
            }
        } else {
            PlayerHost(exoPlayer, Modifier.fillMaxSize())
        }
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
                IconButton(onClick = onCollapse) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "收起播放器") }
                Column(Modifier.weight(1f)) {
                    Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(entry.author.orEmpty(), maxLines = 1, color = TextFaint, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                }
                if (!entry.isAudio) {
                    IconButton(onClick = { onFullscreen(!fullscreen) }) {
                        Icon(if (fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen, contentDescription = if (fullscreen) "退出横屏" else "横屏")
                    }
                }
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "关闭播放器") }
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
                    IconButton(onClick = onToggle, modifier = Modifier.size(48.dp)) {
                        Icon(if (player.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (player.isPlaying) "暂停" else "播放")
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
                if (fullscreen) {
                    OverlayIconControl(label = "退出横屏", onClick = { onFullscreen(false) }) {
                        Icon(Icons.Outlined.FullscreenExit, contentDescription = null)
                    }
                }
            }
        }

        if (fullscreen && controlsVisible) {
            OverlayIconControl(
                label = if (player.isPlaying) "暂停" else "播放",
                size = 64,
                modifier = Modifier.align(Alignment.Center),
                onClick = onToggle,
            ) {
                Icon(
                    if (player.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                )
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
        CircularProgressIndicator(Modifier.size(18.dp), color = TextSecondary, strokeWidth = 2.dp)
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
