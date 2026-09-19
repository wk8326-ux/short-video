package you.deepfuck.shortvideo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import you.deepfuck.shortvideo.data.AdminStatus
import you.deepfuck.shortvideo.data.ApiException
import you.deepfuck.shortvideo.data.AppUpdateInfo
import you.deepfuck.shortvideo.data.AppUpdatePhase
import you.deepfuck.shortvideo.data.AppUpdateUiState
import you.deepfuck.shortvideo.data.AsmrAuthor
import you.deepfuck.shortvideo.data.AsmrFilter
import you.deepfuck.shortvideo.data.DramaDetail
import you.deepfuck.shortvideo.data.DramaEpisode
import you.deepfuck.shortvideo.data.DramaItem
import you.deepfuck.shortvideo.data.FeedMode
import you.deepfuck.shortvideo.data.LibraryScanStatus
import you.deepfuck.shortvideo.data.LibrarySection
import you.deepfuck.shortvideo.data.MediaApi
import you.deepfuck.shortvideo.data.MediaEntry
import you.deepfuck.shortvideo.data.MediaSurface
import you.deepfuck.shortvideo.data.MediaSourceDraft
import you.deepfuck.shortvideo.data.MovieGroup
import you.deepfuck.shortvideo.data.MovieItem
import you.deepfuck.shortvideo.data.PlaybackPreferences
import you.deepfuck.shortvideo.media.PlaybackEngine
import you.deepfuck.shortvideo.media.PlaybackEndedEvent
import you.deepfuck.shortvideo.media.PlaybackEngineProvider
import you.deepfuck.shortvideo.media.PlaybackService
import you.deepfuck.shortvideo.logging.AppLogStore
import you.deepfuck.shortvideo.update.isUpdateAvailable
import you.deepfuck.shortvideo.update.AppUpdateWork
import you.deepfuck.shortvideo.update.sha256Matches

enum class AuthenticationState { CHECKING, SIGNED_IN, SIGNED_OUT }

data class AppUiState(
    val authentication: AuthenticationState = AuthenticationState.CHECKING,
    val loginBusy: Boolean = false,
    val loginError: String? = null,
    val surface: MediaSurface = MediaSurface.SHORT,
    val mode: FeedMode = FeedMode.SHUFFLE,
    val muted: Boolean = true,
    val feedItems: List<MediaEntry> = emptyList(),
    val activeIndex: Int = 0,
    val feedTotal: Int = 0,
    val feedLoading: Boolean = false,
    val feedError: String? = null,
    val asmrAuthors: List<AsmrAuthor> = emptyList(),
    val asmrAuthorsTotal: Int = 0,
    val asmrItems: List<MediaEntry> = emptyList(),
    val asmrTotal: Int = 0,
    val asmrItemsHasMore: Boolean = false,
    val selectedAuthor: String? = null,
    val asmrLoading: Boolean = false,
    val asmrError: String? = null,
    val asmrFilter: AsmrFilter = AsmrFilter.ALL,
    val asmrQuery: String = "",
    val movieItems: List<MovieItem> = emptyList(),
    val movieTotal: Int = 0,
    val movieNextOffset: Int? = 0,
    val movieLoading: Boolean = false,
    val movieError: String? = null,
    val movieQuery: String = "",
    val movieSort: String = "cover",
    val movieGroups: List<MovieGroup> = emptyList(),
    val movieGroupsLoading: Boolean = false,
    // One library opened full-screen ("加载更多"): its own cursor, sort and
    // paging state, so the wall behind it keeps whatever it had loaded.
    val openMovieGroupId: String? = null,
    val openMovieGroupName: String = "",
    val openMovieGroupItems: List<MovieItem> = emptyList(),
    val openMovieGroupTotal: Int = 0,
    val openMovieGroupNextOffset: Int? = 0,
    val openMovieGroupLoading: Boolean = false,
    val openMovieGroupError: String? = null,
    val openMovieGroupSort: String = "cover",
    val movieDetailLoading: Boolean = false,
    val selectedMovie: MovieItem? = null,
    val dramaItems: List<DramaItem> = emptyList(),
    val dramaTotal: Int = 0,
    val dramaNextOffset: Int? = 0,
    val dramaLoading: Boolean = false,
    val dramaError: String? = null,
    val dramaQuery: String = "",
    val dramaDetailLoading: Boolean = false,
    val selectedDrama: DramaDetail? = null,
    val expandedMedia: MediaEntry? = null,
    val nowPlaying: MediaEntry? = null,
    val asmrVideoBackgroundPlayback: Boolean = false,
    val showManagement: Boolean = false,
    val adminStatus: AdminStatus? = null,
    val adminLoading: Boolean = false,
    val adminActions: Set<String> = emptySet(),
    val adminError: String? = null,
    val appUpdate: AppUpdateUiState = AppUpdateUiState(),
    val showRuntimeLogs: Boolean = false,
    val runtimeLogText: String = "",
)

