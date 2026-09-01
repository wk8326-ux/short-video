@file:androidx.annotation.OptIn(
    markerClass = [androidx.media3.common.util.UnstableApi::class],
)

package you.deepfuck.shortvideo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import coil.ImageLoader
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import you.deepfuck.shortvideo.AppUiState
import you.deepfuck.shortvideo.ListPosition
import you.deepfuck.shortvideo.data.MovieItem
import you.deepfuck.shortvideo.media.PlayerSnapshot
import you.deepfuck.shortvideo.shouldLoadMore

@Composable
internal fun MovieScreen(
    state: AppUiState,
    player: PlayerSnapshot,
    exoPlayer: ExoPlayer,
    imageLoader: ImageLoader,
    fullscreen: Boolean,
    listPosition: ListPosition,
    onQuery: (String) -> Unit,
    onSelect: (MovieItem) -> Unit,
    onBack: () -> Unit,
    onPlay: (MovieItem) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onTogglePlayback: () -> Unit,
    onMuted: (Boolean) -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onFullscreen: (Boolean) -> Unit,
    onListPosition: (ListPosition) -> Unit,
) {
    val safePosition = listPosition.clamp(state.movieItems.size)
    val initialListPosition = remember { listPosition }
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = safePosition.index,
        initialFirstVisibleItemScrollOffset = safePosition.offset,
    )
    var listPositionRestored by remember { mutableStateOf(false) }
    var observedQuery by remember { mutableStateOf(state.movieQuery) }
    LaunchedEffect(state.movieItems.size) {
        if (!listPositionRestored && state.movieItems.isNotEmpty()) {
            val restored = initialListPosition.clamp(state.movieItems.size)
            gridState.scrollToItem(restored.index, restored.offset)
            listPositionRestored = true
        }
    }
    LaunchedEffect(state.movieQuery) {
        if (state.movieQuery != observedQuery) {
            observedQuery = state.movieQuery
            gridState.scrollToItem(0)
            onListPosition(ListPosition())
        }
    }

    val movie = state.selectedMovie
    if (movie == null) {
        TrackMovieGrid(
            gridState = gridState,
            itemCount = state.movieItems.size,
            loading = state.movieLoading,
            hasMore = state.movieNextOffset != null,
            enabled = listPositionRestored,
            onLoadMore = onLoadMore,
            onListPosition = onListPosition,
        )
        MovieCatalog(
            state = state,
            imageLoader = imageLoader,
            gridState = gridState,
            onQuery = onQuery,
            onSelect = onSelect,
            onRetry = onRetry,
        )
    } else {
        MovieDetail(
            movie = movie,
            detailLoading = state.movieDetailLoading,
            error = state.movieError,
            player = player,
            exoPlayer = exoPlayer,
            imageLoader = imageLoader,
            muted = state.muted,
            fullscreen = fullscreen,
            playbackStarted = state.nowPlaying?.id == movie.videoId || player.mediaId == movie.videoId,
            onBack = onBack,
            onPlay = { onPlay(movie) },
            onTogglePlayback = onTogglePlayback,
            onMuted = onMuted,
            onSeek = onSeek,
            onSeekBy = onSeekBy,
            onFullscreen = onFullscreen,
        )
    }
}

@Composable
private fun TrackMovieGrid(
    gridState: LazyGridState,
    itemCount: Int,
    loading: Boolean,
    hasMore: Boolean,
    enabled: Boolean,
    onLoadMore: () -> Unit,
    onListPosition: (ListPosition) -> Unit,
) {
    LaunchedEffect(gridState, itemCount, enabled) {
        if (!enabled || itemCount <= 0) return@LaunchedEffect
        snapshotFlow {
            ListPosition(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
        }
            .distinctUntilChanged()
            .collectLatest { position ->
                delay(180)
                onListPosition(position)
            }
    }
    LaunchedEffect(gridState, itemCount, loading, hasMore, enabled) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (shouldLoadMore(lastVisible, itemCount, loading, hasMore, threshold = 8)) onLoadMore()
            }
    }
}

