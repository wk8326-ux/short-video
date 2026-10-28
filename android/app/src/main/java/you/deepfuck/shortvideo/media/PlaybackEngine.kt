@file:androidx.annotation.OptIn(
    markerClass = [androidx.media3.common.util.UnstableApi::class],
)

package you.deepfuck.shortvideo.media

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import you.deepfuck.shortvideo.data.MediaApi
import you.deepfuck.shortvideo.data.MediaEntry
import you.deepfuck.shortvideo.data.MediaSurface
import you.deepfuck.shortvideo.data.PlaybackPreferences
import you.deepfuck.shortvideo.logging.AppLogStore

data class PlayerSnapshot(
    val mediaId: Long? = null,
    val title: String = "",
    val isAudio: Boolean = false,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val isBuffering: Boolean = false,
    val volume: Float = 0f,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val error: String? = null,
)

data class PlaybackEndedEvent(
    val mediaId: Long,
    val generation: Long,
)

internal fun volumeIndexForFraction(fraction: Float, maxVolume: Int): Int {
    if (maxVolume <= 0) return 0
    return (fraction.coerceIn(0f, 1f) * maxVolume).roundToInt().coerceIn(0, maxVolume)
}

internal fun volumeFractionForIndex(index: Int, maxVolume: Int): Float =
    if (maxVolume <= 0) 0f else index.coerceIn(0, maxVolume).toFloat() / maxVolume

internal class PlaybackIdentity {
    private var mediaId: Long? = null
    var generation: Long = 0L
        private set

    fun activate(mediaId: Long) {
        generation += 1L
        this.mediaId = mediaId
    }

    fun endedEvent(): PlaybackEndedEvent? = mediaId?.let {
        PlaybackEndedEvent(mediaId = it, generation = generation)
    }

    fun invalidateThen(playerMutation: () -> Unit) {
        generation += 1L
        mediaId = null
        playerMutation()
    }
}

internal class PlaybackErrorState {
    var error: String? = null
        private set

    fun record(error: String?) {
        if (error != null) this.error = error
    }

    fun clear() {
        error = null
    }
}

internal fun playbackUrl(entry: MediaEntry): String {
    val url = entry.playUrl
    return if (url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)) {
        url
    } else {
        requireNotNull(MediaApi.BASE_URL.toHttpUrl().resolve(url)).toString()
    }
}

internal fun playbackDataSpec(entry: MediaEntry): DataSpec = DataSpec.Builder()
    .setUri(playbackUrl(entry))
    .setHttpRequestHeaders(entry.requestHeaders)
    .build()

internal fun playbackQueueMatches(existing: List<MediaEntry>, requested: List<MediaEntry>): Boolean =
    existing.size == requested.size && existing.zip(requested).all { (previous, next) ->
        previous.id == next.id && previous.playUrl == next.playUrl && previous.requestHeaders == next.requestHeaders && mediaMimeType(previous) == mediaMimeType(next)
    }

