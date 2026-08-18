package you.deepfuck.shortvideo.update

import java.io.File
import java.security.MessageDigest

internal fun isUpdateAvailable(currentVersionCode: Int, latestVersionCode: Int): Boolean =
    latestVersionCode > currentVersionCode

internal fun sha256Matches(file: File, expectedHex: String): Boolean {
    if (!SHA256_PATTERN.matches(expectedHex)) return false
    val expected = ByteArray(32) { index ->
        expectedHex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return MessageDigest.isEqual(digest.digest(), expected)
}

private val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")
