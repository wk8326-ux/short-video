package you.deepfuck.shortvideo.data

import org.json.JSONObject

enum class MediaSurface(val apiValue: String, val label: String) {
    SHORT("short", "短视频"),
    LONG("long", "长视频"),
    ASMR("asmr", "ASMR"),
    MOVIE("movie", "电影"),
    ;

    val isFeed: Boolean get() = this == SHORT || this == LONG
}

enum class FeedMode(val apiValue: String, val label: String) {
    SHUFFLE("shuffle", "随机播放"),
    NEWEST("newest", "最新优先"),
    OLDEST("oldest", "最早优先"),
}

enum class AsmrFilter(val apiValue: String, val label: String) {
    ALL("all", "全部"),
    VIDEO("video", "视频"),
    AUDIO("audio", "音频"),
}

enum class LibrarySection(val apiValue: String, val label: String) {
    FEED("feed", "短视频 / 长视频"),
    ASMR("asmr", "ASMR"),
    MOVIE("movie", "电影"),
    ;

    val usesTreeScan: Boolean get() = this != ASMR
}

data class MediaEntry(
    val id: Long,
    val title: String,
    val size: Long,
    val modified: String?,
    val durationSeconds: Double?,
    val playUrl: String,
    val posterUrl: String?,
    val author: String? = null,
    val format: String? = null,
    val kind: String = "video",
) {
    val isHls: Boolean get() = format.equals("m3u8", ignoreCase = true)
    val isAudio: Boolean get() = kind == "audio" && !isHls

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("size", size)
        .put("modified", modified)
        .put("duration", durationSeconds)
        .put("playUrl", playUrl)
        .put("posterUrl", posterUrl)
        .put("author", author)
        .put("format", format)
        .put("kind", kind)

    companion object {
        fun fromJson(value: JSONObject): MediaEntry = MediaEntry(
            id = value.getLong("id"),
            title = value.optString("title"),
            size = value.optLong("size"),
            modified = value.optNullableString("modified"),
            durationSeconds = value.optDouble("duration").takeIf { it.isFinite() },
            playUrl = value.optString("playUrl"),
            posterUrl = value.optNullableString("posterUrl"),
            author = value.optNullableString("author"),
            format = value.optNullableString("format"),
            kind = value.optString("kind", "video"),
        )
    }
}

data class FeedPage(
    val items: List<MediaEntry>,
    val nextCursor: String?,
    val total: Int,
    val scanRunning: Boolean,
)

data class AsmrAuthor(
    val name: String,
    val itemCount: Int,
    val videoCount: Int,
    val audioCount: Int,
    val modified: String?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("itemCount", itemCount)
        .put("videoCount", videoCount)
        .put("audioCount", audioCount)
        .put("modified", modified)

    companion object {
        fun fromJson(value: JSONObject): AsmrAuthor = AsmrAuthor(
            name = value.optString("name"),
            itemCount = value.optInt("itemCount"),
            videoCount = value.optInt("videoCount"),
            audioCount = value.optInt("audioCount"),
            modified = value.optNullableString("modified"),
        )
    }
}

data class AsmrPage<T>(
    val items: List<T>,
    val total: Int,
    val nextOffset: Int?,
    val scanRunning: Boolean = false,
)

