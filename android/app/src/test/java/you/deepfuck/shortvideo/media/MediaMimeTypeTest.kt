package you.deepfuck.shortvideo.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import you.deepfuck.shortvideo.data.MediaEntry

class MediaMimeTypeTest {
    @Test
    fun `container and audio formats use their real MIME types`() {
        assertEquals("video/mp4", mediaMimeType(media(format = "mp4")))
        assertEquals("video/x-matroska", mediaMimeType(media(format = "mkv")))
        assertEquals("application/x-mpegURL", mediaMimeType(media(format = "m3u8")))
        assertEquals("audio/mp4", mediaMimeType(media(format = "m4a", kind = "audio")))
        assertEquals("audio/wav", mediaMimeType(media(format = "wav", kind = "audio")))
        assertNull(mediaMimeType(media(format = "unknown")))
    }

    private fun media(format: String, kind: String = "video") = MediaEntry(
        id = 1L,
        title = "media",
        size = 1L,
        modified = null,
        durationSeconds = 10.0,
        playUrl = "/api/videos/1/play",
        posterUrl = null,
        format = format,
        kind = kind,
    )
}
