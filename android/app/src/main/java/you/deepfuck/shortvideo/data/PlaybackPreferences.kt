package you.deepfuck.shortvideo.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class PlaybackPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("short_video", Context.MODE_PRIVATE)

    var sessionCookie: String?
        get() = preferences.getString(KEY_SESSION, null)
        set(value) {
            preferences.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_SESSION) else putString(KEY_SESSION, value)
            }.apply()
        }

    var surface: MediaSurface
        get() = runCatching {
            MediaSurface.valueOf(preferences.getString(KEY_SURFACE, null).orEmpty())
        }.getOrDefault(MediaSurface.SHORT)
        set(value) = preferences.edit().putString(KEY_SURFACE, value.name).apply()

    var mode: FeedMode
        get() = runCatching {
            FeedMode.valueOf(preferences.getString(KEY_MODE, null).orEmpty())
        }.getOrDefault(FeedMode.SHUFFLE)
        set(value) = preferences.edit().putString(KEY_MODE, value.name).apply()

    var muted: Boolean
        get() = preferences.getBoolean(KEY_MUTED, true)
        set(value) = preferences.edit().putBoolean(KEY_MUTED, value).apply()

    var asmrVideoBackgroundPlayback: Boolean
        get() = preferences.getBoolean(KEY_ASMR_VIDEO_BACKGROUND, false)
        set(value) = preferences.edit().putBoolean(KEY_ASMR_VIDEO_BACKGROUND, value).apply()

    fun lastVideoId(surface: MediaSurface): Long? =
        preferences.getLong("last_${surface.name}", -1L).takeIf { it > 0L }

    fun setLastVideo(surface: MediaSurface, mediaId: Long) {
        preferences.edit().putLong("last_${surface.name}", mediaId).apply()
        val recent = listOf(mediaId) + recentVideoIds().filterNot { it == mediaId }
        preferences.edit().putString(KEY_RECENT, JSONArray(recent.take(100)).toString()).apply()
    }

    fun recentVideoIds(): List<Long> = runCatching {
        val array = JSONArray(preferences.getString(KEY_RECENT, "[]"))
        buildList {
            for (index in 0 until array.length()) {
                array.optLong(index).takeIf { it > 0L }?.let(::add)
            }
        }
    }.getOrDefault(emptyList())

    fun position(mediaId: Long): Long = runCatching {
        JSONObject(preferences.getString(KEY_POSITIONS, "{}") ?: "{}")
            .optLong(mediaId.toString(), 0L)
    }.getOrDefault(0L)

    fun savePosition(mediaId: Long, positionMs: Long, durationMs: Long) {
        if (mediaId <= 0L || positionMs < 0L) return
        val saved = if (
            durationMs > 0L &&
            (durationMs - positionMs <= 3_000L || positionMs.toDouble() / durationMs >= 0.97)
        ) 0L else positionMs
        val positions = runCatching {
            JSONObject(preferences.getString(KEY_POSITIONS, "{}") ?: "{}")
        }.getOrElse { JSONObject() }
        positions.put(mediaId.toString(), saved)
        preferences.edit().putString(KEY_POSITIONS, positions.toString()).apply()
    }

    fun cachedFeed(surface: MediaSurface, mode: FeedMode): List<MediaEntry> = runCatching {
        val raw = preferences.getString(feedKey(surface, mode), null) ?: return emptyList()
        val payload = JSONObject(raw)
        if (System.currentTimeMillis() - payload.optLong("savedAt") > FEED_MAX_AGE_MS) {
            return emptyList()
        }
        val items = payload.optJSONArray("items") ?: return emptyList()
        buildList {
            for (index in 0 until items.length()) {
                items.optJSONObject(index)?.let { add(MediaEntry.fromJson(it)) }
            }
        }
    }.getOrDefault(emptyList())

    fun saveFeed(surface: MediaSurface, mode: FeedMode, items: List<MediaEntry>) {
        val payload = JSONObject()
            .put("savedAt", System.currentTimeMillis())
            .put("items", JSONArray(items.take(18).map(MediaEntry::toJson)))
        preferences.edit().putString(feedKey(surface, mode), payload.toString()).apply()
    }

    fun clearSession() {
        sessionCookie = null
    }

    private fun feedKey(surface: MediaSurface, mode: FeedMode) =
        "feed_${surface.name}_${mode.name}"

    private companion object {
        const val KEY_SESSION = "session_cookie"
        const val KEY_SURFACE = "surface"
        const val KEY_MODE = "mode"
        const val KEY_MUTED = "muted"
        const val KEY_ASMR_VIDEO_BACKGROUND = "asmr_video_background"
        const val KEY_RECENT = "recent"
        const val KEY_POSITIONS = "positions"
        const val FEED_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1_000
    }
}
