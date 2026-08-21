package you.deepfuck.shortvideo.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import you.deepfuck.shortvideo.AsmrPlaybackState
import you.deepfuck.shortvideo.FeedSessionState
import you.deepfuck.shortvideo.ListPosition

internal data class AsmrAuthorIndex(
    val items: List<AsmrAuthor>,
    val total: Int,
    val savedAtMs: Long,
)

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

    var selectedAsmrAuthor: String?
        get() = preferences.getString(KEY_ASMR_AUTHOR, null)
        set(value) {
            preferences.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_ASMR_AUTHOR) else putString(KEY_ASMR_AUTHOR, value)
            }.apply()
        }

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

    internal fun feedSession(surface: MediaSurface, mode: FeedMode): FeedSessionState? = runCatching {
        if (!surface.isFeed) return null
        val raw = preferences.getString(feedKey(surface, mode), null) ?: return null
        val payload = JSONObject(raw)
        if (System.currentTimeMillis() - payload.optLong("savedAt") > FEED_MAX_AGE_MS) {
            return null
        }
        val itemsJson = payload.optJSONArray("items") ?: return null
        val items = buildList {
            for (index in 0 until itemsJson.length()) {
                itemsJson.optJSONObject(index)?.let { add(MediaEntry.fromJson(it)) }
            }
        }
        val excludedJson = payload.optJSONArray("excludedIds")
        val excludedIds = buildList {
            if (excludedJson != null) for (index in 0 until excludedJson.length()) {
                excludedJson.optLong(index).takeIf { it > 0L }?.let(::add)
            }
        }
        FeedSessionState(
            items = items,
            activeMediaId = payload.optLong("activeMediaId", -1L).takeIf { it > 0L }
                ?: lastVideoId(surface),
            nextCursor = payload.optNullableString("nextCursor"),
            total = payload.optInt("total", items.size),
            playWhenReady = payload.optBoolean("playWhenReady", true),
            excludedIds = excludedIds,
            startId = payload.optLong("startId", -1L).takeIf { it > 0L },
        )
    }.getOrNull()

    internal fun saveFeedSession(surface: MediaSurface, mode: FeedMode, session: FeedSessionState) {
        require(surface.isFeed) { "Only feed surfaces can own feed sessions" }
        val stored = session.storageWindow(FEED_CACHE_ITEM_LIMIT)
        val payload = JSONObject()
            .put("savedAt", System.currentTimeMillis())
            .put("items", JSONArray(stored.items.map(MediaEntry::toJson)))
            .put("activeMediaId", stored.activeMediaId)
            .put("nextCursor", stored.nextCursor)
            .put("total", stored.total)
            .put("playWhenReady", stored.playWhenReady)
            .put("excludedIds", JSONArray(stored.excludedIds))
            .put("startId", stored.startId)
        preferences.edit().putString(feedKey(surface, mode), payload.toString()).apply()
    }

    internal fun asmrPlaybackState(): AsmrPlaybackState? = runCatching {
        val raw = preferences.getString(KEY_ASMR_PLAYBACK, null) ?: return null
        val payload = JSONObject(raw)
        AsmrPlaybackState(
            entry = MediaEntry.fromJson(payload.getJSONObject("entry")),
            playWhenReady = payload.optBoolean("playWhenReady", true),
            expanded = payload.optBoolean("expanded", false),
        )
    }.getOrNull()

    internal fun saveAsmrPlaybackState(state: AsmrPlaybackState?) {
        preferences.edit().apply {
            if (state == null) {
                remove(KEY_ASMR_PLAYBACK)
            } else {
                putString(
                    KEY_ASMR_PLAYBACK,
                    JSONObject()
                        .put("entry", state.entry.toJson())
                        .put("playWhenReady", state.playWhenReady)
                        .put("expanded", state.expanded)
                        .toString(),
                )
            }
        }.apply()
    }

    internal fun asmrAuthorIndex(): AsmrAuthorIndex? = runCatching {
        val raw = preferences.getString(KEY_ASMR_AUTHOR_INDEX, null) ?: return null
        val payload = JSONObject(raw)
        val itemsJson = payload.optJSONArray("items") ?: return null
        val items = buildList {
            for (index in 0 until itemsJson.length()) {
                val author = itemsJson.optJSONObject(index)?.let(AsmrAuthor::fromJson) ?: continue
                if (author.name.isNotBlank()) add(author)
            }
        }
        if (items.isEmpty()) return null
        AsmrAuthorIndex(
            items = items,
            total = payload.optInt("total", items.size).coerceAtLeast(items.size),
            savedAtMs = payload.optLong("savedAt"),
        )
    }.getOrNull()

    internal fun saveAsmrAuthorIndex(items: List<AsmrAuthor>, total: Int) {
        if (items.isEmpty()) {
            clearAsmrAuthorIndex()
            return
        }
        val payload = JSONObject()
            .put("savedAt", System.currentTimeMillis())
            .put("total", total.coerceAtLeast(items.size))
            .put("items", JSONArray(items.map(AsmrAuthor::toJson)))
        preferences.edit().putString(KEY_ASMR_AUTHOR_INDEX, payload.toString()).apply()
    }

    internal fun clearAsmrAuthorIndex() {
        preferences.edit().remove(KEY_ASMR_AUTHOR_INDEX).apply()
    }

    internal fun clearFeedSessions() {
        preferences.edit().apply {
            MediaSurface.entries
                .filter { it.isFeed }
                .forEach { surface ->
                    FeedMode.entries.forEach { mode -> remove(feedKey(surface, mode)) }
                }
        }.apply()
    }

    internal fun asmrAuthorListPosition(): ListPosition =
        listPosition(KEY_ASMR_AUTHOR_LIST_POSITION)

    internal fun saveAsmrAuthorListPosition(position: ListPosition) =
        saveListPosition(KEY_ASMR_AUTHOR_LIST_POSITION, position)

    internal fun asmrMediaListPosition(author: String): ListPosition =
        listPosition("$KEY_ASMR_MEDIA_LIST_POSITION:$author")

    internal fun saveAsmrMediaListPosition(author: String, position: ListPosition) =
        saveListPosition("$KEY_ASMR_MEDIA_LIST_POSITION:$author", position)

    internal fun movieListPosition(): ListPosition =
        listPosition(KEY_MOVIE_LIST_POSITION)

    internal fun saveMovieListPosition(position: ListPosition) =
        saveListPosition(KEY_MOVIE_LIST_POSITION, position)

    fun clearSession() {
        sessionCookie = null
    }

    private fun feedKey(surface: MediaSurface, mode: FeedMode) =
        "feed_${surface.name}_${mode.name}"

    private fun listPosition(key: String): ListPosition = runCatching {
        val payload = JSONObject(preferences.getString(key, "{}") ?: "{}")
        ListPosition(payload.optInt("index"), payload.optInt("offset"))
    }.getOrDefault(ListPosition())

    private fun saveListPosition(key: String, position: ListPosition) {
        preferences.edit().putString(
            key,
            JSONObject().put("index", position.index).put("offset", position.offset).toString(),
        ).apply()
    }

    private companion object {
        const val KEY_SESSION = "session_cookie"
        const val KEY_SURFACE = "surface"
        const val KEY_MODE = "mode"
        const val KEY_MUTED = "muted"
        const val KEY_ASMR_VIDEO_BACKGROUND = "asmr_video_background"
        const val KEY_ASMR_AUTHOR = "asmr_author"
        const val KEY_ASMR_PLAYBACK = "asmr_playback"
        const val KEY_ASMR_AUTHOR_INDEX = "asmr_author_index"
        const val KEY_ASMR_AUTHOR_LIST_POSITION = "asmr_author_list_position"
        const val KEY_ASMR_MEDIA_LIST_POSITION = "asmr_media_list_position"
        const val KEY_MOVIE_LIST_POSITION = "movie_list_position"
        const val KEY_RECENT = "recent"
        const val KEY_POSITIONS = "positions"
        const val FEED_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1_000
        const val FEED_CACHE_ITEM_LIMIT = 180
    }
}
