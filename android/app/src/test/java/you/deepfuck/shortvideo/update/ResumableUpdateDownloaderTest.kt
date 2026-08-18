package you.deepfuck.shortvideo.update

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResumableUpdateDownloaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var downloader: ResumableUpdateDownloader

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        downloader = ResumableUpdateDownloader(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun interruptedDownloadKeepsPartialBytesAndResumesWithRange() {
        val bytes = ByteArray(128 * 1024) { (it % 251).toByte() }
        val target = File(temporaryFolder.root, "update.apk")
        val partial = File(temporaryFolder.root, "update.apk.part")
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(bytes))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )

        assertThrows(IOException::class.java) {
            downloader.download(server.url("/update.apk"), target, bytes.size.toLong(), sha256(bytes)) { _, _ -> }
        }
        val resumedFrom = partial.length()
        assertTrue(resumedFrom in 1 until bytes.size.toLong())

        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes $resumedFrom-${bytes.lastIndex}/${bytes.size}")
                .setBody(Buffer().write(bytes, resumedFrom.toInt(), bytes.size - resumedFrom.toInt())),
        )
        downloader.download(server.url("/update.apk"), target, bytes.size.toLong(), sha256(bytes)) { _, _ -> }

        assertEquals(null, server.takeRequest().getHeader("Range"))
        assertEquals("bytes=$resumedFrom-", server.takeRequest().getHeader("Range"))
        assertArrayEquals(bytes, target.readBytes())
    }

    @Test
    fun serverIgnoringRangeRestartsWithoutAppendingDuplicateBytes() {
        val bytes = "complete-update".toByteArray()
        val target = File(temporaryFolder.root, "update.apk")
        File(temporaryFolder.root, "update.apk.part").writeBytes("old".toByteArray())
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(bytes)))

        downloader.download(server.url("/update.apk"), target, bytes.size.toLong(), sha256(bytes)) { _, _ -> }

        assertEquals("bytes=3-", server.takeRequest().getHeader("Range"))
        assertArrayEquals(bytes, target.readBytes())
    }

    @Test
    fun alreadyVerifiedTargetAvoidsAnotherNetworkRequest() {
        val bytes = "verified-update".toByteArray()
        val target = File(temporaryFolder.root, "update.apk").apply { writeBytes(bytes) }

        downloader.download(server.url("/update.apk"), target, bytes.size.toLong(), sha256(bytes)) { _, _ -> }

        assertEquals(0, server.requestCount)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
