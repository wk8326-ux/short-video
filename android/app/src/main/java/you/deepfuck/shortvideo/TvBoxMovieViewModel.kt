package you.deepfuck.shortvideo

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import coil.disk.DiskCache
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import you.deepfuck.shortvideo.data.MediaSurface
import you.deepfuck.shortvideo.data.MovieGroup
import you.deepfuck.shortvideo.data.MovieItem
import you.deepfuck.shortvideo.data.MovieWallView
import you.deepfuck.shortvideo.data.TvBoxConfigException
import you.deepfuck.shortvideo.data.TvBoxDetail
import you.deepfuck.shortvideo.data.TvBoxHttpException
import you.deepfuck.shortvideo.data.TvBoxPlaybackException
import you.deepfuck.shortvideo.data.TvBoxRepository
import you.deepfuck.shortvideo.data.TvBoxSettings
import you.deepfuck.shortvideo.data.TvBoxSite
import you.deepfuck.shortvideo.media.PlaybackEngineProvider

internal data class TvBoxUiState(
    val catalog: AppUiState = AppUiState(authentication = AuthenticationState.SIGNED_IN, surface = MediaSurface.MOVIE),
    val sites: List<TvBoxSite> = emptyList(),
    val unsupportedSites: List<String> = emptyList(),
    val disabledSourceIds: Set<String> = emptySet(),
    val configLocation: String = "",
    val importing: Boolean = false,
    val importError: String? = null,
    val notice: String? = null,
    val refreshing: Boolean = false,
    val completedCategories: Int = 0,
    val totalCategories: Int = 0,
    val cacheFallback: Boolean = false,
    val detail: TvBoxDetail? = null,
    val selectedLine: Int = 0,
    val selectedEpisode: Int = 0,
    val playing: Boolean = false,
    val searchQuery: String = "",
    val searchItems: List<MovieItem> = emptyList(),
    val searchLoading: Boolean = false,
    val searchError: String? = null,
    val searchNextPage: Int? = null,
)

