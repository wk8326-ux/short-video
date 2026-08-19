package you.deepfuck.shortvideo.update

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import you.deepfuck.shortvideo.data.AppUpdateInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
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

    @Test
    fun downloadRequestStartsImmediatelyAndWaitsForConnectivity() {
        val request = AppUpdateWork.request(update())

        assertTrue(request.workSpec.expedited)
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
    }

    @Test
    fun foregroundDownloadPublishesPersistentProgress() {
        val context = RuntimeEnvironment.getApplication() as Context
        val foreground = AppUpdateForeground.info(
            context = context,
            update = update(),
            downloadedBytes = 4_321L,
        )

        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, foreground.foregroundServiceType)
        assertTrue(
            foreground.notification.flags.toInt() and
                Notification.FLAG_ONGOING_EVENT.toInt() != 0,
        )
        assertEquals(35, foreground.notification.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(100, foreground.notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
    }

    private fun update() = AppUpdateInfo(
        versionCode = 134,
        versionName = "1.3.4",
        apkFile = "short-video-android-v1.3.4-debug.apk",
        sha256 = "ab".repeat(32),
        size = 12_345L,
        notes = "Background download",
        downloadUrl = "/api/app/update/apk?versionCode=134",
    )
}
