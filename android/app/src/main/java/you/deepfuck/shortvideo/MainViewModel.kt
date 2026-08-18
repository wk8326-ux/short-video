package you.deepfuck.shortvideo

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import you.deepfuck.shortvideo.data.AdminStatus
import you.deepfuck.shortvideo.data.ApiException
import you.deepfuck.shortvideo.data.AsmrAuthor
import you.deepfuck.shortvideo.data.FeedMode
import you.deepfuck.shortvideo.data.MediaApi
import you.deepfuck.shortvideo.data.MediaEntry
import you.deepfuck.shortvideo.data.MediaSurface
import you.deepfuck.shortvideo.data.PlaybackPreferences
import you.deepfuck.shortvideo.media.PlaybackEngine
import you.deepfuck.shortvideo.media.PlaybackEngineProvider
import you.deepfuck.shortvideo.media.PlaybackService

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
    val asmrAuthorsHasMore: Boolean = false,
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
    val showManagement: Boolean = false,
    val adminStatus: AdminStatus? = null,
    val adminLoading: Boolean = false,
    val adminError: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = PlaybackPreferences(application)
    private val api = MediaApi(preferences)
    val playback: PlaybackEngine = PlaybackEngineProvider.get(application)

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
            nowPlaying = playback.currentMedia().takeIf {
                preferences.surface.supportsBackgroundPlayback
            },
        ),
    )
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()

    private var feedCursor: String? = null
    private var feedJob: Job? = null
    private var asmrAuthorsJob: Job? = null
    private val asmrItemJobs = mutableMapOf<String, Job>()
    private var activeFeedItemId: Long? = null
    private val asmrItemsCache = mutableMapOf<String, List<MediaEntry>>()
    private var asmrAuthorsNextOffset: Int? = 0
    private val asmrItemsNextOffsets = mutableMapOf<String, Int?>()
    private val asmrItemsTotals = mutableMapOf<String, Int>()
    private var audioQueueAuthor: String? = null
    private var audioQueue: List<MediaEntry> = emptyList()
    private var pendingAudioAdvanceId: Long? = null

    init {
        playback.setMuted(preferences.muted)
        playback.setOnPlaybackEndedListener {
            viewModelScope.launch { advanceAfterPlaybackEnded() }
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
        saveCurrentPosition()
        viewModelScope.launch(Dispatchers.IO) { api.logout() }
        playback.stop()
        stopPlaybackService()
        activeFeedItemId = null
        mutableState.value = AppUiState(authentication = AuthenticationState.SIGNED_OUT)
    }

    fun changeSurface(surface: MediaSurface) {
        if (surface == mutableState.value.surface) return
        saveCurrentPosition()
        playback.stop()
        if (mutableState.value.surface == MediaSurface.ASMR) {
            stopPlaybackService()
            asmrAuthorsJob?.cancel()
            asmrItemJobs.values.forEach { it.cancel() }
            asmrItemJobs.clear()
        }
        preferences.surface = surface
        activeFeedItemId = null
        mutableState.value = mutableState.value.copy(
            surface = surface,
            activeIndex = 0,
            expandedMedia = null,
            nowPlaying = null,
            showManagement = false,
            feedError = null,
            asmrError = null,
        )
        if (surface == MediaSurface.ASMR) {
            val author = mutableState.value.selectedAuthor
            when {
                author == null && mutableState.value.asmrAuthors.isEmpty() -> loadAsmrAuthors(reset = true)
                author != null && !asmrItemsCache.containsKey(author) -> loadAsmrItems(author, reset = true)
            }
        } else {
            restoreFeed(surface)
        }
    }

    fun changeMode(mode: FeedMode) {
        if (mode == mutableState.value.mode) return
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

    fun retryFeed() = requestFeed(reset = true)

    fun loadMoreFeed() {
        if (feedCursor != null && !mutableState.value.feedLoading) requestFeed(reset = false)
    }

    fun activateFeedItem(index: Int) {
        val state = mutableState.value
        val entry = state.feedItems.getOrNull(index) ?: return
        if (activeFeedItemId == entry.id) return
        saveCurrentPosition()
        activeFeedItemId = entry.id
        preferences.setLastVideo(state.surface, entry.id)
        mutableState.value = state.copy(activeIndex = index, nowPlaying = entry)
        playback.play(entry, preferences.position(entry.id))
        playback.prefetch(state.surface, state.feedItems.drop(index).take(2))
        if (index >= state.feedItems.lastIndex - 4) loadMoreFeed()
    }

    fun selectAsmrAuthor(author: String) {
        asmrAuthorsJob?.cancel()
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

    fun loadMoreAsmrAuthors() {
        val state = mutableState.value
        if (state.surface == MediaSurface.ASMR && state.selectedAuthor == null) {
            loadAsmrAuthors(reset = false)
        }
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
        saveCurrentPosition()
        ContextCompat.startForegroundService(
            getApplication(),
            Intent(getApplication(), PlaybackService::class.java),
        )
        preferences.setLastVideo(MediaSurface.ASMR, entry.id)
        mutableState.value = mutableState.value.copy(
            nowPlaying = entry,
            expandedMedia = entry.takeUnless(MediaEntry::isAudio),
        )
        playback.play(entry, preferences.position(entry.id))
        playback.prefetch(MediaSurface.ASMR, listOf(entry))
    }

    fun expandNowPlaying() {
        mutableState.value.nowPlaying?.takeUnless(MediaEntry::isAudio)?.let {
            mutableState.value = mutableState.value.copy(expandedMedia = it)
        }
    }

    fun collapsePlayer() {
        mutableState.value = mutableState.value.copy(expandedMedia = null)
    }

    fun closeAsmrPlayer() {
        saveCurrentPosition()
        playback.stop()
        stopPlaybackService()
        audioQueueAuthor = null
        audioQueue = emptyList()
        pendingAudioAdvanceId = null
        mutableState.value = mutableState.value.copy(expandedMedia = null, nowPlaying = null)
    }

    fun showManagement() {
        playback.pause()
        mutableState.value = mutableState.value.copy(showManagement = true)
        loadAdminStatus()
    }

    fun hideManagement() {
        mutableState.value = mutableState.value.copy(showManagement = false)
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

    fun saveCurrentPosition() {
        val snapshot = playback.snapshot.value
        snapshot.mediaId?.let {
            preferences.savePosition(it, snapshot.positionMs, snapshot.durationMs)
        }
    }

    fun onAppBackgrounded() {
        if (!mutableState.value.surface.supportsBackgroundPlayback) playback.pause()
    }

    private fun restoreCurrentSurface() {
        if (mutableState.value.surface == MediaSurface.ASMR) loadAsmrAuthors(reset = true)
        else restoreFeed(mutableState.value.surface)
    }

    private fun restoreFeed(surface: MediaSurface) {
        feedJob?.cancel()
        feedCursor = null
        val cached = preferences.cachedFeed(surface, mutableState.value.mode)
        val lastId = preferences.lastVideoId(surface)
        val ordered = if (lastId == null) cached else cached.sortedBy { if (it.id == lastId) 0 else 1 }
        mutableState.value = mutableState.value.copy(
            feedItems = ordered,
            activeIndex = 0,
            feedLoading = ordered.isEmpty(),
            feedError = null,
        )
        if (ordered.isNotEmpty()) activateFeedItem(0)
        requestFeed(reset = true)
    }

    private fun requestFeed(reset: Boolean) {
        val requestedSurface = mutableState.value.surface
        if (requestedSurface == MediaSurface.ASMR) return
        val requestedMode = mutableState.value.mode
        if (reset) feedJob?.cancel() else if (mutableState.value.feedLoading) return
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
                    if (mutableState.value.surface != requestedSurface || mutableState.value.mode != requestedMode) return@onSuccess
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
                    if (reset && page.items.isNotEmpty()) {
                        preferences.saveFeed(requestedSurface, requestedMode, page.items)
                        val activeId = activeFeedItemId
                        val index = page.items.indexOfFirst { it.id == activeId }.takeIf { it >= 0 } ?: 0
                        mutableState.value = mutableState.value.copy(activeIndex = index)
                        activateFeedItem(index)
                    } else if (merged.isNotEmpty() && activeFeedItemId == null) {
                        activateFeedItem(0)
                    }
                    if (reset && page.items.isEmpty() && page.scanRunning) {
                        delay(1_600)
                        requestFeed(reset = true)
                    }
                }
                .onFailure { error ->
                    if (!handleUnauthorized(error)) {
                        mutableState.value = mutableState.value.copy(
                            feedLoading = false,
                            feedError = if (mutableState.value.feedItems.isEmpty()) "无法载入视频列表" else null,
                        )
                    }
                }
        }
    }

    private fun loadAsmrAuthors(reset: Boolean) {
        if (reset) {
            asmrAuthorsJob?.cancel()
            asmrAuthorsNextOffset = 0
        } else if (asmrAuthorsJob?.isActive == true) {
            return
        }
        val offset = asmrAuthorsNextOffset ?: return
        val existing = if (reset) emptyList() else mutableState.value.asmrAuthors
        mutableState.value = mutableState.value.copy(asmrLoading = true, asmrError = null)
        asmrAuthorsJob = viewModelScope.launch {
            val page = runApi { api.asmrAuthors(offset = offset) }.getOrElse {
                handleAsmrError(it, "无法载入 ASMR 目录")
                return@launch
            }
            val names = existing.mapTo(mutableSetOf()) { it.name }
            val merged = existing + page.items.filter { names.add(it.name) }
            asmrAuthorsNextOffset = page.nextOffset
            if (mutableState.value.surface != MediaSurface.ASMR || mutableState.value.selectedAuthor != null) return@launch
            mutableState.value = mutableState.value.copy(
                asmrAuthors = merged,
                asmrAuthorsTotal = page.total,
                asmrAuthorsHasMore = page.nextOffset != null,
                asmrLoading = false,
                asmrError = null,
            )
        }
    }

    private fun loadAsmrItems(author: String, reset: Boolean) {
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
            val ids = existing.mapTo(mutableSetOf()) { it.id }
            val merged = existing + page.items.filter { ids.add(it.id) }
            asmrItemsCache[author] = merged
            asmrItemsNextOffsets[author] = page.nextOffset
            asmrItemsTotals[author] = page.total
            if (mutableState.value.selectedAuthor == author) {
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

    private fun advanceAfterPlaybackEnded() {
        val state = mutableState.value
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
        preferences.clearSession()
        playback.stop()
        stopPlaybackService()
        mutableState.value = AppUiState(authentication = AuthenticationState.SIGNED_OUT)
        return true
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

    private fun stopPlaybackService() {
        getApplication<Application>().stopService(
            Intent(getApplication(), PlaybackService::class.java),
        )
    }

    override fun onCleared() {
        saveCurrentPosition()
        playback.setOnPlaybackEndedListener(null)
        super.onCleared()
    }
}
