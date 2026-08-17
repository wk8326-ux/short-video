@file:androidx.annotation.OptIn(
    markerClass = [androidx.media3.common.util.UnstableApi::class],
)

package you.deepfuck.shortvideo.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import you.deepfuck.shortvideo.data.MediaApi
import you.deepfuck.shortvideo.data.MediaEntry
import you.deepfuck.shortvideo.data.MediaSurface

data class PlayerSnapshot(
    val mediaId: Long? = null,
    val title: String = "",
    val isAudio: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val error: String? = null,
)

class PlaybackEngine(context: Context, private val api: MediaApi) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = MediaCacheStore.get(context)
    private val cacheKeyFactory = StableCacheKeyFactory()
    private var currentEntry: MediaEntry? = null
    private var prefetchJob: Job? = null

    private val upstreamFactory = ResolvingDataSource.Factory(
        DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(false),
    ) { dataSpec -> resolveDataSpec(dataSpec) }

    val cacheDataSourceFactory: CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setCacheKeyFactory(cacheKeyFactory)
        .setUpstreamDataSourceFactory(upstreamFactory)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
        .setWakeMode(C.WAKE_MODE_NETWORK)
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
                override fun onPlaybackStateChanged(playbackState: Int) = publish()
                override fun onIsPlayingChanged(isPlaying: Boolean) = publish()

                override fun onPlayerError(error: PlaybackException) {
                    publish(error.errorCodeName)
                }
            },
        )
    }

    fun play(entry: MediaEntry, resumePositionMs: Long, autoPlay: Boolean = true) {
        if (currentEntry?.id == entry.id) {
            if (autoPlay) player.play()
            return
        }
        currentEntry = entry
        val media = MediaItem.Builder()
            .setMediaId(entry.id.toString())
            .setUri(api.absoluteUrl(entry.playUrl))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(entry.title)
                    .setArtist(entry.author)
                    .build(),
            )
            .setMimeType(
                when {
                    entry.isHls -> MimeTypes.APPLICATION_M3U8
                    entry.isAudio -> MimeTypes.AUDIO_MPEG
                    else -> null
                },
            )
            .build()
        player.setMediaItem(media, resumePositionMs.coerceAtLeast(0L))
        player.prepare()
        player.playWhenReady = autoPlay
        publish()
    }

    fun togglePlayback() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun pause() {
        player.pause()
        publish()
    }

    fun setMuted(muted: Boolean) {
        player.volume = if (muted) 0f else 1f
        publish()
    }

    fun seekTo(positionMs: Long) {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
        player.seekTo(positionMs.coerceIn(0L, duration ?: Long.MAX_VALUE))
        publish()
    }

    fun seekBy(deltaMs: Long) {
        seekTo(player.currentPosition + deltaMs)
    }

    fun stop() {
        player.pause()
        player.clearMediaItems()
        currentEntry = null
        publish()
    }

    fun refreshPosition() = publish()

    fun prefetch(surface: MediaSurface, entries: List<MediaEntry>) {
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            entries.take(2).filterNot(MediaEntry::isHls).forEach { entry ->
                runCatching { cacheProgressive(surface, entry) }
            }
        }
    }

    fun cachedBytes(entry: MediaEntry): Long {
        val length = entry.size.takeIf { it > 0L } ?: return 0L
        return cache.getCachedBytes("media:${entry.id}", 0L, length)
    }

    fun currentMedia(): MediaEntry? = currentEntry

    fun release() {
        prefetchJob?.cancel()
        scope.cancel()
        player.release()
    }

    private fun cacheProgressive(surface: MediaSurface, entry: MediaEntry) {
        val target = when {
            surface == MediaSurface.SHORT && entry.size in 1..MAX_FULL_SHORT_BYTES -> entry.size
            surface == MediaSurface.SHORT -> SHORT_PREFIX_BYTES
            else -> LONG_PREFIX_BYTES
        }
        val dataSpec = DataSpec.Builder()
            .setUri(api.absoluteUrl(entry.playUrl))
            .setKey("media:${entry.id}")
            .setPosition(0L)
            .setLength(target)
            .build()
        CacheWriter(cacheDataSourceFactory.createDataSource(), dataSpec, null, null).cache()
    }

    @Throws(IOException::class)
    private fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val uri = dataSpec.uri
        if (!uri.host.equals("short.deepfuck.you", ignoreCase = true)) return dataSpec
        if (!uri.path.orEmpty().matches(PLAY_PATH)) return dataSpec
        return dataSpec.withUri(Uri.parse(api.resolvePlayUrl(uri.toString())))
    }

    private fun publish(error: String? = null) {
        val entry = currentEntry
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
        mutableSnapshot.value = PlayerSnapshot(
            mediaId = entry?.id,
            title = entry?.title.orEmpty(),
            isAudio = entry?.isAudio == true,
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = duration,
            bufferedMs = player.bufferedPosition.coerceAtLeast(0L),
            error = error,
        )
    }

    private companion object {
        val PLAY_PATH = Regex("^/api/videos/\\d+/play$")
        const val MAX_FULL_SHORT_BYTES = 96L * 1024 * 1024
        const val SHORT_PREFIX_BYTES = 24L * 1024 * 1024
        const val LONG_PREFIX_BYTES = 12L * 1024 * 1024
    }
}
