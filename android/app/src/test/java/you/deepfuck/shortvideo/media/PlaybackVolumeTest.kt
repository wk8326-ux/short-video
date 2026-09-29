package you.deepfuck.shortvideo.media

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackVolumeTest {
    @Test
    fun `software fraction maps to the full system media volume range`() {
        assertEquals(0, volumeIndexForFraction(0f, 15))
        assertEquals(8, volumeIndexForFraction(0.5f, 15))
        assertEquals(15, volumeIndexForFraction(1f, 15))
    }

    @Test
    fun `system volume index is reported as a stable fraction`() {
        assertEquals(0f, volumeFractionForIndex(0, 15), 0.001f)
        assertEquals(0.5f, volumeFractionForIndex(8, 16), 0.001f)
        assertEquals(1f, volumeFractionForIndex(15, 15), 0.001f)
    }
}
