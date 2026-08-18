package you.deepfuck.shortvideo

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import you.deepfuck.shortvideo.data.AdminStatus
import you.deepfuck.shortvideo.data.ApiException
import you.deepfuck.shortvideo.data.AppUpdatePhase
import you.deepfuck.shortvideo.data.AppUpdateUiState
import you.deepfuck.shortvideo.data.AsmrAuthor
import you.deepfuck.shortvideo.data.FeedMode
import you.deepfuck.shortvideo.data.MediaApi
import you.deepfuck.shortvideo.data.MediaEntry
import you.deepfuck.shortvideo.data.MediaSurface
import you.deepfuck.shortvideo.data.PlaybackPreferences
import you.deepfuck.shortvideo.data.shouldRefreshAsmrAuthorIndex
import you.deepfuck.shortvideo.media.PlaybackEngine
import you.deepfuck.shortvideo.media.PlaybackEndedEvent
import you.deepfuck.shortvideo.media.PlaybackEngineProvider
import you.deepfuck.shortvideo.media.PlaybackService
import you.deepfuck.shortvideo.logging.AppLogStore
import you.deepfuck.shortvideo.update.isUpdateAvailable
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
    val asmrFilter: String = "all",
    val asmrQuery: String = "",
    val expandedMedia: MediaEntry? = null,
    val nowPlaying: MediaEntry? = null,
    val asmrVideoBackgroundPlayback: Boolean = false,
    val showManagement: Boolean = false,
    val adminStatus: AdminStatus? = null,
    val adminLoading: Boolean = false,
    val adminError: String? = null,
    val appUpdate: AppUpdateUiState = AppUpdateUiState(),
    val showRuntimeLogs: Boolean = false,
    val runtimeLogText: String = "",
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = PlaybackPreferences(application)
    private val api = MediaApi(preferences)
    private val logs = AppLogStore.get(application)
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
    private var feedJob: Job? = null
    private var feedRequestGeneration = 0L
    private val feedSessions = FeedSessionRegistry()
    private var asmrAuthorsJob: Job? = null
    private var asmrAuthorsLoaded = false
    private var asmrAuthorIndexRestored = false
    private val asmrItemJobs = mutableMapOf<String, Job>()
    private val asmrItemGenerations = mutableMapOf<String, Long>()
    private var activeFeedItemId: Long? = null
    private val asmrItemsCache = mutableMapOf<String, List<MediaEntry>>()
    private val asmrItemsNextOffsets = mutableMapOf<String, Int?>()
    private val asmrItemsTotals = mutableMapOf<String, Int>()
    private var audioQueueAuthor: String? = null
    private var audioQueue: List<MediaEntry> = emptyList()
    private var pendingAudioAdvanceId: Long? = null
    private var appUpdateJob: Job? = null
    private var playbackServiceRunning = false

    init {
        playback.setMuted(preferences.muted)
        playback.setOnPlaybackEndedListener { event ->
            viewModelScope.launch { advanceAfterPlaybackEnded(event) }
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
        if (previousSurface == MediaSurface.ASMR) {
            persistAsmrPlaybackState()
        } else {
            persistCurrentFeedSession()
        }
        feedJob?.cancel()
        feedJob = null
        feedRequestGeneration += 1L
        if (previousSurface == MediaSurface.ASMR) {
            val itemJobs = asmrItemJobs.toList()
            asmrItemJobs.clear()
            itemJobs.forEach { (author, job) ->
                asmrItemGenerations[author] = (asmrItemGenerations[author] ?: 0L) + 1L
                job.cancel()
            }
        }
        preferences.surface = surface
        activeFeedItemId = null
        audioQueueAuthor = null
        audioQueue = emptyList()
        pendingAudioAdvanceId = null
        mutableState.value = mutableState.value.copy(
            surface = surface,
            feedItems = if (surface == MediaSurface.ASMR) emptyList() else mutableState.value.feedItems,
            activeIndex = 0,
            expandedMedia = null,
            nowPlaying = null,
            showManagement = false,
            feedError = null,
            asmrError = null,
        )
        playback.stop()
        stopPlaybackService()
        if (surface == MediaSurface.ASMR) {
            restoreAsmrSurface()
        } else {
            restoreFeed(surface)
        }
    }

    fun changeMode(mode: FeedMode) {
        if (mode == mutableState.value.mode) return
        saveCurrentPosition()
        persistCurrentFeedSession()
        preferences.mode = mode
        activeFeedItemId = null
        mutableState.value = mutableState.value.copy(mode = mode, activeIndex = 0)
        if (mutableState.value.surface != MediaSurface.ASMR) restoreFeed(mutableState.value.surface)
    }

    fun setMuted(muted: Boolean) {
        preferences.muted = muted
        playback.setMuted(muted)
        mutableState.value = mutableState.value.copy(muted = muted)
    }

    fun togglePlayback() {
        playback.togglePlayback()
        if (mutableState.value.surface == MediaSurface.ASMR) {
            persistAsmrPlaybackState()
        } else {
            persistCurrentFeedSession()
        }
        if (playback.player.playWhenReady && keepCurrentAsmrPlaybackInBackground()) {
            startPlaybackService()
        }
    }

    fun retryFeed() = requestFeed(reset = true)

    fun loadMoreFeed() {
        if (feedCursor != null && !mutableState.value.feedLoading) requestFeed(reset = false)
    }

    fun activateFeedItem(index: Int) = activateFeedItem(index, autoPlay = true)

    private fun activateFeedItem(index: Int, autoPlay: Boolean) {
        val state = mutableState.value
        if (!shouldActivateFeedItem(state.surface)) return
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
        val cached = asmrItemsCache[author].orEmpty()
        val cachedKnown = asmrItemsCache.containsKey(author)
        val nextOffset = if (cachedKnown) asmrItemsNextOffsets[author] else 0
        mutableState.value = mutableState.value.copy(
            selectedAuthor = author,
            asmrItems = cached,
            asmrTotal = asmrItemsTotals[author] ?: cached.size,
            asmrItemsHasMore = nextOffset != null,
            asmrLoading = false,
            asmrError = null,
            asmrFilter = "all",
            asmrQuery = "",
        )
        if (!cachedKnown) loadAsmrItems(author, reset = true)
    }

    fun leaveAsmrAuthor() {
        val author = mutableState.value.selectedAuthor
        logs.info("asmr_author_close", "authorHash=${author?.hashCode()}")
        preferences.selectedAsmrAuthor = null
        if (audioQueueAuthor != author) author?.let { asmrItemJobs.remove(it)?.cancel() }
        mutableState.value = mutableState.value.copy(
            selectedAuthor = null,
            asmrItems = emptyList(),
            asmrTotal = 0,
            asmrItemsHasMore = false,
            asmrLoading = false,
            asmrQuery = "",
            asmrFilter = "all",
        )
    }

    fun loadMoreAsmrItems() {
        val author = mutableState.value.selectedAuthor ?: return
        loadAsmrItems(author, reset = false)
    }

    fun setAsmrFilter(filter: String) {
        mutableState.value = mutableState.value.copy(asmrFilter = filter)
    }

    fun setAsmrQuery(query: String) {
        mutableState.value = mutableState.value.copy(asmrQuery = query)
    }

    fun playAsmr(entry: MediaEntry) {
        if (mutableState.value.surface != MediaSurface.ASMR) return
        logs.info("asmr_play", "media=${entry.id} kind=${if (entry.isAudio) "audio" else "video"}")
        if (entry.isAudio) {
            audioQueueAuthor = entry.author
            audioQueue = mutableState.value.asmrItems.filter {
                it.isAudio && it.author == entry.author
            }
            pendingAudioAdvanceId = null
        } else {
            audioQueueAuthor = null
            audioQueue = emptyList()
            pendingAudioAdvanceId = null
        }
        startAsmrPlayback(entry)
    }

    private fun startAsmrPlayback(entry: MediaEntry) {
        if (mutableState.value.surface != MediaSurface.ASMR) return
        saveCurrentPosition()
        preferences.setLastVideo(MediaSurface.ASMR, entry.id)
        mutableState.value = mutableState.value.copy(
            nowPlaying = entry,
            expandedMedia = entry.takeUnless(MediaEntry::isAudio),
        )
        if (!playback.play(entry, preferences.position(entry.id))) {
            preferences.saveAsmrPlaybackState(null)
            mutableState.value = mutableState.value.copy(
                asmrError = "无法准备该媒体",
                nowPlaying = null,
                expandedMedia = null,
            )
            return
        }
        playback.prefetch(MediaSurface.ASMR, listOf(entry))
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
        audioQueueAuthor = null
        audioQueue = emptyList()
        pendingAudioAdvanceId = null
        preferences.saveAsmrPlaybackState(null)
        mutableState.value = mutableState.value.copy(expandedMedia = null, nowPlaying = null)
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

    fun startLibraryScan() = runAdminAction { api.startScan() }

    fun startFastStartCheck() = runAdminAction { api.startFastStartCheck() }

    fun checkForAppUpdate() {
        appUpdateJob?.cancel()
        mutableState.value = mutableState.value.copy(
            appUpdate = AppUpdateUiState(
                phase = AppUpdatePhase.CHECKING,
                currentVersionName = BuildConfig.VERSION_NAME,
            ),
        )
        appUpdateJob = viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { api.appUpdate() }
                mutableState.value = mutableState.value.copy(
                    appUpdate = AppUpdateUiState(
                        phase = if (isUpdateAvailable(BuildConfig.VERSION_CODE, info.versionCode)) {
                            AppUpdatePhase.AVAILABLE
                        } else {
                            AppUpdatePhase.LATEST
                        },
                        currentVersionName = BuildConfig.VERSION_NAME,
                        info = info,
                    ),
                )
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
        val directory = File(getApplication<Application>().cacheDir, "app-updates")
        val target = File(directory, "short-video-${update.versionCode}.apk")
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
        mutableState.value = mutableState.value.copy(
            appUpdate = mutableState.value.appUpdate.copy(
                phase = AppUpdatePhase.DOWNLOADING,
                progress = 0f,
                downloadedBytes = 0L,
                downloadedApkPath = null,
                message = null,
            ),
        )
        appUpdateJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    var lastPercent = -1
                    api.downloadAppUpdate(update, target) { downloaded, total ->
                        ensureActive()
                        val percent = if (total > 0L) ((downloaded * 100L) / total).toInt() else 0
                        if (percent != lastPercent) {
                            lastPercent = percent
                            mutableState.update { current ->
                                current.copy(
                                    appUpdate = current.appUpdate.copy(
                                        progress = (downloaded.toFloat() / total.coerceAtLeast(1L)).coerceIn(0f, 1f),
                                        downloadedBytes = downloaded,
                                    ),
                                )
                            }
                        }
                    }
                    if (!sha256Matches(target, update.sha256)) {
                        target.delete()
                        throw IOException("Downloaded APK failed SHA-256 verification")
                    }
                }
                mutableState.value = mutableState.value.copy(
                    appUpdate = mutableState.value.appUpdate.copy(
                        phase = AppUpdatePhase.READY,
                        progress = 1f,
                        downloadedBytes = update.size,
                        downloadedApkPath = target.absolutePath,
                        message = null,
                    ),
                )
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
        mutableState.value = mutableState.value.copy(appUpdate = AppUpdateUiState())
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

    private fun persistCurrentFeedSession(
        playWhenReady: Boolean = playback.snapshot.value.playWhenReady,
    ) {
        val state = mutableState.value
        if (state.surface == MediaSurface.ASMR || state.feedItems.isEmpty()) return
        val activeId = state.feedItems.getOrNull(state.activeIndex)?.id ?: state.nowPlaying?.id
        val session = FeedSessionState(
            items = state.feedItems,
            activeMediaId = activeId,
            nextCursor = feedCursor,
            total = state.feedTotal,
            playWhenReady = playWhenReady,
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
        if (mutableState.value.surface == MediaSurface.ASMR) restoreAsmrSurface()
        else restoreFeed(mutableState.value.surface)
    }

    private fun restoreAsmrSurface() {
        loadAsmrAuthors()
        val author = mutableState.value.selectedAuthor
        if (author != null) {
            val cached = asmrItemsCache[author].orEmpty()
            mutableState.value = mutableState.value.copy(
                asmrItems = cached,
                asmrTotal = asmrItemsTotals[author] ?: cached.size,
                asmrItemsHasMore = asmrItemsNextOffsets[author] != null,
            )
            if (!asmrItemsCache.containsKey(author)) loadAsmrItems(author, reset = true)
        }
        val resume = preferences.asmrPlaybackState() ?: return
        if (resume.entry.isAudio) {
            val resumeAuthor = resume.entry.author
            audioQueueAuthor = resumeAuthor
            audioQueue = resumeAuthor?.let { authorName ->
                asmrItemsCache[authorName].orEmpty().filter(MediaEntry::isAudio)
            }.orEmpty()
            if (resumeAuthor != null && !asmrItemsCache.containsKey(resumeAuthor)) {
                loadAsmrItems(resumeAuthor, reset = true)
            }
        } else {
            audioQueueAuthor = null
            audioQueue = emptyList()
        }
        mutableState.value = mutableState.value.copy(
            nowPlaying = resume.entry,
            expandedMedia = resume.entry.takeIf { !it.isAudio && resume.expanded },
        )
        val restored = playback.play(
            resume.entry,
            preferences.position(resume.entry.id),
            autoPlay = resume.playWhenReady,
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

    private fun restoreFeed(surface: MediaSurface) {
        feedJob?.cancel()
        feedRequestGeneration += 1L
        val mode = mutableState.value.mode
        val session = feedSessions.restore(surface, mode)
            ?: preferences.feedSession(surface, mode)?.also { feedSessions.save(surface, mode, it) }
        val items = session?.items.orEmpty()
        val activeIndex = session?.activeIndex()?.coerceIn(0, items.lastIndex.coerceAtLeast(0)) ?: 0
        feedCursor = session?.nextCursor
        mutableState.value = mutableState.value.copy(
            feedItems = items,
            activeIndex = activeIndex,
            feedTotal = session?.total ?: 0,
            feedLoading = items.isEmpty(),
            feedError = null,
        )
        if (items.isNotEmpty()) {
            activateFeedItem(activeIndex, autoPlay = session?.playWhenReady ?: true)
        } else {
            requestFeed(reset = true)
        }
    }

    private fun requestFeed(reset: Boolean) {
        val requestedSurface = mutableState.value.surface
        if (requestedSurface == MediaSurface.ASMR) return
        val requestedMode = mutableState.value.mode
        if (reset) feedJob?.cancel() else if (mutableState.value.feedLoading) return
        val requestGeneration = ++feedRequestGeneration
        mutableState.value = mutableState.value.copy(feedLoading = true, feedError = null)
        feedJob = viewModelScope.launch {
            val cursor = if (reset) null else feedCursor
            val recent = if (reset && requestedMode == FeedMode.SHUFFLE) {
                preferences.recentVideoIds()
            } else {
                emptyList()
            }
            val start = if (reset) preferences.lastVideoId(requestedSurface) else null
            runApi { api.feed(requestedSurface, requestedMode, cursor, recent, start) }
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
                        feedTotal = page.total,
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
                if (!shouldRefreshAsmrAuthorIndex(cached)) {
                    asmrAuthorsLoaded = true
                    return
                }
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
            mutableState.value = mutableState.value.copy(
                asmrAuthors = page.items,
                asmrAuthorsTotal = page.total,
                asmrLoading = false,
                asmrError = null,
            )
        }
    }

    private fun loadAsmrItems(author: String, reset: Boolean) {
        val generation = if (reset) {
            (asmrItemGenerations[author] ?: 0L) + 1L
        } else {
            asmrItemGenerations[author] ?: 1L
        }
        asmrItemGenerations[author] = generation
        if (reset) {
            asmrItemJobs.remove(author)?.cancel()
            asmrItemsNextOffsets[author] = 0
        } else if (asmrItemJobs[author]?.isActive == true) {
            return
        }
        val offset = asmrItemsNextOffsets[author] ?: return
        val existing = if (reset) emptyList() else asmrItemsCache[author].orEmpty()
        if (mutableState.value.selectedAuthor == author) {
            mutableState.value = mutableState.value.copy(asmrLoading = true, asmrError = null)
        }
        val job = viewModelScope.launch {
            val page = runApi { api.asmrItems(author, offset = offset) }.getOrElse {
                if (mutableState.value.selectedAuthor == author) {
                    handleAsmrError(it, "无法载入作者媒体")
                }
                return@launch
            }
            if (asmrItemGenerations[author] != generation) return@launch
            val ids = existing.mapTo(mutableSetOf()) { it.id }
            val merged = existing + page.items.filter { ids.add(it.id) }
            asmrItemsCache[author] = merged
            asmrItemsNextOffsets[author] = page.nextOffset
            asmrItemsTotals[author] = page.total
            if (
                mutableState.value.surface == MediaSurface.ASMR &&
                mutableState.value.selectedAuthor == author
            ) {
                mutableState.value = mutableState.value.copy(
                    asmrItems = merged,
                    asmrTotal = page.total,
                    asmrItemsHasMore = page.nextOffset != null,
                    asmrLoading = false,
                    asmrError = null,
                )
            } else if (audioQueueAuthor != author) {
                return@launch
            }
            extendAudioQueue(author, page.items)
        }
        asmrItemJobs[author] = job
        job.invokeOnCompletion {
            if (asmrItemJobs[author] === job) asmrItemJobs.remove(author)
        }
    }

    private fun extendAudioQueue(author: String, entries: List<MediaEntry>) {
        if (audioQueueAuthor != author) return
        val ids = audioQueue.mapTo(mutableSetOf()) { it.id }
        audioQueue = audioQueue + entries.filter { it.isAudio && ids.add(it.id) }
        val pendingId = pendingAudioAdvanceId ?: return
        nextAudioEntry(audioQueue, pendingId)?.let { next ->
            pendingAudioAdvanceId = null
            startAsmrPlayback(next)
        }
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
        if (state.surface == MediaSurface.ASMR) {
            val current = state.nowPlaying?.takeIf(MediaEntry::isAudio) ?: return
            val next = nextAudioEntry(audioQueue, current.id)
            if (next != null) {
                startAsmrPlayback(next)
            } else if (audioQueueAuthor?.let { asmrItemJobs[it]?.isActive } == true) {
                pendingAudioAdvanceId = current.id
            } else if (audioQueueAuthor?.let { asmrItemsNextOffsets[it] != null } == true) {
                pendingAudioAdvanceId = current.id
                loadAsmrItems(audioQueueAuthor ?: return, reset = false)
            }
            return
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

    private fun runAdminAction(block: () -> Unit) {
        if (mutableState.value.adminLoading) return
        mutableState.value = mutableState.value.copy(adminLoading = true, adminError = null)
        viewModelScope.launch {
            runApi(block)
                .onSuccess {
                    delay(500)
                    mutableState.value = mutableState.value.copy(adminLoading = false)
                    loadAdminStatus()
                }
                .onFailure { error ->
                    if (!handleUnauthorized(error)) {
                        mutableState.value = mutableState.value.copy(
                            adminLoading = false,
                            adminError = "操作失败，请稍后重试",
                        )
                    }
                }
        }
    }

    private suspend fun <T> runApi(block: () -> T): Result<T> =
        withContext(Dispatchers.IO) { runCatching(block) }

    private fun handleUnauthorized(error: Throwable): Boolean {
        if ((error as? ApiException)?.statusCode != 401) return false
        appUpdateJob?.cancel()
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
        if (playbackServiceRunning) return
        runCatching {
            ContextCompat.startForegroundService(
                getApplication(),
                Intent(getApplication(), PlaybackService::class.java),
            )
        }.onSuccess {
            playbackServiceRunning = true
            logs.info("playback_service_start", "media=${playback.snapshot.value.mediaId}")
        }.onFailure { error ->
            logs.error("playback_service_start_failed", error = error)
        }
    }

    private fun stopPlaybackService() {
        playbackServiceRunning = false
        runCatching {
            getApplication<Application>().stopService(
                Intent(getApplication(), PlaybackService::class.java),
            )
        }.onSuccess {
            logs.info("playback_service_stop", "media=${playback.snapshot.value.mediaId}")
        }.onFailure { error ->
            logs.error("playback_service_stop_failed", error = error)
        }
    }

    override fun onCleared() {
        appUpdateJob?.cancel()
        saveCurrentPosition()
        playback.setOnPlaybackEndedListener(null)
        super.onCleared()
    }
}
