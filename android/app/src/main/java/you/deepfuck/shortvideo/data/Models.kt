package you.deepfuck.shortvideo.data

import org.json.JSONObject

enum class MediaSurface(val apiValue: String, val label: String) {
    SHORT("short", "短视频"),
    LONG("long", "长视频"),
    ASMR("asmr", "ASMR"),

    ;

    val supportsBackgroundPlayback: Boolean get() = this == ASMR
}

enum class FeedMode(val apiValue: String, val label: String) {
    SHUFFLE("shuffle", "随机播放"),
    NEWEST("newest", "最新优先"),
    OLDEST("oldest", "最早优先"),
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
)

data class AdminStatus(
    val totalItems: Int,
    val totalBytes: Long,
    val guangyaItems: Int,
    val asmrItems: Int,
    val scanRunning: Boolean,
    val scanLastSuccess: Long?,
    val scanLastError: String?,
    val fastStartRunning: Boolean,
    val fastStartOptimized: Int,
    val fastStartIssues: Int,
    val fastStartPending: Int,
)

internal fun JSONObject.optNullableString(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
