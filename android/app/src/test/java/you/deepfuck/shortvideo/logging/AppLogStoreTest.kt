package you.deepfuck.shortvideo.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogStoreTest {
    @Test
    fun runtimeLogsRedactSecretsAndPrivateUrlPaths() {
        val safe = RuntimeLogSanitizer.sanitize(
            "cookie=session-secret password=hunter2 url=https://short.deepfuck.you/api/videos/42/play",
        )

        assertFalse(safe.contains("session-secret"))
        assertFalse(safe.contains("hunter2"))
        assertFalse(safe.contains("/api/videos/42/play"))
        assertTrue(safe.contains("<url:short.deepfuck.you>"))
    }

    @Test
    fun visibleLogTextKeepsTheNewestTail() {
        assertEquals("abc", boundedLogText("abc", maxChars = 8))
        val bounded = boundedLogText("0123456789", maxChars = 4)
        assertTrue(bounded.endsWith("6789"))
        assertFalse(bounded.contains("0123"))
    }
}
