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

    var movieWallView: MovieWallView
        get() = MovieWallView.fromValue(preferences.getString(KEY_MOVIE_WALL_VIEW, null))
        set(value) = preferences.edit().putString(KEY_MOVIE_WALL_VIEW, value.apiValue).apply()

    /**
     * The order the wall itself is browsed in. It survives a restart so the
     * wall comes back the way the user left it.
     */
    internal var movieWallSort: String
        get() = preferences.getString(KEY_MOVIE_WALL_SORT, null).orEmpty()
        set(value) = preferences.edit().putString(KEY_MOVIE_WALL_SORT, value).apply()

    fun muted(surface: MediaSurface): Boolean {
        val key = "$KEY_MUTED:${surface.name}"
        if (preferences.contains(key)) return preferences.getBoolean(key, false)
        return if (surface.isFeed) preferences.getBoolean(KEY_MUTED, true) else false
    }

    fun setMuted(surface: MediaSurface, muted: Boolean) {
        preferences.edit().putBoolean("$KEY_MUTED:${surface.name}", muted).apply()
    }

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

    /**
     * One library page per source, so each keeps its own scroll offset. Opening a
     * film from deep inside a library and coming back used to drop the user at the
     * top of the grid.
     */
    internal fun movieGroupListPosition(sourceId: String): ListPosition =
        listPosition("$KEY_MOVIE_GROUP_LIST_POSITION:$sourceId")

    internal fun saveMovieGroupListPosition(sourceId: String, position: ListPosition) =
        saveListPosition("$KEY_MOVIE_GROUP_LIST_POSITION:$sourceId", position)

    /**
     * The sort a library page was last browsed with. Keeping it means the wall
     * preview and the page agree on which six titles lead, and reopening a
     * library does not silently drop the order the user picked.
     */
    internal fun movieGroupSort(sourceId: String): String =
        preferences.getString("$KEY_MOVIE_GROUP_SORT:$sourceId", null).orEmpty()

    internal fun saveMovieGroupSort(sourceId: String, sort: String) {
        preferences.edit().putString("$KEY_MOVIE_GROUP_SORT:$sourceId", sort).apply()
    }

    /**
     * Every library's saved order, for the wall to preview each section with.
     *
     * The wall draws the first six titles of a library, so it needs the same
     * order that library's own page is browsed with; sending them together in
     * one request is what keeps the two from disagreeing.
     */
    internal fun movieGroupSorts(): Map<String, String> =
        preferences.all
            .filterKeys { it.startsWith("$KEY_MOVIE_GROUP_SORT:") }
            .mapNotNull { (key, value) ->
                val sourceId = key.removePrefix("$KEY_MOVIE_GROUP_SORT:")
                val sort = value as? String
                if (sourceId.isBlank() || sort.isNullOrBlank()) null else sourceId to sort
            }
            .toMap()

    internal fun dramaListPosition(): ListPosition =
        listPosition(KEY_DRAMA_LIST_POSITION)

    internal fun saveDramaListPosition(position: ListPosition) =
        saveListPosition(KEY_DRAMA_LIST_POSITION, position)

    /**
     * The favourites grid is a permanent destination of its own, so it keeps a
     * scroll offset like a library page does: opening a film from halfway down
     * and coming back should land on that poster again.
     */
    internal fun movieFavoritesListPosition(): ListPosition =
        listPosition(KEY_MOVIE_FAVORITES_LIST_POSITION)

    internal fun saveMovieFavoritesListPosition(position: ListPosition) =
        saveListPosition(KEY_MOVIE_FAVORITES_LIST_POSITION, position)

    fun dramaEpisodeId(dramaId: String): Long? =
        preferences.getLong("${KEY_DRAMA_EPISODE}:$dramaId", -1L).takeIf { it > 0L }

    fun setDramaEpisodeId(dramaId: String, videoId: Long) {
        if (dramaId.isBlank() || videoId <= 0L) return
        preferences.edit().putLong("${KEY_DRAMA_EPISODE}:$dramaId", videoId).apply()
    }

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
        const val KEY_MOVIE_WALL_VIEW = "movie_wall_view"
        const val KEY_MOVIE_WALL_SORT = "movie_wall_sort"
        const val KEY_MUTED = "muted"
        const val KEY_ASMR_VIDEO_BACKGROUND = "asmr_video_background"
        const val KEY_ASMR_AUTHOR = "asmr_author"
        const val KEY_ASMR_PLAYBACK = "asmr_playback"
        const val KEY_ASMR_AUTHOR_INDEX = "asmr_author_index"
        const val KEY_ASMR_AUTHOR_LIST_POSITION = "asmr_author_list_position"
        const val KEY_ASMR_MEDIA_LIST_POSITION = "asmr_media_list_position"
        const val KEY_MOVIE_LIST_POSITION = "movie_list_position"
        const val KEY_MOVIE_GROUP_LIST_POSITION = "movie_group_list_position"
        const val KEY_MOVIE_GROUP_SORT = "movie_group_sort"
        const val KEY_MOVIE_FAVORITES_LIST_POSITION = "movie_favorites_list_position"
        const val KEY_DRAMA_LIST_POSITION = "drama_list_position"
        const val KEY_DRAMA_EPISODE = "drama_episode"
        const val KEY_RECENT = "recent"
        const val KEY_POSITIONS = "positions"
        const val FEED_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1_000
        const val FEED_CACHE_ITEM_LIMIT = 180
    }
}
