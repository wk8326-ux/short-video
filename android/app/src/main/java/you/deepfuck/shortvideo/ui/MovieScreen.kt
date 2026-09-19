@file:androidx.annotation.OptIn(
    markerClass = [androidx.media3.common.util.UnstableApi::class],
)

package you.deepfuck.shortvideo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
import you.deepfuck.shortvideo.AppUiState
import you.deepfuck.shortvideo.ListPosition
import you.deepfuck.shortvideo.data.MovieItem
import you.deepfuck.shortvideo.data.MovieGroup
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
    onSort: (String) -> Unit,
    onSelect: (MovieItem) -> Unit,
    onBack: () -> Unit,
    onPlay: (MovieItem) -> Unit,
    onLoadMore: () -> Unit,
    onLoadMoreGroup: (String) -> Unit,
    onRetry: () -> Unit,
    onTogglePlayback: () -> Unit,
    onMuted: (Boolean) -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onFullscreen: (Boolean) -> Unit,
    onListPosition: (ListPosition) -> Unit,
) {
    // The wall is a list of library sections, so position memory tracks section
    // indexes rather than individual posters.
    val safePosition = listPosition.clamp(state.movieGroups.size)
    val initialListPosition = remember { listPosition }
    val wallState = rememberLazyListState(
        initialFirstVisibleItemIndex = safePosition.index,
        initialFirstVisibleItemScrollOffset = safePosition.offset,
    )
    val searchGridState = rememberLazyGridState()
    var listPositionRestored by remember { mutableStateOf(false) }
    var observedQuery by remember { mutableStateOf(state.movieQuery) }
    val searching = state.movieQuery.isNotBlank()
    LaunchedEffect(state.movieGroups.size) {
        if (!listPositionRestored && state.movieGroups.isNotEmpty()) {
            val restored = initialListPosition.clamp(state.movieGroups.size)
            wallState.scrollToItem(restored.index, restored.offset)
            listPositionRestored = true
        }
    }
    LaunchedEffect(state.movieQuery) {
        if (state.movieQuery != observedQuery) {
            observedQuery = state.movieQuery
            wallState.scrollToItem(0)
            onListPosition(ListPosition())
        }
    }

    if (!searching) {
        TrackMovieWall(
            listState = wallState,
            itemCount = state.movieGroups.size,
            enabled = listPositionRestored,
            onListPosition = onListPosition,
        )
    } else {
        TrackMovieGrid(
            gridState = searchGridState,
            itemCount = state.movieItems.size,
            loading = state.movieLoading,
            hasMore = state.movieNextOffset != null,
            onLoadMore = onLoadMore,
        )
    }

    val movie = state.selectedMovie
    if (movie == null) {
        MovieCatalog(
            state = state,
            imageLoader = imageLoader,
            wallState = wallState,
            searchGridState = searchGridState,
            onQuery = onQuery,
            onSort = onSort,
            onSelect = onSelect,
            onLoadMoreGroup = onLoadMoreGroup,
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

/** Remembers which library section the user scrolled away from. */
@Composable
private fun TrackMovieWall(
    listState: LazyListState,
    itemCount: Int,
    enabled: Boolean,
    onListPosition: (ListPosition) -> Unit,
) {
    LaunchedEffect(listState, itemCount, enabled) {
        if (!enabled || itemCount <= 0) return@LaunchedEffect
        snapshotFlow {
            ListPosition(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }
            .distinctUntilChanged()
            .collectLatest { position ->
                delay(180)
                onListPosition(position)
            }
    }
}

/** Pages the flat search result grid; the wall pages per section instead. */
@Composable
private fun TrackMovieGrid(
    gridState: LazyGridState,
    itemCount: Int,
    loading: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
) {
    LaunchedEffect(gridState, itemCount, loading, hasMore) {
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
    wallState: LazyListState,
    searchGridState: LazyGridState,
    onQuery: (String) -> Unit,
    onSort: (String) -> Unit,
    onSelect: (MovieItem) -> Unit,
    onLoadMoreGroup: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var sortMenuOpen by remember { mutableStateOf(false) }
    var jumpMenuOpen by remember { mutableStateOf(false) }
    val searching = state.movieQuery.isNotBlank()
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
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
            Box {
                GlassIconButton(
                    label = "排序",
                    onClick = { sortMenuOpen = true },
                ) {
                    Icon(Icons.Outlined.Sort, contentDescription = null)
                }
                DropdownMenu(
                    expanded = sortMenuOpen,
                    onDismissRequest = { sortMenuOpen = false },
                    containerColor = RaisedStrong,
                    shape = GlassPanelShape,
                    shadowElevation = 0.dp,
                ) {
                    MovieSort.entries.forEach { sort ->
                        DropdownMenuItem(
                            text = { Text(sort.label) },
                            trailingIcon = {
                                if (state.movieSort == sort.value) {
                                    Icon(Icons.Outlined.Check, contentDescription = null)
                                }
                            },
                            onClick = {
                                sortMenuOpen = false
                                onSort(sort.value)
                            },
                        )
                    }
                }
            }
            // Quick jump between libraries: the wall is grouped by media source,
            // so the menu mirrors that order.
            if (state.movieGroups.size > 1) {
                Box {
                    GlassIconButton(
                        label = "媒体库导航",
                        onClick = { jumpMenuOpen = true },
                    ) {
                        Icon(Icons.Outlined.Menu, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = jumpMenuOpen,
                        onDismissRequest = { jumpMenuOpen = false },
                        containerColor = RaisedStrong,
                        shape = GlassPanelShape,
                        shadowElevation = 0.dp,
                    ) {
                        state.movieGroups.forEachIndexed { index, group ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        group.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                trailingIcon = {
                                    if (wallState.firstVisibleItemIndex == index) {
                                        Icon(Icons.Outlined.Check, contentDescription = null)
                                    }
                                },
                                onClick = {
                                    jumpMenuOpen = false
                                    scope.launch { wallState.animateScrollToItem(index) }
                                },
                            )
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MovieStatChip("片库", state.movieTotal.toString())
            if (searching) {
                MovieStatChip("已载入", state.movieItems.size.toString())
            } else {
                MovieStatChip("媒体源", state.movieGroups.size.toString())
            }
            MovieStatChip("排序", MovieSort.labelOf(state.movieSort))
        }

        when {
            searching -> {
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
                        title = "没有匹配的电影",
                        action = "清除搜索",
                        onAction = { onQuery("") },
                    )
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(148.dp),
                        state = searchGridState,
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
            state.movieGroups.isEmpty() && state.movieError != null -> MovieCatalogState(
                icon = Icons.Outlined.Refresh,
                title = state.movieError,
                action = "重试",
                onAction = onRetry,
            )
            state.movieGroups.isEmpty() && state.movieLoading -> MovieSkeletonGrid()
            state.movieGroups.isEmpty() -> MovieCatalogState(
                icon = Icons.Outlined.Movie,
                title = "还没有电影索引",
                action = null,
                onAction = {},
            )
            else -> LazyColumn(
                state = wallState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 34.dp),
            ) {
                state.movieGroups.forEach { group ->
                    item(key = group.sourceId) {
                        MovieGroupSection(
                            group = group,
                            imageLoader = imageLoader,
                            loadingMore = group.sourceId in state.movieGroupLoading,
                            error = state.movieGroupErrors[group.sourceId],
                            onSelect = onSelect,
                            onLoadMore = { onLoadMoreGroup(group.sourceId) },
                        )
                    }
                }
                if (state.movieGroupsLoading) {
                    item(key = "groups-loading") {
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
private fun MovieGroupSection(
    group: MovieGroup,
    imageLoader: ImageLoader,
    loadingMore: Boolean,
    error: String?,
    onSelect: (MovieItem) -> Unit,
    onLoadMore: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(15.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Accent),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                group.name,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${group.items.size}/${group.total}",
                color = TextFaint,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
        // Two columns are laid out by hand: a nested lazy grid inside this lazy
        // column would fight the parent for scroll ownership.
        group.items.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { movie ->
                    Box(Modifier.weight(1f)) {
                        MoviePoster(movie, imageLoader, onClick = { onSelect(movie) })
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
        }
        if (group.hasMore) {
            val remaining = (group.total - group.items.size).coerceAtLeast(0)
            Surface(
                onClick = onLoadMore,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .heightIn(min = 48.dp),
                color = Raised,
                contentColor = TextSecondary,
                shape = RoundedCornerShape(10.dp),
            ) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (loadingMore) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = TextSecondary, strokeWidth = 2.dp)
                    } else {
                        Text(
                            if (remaining > 0) "加载更多 · 还有 $remaining 部" else "加载更多",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        // A failed section stays visible with its own message: the wall itself
        // is fine, only this library's next page failed.
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            )
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
                    .background(Raised),
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
        // Use the high-resolution landscape cover as the detail artwork. The
        // small thumb/backdrop image is not suitable for full-screen scaling.
        val detailBackgroundUrl = movie.wallUrl ?: movie.posterUrl ?: movie.backdropUrl
        detailBackgroundUrl?.let { url ->
            AsyncImage(
                model = url,
                imageLoader = imageLoader,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.16f
                        scaleY = 1.16f
                    },
                contentScale = ContentScale.Crop,
                alignment = Alignment.CenterEnd,
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.32f to Color.Black.copy(alpha = 0.04f),
                        0.66f to Canvas.copy(alpha = 0.78f),
                        0.94f to Canvas.copy(alpha = 0.98f),
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
                        MovieMetadataLine("资料来源", metadataProviderLabel(provider))
                    }
                }
            }
        }
    }
    }
}

/** Raw provider ids come straight from the API, so keep the UI labels here. */
private fun metadataProviderLabel(provider: String): String = when (provider.lowercase()) {
    "tmdb" -> "TMDB"
    "shared" -> "共享元数据"
    else -> provider.uppercase()
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
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Movie, contentDescription = null, tint = TextFaint, modifier = Modifier.size(30.dp))
        (movie.wallUrl ?: movie.posterUrl)?.let {
            AsyncImage(
                model = it,
                imageLoader = imageLoader,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = 1.16f; scaleY = 1.16f },
                contentScale = ContentScale.Crop,
                alignment = Alignment.CenterEnd,
            )
        }
    }
}

@Composable
internal fun MoviePlayer(
    player: PlayerSnapshot,
    exoPlayer: ExoPlayer,
    muted: Boolean,
    fullscreen: Boolean,
    onTogglePlayback: () -> Unit,
    onMuted: (Boolean) -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onFullscreen: (Boolean) -> Unit,
    onBack: (() -> Unit)? = null,
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
            onBack?.let { back ->
                GlassIconButton(
                    label = "返回",
                    onClick = back,
                    modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(6.dp),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                }
            }
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

/** Sort values mirror the API contract; the labels belong to the UI. */
internal enum class MovieSort(val value: String, val label: String) {
    COVER("cover", "封面优先"),
    TITLE("title", "标题"),
    ;

    companion object {
        fun labelOf(value: String): String =
            entries.firstOrNull { it.value == value }?.label ?: COVER.label
    }
}

@Composable
private fun MovieStatChip(label: String, value: String) {
    Row(
        modifier = Modifier
            .glassSurface(shape = RoundedCornerShape(999.dp), fill = GlassFillSoft)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, color = TextFaint, style = MaterialTheme.typography.labelSmall)
        Text(
            value,
            color = TextPrimary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
