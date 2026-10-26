@file:androidx.annotation.OptIn(
    markerClass = [androidx.media3.common.util.UnstableApi::class],
)

package you.deepfuck.shortvideo.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.ImageLoader
import kotlinx.coroutines.flow.distinctUntilChanged
import you.deepfuck.shortvideo.ListPosition
import you.deepfuck.shortvideo.TvBoxMovieViewModel
import you.deepfuck.shortvideo.TvBoxUiState
import you.deepfuck.shortvideo.data.MovieItem
import you.deepfuck.shortvideo.data.TvBoxDetail
import you.deepfuck.shortvideo.data.TvBoxSite

private enum class TvBoxTab(val label: String, val icon: ImageVector) {
    MOVIES("电影", Icons.Outlined.Movie),
    SEARCH("搜索", Icons.Outlined.Search),
    FAVORITES("收藏", Icons.Outlined.FavoriteBorder),
    SOURCES("片源", Icons.Outlined.Cloud),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TvBoxApp(
    viewModel: TvBoxMovieViewModel,
    pipMode: Boolean = false,
    onFullscreenChanged: (Boolean) -> Unit = {},
    onPipRequested: (() -> Unit)? = null,
    onOpenLegacy: (() -> Unit)? = null,
    appReleaseUrl: String? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val player by viewModel.playback.snapshot.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(TvBoxTab.MOVIES) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var episodesOpen by rememberSaveable { mutableStateOf(false) }
    val currentFullscreenCallback by rememberUpdatedState(onFullscreenChanged)
    val positions = remember { mutableMapOf<String, ListPosition>() }
    val catalog = state.catalog
    val detail = state.detail
    val selectedSource = state.sites.firstOrNull { it.id == catalog.selectedMovieSourceId }
    val changeFullscreen: (Boolean) -> Unit = {
        fullscreen = it
        onFullscreenChanged(it)
    }
    val chooseTab: (TvBoxTab) -> Unit = {
        if (tab != it) {
            viewModel.closeDetail()
            viewModel.closeCategory()
            if (it == TvBoxTab.FAVORITES) viewModel.openFavorites() else viewModel.closeFavorites()
            tab = it
        }
    }

    LaunchedEffect(state.playing) {
        if (!state.playing) {
            episodesOpen = false
            if (fullscreen) changeFullscreen(false)
        }
    }
    DisposableEffect(Unit) {
        onDispose { currentFullscreenCallback(false) }
    }
    BackHandler(
        enabled = state.playing || catalog.selectedMovie != null ||
            catalog.openMovieGroupId != null || tab != TvBoxTab.MOVIES,
    ) {
        when {
            episodesOpen -> episodesOpen = false
            fullscreen -> changeFullscreen(false)
            state.playing -> viewModel.stopPlayback()
            catalog.selectedMovie != null -> viewModel.closeDetail()
            catalog.openMovieGroupId != null -> viewModel.closeCategory()
            else -> chooseTab(TvBoxTab.MOVIES)
        }
    }

    if (state.playing) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val episode = detail?.playLines?.getOrNull(state.selectedLine)
                ?.episodes?.getOrNull(state.selectedEpisode)
            MoviePlayer(
                player = player,
                exoPlayer = viewModel.playback.player,
                muted = catalog.muted,
                fullscreen = fullscreen,
                onTogglePlayback = viewModel::togglePlayback,
                onMuted = viewModel::setMuted,
                onVolume = viewModel::setVolume,
                onSeek = viewModel.playback::seekTo,
                onSeekBy = viewModel.playback::seekBy,
                onFullscreen = changeFullscreen,
                pipMode = pipMode,
                onPip = onPipRequested,
                onBack = {
                    if (fullscreen) changeFullscreen(false) else viewModel.stopPlayback()
                },
                title = listOfNotNull(detail?.movie?.title, episode?.name).joinToString(" · "),
                modifier = Modifier.fillMaxSize(),
                onEpisodes = if (detail?.playLines?.isNotEmpty() == true) ({ episodesOpen = true }) else null,
            )
            val playbackError = catalog.movieError ?: if (player.error != null) "播放失败" else null
            if (playbackError != null && !pipMode) {
                Row(
                    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding()
                        .padding(horizontal = 64.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        playbackError,
                        modifier = Modifier.weight(1f, fill = false),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = viewModel::retryPlayback) { Text("重试") }
                }
            }
        }
        if (episodesOpen && !pipMode && detail != null) {
            ModalBottomSheet(
                onDismissRequest = { episodesOpen = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = RaisedStrong,
                contentColor = TextPrimary,
                shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                ) {
                    item {
                        TvBoxEpisodes(
                            detail = detail,
                            selectedLine = state.selectedLine,
                            selectedEpisode = state.selectedEpisode,
                            onLine = viewModel::selectLine,
                            onEpisode = {
                                viewModel.playEpisode(it)
                                episodesOpen = false
                            },
                        )
                    }
                }
            }
        }
        return
    }

    @Composable
    fun MovieContent() {
        val sourceKey = catalog.selectedMovieSourceId.orEmpty()
        MovieScreen(
            state = catalog,
            player = player,
            exoPlayer = viewModel.playback.player,
            imageLoader = viewModel.imageLoader,
            fullscreen = false,
            pipMode = false,
            listPosition = positions["wall:$sourceKey"] ?: ListPosition(),
            groupListPosition = positions["category:$sourceKey:${catalog.openMovieGroupId}"] ?: ListPosition(),
            favoritesListPosition = positions["favorites"] ?: ListPosition(),
            metadataListPosition = { ListPosition() },
            onSelect = viewModel::selectMovie,
            onBack = viewModel::closeDetail,
            onPlay = { viewModel.play() },
            onOpenGroup = viewModel::openCategory,
            onMovieSource = viewModel::selectSource,
            onCloseGroup = viewModel::closeCategory,
            onGroupSort = {},
            onLoadMoreGroup = viewModel::loadMoreCategory,
            onGroupListPosition = { id, position -> positions["category:$sourceKey:$id"] = position },
            onOpenFavorites = { chooseTab(TvBoxTab.FAVORITES) },
            onCloseFavorites = { chooseTab(TvBoxTab.MOVIES) },
            onLoadMoreFavorites = {},
            onFavoritesListPosition = { positions["favorites"] = it },
            onOpenMetadata = {},
            onCloseMetadata = {},
            onLoadMoreMetadata = {},
            onSortMetadata = {},
            onMetadataListPosition = { _, _ -> },
            onToggleFavorite = viewModel::toggleFavorite,
            onWallView = viewModel::setWallView,
            onRetry = viewModel::refresh,
            onTogglePlayback = viewModel::togglePlayback,
            onMuted = viewModel::setMuted,
            onVolume = viewModel::setVolume,
            onSeek = viewModel.playback::seekTo,
            onSeekBy = viewModel.playback::seekBy,
            onFullscreen = changeFullscreen,
            onPip = { onPipRequested?.invoke() },
            onListPosition = { positions["wall:$sourceKey"] = it },
            standalone = true,
            playEnabled = detail?.playLines?.any { it.episodes.isNotEmpty() } == true,
            detailExtras = {
                if (detail == null && !catalog.movieDetailLoading && catalog.movieError != null) {
                    TextButton(onClick = { catalog.selectedMovie?.let(viewModel::selectMovie) }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("重新加载详情")
                    }
                }
                if (detail != null && detail.movie.id == catalog.selectedMovie?.id) {
                    TvBoxEpisodes(
                        detail = detail,
                        selectedLine = state.selectedLine,
                        selectedEpisode = state.selectedEpisode,
                        onLine = viewModel::selectLine,
                        onEpisode = viewModel::playEpisode,
                    )
                }
            },
            onOpenSources = { chooseTab(TvBoxTab.SOURCES) },
        )
    }

    if (catalog.selectedMovie != null) {
        MovieContent()
        return
    }

    Column(
        Modifier.fillMaxSize().background(Canvas).statusBarsPadding().navigationBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "TVBox",
                modifier = Modifier.weight(1f),
                color = TextPrimary,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            if (tab == TvBoxTab.MOVIES || tab == TvBoxTab.SEARCH) {
                GlassIconButton(
                    label = "刷新片源",
                    enabled = !state.refreshing && !state.importing,
                    onClick = viewModel::refresh,
                ) { Icon(Icons.Outlined.Refresh, contentDescription = null) }
            }
        }
        TabRow(
            selectedTabIndex = tab.ordinal,
            containerColor = Color.Transparent,
            contentColor = Accent,
            divider = {},
        ) {
            TvBoxTab.entries.forEach { destination ->
                Tab(
                    selected = tab == destination,
                    onClick = { chooseTab(destination) },
                    selectedContentColor = Accent,
                    unselectedContentColor = TextSecondary,
                    text = { Text(destination.label, maxLines = 1) },
                    icon = { Icon(destination.icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
                )
            }
        }
        if (tab != TvBoxTab.SOURCES) {
            TvBoxCatalogStatus(state)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                TvBoxTab.MOVIES, TvBoxTab.FAVORITES -> MovieContent()
                TvBoxTab.SEARCH -> TvBoxSearch(
                    state = state,
                    sourceName = selectedSource?.name,
                    imageLoader = viewModel.imageLoader,
                    onQuery = viewModel::setQuery,
                    onSearch = viewModel::search,
                    onLoadMore = viewModel::loadMoreSearch,
                    onSelect = viewModel::selectMovie,
                    onOpenSources = { chooseTab(TvBoxTab.SOURCES) },
                )
                TvBoxTab.SOURCES -> TvBoxSources(
                    state = state,
                    onImport = viewModel::importConfig,
                    onImportFile = viewModel::importConfigFile,
                    onRefresh = viewModel::refreshConfig,
                    onBundled = viewModel::useBundledConfig,
                    onSelect = { id ->
                        viewModel.selectSource(id)
                        chooseTab(TvBoxTab.MOVIES)
                    },
                    onEnabled = viewModel::setSourceEnabled,
                    onRemove = viewModel::removeSource,
                    onMove = viewModel::moveSource,
                    onClearCache = viewModel::clearCache,
                    onOpenLegacy = onOpenLegacy,
                    appReleaseUrl = appReleaseUrl,
                )
            }
        }
    }
}

