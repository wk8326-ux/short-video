@file:androidx.annotation.OptIn(
    markerClass = [androidx.media3.common.util.UnstableApi::class],
)

package you.deepfuck.shortvideo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import you.deepfuck.shortvideo.data.MovieWallView
import you.deepfuck.shortvideo.data.movieSortOnSelect
import you.deepfuck.shortvideo.data.movieSortParts
import you.deepfuck.shortvideo.media.PlayerSnapshot
import you.deepfuck.shortvideo.shouldLoadMore

@Composable
internal fun MovieScreen(
    state: AppUiState,
    player: PlayerSnapshot,
    exoPlayer: ExoPlayer,
    imageLoader: ImageLoader,
    fullscreen: Boolean,
    pipMode: Boolean,
    listPosition: ListPosition,
    groupListPosition: ListPosition,
    onSelect: (MovieItem) -> Unit,
    onBack: () -> Unit,
    onPlay: (MovieItem) -> Unit,
    onOpenGroup: (String) -> Unit,
    onCloseGroup: () -> Unit,
    onGroupSort: (String) -> Unit,
    onLoadMoreGroup: () -> Unit,
    onGroupListPosition: (String, ListPosition) -> Unit,
    onWallView: (MovieWallView) -> Unit,
    onRetry: () -> Unit,
    onTogglePlayback: () -> Unit,
    onMuted: (Boolean) -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onFullscreen: (Boolean) -> Unit,
    onPip: () -> Unit,
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
    var listPositionRestored by remember { mutableStateOf(false) }
    LaunchedEffect(state.movieGroups.size) {
        if (!listPositionRestored && state.movieGroups.isNotEmpty()) {
            val restored = initialListPosition.clamp(state.movieGroups.size)
            wallState.scrollToItem(restored.index, restored.offset)
            listPositionRestored = true
        }
    }

    TrackMovieWall(
        listState = wallState,
        itemCount = state.movieGroups.size,
        enabled = listPositionRestored,
        onListPosition = onListPosition,
    )

    val movie = state.selectedMovie
    if (movie != null) {
        MovieDetail(
            movie = movie,
            detailLoading = state.movieDetailLoading,
            error = state.movieError,
            player = player,
            exoPlayer = exoPlayer,
            imageLoader = imageLoader,
            muted = state.muted,
            fullscreen = fullscreen,
            pipMode = pipMode,
            playbackStarted = state.nowPlaying?.id == movie.videoId || player.mediaId == movie.videoId,
            onBack = onBack,
            onPlay = { onPlay(movie) },
            onTogglePlayback = onTogglePlayback,
            onMuted = onMuted,
            onSeek = onSeek,
            onSeekBy = onSeekBy,
            onFullscreen = onFullscreen,
            onPip = onPip,
        )
        return
    }

    if (state.openMovieGroupId != null) {
        val groupId = state.openMovieGroupId
        MovieLibraryPage(
            name = state.openMovieGroupName,
            items = state.openMovieGroupItems,
            total = state.openMovieGroupTotal,
            loading = state.openMovieGroupLoading,
            error = state.openMovieGroupError,
            sort = state.openMovieGroupSort,
            imageLoader = imageLoader,
            onBack = onCloseGroup,
            onSort = onGroupSort,
            onSelect = onSelect,
            onLoadMore = onLoadMoreGroup,
            initialPosition = groupListPosition,
            onListPosition = { onGroupListPosition(groupId, it) },
        )
        return
    }

    MovieCatalog(
        state = state,
        imageLoader = imageLoader,
        wallState = wallState,
        onSelect = onSelect,
        onOpenGroup = onOpenGroup,
        onWallView = onWallView,
        onRetry = onRetry,
    )
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

/** Pages the grid of a library opened on its own page. */
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

/**
 * The wall: one section per media source.
 *
 * Search, sort and the counting chips used to live here. A library of a few
 * thousand titles is browsed by opening it, so the header keeps only the
 * library jump menu and each section carries its own "加载更多" entry point.
 */
@Composable
private fun MovieCatalog(
    state: AppUiState,
    imageLoader: ImageLoader,
    wallState: LazyListState,
    onSelect: (MovieItem) -> Unit,
    onOpenGroup: (String) -> Unit,
    onWallView: (MovieWallView) -> Unit,
    onRetry: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var jumpMenuOpen by remember { mutableStateOf(false) }
    var viewMenuOpen by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .background(Canvas)
            .statusBarsPadding()
            .padding(top = 62.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // How the wall draws itself. Mirrors the library menu on the right
            // so the header reads as one symmetrical pair of controls.
            Box {
                GlassIconButton(
                    label = "电影墙展示方式",
                    onClick = { viewMenuOpen = true },
                ) {
                    Icon(Icons.Outlined.GridView, contentDescription = null)
                }
                DropdownMenu(
                    expanded = viewMenuOpen,
                    onDismissRequest = { viewMenuOpen = false },
                    containerColor = RaisedStrong,
                    shape = GlassPanelShape,
                    shadowElevation = 0.dp,
                ) {
                    MovieWallView.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            trailingIcon = {
                                if (state.movieWallView == option) {
                                    Icon(Icons.Outlined.Check, contentDescription = null)
                                }
                            },
                            onClick = {
                                viewMenuOpen = false
                                onWallView(option)
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            // Quick jump between libraries: the wall is grouped by media source,
            // so the menu mirrors that order.
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

        when {
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
                if (state.recentMovies.isNotEmpty()) {
                    item(key = "recent") {
                        RecentMovieStrip(
                            items = state.recentMovies,
                            imageLoader = imageLoader,
                            onSelect = onSelect,
                        )
                    }
                }
                when (state.movieWallView) {
                    MovieWallView.SHELVES -> state.movieGroups.forEach { group ->
                        item(key = group.sourceId) {
                            MovieGroupSection(
                                group = group,
                                imageLoader = imageLoader,
                                onSelect = onSelect,
                                onOpenGroup = { onOpenGroup(group.sourceId) },
                            )
                        }
                    }
                    // One tile per library: a wall of twenty libraries still
                    // fits on a screen.
                    MovieWallView.LIBRARIES -> state.movieGroups.chunked(2).forEach { row ->
                        item(key = "row-" + row.joinToString("-") { it.sourceId }) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                row.forEach { group ->
                                    Box(Modifier.weight(1f)) {
                                        MovieLibraryTile(
                                            group = group,
                                            imageLoader = imageLoader,
                                            onClick = { onOpenGroup(group.sourceId) },
                                        )
                                    }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                    // One sideways row per library, the way a library page in
                    // Emby reads.
                    MovieWallView.ROWS -> state.movieGroups.forEach { group ->
                        item(key = group.sourceId) {
                            MovieRowSection(
                                group = group,
                                imageLoader = imageLoader,
                                onSelect = onSelect,
                                onOpenGroup = { onOpenGroup(group.sourceId) },
                            )
                        }
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

/**
 * Shared shelf header: the accent tick, the library name and its counter.
 *
 * Every view of the wall is a stack of these, so they all read the same way
 * whatever shape the shelves below them take.
 */
@Composable
private fun MovieSectionHeader(
    title: String,
    trailing: String,
    onClick: (() -> Unit)? = null,
) {
    val base = Modifier
        .fillMaxWidth()
        .padding(start = 14.dp, end = 14.dp, bottom = 10.dp)
    Row(
        modifier = if (onClick == null) base else base.clip(ControlShape).clickable(onClick = onClick),
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
            title,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            trailing,
            color = TextFaint,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

/**
 * "Keep watching": the last ten titles the app saw half-finished.
 *
 * A wall sorted by anything else buries the one row a viewer actually wants,
 * so this strip sits above every library and never scrolls away sideways.
 */
@Composable
private fun RecentMovieStrip(
    items: List<MovieItem>,
    imageLoader: ImageLoader,
    onSelect: (MovieItem) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp)) {
        MovieSectionHeader(title = "最近播放", trailing = "${items.size} 部")
        LazyRow(
            contentPadding = PaddingValues(horizontal = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(items, key = { "recent-" + it.id }) { movie ->
                RecentMovieCard(movie, imageLoader, onClick = { onSelect(movie) })
            }
        }
    }
}

@Composable
private fun RecentMovieCard(
    movie: MovieItem,
    imageLoader: ImageLoader,
    onClick: () -> Unit,
) {
    val watchedPercent = (movie.resumeFraction * 100).toInt()
    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(178.dp)
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
                    .aspectRatio(3f / 2f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Raised),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Movie,
                    contentDescription = null,
                    tint = TextFaint,
                    modifier = Modifier.size(24.dp),
                )
                (movie.wallUrl ?: movie.posterUrl)?.let { url ->
                    AsyncImage(
                        model = url,
                        imageLoader = imageLoader,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        // Every cell is one fixed 3:2 frame and the proxy cuts
                        // the cover to match, so a wide picture lands edge to
                        // edge and a portrait one keeps its own proportions
                        // inside the frame.
                        contentScale = ContentScale.Fit,
                    )
                }
                if (movie.resumeFraction > 0f) {
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(Color.Black.copy(alpha = 0.45f)),
                    )
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(movie.resumeFraction)
                            .height(3.dp)
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
                if (watchedPercent > 0) "已观看 $watchedPercent%" else "继续观看",
                color = TextFaint,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }
}

/**
 * One library as a single tile: its first four covers stitched into one poster.
 *
 * This is the view for someone who has added twenty sources - the whole wall
 * then fits on a screen without a single horizontal scroll. Four covers is the
 * most a half-width tile can hold while each one still reads as artwork: a
 * 3x2 grid halves the cells and turns covers into slivers, so the grid is kept
 * square and the tile is taller instead.
 */
@Composable
private fun MovieLibraryTile(
    group: MovieGroup,
    imageLoader: ImageLoader,
    onClick: () -> Unit,
) {
    val covers = group.items
        .mapNotNull { it.wallUrl ?: it.posterUrl }
        .distinct()
        .take(MOSAIC_TILES)
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "${group.name}，共 ${group.total} 部" },
        color = Color.Transparent,
        contentColor = TextPrimary,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Raised),
            ) {
                repeat(MOSAIC_TILES / MOSAIC_COLUMNS) { rowIndex ->
                    Row(Modifier.fillMaxWidth()) {
                        repeat(MOSAIC_COLUMNS) { columnIndex ->
                            val cover = covers.getOrNull(rowIndex * MOSAIC_COLUMNS + columnIndex)
                            Box(
                                Modifier
                                    .weight(1f)
                                    .aspectRatio(3f / 2f)
                                    .background(Raised),
                            ) {
                                cover?.let { url ->
                                    AsyncImage(
                                        model = url,
                                        imageLoader = imageLoader,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                group.name,
                color = TextPrimary,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${group.total} 部",
                color = TextFaint,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }
}

private const val MOSAIC_COLUMNS = 2
private const val MOSAIC_TILES = MOSAIC_COLUMNS * MOSAIC_COLUMNS

/**
 * One library as a sideways row with a trailing "more" tile, the way a media
 * server lists a collection: a taste of the library, then the door to it.
 */
@Composable
private fun MovieRowSection(
    group: MovieGroup,
    imageLoader: ImageLoader,
    onSelect: (MovieItem) -> Unit,
    onOpenGroup: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp)) {
        MovieSectionHeader(
            title = group.name,
            trailing = "${group.items.size}/${group.total}",
            onClick = onOpenGroup,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(group.items, key = { it.id }) { movie ->
                Box(Modifier.width(166.dp)) {
                    MoviePoster(movie, imageLoader, onClick = { onSelect(movie) })
                }
            }
            item(key = "more") {
                MovieRowMoreCard(
                    remaining = (group.total - group.items.size).coerceAtLeast(0),
                    onClick = onOpenGroup,
                )
            }
        }
    }
}

@Composable
private fun MovieRowMoreCard(remaining: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(96.dp)
            .heightIn(min = 96.dp),
        color = GlassFillSoft,
        contentColor = TextSecondary,
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 96.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text("更多", style = MaterialTheme.typography.labelLarge, maxLines = 1)
            if (remaining > 0) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "$remaining 部",
                    color = TextFaint,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun MovieGroupSection(
    group: MovieGroup,
    imageLoader: ImageLoader,
    onSelect: (MovieItem) -> Unit,
    onOpenGroup: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ControlShape)
                .clickable(onClick = onOpenGroup)
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
                onClick = onOpenGroup,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .heightIn(min = 48.dp),
                color = GlassFillSoft,
                contentColor = TextSecondary,
                shape = ControlShape,
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        if (remaining > 0) "加载更多 · 还有 $remaining 部" else "加载更多",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * One library, opened full screen.
 *
 * Same wall language as the main page, but with the whole library, its own
 * cursor and a sort control: a library of a few thousand posters cannot be
 * appended to a shared page without burying every other library.
 */
@Composable
private fun MovieLibraryPage(
    name: String,
    items: List<MovieItem>,
    total: Int,
    loading: Boolean,
    error: String?,
    sort: String,
    imageLoader: ImageLoader,
    onBack: () -> Unit,
    onSort: (String) -> Unit,
    onSelect: (MovieItem) -> Unit,
    onLoadMore: () -> Unit,
    initialPosition: ListPosition,
    onListPosition: (ListPosition) -> Unit,
) {
    // The grid reopens where it was left, so coming back from a detail page
    // lands on the poster that was tapped instead of the top of the library.
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = initialPosition.index.coerceAtLeast(0),
        initialFirstVisibleItemScrollOffset = initialPosition.offset.coerceAtLeast(0),
    )
    DisposableEffect(gridState) {
        onDispose {
            onListPosition(
                ListPosition(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset),
            )
        }
    }
    var sortMenuOpen by remember { mutableStateOf(false) }
    val hasMore = items.size < total
    TrackMovieGrid(
        gridState = gridState,
        itemCount = items.size,
        loading = loading,
        hasMore = hasMore,
        onLoadMore = onLoadMore,
    )
    Column(
        Modifier
            .fillMaxSize()
            .background(Canvas)
            .statusBarsPadding()
            .padding(top = 62.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassIconButton(label = "返回电影墙", onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (total > 0) "共 $total 部" else "正在统计…",
                    color = TextFaint,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
            Box {
                GlassIconButton(
                    label = "排序",
                    onClick = { sortMenuOpen = true },
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = null)
                }
                DropdownMenu(
                    expanded = sortMenuOpen,
                    onDismissRequest = { sortMenuOpen = false },
                    containerColor = RaisedStrong,
                    shape = GlassPanelShape,
                    shadowElevation = 0.dp,
                ) {
                    // Tapping the option that is already selected flips its
                    // arrow instead of doing nothing, so both directions of
                    // title and time are reachable from one menu.
                    val (activeField, activeDirection) = movieSortParts(sort)
                    MovieSort.entries.forEach { option ->
                        val selected = option.field == activeField
                        val arrow = if (selected) directionArrow(activeDirection) else ""
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (arrow.isEmpty()) option.label
                                    else "${option.label}  $arrow",
                                )
                            },
                            trailingIcon = {
                                if (selected) {
                                    Icon(Icons.Outlined.Check, contentDescription = null)
                                }
                            },
                            onClick = {
                                sortMenuOpen = false
                                onSort(movieSortOnSelect(sort, option.field))
                            },
                        )
                    }
                }
            }
        }

        when {
            items.isEmpty() && loading -> MovieSkeletonGrid()
            items.isEmpty() && error != null -> MovieCatalogState(
                icon = Icons.Outlined.Refresh,
                title = error,
                action = "重试",
                onAction = onLoadMore,
            )
            items.isEmpty() -> MovieCatalogState(
                icon = Icons.Outlined.Movie,
                title = "这个媒体库还没有影片",
                action = null,
                onAction = {},
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(148.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 10.dp, top = 8.dp, end = 10.dp, bottom = 34.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(items, key = MovieItem::id) { movie ->
                    MoviePoster(movie, imageLoader, onClick = { onSelect(movie) })
                }
                if (loading) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(22.dp), color = TextSecondary, strokeWidth = 2.dp)
                        }
                    }
                } else if (hasMore) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                            Text("正在载入更多…", color = TextFaint, style = MaterialTheme.typography.labelSmall)
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
                    .aspectRatio(3f / 2f)
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
                        contentScale = ContentScale.Fit,
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
                Box(Modifier.fillMaxWidth().aspectRatio(3f / 2f).clip(RoundedCornerShape(6.dp)).background(Raised))
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
    pipMode: Boolean,
    playbackStarted: Boolean,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onTogglePlayback: () -> Unit,
    onMuted: (Boolean) -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onFullscreen: (Boolean) -> Unit,
    onPip: () -> Unit,
) {
    // A started film takes the whole surface. The portrait case used to pin a
    // sixteen by nine strip under the top edge, leave the rest of the page to the
    // metadata and draw the controls over the picture with no way to clear them;
    // now it is the same immersive surface as the landscape one, with the video
    // letterboxed in the middle and chrome that fades on its own.
    if (playbackStarted) {
        MoviePlayer(
            player = player,
            exoPlayer = exoPlayer,
            muted = muted,
            fullscreen = fullscreen,
            onTogglePlayback = onTogglePlayback,
            onMuted = onMuted,
            onSeek = onSeek,
            onSeekBy = onSeekBy,
            onFullscreen = onFullscreen,
            pipMode = pipMode,
            onPip = onPip,
            // The landscape bar already carries the exit button, so the arrow is
            // only an affordance where the metadata page it used to sit on is
            // gone.
            onBack = if (fullscreen) null else onBack,
            title = movie.title,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    Box(Modifier.fillMaxSize().background(Canvas)) {
    // The detail page keeps one piece of artwork: the landscape cover at the top.
    // A second, full-page copy of the same picture behind the metadata only made
    // the text harder to read and doubled the image traffic.
    LazyColumn(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        contentPadding = PaddingValues(bottom = 40.dp),
    ) {
        item {
            Box(Modifier.fillMaxWidth()) {
                MovieHero(movie, imageLoader)
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
            .aspectRatio(3f / 2f)
            .background(Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Movie, contentDescription = null, tint = TextFaint, modifier = Modifier.size(30.dp))
        // Exactly the wall's expression, at exactly the wall's URL and in
        // exactly the wall's 3:2 frame: the detail page used to scale the
        // picture up and pin it to one edge, which both cropped it and asked
        // for a second copy of the same bytes.
        (movie.wallUrl ?: movie.posterUrl)?.let {
            AsyncImage(
                model = it,
                imageLoader = imageLoader,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
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
    pipMode: Boolean = false,
    onPip: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    title: String? = null,
    modifier: Modifier = Modifier,
) {
    var chromeVisible by remember(fullscreen) { mutableStateOf(true) }
    var dragPixels by remember { mutableFloatStateOf(0f) }
    var seekPreviewMs by remember { mutableStateOf<Long?>(null) }
    var seekBaseMs by remember { mutableStateOf(0L) }
    val latestPositionMs = rememberUpdatedState(player.positionMs)

    // Five quiet seconds of playback and the controls step aside, in portrait as
    // well as landscape. A paused film keeps them: the pause was deliberate, and
    // hiding the way to resume it is how a video ends up looking frozen.
    LaunchedEffect(chromeVisible, player.isPlaying, player.mediaId, pipMode, fullscreen) {
        if (chromeVisible && player.isPlaying && !pipMode) {
            delay(5_000)
            chromeVisible = false
        }
    }

    // Scrubbing is a drag, so it never competes with the tap that shows or hides
    // the chrome. Portrait used to be left out of this, which is why the gesture
    // only worked after rotating.
    val gestureModifier = if (pipMode) {
        Modifier
    } else {
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
    }

    // One tap anywhere toggles the chrome, portrait included. Buttons inside the
    // surface handle their own presses first, so a tap on play still plays.
    val surfaceTapModifier = if (pipMode) {
        Modifier
    } else {
        Modifier.pointerInput(fullscreen, pipMode) {
            detectTapGestures { chromeVisible = !chromeVisible }
        }
    }
    Box(
        modifier = modifier
            .background(Color.Black)
            .then(gestureModifier)
            .then(surfaceTapModifier),
    ) {
        PlayerHost(exoPlayer, Modifier.fillMaxSize())
        BufferSpinner(player.isBuffering)

        // Chrome follows the tap in both orientations. Inside the floating window
        // it never draws at all: the system already frames that surface.
        val controlsVisible = !pipMode && chromeVisible
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
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.52f))
                    .navigationBarsPadding()
                    .padding(start = 8.dp, top = 2.dp, end = 8.dp, bottom = 4.dp),
            ) {
                FineProgressBar(player, onSeek, compact = true)
                // The metadata page is gone once playback starts, so the bar is
                // what still says which film is running.
                val barTitle = title?.takeIf { it.isNotBlank() }
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
                    if (barTitle != null) {
                        Text(
                            barTitle,
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                            color = TextSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
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
                    onPip?.let { requestPip ->
                        IconButton(
                            onClick = requestPip,
                            modifier = Modifier.size(48.dp),
                            colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
                        ) {
                            Icon(
                                Icons.Outlined.PictureInPictureAlt,
                                contentDescription = "浮窗播放",
                            )
                        }
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

/**
 * Sort options mirror the API contract; the labels belong to the UI.
 *
 * [field] is what the menu compares against a stored token, so the direction
 * lives in the token rather than in a separate enum entry.
 */
internal enum class MovieSort(val field: String, val label: String) {
    COVER("cover", "封面优先"),
    TITLE("title", "标题"),
    TIME("time", "按时间"),
    ;

    companion object {
        fun labelOf(value: String): String {
            val field = movieSortParts(value).first
            return entries.firstOrNull { it.field == field }?.label ?: COVER.label
        }
    }
}

internal fun directionArrow(direction: String): String = when (direction) {
    "asc" -> "↑"
    "desc" -> "↓"
    else -> ""
}
