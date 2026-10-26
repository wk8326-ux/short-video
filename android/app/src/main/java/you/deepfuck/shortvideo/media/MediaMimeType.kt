package you.deepfuck.shortvideo.media

import androidx.media3.common.MimeTypes
import java.net.URI
import java.util.Locale
import you.deepfuck.shortvideo.data.MediaEntry

internal fun mediaMimeType(entry: MediaEntry): String? {
    val format = entry.format?.trim()?.trimStart('.')?.lowercase(Locale.ROOT)
        ?.substringBefore(';')?.trimEnd()
    val path = runCatching { URI(entry.playUrl).path }.getOrNull()
        ?.lowercase(Locale.ROOT).orEmpty()
    if (path.endsWith(".m3u8") || path.endsWith(".m3u") || format in setOf(
            "m3u8", "m3u", "hls", "application/x-mpegurl", "application/vnd.apple.mpegurl",
            "audio/mpegurl", "audio/x-mpegurl",
        )
    ) return MimeTypes.APPLICATION_M3U8

    return when (format) {
        "mp3" -> MimeTypes.AUDIO_MPEG
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "ogg" -> "audio/ogg"
        "opus" -> "audio/opus"
        "mp4", "m4v", "mov" -> MimeTypes.VIDEO_MP4
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "ts", "m2ts" -> "video/mp2t"
        "avi" -> "video/x-msvideo"
        else -> null
    }
}