private data class AsmrItemsKey(
    val author: String,
    val filter: AsmrFilter,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = PlaybackPreferences(application)
    private val api = MediaApi(preferences)
    private val logs = AppLogStore.get(application)
    private val workManager = WorkManager.getInstance(application)
    val playback: PlaybackEngine = PlaybackEngineProvider.get(application)
    private val restoredMedia = playback.currentMedia()
    private val initialSurface = preferences.surface

    private val mutableState = MutableStateFlow(
        AppUiState(
            authentication = if (api.hasSession()) {
                AuthenticationState.SIGNED_IN
            } else {
                AuthenticationState.CHECKING
            },
            surface = initialSurface,
            mode = preferences.mode,
            muted = preferences.muted(initialSurface),
            asmrVideoBackgroundPlayback = preferences.asmrVideoBackgroundPlayback,
            selectedAuthor = preferences.selectedAsmrAuthor,
            nowPlaying = restoredMedia.takeIf {
                shouldKeepPlayingInBackground(
                    initialSurface,
                    it,
                    preferences.asmrVideoBackgroundPlayback,
                )
            },
        ),
    )
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()

    private var feedCursor: String? = null
    private var feedExcludedIds: List<Long> = emptyList()
    private var feedStartId: Long? = null
    private var feedJob: Job? = null
    private var feedTotalJob: Job? = null
    private var feedRequestGeneration = 0L
    private val feedSessions = FeedSessionRegistry()
    private val feedLibraryRefresh = FeedLibraryRefreshGate()
    private var asmrAuthorsJob: Job? = null
    private var asmrAuthorsLoaded = false
    private var asmrAuthorIndexRestored = false
    private val asmrItemJobs = mutableMapOf<AsmrItemsKey, Job>()
    private val asmrItemGenerations = mutableMapOf<AsmrItemsKey, Long>()
    private var activeFeedItemId: Long? = null
    private val asmrItemsCache = mutableMapOf<AsmrItemsKey, List<MediaEntry>>()
    private val asmrItemsNextOffsets = mutableMapOf<AsmrItemsKey, Int?>()
    private val asmrItemsTotals = mutableMapOf<AsmrItemsKey, Int>()
    private var asmrQueueKey: AsmrItemsKey? = null
    private var asmrQueue: List<MediaEntry> = emptyList()
    private var pendingAsmrAdvanceId: Long? = null
    private var movieJob: Job? = null
    private var movieSearchJob: Job? = null
    private var movieDetailJob: Job? = null
    private var movieGroupPageJob: Job? = null
    private var movieGroupPageGeneration = 0L
    private var movieRequestGeneration = 0L
    private var movieCatalogDirty = false
    private var dramaJob: Job? = null
    private var dramaSearchJob: Job? = null
    private var dramaDetailJob: Job? = null
    private var dramaRequestGeneration = 0L
    private var dramaCatalogDirty = false
    private var appUpdateJob: Job? = null
    private var appUpdateObserverJob: Job? = null
    val movieImageLoader by lazy { api.movieImageLoader(application) }

    init {
        playback.setMuted(preferences.muted(initialSurface))
        playback.setOnPlaybackEndedListener { event ->
            viewModelScope.launch { advanceAfterPlaybackEnded(event) }
        }
        playback.setOnMediaTransitionListener { entry ->
            viewModelScope.launch { handleMediaTransition(entry) }
        }
        if (api.hasSession()) {
            restoreCurrentSurface()
            verifySessionInBackground()
        } else {
            viewModelScope.launch { mutableState.value = mutableState.value.copy(authentication = AuthenticationState.SIGNED_OUT) }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                playback.refreshPosition()
                saveCurrentPosition()
            }
        }
    }

    fun login(password: String) {
        if (password.isBlank() || mutableState.value.loginBusy) {
            if (password.isBlank()) mutableState.value = mutableState.value.copy(loginError = "请输入密码")
            return
        }
        mutableState.value = mutableState.value.copy(loginBusy = true, loginError = null)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { api.login(password) } }
                .onSuccess {
                    mutableState.value = mutableState.value.copy(
                        authentication = AuthenticationState.SIGNED_IN,
                        loginBusy = false,
                    )
                    restoreCurrentSurface()
                }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(
                        loginBusy = false,
                        loginError = when ((error as? ApiException)?.statusCode) {
                            401 -> "密码不正确"
                            429 -> "尝试次数过多，请稍后再试"
                            else -> "暂时无法连接服务器"
                        },
                    )
                }
        }
    }

    fun logout() {
        appUpdateJob?.cancel()
        appUpdateObserverJob?.cancel()
        saveCurrentPosition()
        viewModelScope.launch(Dispatchers.IO) { api.logout() }
        playback.stop()
        stopPlaybackService()
        activeFeedItemId = null
        mutableState.value = AppUiState(authentication = AuthenticationState.SIGNED_OUT)
    }

    fun changeSurface(surface: MediaSurface) {
        val previousSurface = mutableState.value.surface
        if (surface == previousSurface) return
        logs.info("surface_change", "from=$previousSurface to=$surface media=${playback.snapshot.value.mediaId}")
        saveCurrentPosition()
        when {
            previousSurface == MediaSurface.ASMR -> persistAsmrPlaybackState()
            previousSurface.isFeed -> persistCurrentFeedSession()
            previousSurface == MediaSurface.MOVIE -> updateMovieResumePosition()
        }
        feedJob?.cancel()
        feedJob = null
        feedTotalJob?.cancel()
        feedTotalJob = null
        feedRequestGeneration += 1L
        if (previousSurface == MediaSurface.ASMR) {
            val itemJobs = asmrItemJobs.toList()
            asmrItemJobs.clear()
            itemJobs.forEach { (key, job) ->
                asmrItemGenerations[key] = (asmrItemGenerations[key] ?: 0L) + 1L
                job.cancel()
            }
        }
        if (previousSurface == MediaSurface.MOVIE) {
            movieJob?.cancel()
            movieSearchJob?.cancel()
            movieDetailJob?.cancel()
            cancelMovieGroupPageLoad()
            movieGroupPageGeneration += 1L
            movieRequestGeneration += 1L
        }
        if (previousSurface == MediaSurface.DRAMA) {
            dramaJob?.cancel()
            dramaSearchJob?.cancel()
            dramaDetailJob?.cancel()
            dramaRequestGeneration += 1L
        }
        preferences.surface = surface
        val surfaceMuted = preferences.muted(surface)
        playback.setMuted(surfaceMuted)
        activeFeedItemId = null
        asmrQueueKey = null
        asmrQueue = emptyList()
        pendingAsmrAdvanceId = null
        mutableState.value = mutableState.value.copy(
            surface = surface,
            muted = surfaceMuted,
            feedItems = if (surface.isFeed) mutableState.value.feedItems else emptyList(),
            activeIndex = 0,
            expandedMedia = null,
            nowPlaying = null,
            selectedMovie = null,
            movieDetailLoading = false,
            openMovieGroupId = null,
            openMovieGroupName = "",
            openMovieGroupItems = emptyList(),
            openMovieGroupTotal = 0,
            openMovieGroupNextOffset = 0,
            openMovieGroupLoading = false,
            openMovieGroupError = null,
            selectedDrama = null,
            dramaDetailLoading = false,
            showManagement = false,
            feedError = null,
            asmrError = null,
            movieError = null,
            dramaError = null,
        )
        playback.stop()
        playback.prefetch(surface, emptyList())
        stopPlaybackService()
        when {
            surface.isFeed -> if (!refreshPendingFeedLibrary()) restoreFeed(surface)
            surface == MediaSurface.ASMR -> restoreAsmrSurface()
            surface == MediaSurface.MOVIE -> restoreMovieSurface()
            surface == MediaSurface.DRAMA -> restoreDramaSurface()
        }
    }

    fun changeMode(mode: FeedMode) {
        if (!mutableState.value.surface.isFeed) return
        if (mode == mutableState.value.mode) return
        saveCurrentPosition()
        persistCurrentFeedSession()
        preferences.mode = mode
        activeFeedItemId = null
        mutableState.value = mutableState.value.copy(mode = mode, activeIndex = 0)
        restoreFeed(mutableState.value.surface)
    }

    fun setMuted(muted: Boolean) {
        preferences.setMuted(mutableState.value.surface, muted)
        playback.setMuted(muted)
        mutableState.value = mutableState.value.copy(muted = muted)
    }

    fun togglePlayback() {
        playback.togglePlayback()
        when {
            mutableState.value.surface == MediaSurface.ASMR -> persistAsmrPlaybackState()
            mutableState.value.surface.isFeed -> persistCurrentFeedSession()
            mutableState.value.surface == MediaSurface.MOVIE -> saveCurrentPosition()
        }
        if (playback.player.playWhenReady && keepCurrentAsmrPlaybackInBackground()) {
            startPlaybackService()
        }
    }

    fun retryFeed() = requestFeed(reset = true)

    fun loadMoreFeed() {
        val state = mutableState.value
        if (shouldRequestMoreFeed(feedCursor, state.feedItems.size, state.feedTotal, state.feedLoading)) {
            requestFeed(reset = false)
        }
    }

    fun activateFeedItem(index: Int) = activateFeedItem(index, autoPlay = true)

    private fun activateFeedItem(index: Int, autoPlay: Boolean) {
        val state = mutableState.value
        if (!shouldActivateFeedItem(state.surface, state.showManagement)) return
        val entry = state.feedItems.getOrNull(index) ?: return
        if (activeFeedItemId == entry.id) {
            if (autoPlay && !playback.player.playWhenReady) playback.player.play()
            return
        }
        saveCurrentPosition()
        activeFeedItemId = entry.id
        preferences.setLastVideo(state.surface, entry.id)
        logs.info("feed_activate", "surface=${state.surface} index=$index media=${entry.id} autoPlay=$autoPlay")
        mutableState.value = state.copy(activeIndex = index, nowPlaying = entry)
        if (!playback.play(entry, preferences.position(entry.id), autoPlay = autoPlay)) {
            mutableState.value = mutableState.value.copy(feedError = "无法准备该视频")
            return
        }
        playback.prefetch(state.surface, feedPrefetchCandidates(state.feedItems, index))
        persistCurrentFeedSession(playWhenReady = autoPlay)
        if (index >= state.feedItems.lastIndex - 4) loadMoreFeed()
    }

    fun selectAsmrAuthor(author: String) {
        logs.info("asmr_author_open", "authorHash=${author.hashCode()}")
        asmrAuthorsJob?.cancel()
        preferences.selectedAsmrAuthor = author
        val key = AsmrItemsKey(author, mutableState.value.asmrFilter)
        val cached = asmrItemsCache[key].orEmpty()
        val cachedKnown = asmrItemsCache.containsKey(key)
        val nextOffset = if (cachedKnown) asmrItemsNextOffsets[key] else 0
        mutableState.value = mutableState.value.copy(
            selectedAuthor = author,
            asmrItems = cached,
            asmrTotal = asmrItemsTotals[key] ?: cached.size,
            asmrItemsHasMore = nextOffset != null,
            asmrLoading = false,
            asmrError = null,
            asmrQuery = "",
        )
        if (!cachedKnown) loadAsmrItems(key, reset = true)
    }

    fun leaveAsmrAuthor() {
        val author = mutableState.value.selectedAuthor
        logs.info("asmr_author_close", "authorHash=${author?.hashCode()}")
        preferences.selectedAsmrAuthor = null
        if (author != null) {
            asmrItemJobs.keys
                .filter { it.author == author && it != asmrQueueKey }
                .forEach { key ->
                    asmrItemGenerations[key] = (asmrItemGenerations[key] ?: 0L) + 1L
                    asmrItemJobs.remove(key)?.cancel()
                }
        }
        mutableState.value = mutableState.value.copy(
            selectedAuthor = null,
            asmrItems = emptyList(),
            asmrTotal = 0,
            asmrItemsHasMore = false,
            asmrLoading = false,
            asmrQuery = "",
        )
    }

    fun loadMoreAsmrItems() {
        val author = mutableState.value.selectedAuthor ?: return
        loadAsmrItems(AsmrItemsKey(author, mutableState.value.asmrFilter), reset = false)
    }

    fun setAsmrFilter(filter: AsmrFilter) {
        val state = mutableState.value
        if (filter == state.asmrFilter) return
        val author = state.selectedAuthor
        if (author == null) {
            mutableState.value = state.copy(asmrFilter = filter)
            return
        }
        val key = AsmrItemsKey(author, filter)
        val cached = asmrItemsCache[key].orEmpty()
        val cachedKnown = asmrItemsCache.containsKey(key)
        mutableState.value = state.copy(
            asmrFilter = filter,
            asmrItems = cached,
            asmrTotal = asmrItemsTotals[key] ?: cached.size,
            asmrItemsHasMore = if (cachedKnown) asmrItemsNextOffsets[key] != null else true,
            asmrLoading = !cachedKnown,
            asmrError = null,
        )
        if (!cachedKnown) loadAsmrItems(key, reset = true)
    }

    fun setAsmrQuery(query: String) {
        mutableState.value = mutableState.value.copy(asmrQuery = query)
    }

    fun playAsmr(entry: MediaEntry) {
        if (mutableState.value.surface != MediaSurface.ASMR) return
        logs.info("asmr_play", "media=${entry.id} kind=${if (entry.isAudio) "audio" else "video"}")
        asmrQueueKey = entry.author?.let { AsmrItemsKey(it, mutableState.value.asmrFilter) }
        asmrQueue = asmrPlaybackQueue(mutableState.value.asmrItems, entry)
        pendingAsmrAdvanceId = null
        startAsmrPlayback(entry)
    }

    private fun startAsmrPlayback(entry: MediaEntry, expandVideo: Boolean = true) {
        if (mutableState.value.surface != MediaSurface.ASMR) return
        saveCurrentPosition()
        preferences.setLastVideo(MediaSurface.ASMR, entry.id)
        mutableState.value = mutableState.value.copy(
            nowPlaying = entry,
            expandedMedia = entry.takeIf { !it.isAudio && expandVideo },
        )
        val queue = asmrQueue.takeIf { entries -> entries.any { it.id == entry.id } } ?: listOf(entry)
        if (!playback.play(entry, preferences.position(entry.id), queue = queue)) {
            preferences.saveAsmrPlaybackState(null)
            mutableState.value = mutableState.value.copy(
                asmrError = "无法准备该媒体",
                nowPlaying = null,
                expandedMedia = null,
            )
            return
        }
        prefetchNextAsmrEntries(entry)
        loadMoreAsmrQueueIfNeeded(entry)
        persistAsmrPlaybackState()
        if (backgroundPlaybackEnabled(entry)) startPlaybackService() else stopPlaybackService()
    }

    fun setAsmrVideoBackgroundPlayback(enabled: Boolean) {
        if (mutableState.value.asmrVideoBackgroundPlayback == enabled) return
        logs.info("asmr_video_background", "enabled=$enabled media=${mutableState.value.nowPlaying?.id}")
        preferences.asmrVideoBackgroundPlayback = enabled
        mutableState.value = mutableState.value.copy(asmrVideoBackgroundPlayback = enabled)
        val current = mutableState.value.nowPlaying ?: return
        if (current.isAudio) return
        if (enabled && playback.player.playWhenReady) startPlaybackService() else stopPlaybackService()
        persistAsmrPlaybackState()
    }

    fun expandNowPlaying() {
        mutableState.value.nowPlaying?.takeUnless(MediaEntry::isAudio)?.let {
            mutableState.value = mutableState.value.copy(expandedMedia = it)
            persistAsmrPlaybackState()
        }
    }

    fun collapsePlayer() {
        mutableState.value = mutableState.value.copy(expandedMedia = null)
        persistAsmrPlaybackState()
    }

    fun closeAsmrPlayer() {
        saveCurrentPosition()
        playback.stop()
        stopPlaybackService()
        asmrQueueKey = null
        asmrQueue = emptyList()
        pendingAsmrAdvanceId = null
        preferences.saveAsmrPlaybackState(null)
        mutableState.value = mutableState.value.copy(expandedMedia = null, nowPlaying = null)
    }

    fun setMovieSort(sort: String) {
        if (sort == mutableState.value.movieSort) return
        movieJob?.cancel()
        movieSearchJob?.cancel()
        movieRequestGeneration += 1L
        mutableState.value = mutableState.value.copy(movieSort = sort, movieError = null)
        if (mutableState.value.surface == MediaSurface.MOVIE) loadMovies(reset = true)
    }

    fun setMovieQuery(query: String) {
        if (query == mutableState.value.movieQuery) return
        movieJob?.cancel()
        movieSearchJob?.cancel()
        movieRequestGeneration += 1L
        mutableState.value = mutableState.value.copy(
            movieQuery = query,
            movieError = null,
            movieLoading = false,
            movieGroupsLoading = false,
        )
        // Clearing the box returns to the wall that is already loaded: refetching
        // it would drop the sections the user had expanded.
        if (query.isBlank() && !movieCatalogDirty && mutableState.value.movieGroups.isNotEmpty()) {
            // The header counts the whole library, so it cannot keep the number
            // the search left behind.
            val groups = mutableState.value.movieGroups
            mutableState.value = mutableState.value.copy(movieTotal = groups.sumOf { it.total })
            return
        }
        movieSearchJob = viewModelScope.launch {
            delay(320)
            if (mutableState.value.surface == MediaSurface.MOVIE) loadMovies(reset = true)
        }
    }

    fun retryMovies() = loadMovies(reset = true)

    fun loadMoreMovies() {
        val state = mutableState.value
        if (
            state.surface == MediaSurface.MOVIE &&
            state.movieQuery.isNotBlank() &&
            !state.movieLoading &&
            state.movieNextOffset != null
        ) {
            loadMovies(reset = false)
        }
    }

    /**
     * Opens one library on its own page.
     *
     * "加载更多" used to append pages in place, which buried the rest of the
     * wall once a library held thousands of titles. The library now gets a page
     * with its own cursor and sort, and the wall keeps whatever it had loaded.
     */
    fun openMovieGroup(sourceId: String) {
        val state = mutableState.value
        if (state.surface != MediaSurface.MOVIE) return
        val group = state.movieGroups.firstOrNull { it.sourceId == sourceId } ?: return
        cancelMovieGroupPageLoad()
        mutableState.value = state.copy(
            openMovieGroupId = sourceId,
            openMovieGroupName = group.name,
            openMovieGroupItems = emptyList(),
            openMovieGroupTotal = group.total,
            openMovieGroupNextOffset = 0,
            openMovieGroupLoading = true,
            openMovieGroupError = null,
            openMovieGroupSort = DEFAULT_MOVIE_SORT,
        )
        loadMovieGroupPage(reset = true)
    }

    fun closeMovieGroup() {
        if (mutableState.value.surface != MediaSurface.MOVIE) return
        cancelMovieGroupPageLoad()
        movieGroupPageGeneration += 1L
        mutableState.value = mutableState.value.copy(
            openMovieGroupId = null,
            openMovieGroupName = "",
            openMovieGroupItems = emptyList(),
            openMovieGroupTotal = 0,
            openMovieGroupNextOffset = 0,
            openMovieGroupLoading = false,
            openMovieGroupError = null,
        )
    }

    fun setMovieGroupSort(sort: String) {
        val state = mutableState.value
        if (state.openMovieGroupId == null || state.openMovieGroupSort == sort) return
        mutableState.value = state.copy(
            openMovieGroupSort = sort,
            openMovieGroupItems = emptyList(),
            openMovieGroupNextOffset = 0,
            openMovieGroupError = null,
        )
        loadMovieGroupPage(reset = true)
    }

    fun loadMoreMovieGroup() {
        val state = mutableState.value
        if (state.openMovieGroupId == null || state.openMovieGroupLoading) return
        if (state.openMovieGroupNextOffset == null) return
        loadMovieGroupPage(reset = false)
    }

    private fun loadMovieGroupPage(reset: Boolean) {
        val state = mutableState.value
        if (state.surface != MediaSurface.MOVIE) return
        val sourceId = state.openMovieGroupId ?: return
        val offset = if (reset) 0 else state.openMovieGroupNextOffset ?: return
        val requestedSort = state.openMovieGroupSort
        cancelMovieGroupPageLoad()
        val generation = ++movieGroupPageGeneration
        mutableState.value = state.copy(openMovieGroupLoading = true, openMovieGroupError = null)
        movieGroupPageJob = viewModelScope.launch {
            runApi {
                api.movieGroupItems(
                    sourceId = sourceId,
                    offset = offset,
                    limit = MediaApi.MOVIE_LIBRARY_PAGE_SIZE,
                    sort = requestedSort,
                )
            }
                .onSuccess { page ->
                    val current = mutableState.value
                    if (generation != movieGroupPageGeneration || current.openMovieGroupId != sourceId) {
                        return@onSuccess
                    }
                    val incoming = page.items.map { it.withResumePosition() }
                    val merged = mergeMoviePages(current.openMovieGroupItems, incoming, reset = reset)
                    mutableState.value = current.copy(
                        openMovieGroupItems = merged,
                        openMovieGroupTotal = maxOf(page.total, merged.size),
                        openMovieGroupNextOffset = page.nextOffset,
                        openMovieGroupLoading = false,
                        openMovieGroupError = null,
                    )
                }
                .onFailure { error ->
                    val current = mutableState.value
                    if (generation != movieGroupPageGeneration || current.openMovieGroupId != sourceId) {
                        return@onFailure
                    }
                    if (!handleUnauthorized(error)) {
                        mutableState.value = current.copy(
                            openMovieGroupLoading = false,
                            openMovieGroupError = "这个媒体库暂时载入不了，稍后再试",
                        )
                    }
                }
        }
    }

    private fun cancelMovieGroupPageLoad() {
        movieGroupPageJob?.cancel()
        movieGroupPageJob = null
    }

    fun selectMovie(movie: MovieItem) {
        if (mutableState.value.surface != MediaSurface.MOVIE) return
        val localMovie = movie.withResumePosition()
        mutableState.value = mutableState.value.copy(
            selectedMovie = localMovie,
            movieDetailLoading = true,
            movieError = null,
            nowPlaying = null,
        )
        playback.prefetch(MediaSurface.MOVIE, listOf(localMovie.asMediaEntry()))
        movieDetailJob?.cancel()
        movieDetailJob = viewModelScope.launch {
            runApi { api.movieDetail(movie.id) }
                .onSuccess { detail ->
                    val state = mutableState.value
                    if (state.surface != MediaSurface.MOVIE || state.selectedMovie?.id != movie.id) return@onSuccess
                    val resolved = detail.withResumePosition()
                    mutableState.value = state.copy(
                        selectedMovie = resolved,
                        movieItems = state.movieItems.map { if (it.id == resolved.id) resolved else it },
                        movieGroups = state.movieGroups.map { it.withItem(resolved) },
                        movieDetailLoading = false,
                    )
                }
                .onFailure { error ->
                    if (!handleUnauthorized(error)) {
                        val state = mutableState.value
                        if (state.surface == MediaSurface.MOVIE && state.selectedMovie?.id == movie.id) {
                            mutableState.value = state.copy(movieDetailLoading = false)
                        }
                    }
                }
        }
    }

    fun closeMovieDetail() {
        if (mutableState.value.surface != MediaSurface.MOVIE) return
        saveCurrentPosition()
        movieDetailJob?.cancel()
        playback.stop()
        playback.prefetch(MediaSurface.MOVIE, emptyList())
        mutableState.value = mutableState.value.copy(
            selectedMovie = null,
            movieDetailLoading = false,
            nowPlaying = null,
        )
    }

    fun playMovie(movie: MovieItem) {
        if (mutableState.value.surface != MediaSurface.MOVIE) return
        saveCurrentPosition()
        val resolved = movie.withResumePosition()
        val entry = resolved.asMediaEntry()
        preferences.setLastVideo(MediaSurface.MOVIE, entry.id)
        mutableState.value = mutableState.value.copy(
            selectedMovie = resolved,
            nowPlaying = entry,
            movieError = null,
        )
        playback.cancelBackgroundPrefetch()
        if (!playback.play(entry, resolved.resumePositionMs)) {
            mutableState.value = mutableState.value.copy(
                nowPlaying = null,
                movieError = "无法准备该电影",
            )
        }
    }

    fun setDramaQuery(query: String) {
        if (query == mutableState.value.dramaQuery) return
        dramaJob?.cancel()
        dramaSearchJob?.cancel()
        dramaRequestGeneration += 1L
        mutableState.value = mutableState.value.copy(dramaQuery = query, dramaError = null)
        dramaSearchJob = viewModelScope.launch {
            delay(320)
            if (mutableState.value.surface == MediaSurface.DRAMA) loadDramas(reset = true)
        }
    }

    fun retryDramas() = loadDramas(reset = true)

    fun loadMoreDramas() {
        val state = mutableState.value
        if (
            state.surface == MediaSurface.DRAMA &&
            !state.dramaLoading &&
            state.dramaNextOffset != null
        ) {
            loadDramas(reset = false)
        }
    }

    fun selectDrama(drama: DramaItem) {
        if (mutableState.value.surface != MediaSurface.DRAMA) return
        mutableState.value = mutableState.value.copy(
            selectedDrama = DramaDetail(item = drama, episodes = emptyList()),
            dramaDetailLoading = true,
            dramaError = null,
            nowPlaying = null,
        )
        dramaDetailJob?.cancel()
        dramaDetailJob = viewModelScope.launch {
            runApi { api.dramaDetail(drama.id) }
                .onSuccess { detail ->
                    val state = mutableState.value
                    if (state.surface != MediaSurface.DRAMA || state.selectedDrama?.item?.id != drama.id) {
                        return@onSuccess
                    }
                    mutableState.value = state.copy(
                        selectedDrama = detail.copy(item = detail.item.withAbsolutePoster()),
                        dramaDetailLoading = false,
                    )
                    // The first episode is the likeliest next tap, so warm it while
                    // the user is still reading the synopsis.
                    detail.episodes.firstOrNull()?.let { first ->
                        playback.prefetch(MediaSurface.DRAMA, listOf(first.asMediaEntry(detail.item)))
                    }
                }
                .onFailure { error ->
                    if (!handleUnauthorized(error)) {
                        val state = mutableState.value
                        if (state.surface == MediaSurface.DRAMA && state.selectedDrama?.item?.id == drama.id) {
                            mutableState.value = state.copy(
                                dramaDetailLoading = false,
                                dramaError = "无法载入分集列表",
                            )
                        }
                    }
                }
        }
    }

    /** Episode the user should continue from, or null when nothing was watched. */
    fun dramaResumeEpisode(drama: DramaItem, episodes: List<DramaEpisode>): DramaEpisode? {
        if (episodes.isEmpty()) return null
        val savedId = preferences.dramaEpisodeId(drama.id) ?: return null
        return episodes.firstOrNull { it.videoId == savedId }
    }

    /** Leave playback but stay on the series page. */
    fun stopDramaPlayback() {
        if (mutableState.value.surface != MediaSurface.DRAMA) return
        saveCurrentPosition()
        playback.stop()
        playback.prefetch(MediaSurface.DRAMA, emptyList())
        stopPlaybackService()
        mutableState.value = mutableState.value.copy(nowPlaying = null)
    }

    fun playDramaEpisode(drama: DramaItem, episode: DramaEpisode) {
        if (mutableState.value.surface != MediaSurface.DRAMA) return
        saveCurrentPosition()
        val entry = episode.asMediaEntry(drama)
        preferences.setLastVideo(MediaSurface.DRAMA, entry.id)
        preferences.setDramaEpisodeId(drama.id, entry.id)
        mutableState.value = mutableState.value.copy(
            nowPlaying = entry,
            dramaError = null,
        )
        playback.cancelBackgroundPrefetch()
        val queue = mutableState.value.selectedDrama?.episodes
            ?.map { it.asMediaEntry(drama) }
            .orEmpty()
        if (!playback.play(entry, preferences.position(entry.id), queue = queue)) {
            mutableState.value = mutableState.value.copy(
                nowPlaying = null,
                dramaError = "无法准备该剧集",
            )
        }
    }

    fun closeDramaDetail() {
        if (mutableState.value.surface != MediaSurface.DRAMA) return
        saveCurrentPosition()
        dramaDetailJob?.cancel()
        playback.stop()
        playback.prefetch(MediaSurface.DRAMA, emptyList())
        stopPlaybackService()
        mutableState.value = mutableState.value.copy(
            selectedDrama = null,
            dramaDetailLoading = false,
            nowPlaying = null,
        )
    }

    fun showManagement() {
        logs.info("management_open", "surface=${mutableState.value.surface} media=${mutableState.value.nowPlaying?.id}")
        if (!keepCurrentAsmrPlaybackInBackground()) {
            playback.pause()
            stopPlaybackService()
        }
        mutableState.value = mutableState.value.copy(
            showManagement = true,
            runtimeLogText = logs.readText(),
        )
        loadAdminStatus()
    }

    fun hideManagement() {
        logs.info("management_close", "surface=${mutableState.value.surface}")
        mutableState.value = mutableState.value.copy(showManagement = false)
        refreshPendingFeedLibrary()
    }

    fun showRuntimeLogs() {
        mutableState.value = mutableState.value.copy(
            showRuntimeLogs = true,
            runtimeLogText = logs.readText(),
        )
    }

    fun dismissRuntimeLogs() {
        mutableState.value = mutableState.value.copy(showRuntimeLogs = false)
    }

    fun clearRuntimeLogs() {
        logs.clear()
        logs.info("logs_cleared")
        mutableState.value = mutableState.value.copy(runtimeLogText = logs.readText())
    }

    fun loadAdminStatus() {
        if (mutableState.value.adminLoading) return
        mutableState.value = mutableState.value.copy(adminLoading = true, adminError = null)
        viewModelScope.launch {
            runApi { api.adminStatus() }
                .onSuccess {
                    mutableState.value = mutableState.value.copy(adminStatus = it, adminLoading = false)
                }
                .onFailure { error ->
                    if (!handleUnauthorized(error)) {
                        mutableState.value = mutableState.value.copy(
                            adminLoading = false,
                            adminError = "无法载入管理状态",
                        )
                    }
                }
        }
    }

    fun startLibraryScan(sourceId: String) {
        val action = "scan-$sourceId"
        if (action in mutableState.value.adminActions) return
        val previous = mutableState.value.adminStatus
            ?.sources
            ?.firstOrNull { it.id == sourceId }
            ?.scan
        mutableState.update {
            it.copy(adminActions = it.adminActions + action, adminError = null)
        }
        viewModelScope.launch {
            val start = runApi { api.startScan(sourceId) }
            if (start.isFailure) {
                finishScanAction(action, start.exceptionOrNull())
                return@launch
            }
            monitorSourceScan(
                action = action,
                sourceId = sourceId,
                previous = previous,
                scanWasAccepted = start.getOrDefault(false),
            )
        }
    }

    fun startMovieMetadata(sourceId: String) {
        val action = "metadata-$sourceId"
        val state = mutableState.value
        if (action in state.adminActions || state.adminStatus?.movieMetadata?.running == true) return
        mutableState.update {
            it.copy(adminActions = it.adminActions + action, adminError = null)
        }
        viewModelScope.launch {
            val start = runApi { api.startMovieMetadata(sourceId) }
            if (start.isFailure) {
                finishMovieMetadataAction(action, start.exceptionOrNull())
                return@launch
            }
            if (!start.getOrDefault(false)) {
                finishMovieMetadataAction(
                    action,
                    IllegalStateException("已有电影刮削任务正在运行"),
                )
                loadAdminStatus()
                return@launch
            }
            monitorMovieMetadata(action, sourceId)
        }
    }

    fun startDramaMetadata(sourceId: String) {
        val action = "drama-metadata-$sourceId"
        val state = mutableState.value
        if (action in state.adminActions || state.adminStatus?.dramaMetadata?.running == true) return
        mutableState.update {
            it.copy(adminActions = it.adminActions + action, adminError = null)
        }
        viewModelScope.launch {
            val start = runApi { api.startDramaMetadata(sourceId) }
            if (start.isFailure) {
                finishDramaMetadataAction(action, start.exceptionOrNull())
                return@launch
            }
            if (!start.getOrDefault(false)) {
                finishDramaMetadataAction(
                    action,
                    IllegalStateException("已有短剧刮削任务正在运行"),
                )
                loadAdminStatus()
                return@launch
            }
            monitorDramaMetadata(action, sourceId)
        }
    }

    fun saveMediaSource(sourceId: String?, draft: MediaSourceDraft) =
        runAdminAction("source-${sourceId ?: "new"}") {
            api.saveMediaSource(sourceId, draft)
        }

    fun deleteMediaSource(sourceId: String) {
        val source = mutableState.value.adminStatus?.sources?.firstOrNull { it.id == sourceId } ?: return
        val action = "delete-source-$sourceId"
        if (action in mutableState.value.adminActions) return
        mutableState.update { it.copy(adminActions = it.adminActions + action, adminError = null) }
        viewModelScope.launch {
            runApi { api.deleteMediaSource(sourceId) }
                .onSuccess {
                    if (source.section == LibrarySection.ASMR) {
                        playback.stop()
                        stopPlaybackService()
                        mutableState.update { it.copy(nowPlaying = null, expandedMedia = null) }
                    }
                    when (source.section) {
                        LibrarySection.FEED -> feedLibraryRefresh.markPending()
                        LibrarySection.ASMR -> refreshAsmrLibrary()
                        LibrarySection.MOVIE -> refreshMovieLibrary()
                        LibrarySection.DRAMA -> refreshDramaLibrary()
                    }
                    mutableState.update { it.copy(adminActions = it.adminActions - action) }
                    loadAdminStatus()
                }
                .onFailure { error ->
                    if (!handleUnauthorized(error)) {
                        mutableState.update {
                            it.copy(
                                adminActions = it.adminActions - action,
                                adminError = error.message ?: "删除媒体源失败",
                            )
                        }
                    }
                }
        }
    }

    fun startFastStartCheck() = runAdminAction("fast-start") { api.startFastStartCheck() }

    fun checkForAppUpdate() {
        appUpdateJob?.cancel()
        appUpdateObserverJob?.cancel()
        mutableState.value = mutableState.value.copy(
            appUpdate = AppUpdateUiState(
                phase = AppUpdatePhase.CHECKING,
                currentVersionName = BuildConfig.VERSION_NAME,
            ),
        )
        appUpdateJob = viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { api.appUpdate() }
                if (!isUpdateAvailable(BuildConfig.VERSION_CODE, info.versionCode)) {
                    mutableState.value = mutableState.value.copy(
                        appUpdate = AppUpdateUiState(
                            phase = AppUpdatePhase.LATEST,
                            currentVersionName = BuildConfig.VERSION_NAME,
                            info = info,
                        ),
                    )
                    return@launch
                }
                val target = AppUpdateWork.targetFile(getApplication(), info)
                val targetReady = withContext(Dispatchers.IO) {
                    target.isFile && target.length() == info.size && sha256Matches(target, info.sha256)
                }
                val partialBytes = AppUpdateWork.partialFile(getApplication(), info)
                    .length()
                    .coerceAtMost(info.size)
                val activeWork = if (targetReady) null else withContext(Dispatchers.IO) {
                    activeAppUpdateWork(info)
                }
                mutableState.value = mutableState.value.copy(
                    appUpdate = AppUpdateUiState(
                        phase = when {
                            targetReady -> AppUpdatePhase.READY
                            activeWork != null -> AppUpdatePhase.DOWNLOADING
                            else -> AppUpdatePhase.AVAILABLE
                        },
                        currentVersionName = BuildConfig.VERSION_NAME,
                        info = info,
                        progress = when {
                            targetReady -> 1f
                            activeWork != null -> workDownloadedBytes(activeWork, partialBytes)
                                .toFloat() / info.size
                            else -> partialBytes.toFloat() / info.size
                        }.coerceIn(0f, 1f),
                        downloadedBytes = if (targetReady) info.size else activeWork?.let {
                            workDownloadedBytes(it, partialBytes)
                        } ?: partialBytes,
                        downloadedApkPath = target.absolutePath.takeIf { targetReady },
                    ),
                )
                activeWork?.let { observeAppUpdateWork(info, it.id) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!handleUnauthorized(error)) {
                    mutableState.value = mutableState.value.copy(
                        appUpdate = mutableState.value.appUpdate.copy(
                            phase = AppUpdatePhase.ERROR,
                            message = appUpdateErrorMessage(error),
                        ),
                    )
                }
            }
        }
    }

    fun downloadAppUpdate() {
        val update = mutableState.value.appUpdate.info ?: return
        appUpdateJob?.cancel()
        appUpdateObserverJob?.cancel()
        val target = AppUpdateWork.targetFile(getApplication(), update)
        if (target.isFile && target.length() == update.size && sha256Matches(target, update.sha256)) {
            mutableState.value = mutableState.value.copy(
                appUpdate = mutableState.value.appUpdate.copy(
                    phase = AppUpdatePhase.READY,
                    progress = 1f,
                    downloadedBytes = update.size,
                    downloadedApkPath = target.absolutePath,
                    message = null,
                ),
            )
            return
        }
        val retainedBytes = AppUpdateWork.partialFile(getApplication(), update)
            .length()
            .coerceAtMost(update.size)
        mutableState.value = mutableState.value.copy(
            appUpdate = mutableState.value.appUpdate.copy(
                phase = AppUpdatePhase.DOWNLOADING,
                progress = (retainedBytes.toFloat() / update.size).coerceIn(0f, 1f),
                downloadedBytes = retainedBytes,
                downloadedApkPath = null,
                message = null,
            ),
        )
        appUpdateJob = viewModelScope.launch {
            try {
                val workId = withContext(Dispatchers.IO) {
                    activeAppUpdateWork(update)?.id ?: run {
                        val request = AppUpdateWork.request(update)
                        workManager.enqueueUniqueWork(
                            AppUpdateWork.uniqueName(update.versionCode),
                            ExistingWorkPolicy.KEEP,
                            request,
                        ).result.get()
                        activeAppUpdateWork(update)?.id ?: request.id
                    }
                }
                logs.info("app_update_background_enqueued", "version=${update.versionCode} retained=$retainedBytes")
                observeAppUpdateWork(update, workId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!handleUnauthorized(error)) {
                    mutableState.value = mutableState.value.copy(
                        appUpdate = mutableState.value.appUpdate.copy(
                            phase = AppUpdatePhase.ERROR,
                            message = appUpdateErrorMessage(error),
                        ),
                    )
                }
            }
        }
    }

    fun dismissAppUpdate() {
        appUpdateJob?.cancel()
        appUpdateJob = null
        appUpdateObserverJob?.cancel()
        appUpdateObserverJob = null
        if (mutableState.value.appUpdate.phase == AppUpdatePhase.DOWNLOADING) {
            logs.info(
                "app_update_ui_dismissed",
                "background=true bytes=${mutableState.value.appUpdate.downloadedBytes}",
            )
        }
        mutableState.value = mutableState.value.copy(appUpdate = AppUpdateUiState())
    }

    private fun activeAppUpdateWork(update: AppUpdateInfo): WorkInfo? =
        workManager.getWorkInfosForUniqueWork(AppUpdateWork.uniqueName(update.versionCode))
            .get()
            .firstOrNull { !it.state.isFinished }

    private fun workDownloadedBytes(workInfo: WorkInfo, fallbackBytes: Long): Long =
        workInfo.progress
            .getLong(AppUpdateWork.KEY_DOWNLOADED_BYTES, -1L)
            .takeIf { it >= 0L }
            ?.coerceAtLeast(fallbackBytes)
            ?: fallbackBytes

    private fun observeAppUpdateWork(update: AppUpdateInfo, workId: java.util.UUID) {
        appUpdateObserverJob?.cancel()
        appUpdateObserverJob = viewModelScope.launch {
            while (isActive) {
                val workInfo = withContext(Dispatchers.IO) {
                    workManager.getWorkInfoById(workId).get()
                } ?: break
                val current = mutableState.value.appUpdate
                if (current.phase == AppUpdatePhase.IDLE || current.info?.versionCode != update.versionCode) break

                val partialBytes = AppUpdateWork.partialFile(getApplication(), update)
                    .length()
                    .coerceAtMost(update.size)
                val downloadedBytes = workDownloadedBytes(workInfo, partialBytes)
                    .coerceIn(0L, update.size)
                when (workInfo.state) {
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.BLOCKED,
                    WorkInfo.State.RUNNING,
                    -> mutableState.update { state ->
                        state.copy(
                            appUpdate = state.appUpdate.copy(
                                phase = AppUpdatePhase.DOWNLOADING,
                                progress = (downloadedBytes.toFloat() / update.size).coerceIn(0f, 1f),
                                downloadedBytes = downloadedBytes,
                                downloadedApkPath = null,
                                message = null,
                            ),
                        )
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        val target = AppUpdateWork.targetFile(getApplication(), update)
                        val verified = withContext(Dispatchers.IO) {
                            target.isFile && target.length() == update.size && sha256Matches(target, update.sha256)
                        }
                        mutableState.update { state ->
                            state.copy(
                                appUpdate = state.appUpdate.copy(
                                    phase = if (verified) AppUpdatePhase.READY else AppUpdatePhase.ERROR,
                                    progress = if (verified) 1f else state.appUpdate.progress,
                                    downloadedBytes = if (verified) update.size else partialBytes,
                                    downloadedApkPath = target.absolutePath.takeIf { verified },
                                    message = if (verified) null else "安装包校验失败，请继续下载",
                                ),
                            )
                        }
                    }
                    WorkInfo.State.FAILED,
                    WorkInfo.State.CANCELLED,
                    -> mutableState.update { state ->
                        state.copy(
                            appUpdate = state.appUpdate.copy(
                                phase = AppUpdatePhase.ERROR,
                                progress = (partialBytes.toFloat() / update.size).coerceIn(0f, 1f),
                                downloadedBytes = partialBytes,
                                downloadedApkPath = null,
                                message = workInfo.outputData.getString(AppUpdateWork.KEY_ERROR)
                                    ?: "下载暂时中断，已保留进度，可继续下载",
                            ),
                        )
                    }
                }
                if (workInfo.state.isFinished) break
                delay(400)
            }
        }
    }

    fun reportAppUpdateInstallError(message: String) {
        mutableState.value = mutableState.value.copy(
            appUpdate = mutableState.value.appUpdate.copy(
                phase = AppUpdatePhase.ERROR,
                message = message,
            ),
        )
    }

    fun saveCurrentPosition() {
        val snapshot = playback.snapshot.value
        snapshot.mediaId?.let {
            preferences.savePosition(it, snapshot.positionMs, snapshot.durationMs)
        }
        if (mutableState.value.surface == MediaSurface.MOVIE) updateMovieResumePosition()
    }

    internal fun asmrAuthorListPosition(): ListPosition = preferences.asmrAuthorListPosition()

    internal fun asmrMediaListPosition(author: String): ListPosition =
        preferences.asmrMediaListPosition(author)

    internal fun saveAsmrAuthorListPosition(position: ListPosition) {
        preferences.saveAsmrAuthorListPosition(position)
    }

    internal fun saveAsmrMediaListPosition(author: String, position: ListPosition) {
        preferences.saveAsmrMediaListPosition(author, position)
    }

    internal fun movieListPosition(): ListPosition = preferences.movieListPosition()

    internal fun saveMovieListPosition(position: ListPosition) {
        preferences.saveMovieListPosition(position)
    }

    internal fun dramaListPosition(): ListPosition = preferences.dramaListPosition()

    internal fun saveDramaListPosition(position: ListPosition) {
        preferences.saveDramaListPosition(position)
    }

    private fun persistCurrentFeedSession(
        playWhenReady: Boolean = playback.snapshot.value.playWhenReady,
    ) {
        val state = mutableState.value
        if (!state.surface.isFeed || state.feedItems.isEmpty()) return
        val activeId = state.feedItems.getOrNull(state.activeIndex)?.id ?: state.nowPlaying?.id
        val session = FeedSessionState(
            items = state.feedItems,
            activeMediaId = activeId,
            nextCursor = feedCursor,
            total = state.feedTotal,
            playWhenReady = playWhenReady,
            excludedIds = feedExcludedIds,
            startId = feedStartId,
        )
        feedSessions.save(state.surface, state.mode, session)
        preferences.saveFeedSession(state.surface, state.mode, session)
    }

    private fun persistAsmrPlaybackState() {
        val state = mutableState.value
        val entry = state.nowPlaying ?: return
        preferences.saveAsmrPlaybackState(
            AsmrPlaybackState(
                entry = entry,
                playWhenReady = playback.snapshot.value.playWhenReady,
                expanded = state.expandedMedia?.id == entry.id,
            ),
        )
    }

    fun onAppBackgrounded() {
        if (!keepCurrentAsmrPlaybackInBackground()) {
            playback.pause()
            stopPlaybackService()
        }
    }

    private fun restoreCurrentSurface() {
        val surface = mutableState.value.surface
        when {
            surface.isFeed -> restoreFeed(surface)
            surface == MediaSurface.ASMR -> restoreAsmrSurface()
            surface == MediaSurface.MOVIE -> restoreMovieSurface()
            surface == MediaSurface.DRAMA -> restoreDramaSurface()
        }
    }

    private fun restoreAsmrSurface() {
        loadAsmrAuthors()
        val author = mutableState.value.selectedAuthor
        if (author != null) {
            val key = AsmrItemsKey(author, mutableState.value.asmrFilter)
            val cached = asmrItemsCache[key].orEmpty()
            mutableState.value = mutableState.value.copy(
                asmrItems = cached,
                asmrTotal = asmrItemsTotals[key] ?: cached.size,
                asmrItemsHasMore = asmrItemsNextOffsets[key] != null,
            )
            if (!asmrItemsCache.containsKey(key)) loadAsmrItems(key, reset = true)
        }
        val resume = preferences.asmrPlaybackState() ?: return
        val queueFilter = if (resume.entry.isAudio) AsmrFilter.AUDIO else AsmrFilter.VIDEO
        val queueKey = resume.entry.author?.let { AsmrItemsKey(it, queueFilter) }
        asmrQueueKey = queueKey
        asmrQueue = asmrPlaybackQueue(
            queueKey?.let { asmrItemsCache[it].orEmpty() }.orEmpty(),
            resume.entry,
        )
        pendingAsmrAdvanceId = null
        if (queueKey != null && !asmrItemsCache.containsKey(queueKey)) {
            loadAsmrItems(queueKey, reset = true)
        }
        mutableState.value = mutableState.value.copy(
            nowPlaying = resume.entry,
            expandedMedia = resume.entry.takeIf { !it.isAudio && resume.expanded },
        )
        val restored = playback.play(
            resume.entry,
            preferences.position(resume.entry.id),
            autoPlay = resume.playWhenReady,
            queue = asmrQueue,
        )
        if (!restored) {
            preferences.saveAsmrPlaybackState(null)
            mutableState.value = mutableState.value.copy(
                asmrError = "无法恢复上次播放",
                nowPlaying = null,
                expandedMedia = null,
            )
            return
        }
        if (resume.playWhenReady && backgroundPlaybackEnabled(resume.entry)) startPlaybackService()
    }

    private fun restoreMovieSurface() {
        mutableState.value = mutableState.value.copy(
            selectedMovie = null,
            movieDetailLoading = false,
            nowPlaying = null,
            movieError = null,
        )
        val catalogEmpty = mutableState.value.movieGroups.isEmpty() &&
            mutableState.value.movieItems.isEmpty()
        if (movieCatalogDirty || catalogEmpty) {
            loadMovies(reset = true)
        }
    }

    private fun restoreDramaSurface() {
        mutableState.value = mutableState.value.copy(
            selectedDrama = null,
            dramaDetailLoading = false,
            nowPlaying = null,
            dramaError = null,
        )
        if (dramaCatalogDirty || mutableState.value.dramaItems.isEmpty()) {
            loadDramas(reset = true)
        }
    }

    private fun loadDramas(reset: Boolean) {
        val state = mutableState.value
        if (state.surface != MediaSurface.DRAMA) return
        val offset = if (reset) 0 else state.dramaNextOffset ?: return
        if (reset) dramaJob?.cancel() else if (state.dramaLoading) return
        val requestedQuery = state.dramaQuery.trim()
        val requestGeneration = ++dramaRequestGeneration
        mutableState.value = state.copy(dramaLoading = true, dramaError = null)
        dramaJob = viewModelScope.launch {
            runApi { api.dramas(query = requestedQuery, offset = offset) }
                .onSuccess { page ->
                    val current = mutableState.value
                    if (
                        current.surface != MediaSurface.DRAMA ||
                        requestGeneration != dramaRequestGeneration ||
                        requestedQuery != current.dramaQuery.trim()
                    ) return@onSuccess
                    val incoming = page.items.map { it.withAbsolutePoster() }
                    val ids = if (reset) mutableSetOf() else current.dramaItems.mapTo(mutableSetOf()) { it.id }
                    val merged = if (reset) incoming else current.dramaItems + incoming.filter { ids.add(it.id) }
                    dramaCatalogDirty = false
                    mutableState.value = current.copy(
                        dramaItems = merged,
                        dramaTotal = maxOf(page.total, merged.size),
                        dramaNextOffset = page.nextOffset,
                        dramaLoading = false,
                        dramaError = null,
                    )
                    if (reset && page.items.isEmpty() && page.scanRunning) {
                        delay(1_500)
                        loadDramas(reset = true)
                    }
                }
                .onFailure { error ->
                    val current = mutableState.value
                    if (
                        current.surface != MediaSurface.DRAMA ||
                        requestGeneration != dramaRequestGeneration
                    ) return@onFailure
                    if (!handleUnauthorized(error)) {
                        mutableState.value = current.copy(
                            dramaLoading = false,
                            dramaError = "短剧目录暂时无法载入",
                        )
                    }
                }
        }
    }

    private fun DramaItem.withAbsolutePoster(): DramaItem =
        copy(posterUrl = posterUrl?.let(api::absoluteUrl))

    private fun loadMovies(reset: Boolean) {
        val state = mutableState.value
        if (state.surface != MediaSurface.MOVIE) return
        // An empty query is the browsable wall (grouped by media source); a
        // query is a flat lookup across every library.
        if (state.movieQuery.isBlank()) {
            loadMovieGroups(reset)
            return
        }
        val offset = if (reset) 0 else state.movieNextOffset ?: return
        if (reset) movieJob?.cancel() else if (state.movieLoading) return
        val requestedQuery = state.movieQuery.trim()
        val requestGeneration = ++movieRequestGeneration
        val requestedSort = state.movieSort
        mutableState.value = state.copy(movieLoading = true, movieError = null)
        movieJob = viewModelScope.launch {
            runApi { api.movies(query = requestedQuery, offset = offset, sort = requestedSort) }
                .onSuccess { page ->
                    val current = mutableState.value
                    if (
                        !shouldApplyMovieResponse(
                            requestGeneration = requestGeneration,
                            currentGeneration = movieRequestGeneration,
                            requestedQuery = requestedQuery,
                            currentQuery = current.movieQuery.trim(),
                            requestedSort = requestedSort,
                            currentSort = current.movieSort,
                            currentSurface = current.surface,
                        )
                    ) return@onSuccess
                    val incoming = page.items.map { it.withResumePosition() }
                    val merged = mergeMoviePages(current.movieItems, incoming, reset)
                    movieCatalogDirty = false
                    mutableState.value = current.copy(
                        movieItems = merged,
                        movieTotal = maxOf(page.total, merged.size),
                        movieNextOffset = page.nextOffset,
                        movieLoading = false,
                        movieError = null,
                    )
                    if (reset && page.items.isEmpty() && page.scanRunning) {
                        delay(1_500)
                        loadMovies(reset = true)
                    }
                }
                .onFailure { error ->
                    val current = mutableState.value
                    if (
                        !shouldApplyMovieResponse(
                            requestGeneration = requestGeneration,
                            currentGeneration = movieRequestGeneration,
                            requestedQuery = requestedQuery,
                            currentQuery = current.movieQuery.trim(),
                            requestedSort = requestedSort,
                            currentSort = current.movieSort,
                            currentSurface = current.surface,
                        )
                    ) return@onFailure
                    if (!handleUnauthorized(error)) {
                        mutableState.value = current.copy(
                            movieLoading = false,
                            movieError = "电影目录暂时无法载入",
                        )
                    }
                }
        }
    }

    private fun MovieItem.withResumePosition(): MovieItem =
        copy(
            posterUrl = posterUrl?.let(api::absoluteUrl),
            backdropUrl = backdropUrl?.let(api::absoluteUrl),
            wallUrl = wallUrl?.let(api::absoluteUrl),
            resumePositionMs = preferences.position(videoId),
        )

    private fun loadMovieGroups(reset: Boolean) {
        val state = mutableState.value
        if (!reset && state.movieGroupsLoading) return
        movieJob?.cancel()
        val requestedSort = state.movieSort
        val requestGeneration = ++movieRequestGeneration
        mutableState.value = state.copy(
            movieGroupsLoading = true,
            movieLoading = true,
            movieError = null,
        )
        movieJob = viewModelScope.launch {
            runApi { api.movieGroups(sort = requestedSort) }
                .onSuccess { page ->
                    val current = mutableState.value
                    if (
                        !shouldApplyMovieResponse(
                            requestGeneration = requestGeneration,
                            currentGeneration = movieRequestGeneration,
                            requestedQuery = "",
                            currentQuery = current.movieQuery.trim(),
                            requestedSort = requestedSort,
                            currentSort = current.movieSort,
                            currentSurface = current.surface,
                        )
                    ) return@onSuccess
                    movieCatalogDirty = false
                    mutableState.value = current.copy(
                        movieGroups = page.groups.map { group ->
                            group.replaceItems(
                                items = group.items.map { it.withResumePosition() },
                                nextOffset = group.nextOffset,
                            )
                        },
                        movieTotal = page.total,
                        movieGroupsLoading = false,
                        movieLoading = false,
                        movieError = null,
                    )
                    if (page.groups.isEmpty() && page.scanRunning) {
                        delay(1_500)
                        loadMovieGroups(reset = true)
                    }
                }
                .onFailure { error ->
                    val current = mutableState.value
                    if (
                        !shouldApplyMovieResponse(
                            requestGeneration = requestGeneration,
                            currentGeneration = movieRequestGeneration,
                            requestedQuery = "",
                            currentQuery = current.movieQuery.trim(),
                            requestedSort = requestedSort,
                            currentSort = current.movieSort,
                            currentSurface = current.surface,
                        )
                    ) return@onFailure
                    if (!handleUnauthorized(error)) {
                        mutableState.value = current.copy(
                            movieGroupsLoading = false,
                            movieLoading = false,
                            movieError = "电影目录暂时无法载入",
                        )
                    }
                }
        }
    }

    private fun updateMovieResumePosition() {
        val state = mutableState.value
        val selected = state.selectedMovie ?: return
        val position = preferences.position(selected.videoId)
        if (position == selected.resumePositionMs) return
        val updated = selected.copy(resumePositionMs = position)
        mutableState.value = state.copy(
            selectedMovie = updated,
            movieItems = state.movieItems.map { if (it.id == updated.id) updated else it },
            movieGroups = state.movieGroups.map { it.withItem(updated) },
        )
    }

    private fun restoreFeed(surface: MediaSurface) {
        require(surface.isFeed) { "Only short and long surfaces can restore a feed" }
        feedJob?.cancel()
        feedTotalJob?.cancel()
        feedRequestGeneration += 1L
        val mode = mutableState.value.mode
        val session = feedSessions.restore(surface, mode)
            ?: preferences.feedSession(surface, mode)?.also { feedSessions.save(surface, mode, it) }
        val items = session?.items.orEmpty()
        val activeIndex = session?.activeIndex()?.coerceIn(0, items.lastIndex.coerceAtLeast(0)) ?: 0
        feedCursor = session?.nextCursor
        feedExcludedIds = session?.excludedIds.orEmpty()
        feedStartId = session?.startId
        mutableState.value = mutableState.value.copy(
            feedItems = items,
            activeIndex = activeIndex,
            feedTotal = session?.total ?: 0,
            feedLoading = items.isEmpty(),
            feedError = null,
        )
        if (items.isNotEmpty()) {
            activateFeedItem(activeIndex, autoPlay = session?.playWhenReady ?: true)
            refreshFeedTotal(surface)
        } else {
            requestFeed(reset = true)
        }
    }

    private fun requestFeed(reset: Boolean) {
        val requestedSurface = mutableState.value.surface
        if (!requestedSurface.isFeed) return
        val requestedMode = mutableState.value.mode
        if (reset) feedJob?.cancel() else if (mutableState.value.feedLoading) return
        val currentItems = mutableState.value.feedItems
        if (reset) {
            feedExcludedIds = if (requestedMode == FeedMode.SHUFFLE) preferences.recentVideoIds() else emptyList()
            feedStartId = preferences.lastVideoId(requestedSurface)
        } else if (feedCursor == null) {
            feedExcludedIds = if (requestedMode == FeedMode.SHUFFLE) {
                (currentItems.map(MediaEntry::id) + preferences.recentVideoIds()).distinct().take(100)
            } else {
                emptyList()
            }
            feedStartId = null
        }
        val requestedExcludedIds = feedExcludedIds
        val requestedStartId = feedStartId
        val requestGeneration = ++feedRequestGeneration
        mutableState.value = mutableState.value.copy(feedLoading = true, feedError = null)
        feedJob = viewModelScope.launch {
            val cursor = if (reset) null else feedCursor
            runApi {
                api.feed(requestedSurface, requestedMode, cursor, requestedExcludedIds, requestedStartId)
            }
                .onSuccess { page ->
                    if (
                        !shouldApplyFeedResponse(
                            requestGeneration = requestGeneration,
                            currentGeneration = feedRequestGeneration,
                            requestedSurface = requestedSurface,
                            currentSurface = mutableState.value.surface,
                            requestedMode = requestedMode,
                            currentMode = mutableState.value.mode,
                        )
                    ) return@onSuccess
                    feedCursor = page.nextCursor
                    val current = mutableState.value.feedItems
                    val merged = if (reset) {
                        if (page.items.isEmpty() && page.scanRunning && current.isNotEmpty()) current else page.items
                    } else {
                        val ids = current.mapTo(mutableSetOf()) { it.id }
                        current + page.items.filter { ids.add(it.id) }
                    }
                    mutableState.value = mutableState.value.copy(
                        feedItems = merged,
                        feedTotal = reconcileFeedTotal(
                            remoteTotal = page.total,
                            currentTotal = mutableState.value.feedTotal,
                            loadedCount = merged.size,
                        ),
                        feedLoading = false,
                    )
                    if (page.items.isNotEmpty()) {
                        val activeId = activeFeedItemId
                        val index = merged.indexOfFirst { it.id == activeId }.takeIf { it >= 0 }
                            ?: mutableState.value.activeIndex.coerceIn(0, merged.lastIndex)
                        mutableState.value = mutableState.value.copy(activeIndex = index)
                        if (reset || activeFeedItemId == null) activateFeedItem(index)
                        persistCurrentFeedSession()
                    } else if (merged.isNotEmpty() && activeFeedItemId == null) {
                        activateFeedItem(0)
                    }
                    if (reset && page.items.isEmpty() && page.scanRunning) {
                        delay(1_600)
                        requestFeed(reset = true)
                    }
                }
                .onFailure { error ->
                    if (
                        !shouldApplyFeedResponse(
                            requestGeneration = requestGeneration,
                            currentGeneration = feedRequestGeneration,
                            requestedSurface = requestedSurface,
                            currentSurface = mutableState.value.surface,
                            requestedMode = requestedMode,
                            currentMode = mutableState.value.mode,
                        )
                    ) return@onFailure
                    if (!handleUnauthorized(error)) {
                        mutableState.value = mutableState.value.copy(
                            feedLoading = false,
                            feedError = if (mutableState.value.feedItems.isEmpty()) "无法载入视频列表" else null,
                        )
                    }
                }
        }
    }

    private fun refreshFeedTotal(surface: MediaSurface) {
        if (!surface.isFeed) return
        val requestedMode = mutableState.value.mode
        feedTotalJob?.cancel()
        feedTotalJob = viewModelScope.launch {
            runApi { api.feedTotal(surface) }
                .onSuccess { remoteTotal ->
                    val state = mutableState.value
                    if (state.surface != surface || state.mode != requestedMode) return@onSuccess
                    val total = reconcileFeedTotal(remoteTotal, state.feedTotal, state.feedItems.size)
                    if (total != state.feedTotal) {
                        mutableState.value = state.copy(feedTotal = total)
                        persistCurrentFeedSession()
                    }
                    if (feedCursor == null && state.feedItems.size < total) loadMoreFeed()
                }
                .onFailure { error ->
                    logs.error("feed_total_refresh_failed", "surface=$surface", error)
                }
        }
    }

    private fun loadAsmrAuthors() {
        if (!asmrAuthorIndexRestored) {
            asmrAuthorIndexRestored = true
            preferences.asmrAuthorIndex()?.let { cached ->
                mutableState.value = mutableState.value.copy(
                    asmrAuthors = cached.items,
                    asmrAuthorsTotal = cached.total,
                    asmrLoading = false,
                    asmrError = null,
                )
            }
        }
        if (asmrAuthorsLoaded || asmrAuthorsJob?.isActive == true) return
        mutableState.value = mutableState.value.copy(
            asmrLoading = mutableState.value.asmrAuthors.isEmpty(),
            asmrError = null,
        )
        asmrAuthorsJob = viewModelScope.launch {
            val page = runApi { api.asmrAuthors() }.getOrElse {
                if (mutableState.value.asmrAuthors.isEmpty()) {
                    handleAsmrError(it, "无法载入 ASMR 目录")
                } else {
                    logs.error("asmr_authors_refresh_failed", error = it)
                    mutableState.value = mutableState.value.copy(asmrLoading = false)
                }
                return@launch
            }
            asmrAuthorsLoaded = true
            preferences.saveAsmrAuthorIndex(page.items, page.total)
            val selectedAuthor = mutableState.value.selectedAuthor
            val selectedStillExists = selectedAuthor == null || page.items.any { it.name == selectedAuthor }
            if (!selectedStillExists) preferences.selectedAsmrAuthor = null
            mutableState.value = mutableState.value.copy(
                asmrAuthors = page.items,
                asmrAuthorsTotal = page.total,
                selectedAuthor = selectedAuthor.takeIf { selectedStillExists },
                asmrItems = if (selectedStillExists) mutableState.value.asmrItems else emptyList(),
                asmrTotal = if (selectedStillExists) mutableState.value.asmrTotal else 0,
                asmrItemsHasMore = selectedStillExists && mutableState.value.asmrItemsHasMore,
                asmrLoading = false,
                asmrError = null,
            )
            if (page.scanRunning) {
                asmrAuthorsLoaded = false
                delay(1_500)
                asmrAuthorsJob = null
                loadAsmrAuthors()
            }
        }
    }

    private suspend fun monitorSourceScan(
        action: String,
        sourceId: String,
        previous: LibraryScanStatus?,
        scanWasAccepted: Boolean,
    ) {
        var runningWasObserved = previous?.running == true
        while (viewModelScope.isActive) {
            delay(750)
            val statusResult = runApi { api.adminStatus() }
            if (statusResult.isFailure) {
                val error = statusResult.exceptionOrNull()
                if (error != null && handleUnauthorized(error)) return
                logs.warning("source_scan_status_failed", "source=$sourceId")
                delay(1_500)
                continue
            }
            val status = statusResult.getOrThrow()
            mutableState.update {
                it.copy(adminStatus = status, adminLoading = false)
            }
            val source = status.sources.firstOrNull { it.id == sourceId }
            if (source == null) {
                finishScanAction(action, IllegalStateException("媒体源已不存在"))
                return
            }
            when (
                sourceScanOutcome(
                    previous = previous,
                    current = source.scan,
                    scanWasAccepted = scanWasAccepted,
                    runningWasObserved = runningWasObserved,
                )
            ) {
                SourceScanOutcome.WAITING -> Unit
                SourceScanOutcome.RUNNING -> runningWasObserved = true
                SourceScanOutcome.SUCCEEDED -> {
                    mutableState.update { it.copy(adminActions = it.adminActions - action) }
                    refreshLibraryAfterScan(source.section)
                    return
                }
                SourceScanOutcome.FAILED -> {
                    finishScanAction(
                        action,
                        IllegalStateException(source.scan.lastError ?: "扫描失败"),
                    )
                    return
                }
            }
        }
    }

    private suspend fun monitorMovieMetadata(action: String, sourceId: String) {
        while (viewModelScope.isActive) {
            delay(750)
            val statusResult = runApi { api.adminStatus() }
            if (statusResult.isFailure) {
                val error = statusResult.exceptionOrNull()
                if (error != null && handleUnauthorized(error)) return
                logs.warning("movie_metadata_status_failed", "source=$sourceId")
                delay(1_500)
                continue
            }
            val status = statusResult.getOrThrow()
            mutableState.update { it.copy(adminStatus = status, adminLoading = false) }
            val metadata = status.movieMetadata
            if (metadata.sourceId != sourceId) {
                if (metadata.running) continue
                finishMovieMetadataAction(
                    action,
                    IllegalStateException("刮削任务状态已失效"),
                )
                return
            }
            if (metadata.running) continue

            mutableState.update { current ->
                current.copy(
                    adminActions = current.adminActions - action,
                    adminError = metadata.lastError,
                )
            }
            refreshMovieLibrary()
            return
        }
    }

    private suspend fun monitorDramaMetadata(action: String, sourceId: String) {
        while (viewModelScope.isActive) {
            delay(750)
            val statusResult = runApi { api.adminStatus() }
            if (statusResult.isFailure) {
                val error = statusResult.exceptionOrNull()
                if (error != null && handleUnauthorized(error)) return
                logs.warning("drama_metadata_status_failed", "source=$sourceId")
                delay(1_500)
                continue
            }
            val status = statusResult.getOrThrow()
            mutableState.update { it.copy(adminStatus = status, adminLoading = false) }
            val metadata = status.dramaMetadata
            if (metadata.sourceId != sourceId) {
                if (metadata.running) continue
                finishDramaMetadataAction(
                    action,
                    IllegalStateException("刮削任务状态已失效"),
                )
                return
            }
            if (metadata.running) continue

            mutableState.update { current ->
                current.copy(
                    adminActions = current.adminActions - action,
                    adminError = metadata.lastError,
                )
            }
            refreshDramaLibrary()
            return
        }
    }

    private fun finishScanAction(action: String, error: Throwable?) {
        if (error != null && handleUnauthorized(error)) return
        error?.let { logs.error("source_scan_failed", "action=$action", it) }
        mutableState.update {
            it.copy(
                adminActions = it.adminActions - action,
                adminError = error?.message ?: "扫描失败，请稍后重试",
            )
        }
    }

    private fun finishMovieMetadataAction(action: String, error: Throwable?) {
        finishMetadataAction(action, error, "movie_metadata_failed", "电影资料刮削失败，请稍后重试")
    }

    private fun finishDramaMetadataAction(action: String, error: Throwable?) {
        finishMetadataAction(action, error, "drama_metadata_failed", "短剧封面刮削失败，请稍后重试")
    }

    private fun finishMetadataAction(
        action: String,
        error: Throwable?,
        logEvent: String,
        fallback: String,
    ) {
        if (error != null && handleUnauthorized(error)) return
        error?.let { logs.error(logEvent, "action=$action", it) }
        mutableState.update {
            it.copy(
                adminActions = it.adminActions - action,
                adminError = error?.message ?: fallback,
            )
        }
    }

    private fun refreshLibraryAfterScan(section: LibrarySection) {
        when (section) {
            LibrarySection.FEED -> {
                feedSessions.clear()
                preferences.clearFeedSessions()
                feedLibraryRefresh.markPending()
                refreshPendingFeedLibrary()
            }
            LibrarySection.ASMR -> refreshAsmrLibrary()
            LibrarySection.MOVIE -> refreshMovieLibrary()
            LibrarySection.DRAMA -> refreshDramaLibrary()
        }
    }

    private fun refreshPendingFeedLibrary(): Boolean {
        val state = mutableState.value
        if (!feedLibraryRefresh.consumeIfVisible(state.surface, state.showManagement)) return false
        activeFeedItemId = null
        restoreFeed(state.surface)
        return true
    }

    private fun refreshAsmrLibrary() {
        asmrAuthorsJob?.cancel()
        asmrAuthorsJob = null
        asmrAuthorsLoaded = false
        asmrAuthorIndexRestored = false
        preferences.clearAsmrAuthorIndex()

        asmrItemJobs.forEach { (key, job) ->
            asmrItemGenerations[key] = (asmrItemGenerations[key] ?: 0L) + 1L
            job.cancel()
        }
        asmrItemJobs.clear()
        asmrItemsCache.clear()
        asmrItemsNextOffsets.clear()
        asmrItemsTotals.clear()

        val selectedAuthor = mutableState.value.selectedAuthor
        mutableState.value = mutableState.value.copy(
            asmrAuthors = emptyList(),
            asmrAuthorsTotal = 0,
            asmrItems = emptyList(),
            asmrTotal = 0,
            asmrItemsHasMore = false,
            asmrLoading = mutableState.value.surface == MediaSurface.ASMR,
            asmrError = null,
        )
        loadAsmrAuthors()
        selectedAuthor?.let { loadAsmrItems(AsmrItemsKey(it, mutableState.value.asmrFilter), reset = true) }
    }

    private fun refreshMovieLibrary() {
        movieCatalogDirty = true
        if (mutableState.value.surface == MediaSurface.MOVIE) loadMovies(reset = true)
    }

    private fun refreshDramaLibrary() {
        dramaCatalogDirty = true
        if (mutableState.value.surface == MediaSurface.DRAMA) {
            // A rescan can drop or add episodes, so any open detail page is stale.
            mutableState.value = mutableState.value.copy(selectedDrama = null, nowPlaying = null)
            dramaDetailJob?.cancel()
            loadDramas(reset = true)
        }
    }

    private fun loadAsmrItems(key: AsmrItemsKey, reset: Boolean) {
        val generation = if (reset) {
            (asmrItemGenerations[key] ?: 0L) + 1L
        } else {
            asmrItemGenerations[key] ?: 1L
        }
        asmrItemGenerations[key] = generation
        if (reset) {
            asmrItemJobs.remove(key)?.cancel()
            asmrItemsNextOffsets[key] = 0
        } else if (asmrItemJobs[key]?.isActive == true) {
            return
        }
        val offset = asmrItemsNextOffsets[key] ?: return
        val existing = if (reset) emptyList() else asmrItemsCache[key].orEmpty()
        if (isSelectedAsmrKey(key)) {
            mutableState.value = mutableState.value.copy(asmrLoading = true, asmrError = null)
        }
        val job = viewModelScope.launch {
            val page = runApi {
                api.asmrItems(key.author, kind = key.filter.apiValue, offset = offset)
            }.getOrElse {
                if (isSelectedAsmrKey(key)) {
                    handleAsmrError(it, "无法载入作者媒体")
                }
                return@launch
            }
            if (asmrItemGenerations[key] != generation) return@launch
            val ids = existing.mapTo(mutableSetOf()) { it.id }
            val merged = existing + page.items.filter { ids.add(it.id) }
            asmrItemsCache[key] = merged
            asmrItemsNextOffsets[key] = page.nextOffset
            asmrItemsTotals[key] = page.total
            if (isSelectedAsmrKey(key)) {
                mutableState.value = mutableState.value.copy(
                    asmrItems = merged,
                    asmrTotal = page.total,
                    asmrItemsHasMore = page.nextOffset != null,
                    asmrLoading = false,
                    asmrError = null,
                )
            } else if (asmrQueueKey != key) {
                return@launch
            }
            extendAsmrQueue(key, page.items)
        }
        asmrItemJobs[key] = job
        job.invokeOnCompletion {
            if (asmrItemJobs[key] === job) asmrItemJobs.remove(key)
        }
    }

    private fun isSelectedAsmrKey(key: AsmrItemsKey): Boolean {
        val state = mutableState.value
        return state.surface == MediaSurface.ASMR &&
            state.selectedAuthor == key.author &&
            state.asmrFilter == key.filter
    }

    private fun extendAsmrQueue(key: AsmrItemsKey, entries: List<MediaEntry>) {
        if (asmrQueueKey != key) return
        val current = playback.currentMedia() ?: mutableState.value.nowPlaying ?: return
        val previousIds = asmrQueue.mapTo(mutableSetOf()) { it.id }
        val updatedQueue = asmrPlaybackQueue(asmrQueue + entries, current)
        val additions = updatedQueue.filterNot { it.id in previousIds }
        asmrQueue = updatedQueue
        playback.appendQueue(additions)
        val pendingId = pendingAsmrAdvanceId ?: return
        nextAsmrEntry(asmrQueue, pendingId)?.let { next ->
            pendingAsmrAdvanceId = null
            startAsmrPlayback(next, expandVideo = mutableState.value.expandedMedia != null)
        }
    }

    /**
     * One entry point for "the engine moved on to another item".
     *
     * The engine owns the queue, so when an item ends it advances the playlist
     * on its own. Everything the UI shows about the current item therefore has
     * to follow the engine; without this a drama's second episode played while
     * the screen still pointed at the first one and dropped back to the list.
     */
    private fun handleMediaTransition(entry: MediaEntry) {
        when (mutableState.value.surface) {
            MediaSurface.ASMR -> handleAsmrMediaTransition(entry)
            MediaSurface.DRAMA -> handleDramaMediaTransition(entry)
            MediaSurface.MOVIE -> handleMovieMediaTransition(entry)
            else -> Unit
        }
    }

    private fun handleDramaMediaTransition(entry: MediaEntry) {
        val state = mutableState.value
        val drama = state.selectedDrama ?: return
        if (state.nowPlaying?.id == entry.id) return
        preferences.setLastVideo(MediaSurface.DRAMA, entry.id)
        preferences.setDramaEpisodeId(drama.item.id, entry.id)
        mutableState.value = state.copy(nowPlaying = entry, dramaError = null)
    }

    private fun handleMovieMediaTransition(entry: MediaEntry) {
        val state = mutableState.value
        if (state.selectedMovie == null || state.nowPlaying?.id == entry.id) return
        mutableState.value = state.copy(nowPlaying = entry)
    }

    private fun handleAsmrMediaTransition(entry: MediaEntry) {
        val state = mutableState.value
        if (state.surface != MediaSurface.ASMR || state.nowPlaying?.id == entry.id) return
        val keepExpanded = !entry.isAudio && state.expandedMedia != null
        preferences.setLastVideo(MediaSurface.ASMR, entry.id)
        mutableState.value = state.copy(
            nowPlaying = entry,
            expandedMedia = entry.takeIf { keepExpanded },
        )
        persistAsmrPlaybackState()
        prefetchNextAsmrEntries(entry)
        loadMoreAsmrQueueIfNeeded(entry)
        if (backgroundPlaybackEnabled(entry)) startPlaybackService() else stopPlaybackService()
    }

    private fun prefetchNextAsmrEntries(entry: MediaEntry) {
        val index = asmrQueue.indexOfFirst { it.id == entry.id }
        if (index < 0) return
        playback.prefetch(MediaSurface.ASMR, asmrQueue.drop(index + 1).take(2))
    }

    private fun loadMoreAsmrQueueIfNeeded(entry: MediaEntry) {
        val key = asmrQueueKey ?: return
        val index = asmrQueue.indexOfFirst { it.id == entry.id }
        if (index < 0 || index < (asmrQueue.size - 5).coerceAtLeast(0)) return
        if (asmrItemJobs[key]?.isActive == true || asmrItemsNextOffsets[key] == null) return
        loadAsmrItems(key, reset = false)
    }

    private fun advanceAfterPlaybackEnded(event: PlaybackEndedEvent) {
        val state = mutableState.value
        if (
            !shouldHandlePlaybackEnded(
                event = event,
                engineMediaId = playback.currentMedia()?.id,
                engineGeneration = playback.currentPlaybackGeneration(),
                stateMediaId = state.nowPlaying?.id,
            )
        ) return
        when {
            state.surface == MediaSurface.ASMR -> {
                val current = state.nowPlaying ?: return
                val next = nextAsmrEntry(asmrQueue, current.id)
                if (next != null) {
                    startAsmrPlayback(next, expandVideo = state.expandedMedia != null)
                } else if (asmrQueueKey?.let { asmrItemJobs[it]?.isActive } == true) {
                    pendingAsmrAdvanceId = current.id
                } else if (asmrQueueKey?.let { asmrItemsNextOffsets[it] != null } == true) {
                    pendingAsmrAdvanceId = current.id
                    loadAsmrItems(asmrQueueKey ?: return, reset = false)
                }
                return
            }
            state.surface == MediaSurface.MOVIE -> {
                saveCurrentPosition()
                playback.seekTo(0L)
                playback.pause()
                return
            }
            state.surface == MediaSurface.DRAMA -> {
                // Short dramas are consumed episode after episode, so roll on to
                // the next part the way a series player would.
                saveCurrentPosition()
                val detail = state.selectedDrama
                val current = state.nowPlaying
                val next = detail?.episodes?.let { episodes ->
                    val index = episodes.indexOfFirst { it.videoId == current?.id }
                    if (index >= 0) episodes.getOrNull(index + 1) else null
                }
                if (next != null) {
                    playDramaEpisode(detail.item, next)
                } else {
                    playback.seekTo(0L)
                    playback.pause()
                }
                return
            }
            !state.surface.isFeed -> return
        }
        val nextIndex = nextFeedIndex(state.activeIndex, state.feedItems.size) ?: return
        if (nextIndex == state.activeIndex) {
            playback.restart()
        } else {
            activateFeedItem(nextIndex)
        }
    }

    private fun handleAsmrError(error: Throwable, message: String) {
        logs.error("asmr_error", "message=$message authorHash=${mutableState.value.selectedAuthor?.hashCode()}", error)
        if (!handleUnauthorized(error)) {
            mutableState.value = mutableState.value.copy(asmrLoading = false, asmrError = message)
        }
    }

    private fun runAdminAction(action: String, block: () -> Unit) {
        if (action in mutableState.value.adminActions) return
        mutableState.update {
            it.copy(adminActions = it.adminActions + action, adminError = null)
        }
        viewModelScope.launch {
            runApi(block)
                .onSuccess {
                    delay(500)
                    mutableState.update { it.copy(adminActions = it.adminActions - action) }
                    loadAdminStatus()
                }
                .onFailure { error ->
                    if (!handleUnauthorized(error)) {
                        mutableState.update {
                            it.copy(
                                adminActions = it.adminActions - action,
                                adminError = "操作失败，请稍后重试",
                            )
                        }
                    }
                }
        }
    }

    private suspend fun <T> runApi(block: () -> T): Result<T> =
        withContext(Dispatchers.IO) { runCatching(block) }

    private fun handleUnauthorized(error: Throwable): Boolean {
        if ((error as? ApiException)?.statusCode != 401) return false
        appUpdateJob?.cancel()
        appUpdateObserverJob?.cancel()
        preferences.clearSession()
        playback.stop()
        stopPlaybackService()
        mutableState.value = AppUiState(authentication = AuthenticationState.SIGNED_OUT)
        return true
    }

    private fun appUpdateErrorMessage(error: Throwable): String = when ((error as? ApiException)?.statusCode) {
        404 -> "服务器尚未发布可安装版本"
        409 -> "服务器版本已变化，请重新检查"
        else -> if (error.message?.contains("SHA-256") == true) {
            "安装包校验失败，请重新下载"
        } else {
            "更新失败，请检查网络后重试"
        }
    }

    private fun verifySessionInBackground() {
        viewModelScope.launch {
            try {
                if (!withContext(Dispatchers.IO) { api.authenticationStatus() }) {
                    handleUnauthorized(ApiException(401, "Authentication required"))
                }
            } catch (_: IOException) {
                // Cached media remains usable while the server is temporarily unreachable.
            }
        }
    }

    private fun backgroundPlaybackEnabled(entry: MediaEntry): Boolean {
        val state = mutableState.value
        return if (entry.isAudio) {
            true
        } else {
            state.asmrVideoBackgroundPlayback
        }
    }

    private fun keepCurrentAsmrPlaybackInBackground(): Boolean {
        val state = mutableState.value
        return shouldKeepPlayingInBackground(
            surface = state.surface,
            media = state.nowPlaying,
            videoEnabled = state.asmrVideoBackgroundPlayback,
        )
    }

    private fun startPlaybackService() {
        runCatching {
            PlaybackService.start(getApplication())
        }.onSuccess {
            logs.info("playback_service_start_requested", "media=${playback.snapshot.value.mediaId}")
        }.onFailure { error ->
            logs.error("playback_service_start_request_failed", error = error)
        }
    }

    private fun stopPlaybackService() {
        runCatching {
            PlaybackService.stop(getApplication())
        }.onSuccess { stopped ->
            if (stopped) {
                logs.info("playback_service_stop_requested", "media=${playback.snapshot.value.mediaId}")
            }
        }.onFailure { error ->
            logs.error("playback_service_stop_request_failed", error = error)
        }
    }

    override fun onCleared() {
        appUpdateJob?.cancel()
        appUpdateObserverJob?.cancel()
        saveCurrentPosition()
        playback.setOnPlaybackEndedListener(null)
        playback.setOnMediaTransitionListener(null)
        super.onCleared()
    }
}

/** A library's own page opens on the same ordering the wall uses. */
private const val DEFAULT_MOVIE_SORT = "cover"
