package you.deepfuck.shortvideo.update

import java.security.MessageDigest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppUpdateVerifierTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun onlyHigherVersionCodesAreUpdates() {
        assertTrue(isUpdateAvailable(currentVersionCode = 125, latestVersionCode = 130))
        assertFalse(isUpdateAvailable(currentVersionCode = 130, latestVersionCode = 130))
        assertFalse(isUpdateAvailable(currentVersionCode = 131, latestVersionCode = 130))
    }

    @Test
    fun apkHashMustMatchExactly() {
        val apk = temporaryFolder.newFile("update.apk").apply { writeBytes("signed-apk".toByteArray()) }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(apk.readBytes())
            .joinToString("") { "%02x".format(it) }

        assertTrue(sha256Matches(apk, hash))
        assertTrue(sha256Matches(apk, hash.uppercase()))
        assertFalse(sha256Matches(apk, "0".repeat(64)))
        assertFalse(sha256Matches(apk, "invalid"))
    }
}
