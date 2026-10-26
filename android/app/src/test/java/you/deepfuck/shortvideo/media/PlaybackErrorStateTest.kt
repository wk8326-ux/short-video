package you.deepfuck.shortvideo.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackErrorStateTest {
    @Test
    fun positionAndPlaybackUpdatesRetainTheLastFailure() {
        val state = PlaybackErrorState()
        state.record("ERROR_CODE_IO_BAD_HTTP_STATUS")

        repeat(60) { state.record(null) }

        assertEquals("ERROR_CODE_IO_BAD_HTTP_STATUS", state.error)
    }

    @Test
    fun explicitRetryOrRecoveryClearsTheFailureAndAllowsAnotherFailure() {
        val state = PlaybackErrorState()
        state.record("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED")
        state.clear()
        state.record(null)
        assertNull(state.error)

        state.record("ERROR_CODE_IO_BAD_HTTP_STATUS")
        assertEquals("ERROR_CODE_IO_BAD_HTTP_STATUS", state.error)
    }
}