/** Owns the movie workflow; it has no backend session or catalog-sync dependency. */
class TvBoxMovieViewModel internal constructor(
    application: Application,
    private val repository: TvBoxRepository,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, TvBoxRepository(application))
    private var settings = TvBoxSettings()
    private val mutableState = MutableStateFlow(TvBoxUiState())
    internal val state = mutableState.asStateFlow()
    val playback = PlaybackEngineProvider.get(application)
    val imageLoader = ImageLoader.Builder(application).diskCache {
        DiskCache.Builder().directory(application.cacheDir.resolve("tvbox-artwork"))
            .maxSizeBytes(128L * 1024 * 1024).build()
    }.build()
    private var catalogJob: Job? = null
    private var categoryJob: Job? = null
    private var detailJob: Job? = null
    private var searchJob: Job? = null
    private var playJob: Job? = null
    private var sourceGeneration = 0L
    private var categoryGeneration = 0L
    private var detailGeneration = 0L
    private var searchGeneration = 0L
    private var nextCategoryPage: Int? = null
    private val localMutation = Mutex()
    private var activePlayback: PlaybackContext? = null
    private var initialized = false

    private data class PlaybackContext(val movie: MovieItem, val lineId: String, val episodeId: Long)

    init {
        viewModelScope.launch {
            try {
                val saved = io { repository.settings() }
                settings = saved
                mutableState.update { it.copy(catalog = it.catalog.copy(skin = saved.skin, muted = saved.muted, movieWallView = saved.wallView)) }
                syncSources()
                initialized = true
                activate()
                refresh()
            } catch (error: Exception) {
                rethrowCancellation(error)
                mutableState.update { it.copy(importError = message(error), catalog = it.catalog.copy(movieError = message(error))) }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                playback.refreshPosition()
                if (System.currentTimeMillis() / 1000 % 5L == 0L) saveCurrentPosition()
            }
        }
    }

    fun activate() {
        playback.setOnPlaybackEndedListener { event ->
            if (activePlayback?.episodeId == event.mediaId && mutableState.value.playing) {
                viewModelScope.launch {
                    saveCurrentPosition()?.join()
                    val current = mutableState.value
                    val active = activePlayback ?: return@launch
                    val lines = current.detail?.playLines.orEmpty()
                    val lineIndex = lines.indexOfFirst { it.id == active.lineId }
                    val line = lines.getOrNull(lineIndex) ?: return@launch
                    val episodeIndex = line.episodes.indexOfFirst { it.id == active.episodeId }
                    if (episodeIndex in 0 until line.episodes.lastIndex) {
                        mutableState.update { it.copy(selectedLine = lineIndex) }
                        playEpisode(episodeIndex + 1)
                    }
                }
            }
        }
        playback.setOnMediaTransitionListener { }
        playback.setMuted(settings.muted)
    }

    private suspend fun syncSources() {
        val config = io { repository.config() }
        val summaries = io { repository.sourceSummaries() }
        val selected = settings.selectedSourceId?.takeIf { id -> summaries.any { it.id == id } } ?: summaries.firstOrNull()?.id
        settings = settings.copy(selectedSourceId = selected)
        val favorites = io { repository.favorites() }
        val recent = io { repository.recent(selected).take(12) }
        mutableState.update { state -> state.copy(
            sites = config?.sites.orEmpty(),
            unsupportedSites = config?.unsupportedSites.orEmpty(),
            disabledSourceIds = config?.sites.orEmpty().filterNot { it.enabled }.map { it.id }.toSet(),
            configLocation = config?.origin.orEmpty(),
            catalog = state.catalog.copy(movieSources = summaries, selectedMovieSourceId = selected,
                movieTotal = summaries.firstOrNull { it.id == selected }?.total ?: -1,
                movieFavorites = favorites, movieFavoritesTotal = favorites.size, recentMovies = recent),
        ) }
        io { repository.saveSettings(settings) }
    }

    fun selectSource(sourceId: String) {
        if (mutableState.value.sites.none { it.id == sourceId && it.enabled }) return
        if (mutableState.value.catalog.selectedMovieSourceId == sourceId) return
        stopPlayback()
        cancelLoads()
        settings = settings.copy(selectedSourceId = sourceId)
        mutableState.update { it.copy(detail = null, searchItems = emptyList(), searchNextPage = null,
            searchLoading = false, searchError = null, completedCategories = 0, totalCategories = 0,
            catalog = it.catalog.copy(selectedMovieSourceId = sourceId, movieGroups = emptyList(),
                selectedMovie = null, movieDetailLoading = false, openMovieGroupId = null,
                openMovieGroupItems = emptyList(), openMovieGroupLoading = false, openMovieFavorites = false,
                recentMovies = emptyList(), movieError = null, openMovieGroupError = null,
                movieTotal = it.catalog.movieSources.firstOrNull { source -> source.id == sourceId }?.total ?: -1)) }
        persistSettings()
        refresh()
    }

    fun refresh() {
        if (!initialized || mutableState.value.importing) return
        val sourceId = mutableState.value.catalog.selectedMovieSourceId ?: return
        catalogJob?.cancel()
        val generation = ++sourceGeneration
        mutableState.update { it.copy(refreshing = true, completedCategories = 0, totalCategories = 0,
            cacheFallback = false, notice = null,
            catalog = it.catalog.copy(movieLoading = true, movieGroupsLoading = true, movieError = null)) }
        catalogJob = viewModelScope.launch {
            try {
                val catalog = io { repository.catalog(sourceId, forceRefresh = true) }
                if (generation != sourceGeneration) return@launch
                val old = mutableState.value.catalog.movieGroups.associateBy { it.sourceId }
                val groups = catalog.categories.map { category ->
                    val key = "$sourceId/${category.id}"
                    old[key] ?: MovieGroup(key, category.name, -1, emptyList(), null)
                }
                val summaries = io { repository.sourceSummaries() }
                val recent = io { repository.recent(sourceId).take(12) }
                if (generation != sourceGeneration) return@launch
                mutableState.update { it.copy(totalCategories = groups.size, cacheFallback = catalog.page.fromCache,
                    catalog = it.catalog.copy(movieGroups = groups, movieSources = summaries,
                        movieTotal = catalog.source.total, movieLoading = false, recentMovies = recent)) }
                var failures = 0
                val semaphore = Semaphore(2)
                coroutineScope {
                    catalog.categories.map { category -> async {
                        semaphore.withPermit {
                            if (generation != sourceGeneration) return@withPermit
                            try {
                                val page = io { repository.categoryPage(sourceId, category.id, forceRefresh = true) }
                                if (generation != sourceGeneration) return@withPermit
                                mutableState.update { state -> state.copy(
                                    cacheFallback = state.cacheFallback || page.fromCache,
                                    catalog = state.catalog.copy(movieGroups = state.catalog.movieGroups.map { group ->
                                        if (group.sourceId == "$sourceId/${category.id}") group.copy(
                                            total = page.total, items = page.items.take(6),
                                            nextOffset = if (page.nextPage != null || page.items.size > 6) 1 else null,
                                        ) else group
                                    }),
                                ) }
                            } catch (error: Exception) {
                                rethrowCancellation(error)
                                failures += 1
                            } finally {
                                if (generation == sourceGeneration) mutableState.update { it.copy(completedCategories = it.completedCategories + 1) }
                            }
                        }
                    } }.awaitAll()
                }
                if (generation == sourceGeneration) mutableState.update { it.copy(refreshing = false,
                    notice = if (failures > 0) "$failures 个分类加载失败，可打开分类重试" else null,
                    catalog = it.catalog.copy(movieGroupsLoading = false, movieLoading = false)) }
            } catch (error: Exception) {
                rethrowCancellation(error)
                if (generation == sourceGeneration) mutableState.update { it.copy(refreshing = false,
                    catalog = it.catalog.copy(movieLoading = false, movieGroupsLoading = false, movieError = message(error))) }
            }
        }
    }

    fun openCategory(categoryKey: String) {
        val sourceId = mutableState.value.catalog.selectedMovieSourceId ?: return
        if (!categoryKey.startsWith("$sourceId/")) return
        val group = mutableState.value.catalog.movieGroups.firstOrNull { it.sourceId == categoryKey } ?: return
        closeDetail()
        categoryJob?.cancel()
        categoryGeneration += 1
        nextCategoryPage = 1
        mutableState.update { it.copy(catalog = it.catalog.copy(openMovieGroupId = categoryKey,
            openMovieGroupName = group.name, openMovieGroupItems = emptyList(), openMovieGroupTotal = group.total,
            openMovieGroupLoading = false, openMovieGroupError = null, openMovieGroupNextOffset = 1)) }
        loadMoreCategory()
    }

    fun closeCategory() {
        categoryJob?.cancel()
        categoryGeneration += 1
        nextCategoryPage = null
        mutableState.update { it.copy(catalog = it.catalog.copy(openMovieGroupId = null, openMovieGroupLoading = false)) }
    }

    fun loadMoreCategory() {
        val current = mutableState.value.catalog
        val sourceId = current.selectedMovieSourceId ?: return
        val key = current.openMovieGroupId ?: return
        val pageNumber = nextCategoryPage ?: return
        if (current.openMovieGroupLoading) return
        val generation = categoryGeneration
        mutableState.update { it.copy(catalog = it.catalog.copy(openMovieGroupLoading = true, openMovieGroupError = null)) }
        categoryJob = viewModelScope.launch {
            try {
                val page = io { repository.categoryPage(sourceId, key.removePrefix("$sourceId/"), pageNumber) }
                if (generation != categoryGeneration) return@launch
                nextCategoryPage = page.nextPage
                mutableState.update { state -> state.copy(cacheFallback = state.cacheFallback || page.fromCache,
                    catalog = state.catalog.copy(openMovieGroupItems = (state.catalog.openMovieGroupItems + page.items).distinctBy { it.id },
                        openMovieGroupTotal = page.total, openMovieGroupNextOffset = page.nextPage, openMovieGroupLoading = false)) }
            } catch (error: Exception) {
                rethrowCancellation(error)
                if (generation == categoryGeneration) mutableState.update { it.copy(catalog = it.catalog.copy(
                    openMovieGroupLoading = false, openMovieGroupError = message(error))) }
            }
        }
    }

    fun selectMovie(movie: MovieItem) {
        stopPlayback()
        detailJob?.cancel()
        val generation = ++detailGeneration
        mutableState.update { it.copy(detail = null, selectedLine = 0, selectedEpisode = 0,
            catalog = it.catalog.copy(selectedMovie = movie, movieDetailLoading = true, movieError = null)) }
        detailJob = viewModelScope.launch {
            try {
                val detail = io { repository.detail(movie, forceRefresh = true) }
                val saved = io { repository.progress(detail.movie) }
                if (generation != detailGeneration) return@launch
                val line = detail.playLines.indexOfFirst { it.id == saved?.lineId }.takeIf { it >= 0 }
                    ?: detail.playLines.indexOfFirst { it.episodes.isNotEmpty() }.coerceAtLeast(0)
                val episode = detail.playLines.getOrNull(line)?.episodes?.indexOfFirst { it.id == saved?.episodeId }
                    ?.coerceAtLeast(0) ?: 0
                mutableState.update { it.copy(detail = detail, selectedLine = line, selectedEpisode = episode,
                    cacheFallback = it.cacheFallback || detail.fromCache,
                    catalog = it.catalog.copy(selectedMovie = detail.movie, movieDetailLoading = false)) }
            } catch (error: Exception) {
                rethrowCancellation(error)
                if (generation == detailGeneration) mutableState.update { it.copy(
                    catalog = it.catalog.copy(movieDetailLoading = false, movieError = message(error))) }
            }
        }
    }

    fun closeDetail() {
        stopPlayback()
        detailJob?.cancel()
        detailGeneration += 1
        mutableState.update { it.copy(detail = null, catalog = it.catalog.copy(selectedMovie = null,
            movieDetailLoading = false, movieError = null)) }
    }

    fun selectLine(index: Int) {
        if (mutableState.value.detail?.playLines?.getOrNull(index) == null) return
        mutableState.update { it.copy(selectedLine = index, selectedEpisode = 0) }
    }

    fun play() = playEpisode(mutableState.value.selectedEpisode)

    fun retryPlayback() {
        val current = mutableState.value
        val lines = current.detail?.playLines ?: return
        val active = activePlayback
        val lineIndex = lines.indexOfFirst { it.id == active?.lineId }
            .takeIf { it >= 0 } ?: current.selectedLine
        val line = lines.getOrNull(lineIndex) ?: return
        val episodeIndex = line.episodes.indexOfFirst { it.id == active?.episodeId }
            .takeIf { it >= 0 } ?: current.selectedEpisode
        mutableState.update { it.copy(selectedLine = lineIndex) }
        startEpisode(episodeIndex, refreshDetail = true)
    }

    fun playEpisode(index: Int) = startEpisode(index, refreshDetail = false)

    private fun startEpisode(index: Int, refreshDetail: Boolean) {
        val current = mutableState.value
        val detail = current.detail ?: return
        val line = detail.playLines.getOrNull(current.selectedLine) ?: return
        val episode = line.episodes.getOrNull(index) ?: return
        val generation = detailGeneration
        val progressWrite = saveCurrentPosition()
        playJob?.cancel()
        playJob = viewModelScope.launch {
            try {
                progressWrite?.join()
                val playableDetail = if (refreshDetail) io { repository.detail(detail.movie, forceRefresh = true) } else detail
                if (generation != detailGeneration) return@launch
                val refreshedLine = playableDetail.playLines.indexOfFirst { it.id == line.id }
                val refreshedEpisode = playableDetail.playLines.getOrNull(refreshedLine)?.episodes
                    ?.indexOfFirst { it.id == episode.id } ?: -1
                if (refreshedLine < 0 || refreshedEpisode < 0) throw IOException("当前剧集已变更，请返回详情重新选择")
                val entry = io { repository.resolvePlayback(playableDetail, line.id, episode.id) }
                val saved = io { repository.progress(playableDetail.movie) }
                if (generation != detailGeneration) return@launch
                val resume = saved?.takeIf { it.episodeId == episode.id && it.lineId == line.id }
                    ?.takeUnless { it.durationMs > 0 && it.positionMs >= it.durationMs - 3_000 }?.positionMs ?: 0L
                activate()
                if (!playback.play(entry, resume)) throw IOException("无法启动播放器")
                activePlayback = PlaybackContext(playableDetail.movie, line.id, episode.id)
                mutableState.update { it.copy(playing = true, detail = playableDetail,
                    selectedLine = refreshedLine, selectedEpisode = refreshedEpisode,
                    catalog = it.catalog.copy(nowPlaying = entry, movieError = null)) }
                io { repository.recordRecent(playableDetail.movie) }
            } catch (error: Exception) {
                rethrowCancellation(error)
                if (generation == detailGeneration) mutableState.update { it.copy(catalog = it.catalog.copy(movieError = message(error))) }
            }
        }
    }

    fun stopPlayback() {
        if (activePlayback != null) {
            val write = saveCurrentPosition()
            val sourceId = mutableState.value.catalog.selectedMovieSourceId
            playback.stop()
            activePlayback = null
            viewModelScope.launch {
                write?.join()
                val recent = io { repository.recent(sourceId).take(12) }
                mutableState.update {
                    if (it.catalog.selectedMovieSourceId == sourceId) it.copy(catalog = it.catalog.copy(recentMovies = recent)) else it
                }
            }
        }
        playJob?.cancel()
        mutableState.update { it.copy(playing = false, catalog = it.catalog.copy(nowPlaying = null)) }
    }

    fun saveCurrentPosition(): Job? {
        val context = activePlayback ?: return null
        val snapshot = playback.snapshot.value
        if (snapshot.mediaId != context.episodeId) return null
        return viewModelScope.launch {
            localMutation.withLock {
                runCatching { io { repository.saveProgress(context.movie, context.lineId, context.episodeId,
                    snapshot.positionMs.coerceAtLeast(0), snapshot.durationMs.coerceAtLeast(0)) } }
            }
        }
    }

    fun onBackgrounded() {
        saveCurrentPosition()
        if (activePlayback != null) playback.pause()
    }

    fun togglePlayback() = playback.togglePlayback()

    fun setMuted(muted: Boolean) {
        settings = settings.copy(muted = muted)
        playback.setMuted(muted)
        mutableState.update { it.copy(catalog = it.catalog.copy(muted = muted)) }
        persistSettings()
    }

    fun setVolume(volume: Float) = playback.setVolume(volume)

    fun setWallView(view: MovieWallView) {
        settings = settings.copy(wallView = view)
        mutableState.update { it.copy(catalog = it.catalog.copy(movieWallView = view)) }
        persistSettings()
    }

    private fun persistSettings() {
        val saved = settings
        viewModelScope.launch { localMutation.withLock { io { repository.saveSettings(saved) } } }
    }

    fun toggleFavorite(movie: MovieItem) {
        viewModelScope.launch {
            localMutation.withLock {
                try {
                    val updated = io { repository.setFavorite(movie, !movie.favorite) }
                    val favorites = io { repository.favorites() }
                    mutableState.update { state -> state.copy(
                        detail = state.detail?.let { if (it.movie.id == movie.id) it.copy(movie = updated) else it },
                        searchItems = state.searchItems.map { if (it.id == movie.id) updated else it },
                        catalog = state.catalog.copy(
                            movieGroups = state.catalog.movieGroups.map { it.withItem(updated) },
                            openMovieGroupItems = state.catalog.openMovieGroupItems.map { if (it.id == movie.id) updated else it },
                            selectedMovie = state.catalog.selectedMovie?.let { if (it.id == movie.id) updated else it },
                            recentMovies = state.catalog.recentMovies.map { if (it.id == movie.id) updated else it },
                            movieFavorites = favorites, movieFavoritesTotal = favorites.size),
                    ) }
                } catch (error: Exception) { rethrowCancellation(error); mutableState.update { it.copy(notice = message(error)) } }
            }
        }
    }

    fun openFavorites() {
        mutableState.update { it.copy(catalog = it.catalog.copy(openMovieFavorites = true)) }
    }

    fun closeFavorites() {
        mutableState.update { it.copy(catalog = it.catalog.copy(openMovieFavorites = false)) }
    }

    fun setQuery(query: String) {
        searchJob?.cancel()
        searchGeneration += 1
        mutableState.update { it.copy(searchQuery = query, searchItems = emptyList(), searchNextPage = null, searchLoading = false, searchError = null) }
    }

    fun search() {
        searchJob?.cancel()
        searchGeneration += 1
        mutableState.update { it.copy(searchItems = emptyList(), searchNextPage = 1, searchLoading = false, searchError = null) }
        loadMoreSearch()
    }

    fun loadMoreSearch() {
        val current = mutableState.value
        val sourceId = current.catalog.selectedMovieSourceId ?: return
        val page = current.searchNextPage ?: return
        if (current.searchLoading || current.searchQuery.isBlank()) return
        val generation = searchGeneration
        mutableState.update { it.copy(searchLoading = true, searchError = null) }
        searchJob = viewModelScope.launch {
            try {
                val result = io { repository.search(sourceId, current.searchQuery.trim(), page) }
                if (generation != searchGeneration) return@launch
                mutableState.update { it.copy(searchItems = (it.searchItems + result.items).distinctBy { movie -> movie.id },
                    searchNextPage = result.nextPage, searchLoading = false, cacheFallback = it.cacheFallback || result.fromCache) }
            } catch (error: Exception) {
                rethrowCancellation(error)
                if (generation == searchGeneration) mutableState.update { it.copy(searchLoading = false, searchError = message(error)) }
            }
        }
    }

    fun importConfig(input: String) = configure { repository.importConfig(input); Unit }
    fun refreshConfig() = configure { repository.refreshConfig(); Unit }
    fun useBundledConfig() = configure { repository.useBundledConfig(); Unit }
    fun removeSource(id: String) = configure { repository.removeSource(id) }
    fun setSourceEnabled(id: String, enabled: Boolean) = configure { repository.setSourceEnabled(id, enabled) }
    fun moveSource(id: String, delta: Int) = configure { repository.moveSource(id, delta) }
    fun clearCache() = configure { repository.clearCache() }

    fun importConfigFile(uri: Uri) = configure {
        val input = getApplication<Application>().contentResolver.openInputStream(uri) ?: throw IOException("无法读取配置文件")
        val text = input.bufferedReader(Charsets.UTF_8).use { reader ->
            val result = StringBuilder()
            val buffer = CharArray(8_192)
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                if (result.length + read > 4 * 1024 * 1024) throw TvBoxConfigException("配置文件不能超过 4 MB")
                result.append(buffer, 0, read)
            }
            result.toString()
        }
        repository.importConfig(text)
        Unit
    }

    private fun configure(action: () -> Unit) {
        if (mutableState.value.importing) return
        stopPlayback()
        cancelLoads()
        mutableState.update { it.copy(importing = true, importError = null, notice = null, refreshing = false,
            searchLoading = false,
            catalog = it.catalog.copy(movieLoading = false, movieGroupsLoading = false,
                movieDetailLoading = false, openMovieGroupLoading = false)) }
        viewModelScope.launch {
            try {
                localMutation.withLock { io(action) }
                syncSources()
                mutableState.update { it.copy(importing = false, detail = null, searchItems = emptyList(),
                    searchNextPage = null, searchLoading = false, notice = "片源已更新",
                    catalog = it.catalog.copy(movieGroups = emptyList(), selectedMovie = null, movieError = null,
                        openMovieGroupId = null, openMovieGroupItems = emptyList(), openMovieGroupLoading = false)) }
                refresh()
            } catch (error: Exception) {
                rethrowCancellation(error)
                mutableState.update { it.copy(importing = false, importError = message(error)) }
            }
        }
    }

    private fun cancelLoads() {
        sourceGeneration += 1; categoryGeneration += 1; detailGeneration += 1; searchGeneration += 1
        listOf(catalogJob, categoryJob, detailJob, searchJob, playJob).forEach { it?.cancel() }
        repository.cancelPendingRequests()
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun rethrowCancellation(error: Exception) { if (error is CancellationException) throw error }

    private fun message(error: Throwable): String = when (error) {
        is TvBoxHttpException -> "片源请求失败（HTTP ${error.statusCode}），请稍后重试"
        is java.net.SocketTimeoutException -> "片源连接超时，请重试或切换片源"
        is java.io.InterruptedIOException -> "片源请求超时，请重试或切换片源"
        is java.net.UnknownHostException, is java.net.ConnectException -> "无法连接片源，请检查网络或切换片源"
        is java.net.SocketException -> "片源连接中断，请重试或切换片源"
        is TvBoxConfigException -> "配置无效：${error.message}"
        is TvBoxPlaybackException -> "播放失败：${error.message}"
        else -> error.message ?: "加载失败，请重试"
    }

    override fun onCleared() {
        repository.cancelPendingRequests()
        imageLoader.shutdown()
        super.onCleared()
    }
}
