@file:androidx.annotation.OptIn(
    markerClass = [androidx.media3.common.util.UnstableApi::class],
)

package you.deepfuck.shortvideo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
import you.deepfuck.shortvideo.AppUiState
import you.deepfuck.shortvideo.ListPosition
import you.deepfuck.shortvideo.data.DramaDetail
import you.deepfuck.shortvideo.data.DramaEpisode
import you.deepfuck.shortvideo.data.DramaGroup
import you.deepfuck.shortvideo.data.DramaItem
import you.deepfuck.shortvideo.media.PlayerSnapshot
import you.deepfuck.shortvideo.shouldLoadMore

/**
 * Short-drama surface: a poster wall of series, a series page with its episode
 * list, and full-screen playback reusing the shared player chrome.
 */
@Composable
internal fun DramaScreen(
    state: AppUiState,
    player: PlayerSnapshot,
    exoPlayer: ExoPlayer,
    imageLoader: ImageLoader,
    fullscreen: Boolean,
    pipMode: Boolean,
    listPosition: ListPosition,
    onSelect: (DramaItem) -> Unit,
    onResume: (DramaItem) -> Unit,
    onBack: () -> Unit,
    onResumeEpisode: (DramaItem, List<DramaEpisode>) -> DramaEpisode?,
    onPlayEpisode: (DramaItem, DramaEpisode) -> Unit,
    onToggleFavorite: (DramaItem) -> Unit,
    onStopPlayback: () -> Unit,
    onOpenGroup: (String) -> Unit,
    onCloseGroup: () -> Unit,
    onLoadMoreGroup: () -> Unit,
    onSortGroup: (String) -> Unit,
    onRetry: () -> Unit,
    onTogglePlayback: () -> Unit,
    onMuted: (Boolean) -> Unit,
    onVolume: (Float) -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onFullscreen: (Boolean) -> Unit,
    onPip: () -> Unit,
    onListPosition: (ListPosition) -> Unit,
) {
    val detail = state.selectedDrama
    val playingEpisodeId = state.nowPlaying?.id
    val isPlayingCurrent = detail != null &&
        playingEpisodeId != null &&
        playingEpisodeId == player.mediaId

    if (detail != null && isPlayingCurrent) {
        MoviePlayer(
            player = player,
            exoPlayer = exoPlayer,
            muted = state.muted,
            fullscreen = fullscreen,
            onTogglePlayback = onTogglePlayback,
            onMuted = onMuted,
            onVolume = onVolume,
            onSeek = onSeek,
            onSeekBy = onSeekBy,
            onFullscreen = onFullscreen,
            pipMode = pipMode,
            onPip = onPip,
            onBack = onStopPlayback,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    if (detail != null) {
        val resumeEpisode = remember(detail.item.id, detail.episodes.size) {
            onResumeEpisode(detail.item, detail.episodes)
        }
        DramaDetailPage(
            detail = detail,
            detailLoading = state.dramaDetailLoading,
            error = state.dramaError,
            imageLoader = imageLoader,
            resumeEpisode = resumeEpisode,
            favorite = "drama:${detail.item.id}" in state.favoriteMediaIds,
            onBack = onBack,
            onPlay = { episode -> onPlayEpisode(detail.item, episode) },
            onToggleFavorite = { onToggleFavorite(detail.item) },
        )
        return
    }

    // The wall is a list of library sections, so position memory tracks section
    // indexes rather than individual posters.
    val safePosition = listPosition.clamp(state.dramaGroups.size)
    val initialListPosition = remember { listPosition }
    val wallState = rememberLazyListState(
        initialFirstVisibleItemIndex = safePosition.index,
        initialFirstVisibleItemScrollOffset = safePosition.offset,
    )
    var listPositionRestored by remember { mutableStateOf(false) }
    LaunchedEffect(state.dramaGroups.size) {
        if (!listPositionRestored && state.dramaGroups.isNotEmpty()) {
            val restored = initialListPosition.clamp(state.dramaGroups.size)
            wallState.scrollToItem(restored.index, restored.offset)
            listPositionRestored = true
        }
    }

    TrackDramaWall(
        listState = wallState,
        itemCount = state.dramaGroups.size,
        enabled = listPositionRestored,
        onListPosition = onListPosition,
    )

    if (state.openDramaGroupId != null) {
        DramaLibraryPage(
            name = state.openDramaGroupName,
            items = state.openDramaGroupItems,
            total = state.openDramaGroupTotal,
            loading = state.openDramaGroupLoading,
            error = state.openDramaGroupError,
            imageLoader = imageLoader,
            onBack = onCloseGroup,
            onSelect = onSelect,
            onLoadMore = onLoadMoreGroup,
            sort = state.openDramaGroupSort,
            onSort = onSortGroup,
        )
        return
    }

    DramaWall(
        state = state,
        imageLoader = imageLoader,
        wallState = wallState,
        onSelect = onSelect,
        onResume = onResume,
        onOpenGroup = onOpenGroup,
        onRetry = onRetry,
    )
}

/** Remembers which library section the user scrolled away from. */
@Composable
private fun TrackDramaWall(
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

@Composable
private fun DramaWall(
    state: AppUiState,
    imageLoader: ImageLoader,
    wallState: LazyListState,
    onSelect: (DramaItem) -> Unit,
    onResume: (DramaItem) -> Unit,
    onOpenGroup: (String) -> Unit,
    onRetry: () -> Unit,
) {
    AppScreenScaffold {
        when {
            state.dramaGroups.isEmpty() && state.dramaError != null -> DramaWallState(
                title = state.dramaError,
                action = "重试",
                onAction = onRetry,
            )
            state.dramaGroups.isEmpty() && state.dramaGroupsLoading -> DramaSkeletonGrid()
            state.dramaGroups.isEmpty() -> DramaWallState(
                title = "还没有短剧索引",
                action = null,
                onAction = {},
            )
            // "最近播放" is one section of the wall rather than a pinned strip:
            // it scrolls away with the categories the way the film wall does.
            else -> LazyColumn(
                state = wallState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 34.dp),
            ) {
                if (state.recentDramas.isNotEmpty()) {
                    item(key = "recent") {
                        DramaRecentStrip(
                            items = state.recentDramas,
                            imageLoader = imageLoader,
                            onResume = onResume,
                        )
                    }
                }
                state.dramaGroups.forEach { group ->
                    item(key = group.groupId) {
                        DramaGroupSection(
                            group = group,
                            imageLoader = imageLoader,
                            onSelect = onSelect,
                            onOpenGroup = { onOpenGroup(group.groupId) },
                        )
                    }
                }
                if (state.dramaGroupsLoading) {
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
 * One category folder of the short-drama source: three covers abreast plus the
 * door into the full category.
 *
 * The categories are the folders the downloader wrote (短剧 / 漫剧 / …), so the
 * wall reads like a library page: a header naming the folder, a preview of what
 * is inside it, and "加载更多" opening the complete list.
 */
@Composable
private fun DramaGroupSection(
    group: DramaGroup,
    imageLoader: ImageLoader,
    onSelect: (DramaItem) -> Unit,
    onOpenGroup: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 12.dp)) {
        SectionHeader(
            title = group.name,
            trailing = "${group.items.size}/${group.total}",
            modifier = Modifier.padding(bottom = 4.dp),
            onClick = onOpenGroup,
        )
        // Rows are laid out by hand: a nested lazy grid inside this lazy column
        // would fight the parent for scroll ownership.
        group.items.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                row.forEach { drama ->
                    Box(Modifier.weight(1f)) {
                        DramaCard(drama, imageLoader, onClick = { onSelect(drama) })
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(14.dp))
        }
        if (group.hasMore) {
            val remaining = (group.total - group.items.size).coerceAtLeast(0)
            Surface(
                onClick = onOpenGroup,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
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
 * One category, opened full screen.
 *
 * Same tile language as the wall, but with the category's whole catalogue and
 * its own cursor, so a folder holding hundreds of series never has to be
 * appended to the shared page.
 */
@Composable
private fun DramaLibraryPage(
    name: String,
    items: List<DramaItem>,
    total: Int,
    loading: Boolean,
    error: String?,
    imageLoader: ImageLoader,
    onBack: () -> Unit,
    onSelect: (DramaItem) -> Unit,
    onLoadMore: () -> Unit,
    sort: String,
    onSort: (String) -> Unit,
) {
    val gridState = rememberLazyGridState()
    var sortMenuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(sort) { gridState.scrollToItem(0) }
    val hasMore = items.size < total
    TrackDramaGrid(
        gridState = gridState,
        itemCount = items.size,
        loading = loading,
        hasMore = hasMore,
        onLoadMore = onLoadMore,
    )
    AppScreenScaffold {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassIconButton(label = "返回短剧库", onClick = onBack) {
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
                GlassIconButton(label = "排序", onClick = { sortMenuOpen = true }) {
                    Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = null)
                }
                DropdownMenu(
                    expanded = sortMenuOpen,
                    onDismissRequest = { sortMenuOpen = false },
                    containerColor = RaisedStrong,
                    shape = GlassPanelShape,
                    shadowElevation = 0.dp,
                ) {
                    listOf("newest" to "最新", "oldest" to "最早", "name" to "名称").forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            trailingIcon = if (sort == value) {
                                { Icon(Icons.Outlined.Check, contentDescription = "当前排序") }
                            } else null,
                            onClick = {
                                sortMenuOpen = false
                                onSort(value)
                            },
                        )
                    }
                }
            }
        }

        when {
            items.isEmpty() && loading -> DramaSkeletonGrid()
            items.isEmpty() && error != null -> DramaWallState(
                title = error,
                action = "重试",
                onAction = onLoadMore,
            )
            items.isEmpty() -> DramaWallState(
                title = "这个分类还没有短剧",
                action = null,
                onAction = {},
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, top = 6.dp, end = 12.dp, bottom = 34.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(items, key = DramaItem::id) { drama ->
                    DramaCard(drama, imageLoader, onClick = { onSelect(drama) })
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

/** Pulls the next slice of a category once the grid nears its end. */
@Composable
private fun TrackDramaGrid(
    gridState: LazyGridState,
    itemCount: Int,
    loading: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
) {
    LaunchedEffect(gridState, itemCount) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (shouldLoadMore(lastVisible, itemCount, loading, hasMore, threshold = 8)) onLoadMore()
            }
    }
}

/**
 * "Continue watching" for short dramas: the last ten series the app saw, each
 * one reopening on the episode that was interrupted.
 */
@Composable
private fun DramaRecentStrip(
    items: List<DramaItem>,
    imageLoader: ImageLoader,
    onResume: (DramaItem) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 12.dp)) {
        SectionHeader(
            title = "最近播放",
            trailing = "${items.size} 部",
            modifier = Modifier.padding(bottom = 4.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(items, key = { "recent-" + it.id }) { drama ->
                Surface(
                    onClick = { onResume(drama) },
                    modifier = Modifier
                        .width(124.dp)
                        .semantics { contentDescription = "继续观看 ${drama.title}" },
                    color = Color.Transparent,
                    contentColor = TextPrimary,
                    shape = MediaCardShape,
                ) {
                    Column {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(2f / 3f)
                                .clip(MediaCardShape)
                                .background(Raised),
                            contentAlignment = Alignment.Center,
                        ) {
                            val poster = drama.posterUrl
                            if (poster.isNullOrBlank()) {
                                Text(
                                    drama.title,
                                    color = TextSecondary,
                                    style = MaterialTheme.typography.labelSmall,
                                    textAlign = TextAlign.Center,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(8.dp),
                                )
                            } else {
                                AsyncImage(
                                    model = poster,
                                    imageLoader = imageLoader,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                            Text(
                                "继续观看",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(6.dp)
                                    .glassSurface(
                                        shape = RoundedCornerShape(6.dp),
                                        fill = Color.Black.copy(alpha = 0.55f),
                                    )
                                    .padding(horizontal = 6.dp, vertical = 3.dp),
                            )
                        }
                        Spacer(Modifier.height(7.dp))
                        Text(
                            drama.title,
                            color = TextPrimary,
                            fontWeight = FontWeight.Medium,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DramaCard(drama: DramaItem, imageLoader: ImageLoader, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "${drama.title}，共 ${drama.episodeCount} 集" },
        color = Color.Transparent,
        contentColor = TextPrimary,
        shape = MediaCardShape,
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(MediaCardShape)
                    .background(Raised),
                contentAlignment = Alignment.Center,
            ) {
                val poster = drama.posterUrl
                if (poster.isNullOrBlank()) {
                    Text(
                        drama.title,
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(10.dp),
                    )
                } else {
                    AsyncImage(
                        model = poster,
                        imageLoader = imageLoader,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                if (drama.episodeCount > 0) {
                    Text(
                        "${drama.episodeCount} 集",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .glassSurface(shape = RoundedCornerShape(6.dp), fill = Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
            }
            Spacer(Modifier.height(7.dp))
            Text(
                drama.title,
                color = TextPrimary,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                drama.categoryLabel,
                color = TextFaint,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun DramaSkeletonGrid() {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        userScrollEnabled = false,
        contentPadding = PaddingValues(start = 12.dp, top = 6.dp, end = 12.dp, bottom = 34.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(12) {
            Column {
                Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(MediaCardShape).background(Raised))
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth(0.8f).height(12.dp).clip(RoundedCornerShape(3.dp)).background(RaisedStrong))
                Spacer(Modifier.height(6.dp))
                Box(Modifier.width(38.dp).height(9.dp).clip(RoundedCornerShape(3.dp)).background(Raised))
            }
        }
    }
}

@Composable
private fun DramaWallState(title: String, action: String?, onAction: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Outlined.LiveTv, contentDescription = null, tint = TextFaint, modifier = Modifier.size(30.dp))
            Text(title, color = TextSecondary, textAlign = TextAlign.Center)
            action?.let {
                TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(it) }
            }
        }
    }
}

@Composable
private fun DramaDetailPage(
    detail: DramaDetail,
    detailLoading: Boolean,
    error: String?,
    imageLoader: ImageLoader,
    resumeEpisode: DramaEpisode?,
    favorite: Boolean,
    onBack: () -> Unit,
    onPlay: (DramaEpisode) -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val drama = detail.item
    BoxWithConstraints(Modifier.fillMaxSize().background(Canvas)) {
        val topSpacer = maxHeight * 0.42f
        drama.posterUrl?.let { url ->
            AsyncImage(
                model = url,
                imageLoader = imageLoader,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        // The poster is the page background, so the information layer needs a
        // scrim that grows towards the bottom without hiding the artwork.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.34f),
                        0.22f to Color.Transparent,
                        0.48f to Color.Black.copy(alpha = 0.30f),
                        0.70f to Canvas.copy(alpha = 0.86f),
                        0.90f to Canvas.copy(alpha = 0.98f),
                        1f to Canvas,
                    ),
                ),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassIconButton(label = "返回短剧库", onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                    }
                    Spacer(Modifier.weight(1f))
                    GlassIconButton(
                        label = if (favorite) "取消收藏" else "收藏",
                        onClick = onToggleFavorite,
                        tint = if (favorite) Accent else Color.White,
                    ) {
                        Icon(
                            if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = null,
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(topSpacer)) }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
                    Text(
                        drama.title,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DramaMetaChip(drama.categoryLabel)
                        DramaMetaChip("共 ${drama.episodeCount} 集")
                        if (detailLoading) {
                            CircularProgressIndicator(Modifier.size(16.dp), color = TextFaint, strokeWidth = 2.dp)
                        }
                    }
                    if (drama.tags.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            drama.tags.joinToString(" · "),
                            color = TextFaint,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    val primary = resumeEpisode ?: detail.episodes.firstOrNull()
                    Button(
                        onClick = { primary?.let(onPlay) },
                        enabled = primary != null,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = ControlShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        val prefix = if (resumeEpisode != null) "继续观看" else "开始观看"
                        Text(if (primary != null) "$prefix  第${primary.position}集" else "暂无剧集")
                    }
                    error?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (drama.overview.isNotBlank()) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            drama.overview,
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(22.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("选集", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${detail.episodes.size} 集",
                            color = TextFaint,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    if (detail.episodes.isEmpty()) {
                        Text(
                            if (detailLoading) "正在载入分集…" else "这部剧还没有可播放的分集",
                            color = TextFaint,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        EpisodePicker(
                            episodes = detail.episodes,
                            highlightId = resumeEpisode?.videoId,
                            onPlay = onPlay,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DramaMetaChip(label: String) {
    MetadataChip(label = label)
}

@Composable
private fun EpisodePicker(
    episodes: List<DramaEpisode>,
    highlightId: Long?,
    onPlay: (DramaEpisode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        episodes.chunked(5).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { episode ->
                    val active = episode.videoId == highlightId
                    Surface(
                        onClick = { onPlay(episode) },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .semantics { contentDescription = "第 ${episode.position} 集" },
                        color = if (active) Accent.copy(alpha = 0.86f) else GlassFillSoft,
                        contentColor = if (active) Color.White else TextSecondary,
                        shape = ControlShape,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (active) Color.Transparent else GlassLine,
                        ),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                episode.position.toString(),
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