@Composable
private fun MovieCatalog(
    state: AppUiState,
    imageLoader: ImageLoader,
    gridState: LazyGridState,
    onQuery: (String) -> Unit,
    onSelect: (MovieItem) -> Unit,
    onRetry: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    Column(
        Modifier
            .fillMaxSize()
            .background(Canvas)
            .statusBarsPadding()
            .padding(top = 62.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.width(62.dp)) {
                Text("片库", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Text(
                    state.movieTotal.toString(),
                    color = TextFaint,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            OutlinedTextField(
                value = state.movieQuery,
                onValueChange = onQuery,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                singleLine = true,
                placeholder = { Text("搜索片名") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = if (state.movieQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = { onQuery("") }) {
                            Icon(Icons.Outlined.Close, contentDescription = "清除搜索")
                        }
                    }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                shape = ControlShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = CanvasSoft,
                    unfocusedContainerColor = CanvasSoft,
                    focusedBorderColor = GlassLine,
                    unfocusedBorderColor = Line,
                    cursorColor = AccentSoft,
                ),
            )
        }

        when {
            state.movieItems.isEmpty() && state.movieLoading -> MovieSkeletonGrid()
            state.movieItems.isEmpty() && state.movieError != null -> MovieCatalogState(
                icon = Icons.Outlined.Refresh,
                title = state.movieError,
                action = "重试",
                onAction = onRetry,
            )
            state.movieItems.isEmpty() -> MovieCatalogState(
                icon = Icons.Outlined.Movie,
                title = if (state.movieQuery.isBlank()) "还没有电影索引" else "没有匹配的电影",
                action = if (state.movieQuery.isBlank()) null else "清除搜索",
                onAction = { onQuery("") },
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(148.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 10.dp, top = 8.dp, end = 10.dp, bottom = 34.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(state.movieItems, key = MovieItem::id) { movie ->
                    MoviePoster(movie, imageLoader, onClick = { onSelect(movie) })
                }
                if (state.movieLoading) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(22.dp), color = TextSecondary, strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoviePoster(movie: MovieItem, imageLoader: ImageLoader, onClick: () -> Unit) {
    val watchedPercent = (movie.resumeFraction * 100).toInt()
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = buildString {
                    append(movie.title)
                    movie.year?.let { append("，$it 年") }
                    if (watchedPercent > 0) append("，已观看 $watchedPercent%")
                }
            },
        color = Color.Transparent,
        contentColor = TextPrimary,
        shape = RoundedCornerShape(10.dp),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Raised)
                    .border(1.dp, GlassLine, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Movie,
                    contentDescription = null,
                    tint = TextFaint,
                    modifier = Modifier.size(28.dp),
                )
                (movie.wallUrl ?: movie.posterUrl)?.let { url ->
                    AsyncImage(
                        model = url,
                        imageLoader = imageLoader,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                if (movie.resumeFraction > 0f) {
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(movie.resumeFraction)
                            .height(2.dp)
                            .background(Accent),
                    )
                }
            }
            Spacer(Modifier.height(7.dp))
            Text(
                movie.title,
                color = TextPrimary,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                movie.year?.toString() ?: "年份未知",
                color = TextFaint,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun MovieSkeletonGrid() {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(148.dp),
        modifier = Modifier.fillMaxSize(),
        userScrollEnabled = false,
        contentPadding = PaddingValues(start = 10.dp, top = 8.dp, end = 10.dp, bottom = 34.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        items(9) {
            Column {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(Raised))
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth(0.78f).height(12.dp).clip(RoundedCornerShape(3.dp)).background(RaisedStrong))
                Spacer(Modifier.height(6.dp))
                Box(Modifier.width(42.dp).height(9.dp).clip(RoundedCornerShape(3.dp)).background(Raised))
            }
        }
    }
}

@Composable
private fun MovieCatalogState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    action: String?,
    onAction: () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, contentDescription = null, tint = TextFaint, modifier = Modifier.size(30.dp))
            Text(title, color = TextSecondary)
            action?.let {
                TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(it) }
            }
        }
    }
}