data class MovieItem(
    val id: Long,
    val videoId: Long,
    val title: String,
    val originalTitle: String?,
    val year: Int?,
    val overview: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val wallUrl: String? = null,
    val rating: Double?,
    val runtimeMinutes: Int?,
    val matchStatus: String,
    val matchConfidence: Double?,
    val playUrl: String,
    val modified: String?,
    val durationSeconds: Double?,
    val source: String? = null,
    val path: String? = null,
    val size: Long = 0L,
    val format: String? = null,
    val metadataProvider: String? = null,
    val releaseDate: String? = null,
    val genres: List<String> = emptyList(),
    val performers: List<String> = emptyList(),
    val studio: String? = null,
    val resumePositionMs: Long = 0L,
) {
    val resumeFraction: Float
        get() {
            val durationMs = (
                durationSeconds?.times(1_000)?.toLong()
                    ?: runtimeMinutes?.times(60_000L)
                )?.takeIf { it > 0L } ?: return 0f
            return (resumePositionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        }

    fun asMediaEntry(): MediaEntry = MediaEntry(
        id = videoId,
        title = title,
        size = size,
        modified = modified,
        durationSeconds = durationSeconds,
        playUrl = playUrl,
        posterUrl = posterUrl,
        format = format,
    )

    companion object {
        fun fromJson(value: JSONObject): MovieItem = MovieItem(
            id = value.getLong("id"),
            videoId = value.getLong("videoId"),
            title = value.optString("title"),
            originalTitle = value.optNullableString("originalTitle"),
            year = value.optNullableInt("year"),
            overview = value.optString("overview"),
            posterUrl = value.optNullableString("posterUrl"),
            backdropUrl = value.optNullableString("backdropUrl"),
            wallUrl = value.optNullableString("wallUrl"),
            rating = value.optNullableDouble("rating"),
            runtimeMinutes = value.optNullableInt("runtimeMinutes"),
            matchStatus = value.optString("matchStatus", "pending"),
            matchConfidence = value.optNullableDouble("matchConfidence"),
            playUrl = value.optString("playUrl"),
            modified = value.optNullableString("modified"),
            durationSeconds = value.optNullableDouble("duration"),
            source = value.optNullableString("source"),
            path = value.optNullableString("path"),
            size = value.optLong("size"),
            format = value.optNullableString("format"),
            metadataProvider = value.optNullableString("metadataProvider"),
            releaseDate = value.optNullableString("releaseDate"),
            genres = value.optStringList("genres"),
            performers = value.optStringList("performers"),
            studio = value.optNullableString("studio"),
        )
    }
}

data class MoviePage(
    val items: List<MovieItem>,
    val total: Int,
    val nextOffset: Int?,
    val scanRunning: Boolean,
)

data class AdminStatus(
    val totalItems: Int,
    val totalBytes: Long,
    val guangyaItems: Int,
    val asmrItems: Int,
    val movieItems: Int,
    val scanRunning: Boolean,
    val scanLastSuccess: Long?,
    val scanLastError: String?,
    val sources: List<MediaLibrarySource>,
    val movieMetadata: MovieMetadataStatus,
    val fastStartRunning: Boolean,
    val fastStartOptimized: Int,
    val fastStartIssues: Int,
    val fastStartPending: Int,
)

data class MovieMetadataStatus(
    val running: Boolean,
    val sourceId: String?,
    val checked: Int,
    val total: Int,
    val matched: Int,
    val lastSuccess: Long?,
    val lastError: String?,
) {
    companion object {
        fun fromJson(value: JSONObject?): MovieMetadataStatus = MovieMetadataStatus(
            running = value?.optBoolean("running") == true,
            sourceId = value?.optNullableString("source"),
            checked = value?.optInt("checked") ?: 0,
            total = value?.optInt("total") ?: 0,
            matched = value?.optInt("matched") ?: 0,
            lastSuccess = value?.optLong("lastSuccess")?.takeIf { it > 0L },
            lastError = value?.optNullableString("lastError"),
        )
    }
}

data class MovieMetadataSummary(
    val total: Int,
    val pending: Int,
    val matched: Int,
    val ambiguous: Int,
    val unmatched: Int,
    val lastSuccess: Long?,
) {
    val needsReview: Int get() = ambiguous + unmatched

    companion object {
        fun fromJson(value: JSONObject?): MovieMetadataSummary? = value?.let {
            MovieMetadataSummary(
                total = it.optInt("total"),
                pending = it.optInt("pending"),
                matched = it.optInt("matched"),
                ambiguous = it.optInt("ambiguous"),
                unmatched = it.optInt("unmatched"),
                lastSuccess = it.optLong("lastSuccess").takeIf { value -> value > 0L },
            )
        }
    }
}

data class LibraryScanStatus(
    val running: Boolean,
    val lastSuccess: Long?,
    val lastError: String?,
    val directories: Int,
) {
    companion object {
        fun fromJson(value: JSONObject?): LibraryScanStatus = LibraryScanStatus(
            running = value?.optBoolean("running") == true,
            lastSuccess = value?.optLong("lastSuccess")?.takeIf { it > 0L },
            lastError = value?.optNullableString("lastError"),
            directories = value?.optInt("directories") ?: 0,
        )
    }
}

data class MediaLibrarySource(
    val id: String,
    val name: String,
    val provider: String,
    val baseUrl: String,
    val rootPath: String,
    val section: LibrarySection,
    val scanMode: String,
    val anonymous: Boolean,
    val usernameConfigured: Boolean,
    val tokenConfigured: Boolean,
    val enabled: Boolean,
    val videos: Int,
    val bytes: Long,
    val scan: LibraryScanStatus,
    val movieMetadata: MovieMetadataSummary?,
) {
    companion object {
        fun fromJson(value: JSONObject): MediaLibrarySource = MediaLibrarySource(
            id = value.optString("id"),
            name = value.optString("name"),
            provider = value.optString("provider", "alist"),
            baseUrl = value.optString("baseUrl"),
            rootPath = value.optString("rootPath", "/"),
            section = LibrarySection.entries.firstOrNull {
                it.apiValue == value.optString("section")
            } ?: LibrarySection.FEED,
            scanMode = value.optString("scanMode", "tree"),
            anonymous = value.optBoolean("anonymous", true),
            usernameConfigured = value.optBoolean("usernameConfigured"),
            tokenConfigured = value.optBoolean("tokenConfigured"),
            enabled = value.optBoolean("enabled", true),
            videos = value.optInt("videos"),
            bytes = value.optLong("bytes"),
            scan = LibraryScanStatus.fromJson(value.optJSONObject("scan")),
            movieMetadata = MovieMetadataSummary.fromJson(value.optJSONObject("movieMetadata")),
        )
    }
}

data class MediaSourceDraft(
    val name: String,
    val provider: String,
    val baseUrl: String,
    val rootPath: String,
    val section: LibrarySection,
    val scanMode: String,
    val anonymous: Boolean,
    val token: String = "",
    val username: String = "",
    val password: String = "",
    val enabled: Boolean = true,
)

internal fun JSONObject.optNullableString(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

private fun JSONObject.optStringList(name: String): List<String> =
    optJSONArray(name)?.let { array ->
        buildList {
            for (index in 0 until array.length()) {
                array.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }.orEmpty()

private fun JSONObject.optNullableInt(name: String): Int? =
    if (!has(name) || isNull(name)) null else optInt(name)

private fun JSONObject.optNullableDouble(name: String): Double? =
    if (!has(name) || isNull(name)) null else optDouble(name).takeIf { it.isFinite() }
