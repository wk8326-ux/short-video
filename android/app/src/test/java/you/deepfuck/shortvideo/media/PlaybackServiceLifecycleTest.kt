package you.deepfuck.shortvideo.media

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackServiceLifecycleTest {
    @Test
    fun sessionIsRegisteredForTheWholeServiceLifecycle() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val service = controller.get()

        assertEquals(1, service.sessions.size)

        controller.destroy()
        assertEquals(0, service.sessions.size)
    }
}
