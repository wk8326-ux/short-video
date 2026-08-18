package you.deepfuck.shortvideo.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import you.deepfuck.shortvideo.data.AppUpdateInfo

class AppUpdateWorkTest {
    @Test
    fun workerInputRoundTripsRequiredUpdateMetadata() {
        val update = AppUpdateInfo(
            versionCode = 134,
            versionName = "1.3.4",
            apkFile = "short-video-android-v1.3.4-debug.apk",
            sha256 = "ab".repeat(32),
            size = 12_345L,
            notes = "Not needed by the worker",
            downloadUrl = "/api/app/update/apk?versionCode=134",
        )

        val restored = AppUpdateWork.fromInputData(AppUpdateWork.toInputData(update))

        assertEquals(update.copy(notes = ""), restored)
    }

    @Test
    fun invalidWorkerInputIsRejected() {
        assertNull(AppUpdateWork.fromInputData(androidx.work.Data.EMPTY))
    }
}
