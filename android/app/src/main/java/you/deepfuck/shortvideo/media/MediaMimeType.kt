package you.deepfuck.shortvideo.media

import androidx.media3.common.MimeTypes
import you.deepfuck.shortvideo.data.MediaEntry

internal fun mediaMimeType(entry: MediaEntry): String? = when (entry.format?.trimStart('.')?.lowercase()) {
    "m3u8" -> MimeTypes.APPLICATION_M3U8
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
