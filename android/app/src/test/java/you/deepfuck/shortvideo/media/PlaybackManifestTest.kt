package you.deepfuck.shortvideo.media

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackManifestTest {
    @Test
    fun networkWakeModeDeclaresWakeLockPermission() {
        val manifest = File("src/main/AndroidManifest.xml")
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(manifest)
        val permissions = document.getElementsByTagName("uses-permission")
        val declared = buildSet {
            for (index in 0 until permissions.length) {
                permissions.item(index).attributes
                    .getNamedItemNS(ANDROID_NAMESPACE, "name")
                    ?.nodeValue
                    ?.let(::add)
            }
        }

        assertTrue(
            "C.WAKE_MODE_NETWORK requires android.permission.WAKE_LOCK",
            "android.permission.WAKE_LOCK" in declared,
        )
    }

    @Test
    fun mediaSessionServiceOwnsForegroundPromotion() {
        val serviceSource = File("src/main/java/you/deepfuck/shortvideo/media/PlaybackService.kt").readText()
        val viewModelSource = File("src/main/java/you/deepfuck/shortvideo/MainViewModel.kt").readText()

        assertTrue(
            "MediaSessionService must register its session so Media3 can manage the notification and foreground state",
            serviceSource.contains("addSession(session)"),
        )
        assertFalse(
            "Callers must not start MediaSessionService as an FGS before Media3 has produced its notification",
            viewModelSource.contains("ContextCompat.startForegroundService"),
        )
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
