package you.deepfuck.shortvideo.data

import org.json.JSONObject

enum class MediaSurface(val apiValue: String, val label: String) {
    SHORT("short", "短视频"),
    LONG("long", "长视频"),
    ASMR("asmr", "ASMR"),
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
)

data class AdminStatus(
    val totalItems: Int,
    val totalBytes: Long,
    val guangyaItems: Int,
    val asmrItems: Int,
    val scanRunning: Boolean,
    val scanLastSuccess: Long?,
    val scanLastError: String?,
    val sources: List<MediaLibrarySource>,
    val fastStartRunning: Boolean,
    val fastStartOptimized: Int,
    val fastStartIssues: Int,
    val fastStartPending: Int,
)

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
