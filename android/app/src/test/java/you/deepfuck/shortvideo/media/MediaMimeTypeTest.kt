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

    @Test
    fun `direct playlist paths override generic container hints without inspecting query values`() {
        for (format in listOf<String?>(null, "unknown", "mp4")) {
            assertEquals(
                "application/x-mpegURL",
                mediaMimeType(media(format = "mp4").copy(
                    format = format,
                    playUrl = "http://cms.example/series/MASTER.M3U8?token=one#player",
                )),
            )
        }
        assertEquals(
            "application/x-mpegURL",
            mediaMimeType(media(format = "unknown").copy(playUrl = "https://cms.example/master.m3u?id=1")),
        )
        assertEquals(
            "video/mp4",
            mediaMimeType(media(format = "mp4").copy(playUrl = "https://cms.example/movie.mp4?next=master.m3u8")),
        )
    }

    @Test
    fun `extensionless playlists accept normalized CMS HLS format hints`() {
        for (format in listOf(" .M3U8 ", "hls", "application/vnd.apple.mpegurl", "application/x-mpegURL; charset=UTF-8")) {
            assertEquals("application/x-mpegURL", mediaMimeType(media(format = format)))
        }
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