@Composable
private fun MovieDetail(
    movie: MovieItem,
    detailLoading: Boolean,
    error: String?,
    player: PlayerSnapshot,
    exoPlayer: ExoPlayer,
    imageLoader: ImageLoader,
    muted: Boolean,
    fullscreen: Boolean,
    playbackStarted: Boolean,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onTogglePlayback: () -> Unit,
    onMuted: (Boolean) -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onFullscreen: (Boolean) -> Unit,
) {
    if (fullscreen && playbackStarted) {
        MoviePlayer(
            player = player,
            exoPlayer = exoPlayer,
            muted = muted,
            fullscreen = true,
            onTogglePlayback = onTogglePlayback,
            onMuted = onMuted,
            onSeek = onSeek,
            onSeekBy = onSeekBy,
            onFullscreen = onFullscreen,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    Box(Modifier.fillMaxSize().background(Canvas)) {
        val detailBackgroundUrl = movie.backdropUrl ?: movie.wallUrl ?: movie.posterUrl
        detailBackgroundUrl?.let { url ->
            AsyncImage(
                model = url,
                imageLoader = imageLoader,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.24f
                        scaleY = 1.24f
                        alpha = 0.72f
                    }
                    .blur(34.dp),
                contentScale = ContentScale.Crop,
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.42f),
                        0.36f to Canvas.copy(alpha = 0.86f),
                        0.72f to Canvas,
                        1f to Canvas,
                    ),
                ),
        )
    LazyColumn(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        contentPadding = PaddingValues(bottom = 40.dp),
    ) {
        item {
            Box(Modifier.fillMaxWidth()) {
                if (playbackStarted) {
                    MoviePlayer(
                        player = player,
                        exoPlayer = exoPlayer,
                        muted = muted,
                        fullscreen = false,
                        onTogglePlayback = onTogglePlayback,
                        onMuted = onMuted,
                        onSeek = onSeek,
                        onSeekBy = onSeekBy,
                        onFullscreen = onFullscreen,
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    )
                } else {
                    MovieHero(movie, imageLoader)
                }
                GlassIconButton(
                    label = "返回片库",
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            movie.title,
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        val meta = movieMeta(movie)
                        if (meta.isNotEmpty()) {
                            Spacer(Modifier.height(5.dp))
                            Text(meta, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (detailLoading) {
                        CircularProgressIndicator(Modifier.size(19.dp), color = TextFaint, strokeWidth = 2.dp)
                    }
                }
                if (!playbackStarted) {
                    Spacer(Modifier.height(18.dp))
                    Button(
                        onClick = onPlay,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = ControlShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (movie.resumePositionMs > 0L) {
                                "继续观看  ${formatDuration(movie.resumePositionMs)}"
                            } else {
                                "播放"
                            },
                        )
                    }
                }
                error?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(24.dp))
                Text("简介", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    movie.overview.ifBlank { "暂无简介，可以直接从影片开始观看。" },
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyLarge,
                )
                movie.originalTitle?.takeIf { it != movie.title }?.let {
                    Spacer(Modifier.height(20.dp))
                    Text("原名", color = TextFaint, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
                val hasExtendedMetadata = movie.releaseDate != null || movie.studio != null ||
                    movie.genres.isNotEmpty() || movie.performers.isNotEmpty() ||
                    movie.metadataProvider != null
                if (hasExtendedMetadata) {
                    Spacer(Modifier.height(24.dp))
                    Text("资料", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    movie.releaseDate?.let { MovieMetadataLine("发行", it) }
                    movie.studio?.let { MovieMetadataLine("制作", it) }
                    movie.genres.takeIf { it.isNotEmpty() }?.let {
                        MovieMetadataLine("类型", it.joinToString(" · "))
                    }
                    movie.performers.takeIf { it.isNotEmpty() }?.let {
                        MovieMetadataLine("演员", it.joinToString(" · "))
                    }
                    movie.metadataProvider?.let { provider ->
                        MovieMetadataLine("资料来源", provider.uppercase())
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun MovieMetadataLine(label: String, value: String) {
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            modifier = Modifier.width(64.dp),
            color = TextFaint,
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            color = TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun MovieHero(movie: MovieItem, imageLoader: ImageLoader) {
    Box(
        modifier = Modifier.fillMaxWidth().background(CanvasSoft.copy(alpha = 0.62f)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .aspectRatio(16f / 9f)
                .padding(vertical = 18.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Raised)
                .border(1.dp, GlassLine, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Movie, contentDescription = null, tint = TextFaint, modifier = Modifier.size(30.dp))
            movie.posterUrl?.let {
                AsyncImage(
                    model = it,
                    imageLoader = imageLoader,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
}

@Composable
private fun MoviePlayer(
    player: PlayerSnapshot,
    exoPlayer: ExoPlayer,
    muted: Boolean,
    fullscreen: Boolean,
    onTogglePlayback: () -> Unit,
    onMuted: (Boolean) -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onFullscreen: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var chromeVisible by remember(fullscreen) { mutableStateOf(true) }
    var dragPixels by remember { mutableFloatStateOf(0f) }
    var seekPreviewMs by remember { mutableStateOf<Long?>(null) }
    var seekBaseMs by remember { mutableStateOf(0L) }
    val latestPositionMs = rememberUpdatedState(player.positionMs)

    LaunchedEffect(fullscreen, chromeVisible, player.isPlaying, player.mediaId) {
        if (fullscreen && chromeVisible && player.isPlaying) {
            delay(5_000)
            chromeVisible = false
        }
    }

    val gestureModifier = if (fullscreen) {
        Modifier.pointerInput(player.durationMs, player.mediaId) {
            detectHorizontalDragGestures(
                onDragStart = {
                    dragPixels = 0f
                    seekBaseMs = latestPositionMs.value
                    seekPreviewMs = seekBaseMs
                    chromeVisible = true
                },
                onHorizontalDrag = { change, amount ->
                    change.consume()
                    dragPixels += amount
                    if (player.durationMs > 0L) {
                        seekPreviewMs = calculateSeekTarget(
                            basePositionMs = seekBaseMs,
                            accumulatedDragPx = dragPixels,
                            widthPx = size.width,
                            durationMs = player.durationMs,
                            maxDeltaMs = 120_000L,
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
    } else {
        Modifier
    }

    val surfaceTapModifier = if (fullscreen) {
        Modifier.pointerInput(fullscreen) {
            detectTapGestures { chromeVisible = !chromeVisible }
        }
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .background(Color.Black)
            .then(gestureModifier)
            .then(surfaceTapModifier),
    ) {
        PlayerHost(exoPlayer, Modifier.fillMaxSize())
        BufferSpinner(player.isBuffering)

        val controlsVisible = !fullscreen || chromeVisible
        if (controlsVisible) {
            if (!player.playWhenReady || fullscreen) {
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(if (fullscreen) 34.dp else 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (fullscreen) {
                        OverlayIconControl(label = "后退 10 秒", onClick = { onSeekBy(-10_000L) }) {
                            Icon(Icons.Outlined.Replay10, contentDescription = null)
                        }
                    }
                    OverlayIconControl(
                        label = if (player.playWhenReady) "暂停" else "播放",
                        size = 64,
                        onClick = onTogglePlayback,
                    ) {
                        Icon(
                            if (player.playWhenReady) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    if (fullscreen) {
                        OverlayIconControl(label = "快进 10 秒", onClick = { onSeekBy(10_000L) }) {
                            Icon(Icons.Outlined.Forward10, contentDescription = null)
                        }
                    }
                }
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.52f))
                    .navigationBarsPadding()
                    .padding(start = 8.dp, top = 2.dp, end = 8.dp, bottom = 4.dp),
            ) {
                FineProgressBar(player, onSeek, compact = true)
                Row(
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!fullscreen) {
                        IconButton(
                            onClick = onTogglePlayback,
                            modifier = Modifier.size(48.dp),
                            colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
                        ) {
                            Icon(
                                if (player.playWhenReady) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (player.playWhenReady) "暂停" else "播放",
                            )
                        }
                    }
                    Text(
                        "${formatDuration(player.positionMs)} / ${formatDuration(player.durationMs)}",
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = { onMuted(!muted) },
                        modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
                    ) {
                        Icon(
                            if (muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                            contentDescription = if (muted) "打开声音" else "静音",
                        )
                    }
                    IconButton(
                        onClick = { onFullscreen(!fullscreen) },
                        modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
                    ) {
                        Icon(
                            if (fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                            contentDescription = if (fullscreen) "退出横屏" else "横屏",
                        )
                    }
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
                Text("${formatDuration(target)} / ${formatDuration(player.durationMs)}", color = Color.White)
            }
        }
    }
}

private fun movieMeta(movie: MovieItem): String = buildList {
    movie.year?.let { add(it.toString()) }
    movie.runtimeMinutes?.takeIf { it > 0 }?.let { add("$it 分钟") }
    movie.rating?.takeIf { it > 0.0 }?.let { add("评分 ${String.format(java.util.Locale.ROOT, "%.1f", it)}") }
}.joinToString("  ·  ")
