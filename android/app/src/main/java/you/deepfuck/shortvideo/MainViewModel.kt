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
import you.deepfuck.shortvideo.data.FeedMode
import you.deepfuck.shortvideo.data.LibraryScanStatus
import you.deepfuck.shortvideo.data.LibrarySection
import you.deepfuck.shortvideo.data.MediaApi
import you.deepfuck.shortvideo.data.MediaEntry
import you.deepfuck.shortvideo.data.MediaSurface
import you.deepfuck.shortvideo.data.MediaSourceDraft
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
    val movieDetailLoading: Boolean = false,
    val selectedMovie: MovieItem? = null,
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

    private val mutableState = MutableStateFlow(
        AppUiState(
            authentication = if (api.hasSession()) {
                AuthenticationState.SIGNED_IN
            } else {
                AuthenticationState.CHECKING
            },
            surface = preferences.surface,
            mode = preferences.mode,
            muted = preferences.muted,
            asmrVideoBackgroundPlayback = preferences.asmrVideoBackgroundPlayback,
            selectedAuthor = preferences.selectedAsmrAuthor,
            nowPlaying = restoredMedia.takeIf {
                shouldKeepPlayingInBackground(
                    preferences.surface,
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
    private var movieRequestGeneration = 0L
    private var movieCatalogDirty = false
    private var appUpdateJob: Job? = null
    private var appUpdateObserverJob: Job? = null
    val movieImageLoader by lazy { api.movieImageLoader(application) }

    init {
        playback.setMuted(preferences.muted)
        playback.setOnPlaybackEndedListener { event ->
            viewModelScope.launch { advanceAfterPlaybackEnded(event) }
        }
        playback.setOnMediaTransitionListener { entry ->
            viewModelScope.launch { handleAsmrMediaTransition(entry) }
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
            movieRequestGeneration += 1L
        }
        preferences.surface = surface
        activeFeedItemId = null
        asmrQueueKey = null
        asmrQueue = emptyList()
        pendingAsmrAdvanceId = null
        mutableState.value = mutableState.value.copy(
            surface = surface,
            feedItems = if (surface.isFeed) mutableState.value.feedItems else emptyList(),
            activeIndex = 0,
            expandedMedia = null,
            nowPlaying = null,
            selectedMovie = null,
            movieDetailLoading = false,
            showManagement = false,
            feedError = null,
            asmrError = null,
            movieError = null,
        )
        playback.stop()
        playback.prefetch(surface, emptyList())
        stopPlaybackService()
        when {
            surface.isFeed -> if (!refreshPendingFeedLibrary()) restoreFeed(surface)
            surface == MediaSurface.ASMR -> restoreAsmrSurface()
            surface == MediaSurface.MOVIE -> restoreMovieSurface()
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
        preferences.muted = muted
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

    fun setMovieQuery(query: String) {
        if (query == mutableState.value.movieQuery) return
        movieJob?.cancel()
        movieSearchJob?.cancel()
        movieRequestGeneration += 1L
        mutableState.value = mutableState.value.copy(movieQuery = query, movieError = null)
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
            !state.movieLoading &&
            state.movieNextOffset != null
        ) {
            loadMovies(reset = false)
        }
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
                        movieDetailLoading = false,
                    )
                    playback.prefetch(MediaSurface.MOVIE, listOf(resolved.asMediaEntry()))
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
        if (!playback.play(entry, resolved.resumePositionMs)) {
            mutableState.value = mutableState.value.copy(
                nowPlaying = null,
                movieError = "无法准备该电影",
            )
        }
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

    fun saveMediaSource(sourceId: String?, draft: MediaSourceDraft) =
        runAdminAction("source-${sourceId ?: "new"}") {
            api.saveMediaSource(sourceId, draft)
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
        if (movieCatalogDirty || mutableState.value.movieItems.isEmpty()) {
            loadMovies(reset = true)
        }
    }

    private fun loadMovies(reset: Boolean) {
        val state = mutableState.value
        if (state.surface != MediaSurface.MOVIE) return
        val offset = if (reset) 0 else state.movieNextOffset ?: return
        if (reset) movieJob?.cancel() else if (state.movieLoading) return
        val requestedQuery = state.movieQuery.trim()
        val requestGeneration = ++movieRequestGeneration
        mutableState.value = state.copy(movieLoading = true, movieError = null)
        movieJob = viewModelScope.launch {
            runApi { api.movies(query = requestedQuery, offset = offset) }
                .onSuccess { page ->
                    val current = mutableState.value
                    if (
                        !shouldApplyMovieResponse(
                            requestGeneration = requestGeneration,
                            currentGeneration = movieRequestGeneration,
                            requestedQuery = requestedQuery,
                            currentQuery = current.movieQuery.trim(),
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
            resumePositionMs = preferences.position(videoId),
        )

    private fun updateMovieResumePosition() {
        val state = mutableState.value
        val selected = state.selectedMovie ?: return
        val position = preferences.position(selected.videoId)
        if (position == selected.resumePositionMs) return
        val updated = selected.copy(resumePositionMs = position)
        mutableState.value = state.copy(
            selectedMovie = updated,
            movieItems = state.movieItems.map { if (it.id == updated.id) updated else it },
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
