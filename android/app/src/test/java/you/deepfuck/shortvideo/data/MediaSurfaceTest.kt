package you.deepfuck.shortvideo.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSurfaceTest {
    @Test
    fun onlyAsmrSupportsBackgroundPlayback() {
        assertFalse(MediaSurface.SHORT.supportsBackgroundPlayback)
        assertFalse(MediaSurface.LONG.supportsBackgroundPlayback)
        assertTrue(MediaSurface.ASMR.supportsBackgroundPlayback)
    }
}