internal fun playbackHttpClient(
    sessionCookie: () -> String?,
    backendUrl: HttpUrl = MediaApi.BASE_URL.toHttpUrl(),
): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(12, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .addNetworkInterceptor { chain ->
        // Network interceptors run again for every redirect and HLS resource request.
        val request = chain.request()
        val sameOrigin = request.url.scheme == backendUrl.scheme &&
            request.url.host == backendUrl.host && request.url.port == backendUrl.port
        chain.proceed(request.newBuilder().apply {
            removeHeader("Cookie")
            if (sameOrigin) sessionCookie()?.takeUnless(String::isBlank)?.let { header("Cookie", it) }
            else {
                val original = chain.call().request().url
                val fromBackend = original.scheme == backendUrl.scheme && original.host == backendUrl.host && original.port == backendUrl.port
                if (!fromBackend) request.header("Cookie")?.split(';')?.map(String::trim)
                    ?.filterNot { it.substringBefore('=').equals("short_session", true) }
                    ?.filter(String::isNotBlank)?.joinToString("; ")?.takeIf(String::isNotBlank)?.let { header("Cookie", it) }
            }
        }.build())
    }
    .build()

class PlaybackEngine(context: Context, private val api: MediaApi) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logs = AppLogStore.get(context)
    private val cache = MediaCacheStore.get(context)
    private val cacheKeyFactory = StableCacheKeyFactory()
    private val resolvedPlayUrls = ResolvedPlayUrlCache(ttlMs = RESOLVED_URL_TTL_MS)
    private val forceRefreshMediaIds = ConcurrentHashMap.newKeySet<Long>()
    private var currentEntry: MediaEntry? = null
    private val queueEntries = linkedMapOf<Long, MediaEntry>()
    private var prefetchJob: Job? = null
    private val prefetchLock = Any()
    private var prefetchGeneration = 0L
    private var activeCacheWriter: CacheWriter? = null
    private var redirectRetryMediaId: Long? = null
    private val playbackIdentity = PlaybackIdentity()
    private val playbackError = PlaybackErrorState()
    private var onPlaybackEnded: ((PlaybackEndedEvent) -> Unit)? = null
    private var onMediaTransition: ((MediaEntry) -> Unit)? = null

    private val playbackPreferences = PlaybackPreferences(context.applicationContext)
    private val httpClient = playbackHttpClient({ playbackPreferences.sessionCookie })
    private val upstreamFactory = ResolvingDataSource.Factory(
        OkHttpDataSource.Factory(httpClient),
    ) { dataSpec -> resolveDataSpec(dataSpec) }

    val cacheDataSourceFactory: CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setCacheKeyFactory(cacheKeyFactory)
        .setUpstreamDataSourceFactory(upstreamFactory)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    val player: ExoPlayer = ExoPlayer.Builder(
        context, DefaultRenderersFactory(context).setEnableDecoderFallback(true),
    )
        .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .setMaxSeekToPreviousPositionMs(Long.MAX_VALUE)
        .build()
        .apply {
            setAudioAttributes(AudioAttributes.DEFAULT, true)
            setHandleAudioBecomingNoisy(true)
        }

    private val mutableSnapshot = MutableStateFlow(PlayerSnapshot())
    val snapshot: StateFlow<PlayerSnapshot> = mutableSnapshot.asStateFlow()

    init {
        player.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) playbackError.clear()
                    publish()
                    if (playbackState == Player.STATE_ENDED && player.playbackState == Player.STATE_ENDED) {
                        playbackIdentity.endedEvent()?.let { onPlaybackEnded?.invoke(it) }
                    }
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) = publish()

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = publish()

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val entry = mediaItem
                        ?.mediaId
                        ?.toLongOrNull()
                        ?.let(queueEntries::get)
                        ?: return
                    if (currentEntry == entry) {
                        publish()
                        return
                    }
                    playbackIdentity.activate(entry.id)
                    redirectRetryMediaId = null
                    currentEntry = entry
                    publish()
                    logs.info("player_transition", "media=${entry.id} reason=$reason")
                    onMediaTransition?.invoke(entry)
                }

                override fun onPlayerError(error: PlaybackException) {
                    logs.error(
                        "player_error",
                        "media=${currentEntry?.id} code=${error.errorCodeName}",
                        error,
                    )
                    if (!retryWithFreshRedirect(error)) publish(error.errorCodeName)
                }
            },
        )
        publish()
    }

    fun play(
        entry: MediaEntry,
        resumePositionMs: Long,
        autoPlay: Boolean = true,
        queue: List<MediaEntry> = listOf(entry),
    ): Boolean {
        playbackError.clear()
        val normalizedQueue = normalizeQueue(entry, queue)
        val queueIds = normalizedQueue.map(MediaEntry::id)
        if (playbackQueueMatches(queueEntries.values.toList(), normalizedQueue) && player.mediaItemCount == queueIds.size) {
            return runCatching {
                normalizedQueue.forEach { queueEntries[it.id] = it }
                val targetIndex = normalizedQueue.indexOfFirst { it.id == entry.id }
                val changed = currentEntry?.id != entry.id || player.currentMediaItemIndex != targetIndex
                currentEntry = entry
                if (changed) {
                    playbackIdentity.activate(entry.id)
                    redirectRetryMediaId = null
                    player.seekTo(targetIndex, resumePositionMs.coerceAtLeast(0L))
                    onMediaTransition?.invoke(entry)
                }
                if (player.playerError != null) player.prepare()
                if (autoPlay) player.play() else player.pause()
                publish()
            }.onFailure {
                logs.error("player_resume_failed", "media=${entry.id}", it)
            }.isSuccess
        }
        return runCatching {
            playbackIdentity.activate(entry.id)
            redirectRetryMediaId = null
            currentEntry = entry
            queueEntries.clear()
            normalizedQueue.forEach { queueEntries[it.id] = it }
            val startIndex = normalizedQueue.indexOfFirst { it.id == entry.id }
            player.setMediaSources(
                normalizedQueue.map { queued ->
                    val factory = if (queued.requestHeaders.isEmpty()) cacheDataSourceFactory else CacheDataSource.Factory()
                        .setCache(cache).setCacheKeyFactory(cacheKeyFactory)
                        .setUpstreamDataSourceFactory(ResolvingDataSource.Factory(
                            OkHttpDataSource.Factory(httpClient).setDefaultRequestProperties(queued.requestHeaders),
                        ) { spec -> resolveDataSpec(spec) })
                        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                    DefaultMediaSourceFactory(factory).createMediaSource(mediaItem(queued))
                },
                startIndex,
                resumePositionMs.coerceAtLeast(0L),
            )
            player.prepare()
            player.playWhenReady = autoPlay
            publish()
            logs.info(
                "player_prepare",
                "media=${entry.id} kind=${if (entry.isAudio) "audio" else "video"} queue=${normalizedQueue.size} resumeMs=$resumePositionMs autoPlay=$autoPlay",
            )
        }.onFailure { error ->
            currentEntry = null
            queueEntries.clear()
            playbackIdentity.invalidateThen { runCatching { player.clearMediaItems() } }
            publish(error.javaClass.simpleName)
            logs.error("player_prepare_failed", "media=${entry.id}", error)
        }.isSuccess
    }

    fun togglePlayback() {
        runCatching {
            if (player.playerError != null) {
                playbackError.clear()
                player.prepare()
                player.play()
            } else if (player.playWhenReady) player.pause() else player.play()
        }.onFailure { logs.error("player_toggle_failed", "media=${currentEntry?.id}", it) }
    }

    fun restart() {
        playbackError.clear()
        currentEntry?.id?.let(playbackIdentity::activate)
        if (player.playerError != null) player.prepare()
        player.seekTo(0L)
        player.play()
        publish()
    }

    fun setOnPlaybackEndedListener(listener: ((PlaybackEndedEvent) -> Unit)?) {
        onPlaybackEnded = listener
    }

    fun setOnMediaTransitionListener(listener: ((MediaEntry) -> Unit)?) {
        onMediaTransition = listener
    }

    fun appendQueue(entries: List<MediaEntry>): Int {
        val additions = entries.distinctBy(MediaEntry::id).filterNot { queueEntries.containsKey(it.id) }
        if (additions.isEmpty()) return 0
        return runCatching {
            additions.forEach { queueEntries[it.id] = it }
            player.addMediaItems(additions.map(::mediaItem))
            logs.info("player_queue_append", "added=${additions.size} total=${queueEntries.size}")
            additions.size
        }.onFailure { error ->
            additions.forEach { queueEntries.remove(it.id) }
            logs.error("player_queue_append_failed", "added=${additions.size}", error)
        }.getOrDefault(0)
    }

    fun pause() {
        player.pause()
        publish()
    }

    fun setMuted(muted: Boolean) {
        player.volume = if (muted) 0f else 1f
        publish()
    }

    fun setVolume(volume: Float) {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val index = volumeIndexForFraction(volume, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
        publish()
    }

    fun seekTo(positionMs: Long) {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
        runCatching {
            player.seekTo(positionMs.coerceIn(0L, duration ?: Long.MAX_VALUE))
            publish()
        }.onFailure { logs.error("player_seek_failed", "media=${currentEntry?.id}", it) }
    }

    fun seekBy(deltaMs: Long) {
        seekTo(player.currentPosition + deltaMs)
    }

    fun stop() {
        playbackError.clear()
        currentEntry = null
        queueEntries.clear()
        playbackIdentity.invalidateThen {
            player.pause()
            player.clearMediaItems()
        }
        publish()
    }

    fun refreshPosition() = publish()

    fun prefetch(surface: MediaSurface, entries: List<MediaEntry>) {
        prefetchJob?.cancel()
        val generation = synchronized(prefetchLock) {
            prefetchGeneration += 1L
            activeCacheWriter?.cancel()
            activeCacheWriter = null
            prefetchGeneration
        }
        prefetchJob = scope.launch {
            val candidates = entries.take(3)
            candidates.forEach { entry ->
                ensureActive()
                runCatching { resolvePlayEntry(entry) }
                    .onFailure { logs.info("player_redirect_prewarm_failed", "media=${entry.id}") }
            }
            candidates.take(2).filterNot { mediaMimeType(it) == MimeTypes.APPLICATION_M3U8 }.forEach { entry ->
                ensureActive()
                runCatching { cacheProgressive(surface, entry, generation) }
            }
        }
    }

    fun cancelBackgroundPrefetch() {
        prefetchJob?.cancel()
        synchronized(prefetchLock) {
            activeCacheWriter?.cancel()
            activeCacheWriter = null
        }
    }

    fun cachedBytes(entry: MediaEntry): Long {
        val length = entry.size.takeIf { it > 0L } ?: return 0L
        return cache.getCachedBytes(cacheKeyFactory.buildCacheKey(playbackDataSpec(entry)), 0L, length)
    }

    fun currentMedia(): MediaEntry? = currentEntry

    fun currentPlaybackGeneration(): Long = playbackIdentity.generation

    fun release() {
        currentEntry = null
        queueEntries.clear()
        prefetchJob?.cancel()
        synchronized(prefetchLock) {
            prefetchGeneration += 1L
            activeCacheWriter?.cancel()
            activeCacheWriter = null
        }
        scope.cancel()
        playbackIdentity.invalidateThen(player::release)
    }

    private fun cacheProgressive(surface: MediaSurface, entry: MediaEntry, generation: Long) {
        val target = when {
            surface == MediaSurface.SHORT && entry.size in 1..MAX_FULL_SHORT_BYTES -> entry.size
            surface == MediaSurface.SHORT -> SHORT_PREFIX_BYTES
            surface == MediaSurface.MOVIE && entry.size > LARGE_MOVIE_SIZE_BYTES -> LARGE_MOVIE_PREFIX_BYTES
            surface == MediaSurface.MOVIE -> MOVIE_PREFIX_BYTES
            else -> LONG_PREFIX_BYTES
        }
        val dataSpec = playbackDataSpec(entry).buildUpon()
            .setPosition(0L)
            .setLength(target)
            .build()
        val writer = CacheWriter(cacheDataSourceFactory.createDataSource(), dataSpec, null, null)
        synchronized(prefetchLock) {
            if (generation != prefetchGeneration) return
            activeCacheWriter = writer
        }
        try {
            writer.cache()
        } finally {
            synchronized(prefetchLock) {
                if (activeCacheWriter === writer) activeCacheWriter = null
            }
        }
    }

    @Throws(IOException::class)
    private fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val uri = dataSpec.uri
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            !uri.host.equals("short.deepfuck.you", ignoreCase = true) ||
            uri.port !in setOf(-1, 443) || uri.userInfo != null
        ) return dataSpec
        val match = PLAY_PATH.matchEntire(uri.path.orEmpty()) ?: return dataSpec
        val mediaId = match.groupValues[1].toLongOrNull() ?: return dataSpec
        val cacheKey = cacheKeyFactory.buildCacheKey(dataSpec)
        val forceRefresh = forceRefreshMediaIds.remove(mediaId)
        if (forceRefresh) resolvedPlayUrls.invalidate(cacheKey)
        val target = if (forceRefresh) {
            uri.buildUpon().appendQueryParameter("refresh", "true").build().toString()
        } else {
            uri.toString()
        }
        val resolved = resolvedPlayUrls.resolve(cacheKey) { api.resolvePlayUrl(target) }
        return dataSpec.withUri(Uri.parse(resolved))
    }

    private fun resolvePlayEntry(entry: MediaEntry) {
        resolveDataSpec(playbackDataSpec(entry))
    }

    private fun retryWithFreshRedirect(error: PlaybackException): Boolean {
        val entry = currentEntry ?: return false
        val dataSpec = playbackDataSpec(entry)
        val uri = dataSpec.uri
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            !uri.host.equals("short.deepfuck.you", ignoreCase = true) ||
            uri.port !in setOf(-1, 443) || uri.userInfo != null
        ) return false
        val mediaId = PLAY_PATH.matchEntire(uri.path.orEmpty())?.groupValues?.get(1)?.toLongOrNull()
            ?: return false
        if (redirectRetryMediaId == mediaId || !error.isRetryableRedirectFailure()) return false
        redirectRetryMediaId = mediaId
        resolvedPlayUrls.invalidate(cacheKeyFactory.buildCacheKey(dataSpec))
        forceRefreshMediaIds.add(mediaId)
        logs.info("player_redirect_retry", "media=$mediaId code=${error.errorCodeName}")
        val resumePlayback = player.playWhenReady
        playbackError.clear()
        player.prepare()
        player.playWhenReady = resumePlayback
        return true
    }

    private fun normalizeQueue(entry: MediaEntry, queue: List<MediaEntry>): List<MediaEntry> {
        val distinct = queue.distinctBy(MediaEntry::id)
        return if (distinct.any { it.id == entry.id }) distinct.map { if (it.id == entry.id) entry else it }
        else listOf(entry) + distinct
    }

    private fun mediaItem(entry: MediaEntry): MediaItem = MediaItem.Builder()
        .setMediaId(entry.id.toString())
        .setUri(playbackUrl(entry))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(entry.title)
                .setArtist(entry.author)
                .build(),
        )
        .setMimeType(mediaMimeType(entry))
        .build()

    private fun publish(error: String? = null) {
        playbackError.record(error)
        val entry = currentEntry
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
        mutableSnapshot.value = PlayerSnapshot(
            mediaId = entry?.id,
            title = entry?.title.orEmpty(),
            isAudio = entry?.isAudio == true,
            isPlaying = player.isPlaying,
            playWhenReady = player.playWhenReady,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            volume = volumeFractionForIndex(
                audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
                audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            ),
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = duration,
            bufferedMs = player.bufferedPosition.coerceAtLeast(0L),
            error = playbackError.error,
        )
    }

    private companion object {
        val PLAY_PATH = Regex("^/api/videos/(\\d+)/play$")
        const val RESOLVED_URL_TTL_MS = 480_000L
        const val MAX_FULL_SHORT_BYTES = 96L * 1024 * 1024
        const val SHORT_PREFIX_BYTES = 24L * 1024 * 1024
        const val LONG_PREFIX_BYTES = 12L * 1024 * 1024
        const val MOVIE_PREFIX_BYTES = 24L * 1024 * 1024
        const val LARGE_MOVIE_PREFIX_BYTES = 32L * 1024 * 1024
        const val LARGE_MOVIE_SIZE_BYTES = 8L * 1024 * 1024 * 1024
    }
}

private fun PlaybackException.isRetryableRedirectFailure(): Boolean {
    val responseCode = generateSequence(cause) { it.cause }
        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
        .firstOrNull()
        ?.responseCode
    if (responseCode in setOf(401, 403, 404, 410)) return true
    return errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
}
