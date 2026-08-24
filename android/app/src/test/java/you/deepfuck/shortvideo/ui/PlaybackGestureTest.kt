package you.deepfuck.shortvideo.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackGestureTest {
    @Test
    fun `a second gesture starts from the result of the first gesture`() {
        val firstTarget = calculateSeekTarget(
            basePositionMs = 20_000L,
            accumulatedDragPx = 400f,
            widthPx = 900,
            durationMs = 200_000L,
            maxDeltaMs = 90_000L,
        )
        val secondTarget = calculateSeekTarget(
            basePositionMs = firstTarget,
            accumulatedDragPx = -100f,
            widthPx = 900,
            durationMs = 200_000L,
            maxDeltaMs = 90_000L,
        )

        assertEquals(60_000L, firstTarget)
        assertEquals(50_000L, secondTarget)
    }
}