@Composable
private fun TvBoxCatalogStatus(state: TvBoxUiState) {
    val loading = state.refreshing || state.catalog.movieLoading || state.catalog.movieGroupsLoading
    val hasProgress = state.totalCategories > 0
    if (loading) {
        if (hasProgress) {
            LinearProgressIndicator(
                progress = { state.completedCategories.toFloat().div(state.totalCategories).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = Accent,
            )
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Accent)
        }
    }
    val status = when {
        loading && hasProgress -> "分类 ${state.completedCategories}/${state.totalCategories}"
        loading -> "正在加载片源"
        state.cacheFallback -> "当前显示缓存内容"
        else -> state.notice
    }
    status?.let {
        Text(
            it,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            color = TextSecondary,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TvBoxEpisodes(
    detail: TvBoxDetail,
    selectedLine: Int,
    selectedEpisode: Int,
    onLine: (Int) -> Unit,
    onEpisode: (Int) -> Unit,
) {
    Text("选集", color = TextPrimary, fontWeight = FontWeight.SemiBold)
    if (detail.playLines.isEmpty()) {
        Text("暂无可播放剧集", modifier = Modifier.padding(top = 8.dp), color = TextSecondary)
        return
    }
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(detail.playLines.size, key = { detail.playLines[it].id }) { index ->
            FilterChip(
                selected = selectedLine == index,
                onClick = { onLine(index) },
                label = {
                    Text(detail.playLines[index].name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                modifier = Modifier.heightIn(min = 48.dp).widthIn(max = 240.dp),
                shape = RoundedCornerShape(6.dp),
            )
        }
    }
    val episodes = detail.playLines.getOrNull(selectedLine)?.episodes.orEmpty()
    if (episodes.isEmpty()) {
        Text("此线路暂无剧集", modifier = Modifier.padding(top = 8.dp), color = TextSecondary)
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        episodes.forEachIndexed { index, episode ->
            FilterChip(
                selected = selectedEpisode == index,
                onClick = { onEpisode(index) },
                label = { Text(episode.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(6.dp),
            )
        }
    }
}

@Composable
private fun TvBoxSearch(
    state: TvBoxUiState,
    sourceName: String?,
    imageLoader: ImageLoader,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onLoadMore: () -> Unit,
    onSelect: (MovieItem) -> Unit,
    onOpenSources: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var submitted by rememberSaveable { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    val submit = {
        if (state.searchQuery.isNotBlank()) {
            submitted = true
            keyboard?.hide()
            onSearch()
        }
    }
    LaunchedEffect(grid, state.searchItems.size, state.searchLoading, state.searchNextPage, state.searchError) {
        if (state.searchLoading || state.searchNextPage == null || state.searchError != null) return@LaunchedEffect
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { index ->
                if (state.searchItems.isNotEmpty() && index >= state.searchItems.size - 6) onLoadMore()
            }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                sourceName ?: "未选择片源",
                modifier = Modifier.weight(1f),
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            GlassIconButton(label = "管理片源", onClick = onOpenSources) {
                Icon(Icons.Outlined.Cloud, contentDescription = null)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onQuery,
                modifier = Modifier.weight(1f),
                label = { Text("片名") },
                singleLine = true,
                shape = RoundedCornerShape(6.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                trailingIcon = {
                    if (state.searchQuery.isNotEmpty()) {
                        GlassIconButton(label = "清除搜索", onClick = { onQuery("") }) {
                            Icon(Icons.Outlined.Clear, contentDescription = null)
                        }
                    }
                },
            )
            GlassIconButton(
                label = "搜索",
                enabled = state.searchQuery.isNotBlank() && !state.searchLoading && sourceName != null,
                onClick = { submit() },
                tint = Accent,
            ) { Icon(Icons.Outlined.Search, contentDescription = null) }
        }
        when {
            state.searchItems.isEmpty() && state.searchLoading -> TvBoxLoading()
            state.searchItems.isEmpty() && state.searchError != null -> TvBoxEmpty(
                title = state.searchError,
                action = "重试",
                onAction = { submit() },
            )
            state.searchItems.isEmpty() -> TvBoxEmpty(
                title = if (submitted) "未找到影片" else "搜索影片",
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(148.dp),
                state = grid,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                items(state.searchItems, key = MovieItem::id) { movie ->
                    MoviePoster(movie, imageLoader, onClick = { onSelect(movie) })
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    when {
                        state.searchLoading -> Box(
                            Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
                        state.searchError != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(state.searchError, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = onLoadMore) { Text("重试") }
                        }
                        state.searchNextPage != null -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            TextButton(onClick = onLoadMore) { Text("加载更多") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvBoxSources(
    state: TvBoxUiState,
    onImport: (String) -> Unit,
    onImportFile: (android.net.Uri) -> Unit,
    onRefresh: () -> Unit,
    onBundled: () -> Unit,
    onSelect: (String) -> Unit,
    onEnabled: (String, Boolean) -> Unit,
    onRemove: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onClearCache: () -> Unit,
    onOpenLegacy: (() -> Unit)?,
    appReleaseUrl: String?,
) {
    var input by rememberSaveable { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<TvBoxSite?>(null) }
    var restoreDefaults by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val uriHandler = LocalUriHandler.current
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onImportFile)
    }
    val busy = state.importing
    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("配置", color = TextPrimary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                label = { Text("配置地址或 JSON") },
                minLines = 2,
                maxLines = 5,
                shape = RoundedCornerShape(6.dp),
                isError = state.importError != null,
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { keyboard?.hide(); onImport(input.trim()) },
                    enabled = !busy && input.isNotBlank(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("导入配置")
                }
                GlassIconButton(
                    label = "从文件导入",
                    enabled = !busy,
                    onClick = { filePicker.launch(arrayOf("*/*")) },
                ) { Icon(Icons.Outlined.FolderOpen, contentDescription = null) }
                GlassIconButton(label = "刷新配置", enabled = !busy && state.configLocation.isNotBlank(), onClick = onRefresh) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                }
            }
            if (state.configLocation.isNotBlank()) {
                Text(
                    state.configLocation,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    color = TextFaint,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (busy || state.importError != null || state.notice != null || state.refreshing) {
            item {
                Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                    if (busy || state.refreshing) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Accent)
                        Text(
                            if (busy) "正在更新配置" else "正在刷新片源",
                            modifier = Modifier.padding(top = 8.dp),
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    state.importError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    state.notice?.let { Text(it, color = TextSecondary, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        item {
            HorizontalDivider(color = Line)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("片源", modifier = Modifier.weight(1f), color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Text(
                    "${state.sites.count { it.id !in state.disabledSourceIds }}/${state.sites.size}",
                    color = TextFaint,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        if (state.unsupportedSites.isNotEmpty()) {
            item {
                val names = state.unsupportedSites.take(4).joinToString("、")
                Text(
                    "已忽略 ${state.unsupportedSites.size} 个暂不支持的站点：$names。当前支持标准 JSON CMS。",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (state.sites.isEmpty()) {
            item { Text("暂无片源", modifier = Modifier.padding(vertical = 16.dp), color = TextSecondary) }
        }
        items(state.sites, key = TvBoxSite::id) { site ->
            val index = state.sites.indexOfFirst { it.id == site.id }
            TvBoxSourceRow(
                site = site,
                selected = state.catalog.selectedMovieSourceId == site.id,
                enabled = site.id !in state.disabledSourceIds,
                busy = busy,
                canMoveUp = index > 0,
                canMoveDown = index < state.sites.lastIndex,
                onSelect = { onSelect(site.id) },
                onEnabled = { onEnabled(site.id, it) },
                onUp = { onMove(site.id, -1) },
                onDown = { onMove(site.id, 1) },
                onDelete = { pendingDelete = site },
            )
        }
        item {
            HorizontalDivider(color = Line)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { restoreDefaults = true }, enabled = !busy) {
                    Icon(Icons.Outlined.RestartAlt, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("恢复默认")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClearCache, enabled = !busy && !state.refreshing) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("清除缓存")
                }
            }
            onOpenLegacy?.let { openLegacy ->
                TextButton(onClick = openLegacy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("服务器媒体库")
                }
            }
            appReleaseUrl?.takeIf { it.isNotBlank() }?.let { url ->
                TextButton(onClick = { uriHandler.openUri(url) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("应用更新")
                }
            }
        }
    }
    pendingDelete?.let { site ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除片源") },
            text = { Text(site.name) },
            confirmButton = {
                TextButton(onClick = { onRemove(site.id); pendingDelete = null }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
            containerColor = RaisedStrong,
            shape = RoundedCornerShape(8.dp),
        )
    }
    if (restoreDefaults) {
        AlertDialog(
            onDismissRequest = { restoreDefaults = false },
            title = { Text("恢复默认片源？") },
            confirmButton = {
                TextButton(onClick = { onBundled(); restoreDefaults = false }) { Text("恢复") }
            },
            dismissButton = { TextButton(onClick = { restoreDefaults = false }) { Text("取消") } },
            containerColor = RaisedStrong,
            shape = RoundedCornerShape(8.dp),
        )
    }
}

@Composable
private fun TvBoxSourceRow(
    site: TvBoxSite,
    selected: Boolean,
    enabled: Boolean,
    busy: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onSelect: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    GlassPanel(shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        site.name,
                        color = if (enabled) TextPrimary else TextFaint,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        if (!enabled) "已停用" else if (selected) "当前片源" else "已启用",
                        modifier = Modifier.padding(top = 4.dp),
                        color = if (selected && enabled) Accent else TextFaint,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabled,
                    enabled = !busy,
                    modifier = Modifier.semantics { contentDescription = "启用${site.name}" },
                )
                Box {
                    GlassIconButton(label = "${site.name}操作", enabled = !busy, onClick = { menuOpen = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        containerColor = RaisedStrong,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        DropdownMenuItem(
                            text = { Text("上移") }, enabled = canMoveUp,
                            leadingIcon = { Icon(Icons.Outlined.ArrowUpward, contentDescription = null) },
                            onClick = { menuOpen = false; onUp() },
                        )
                        DropdownMenuItem(
                            text = { Text("下移") }, enabled = canMoveDown,
                            leadingIcon = { Icon(Icons.Outlined.ArrowDownward, contentDescription = null) },
                            onClick = { menuOpen = false; onDown() },
                        )
                        DropdownMenuItem(
                            text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }
            Text(
                site.api,
                color = TextFaint,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onSelect, enabled = enabled && !busy && !selected, modifier = Modifier.align(Alignment.End)) {
                if (selected && enabled) {
                    Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (selected && enabled) "已选择" else "选择片源")
            }
        }
    }
}

@Composable
private fun TvBoxLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(24.dp), color = TextSecondary, strokeWidth = 2.dp)
    }
}

@Composable
private fun TvBoxEmpty(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        action?.let { TextButton(onClick = onAction) { Text(it) } }
    }
}
