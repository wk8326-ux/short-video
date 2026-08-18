package you.deepfuck.shortvideo.update

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal class UpdateDownloadHttpException(val statusCode: Int) :
    IOException("Update download returned HTTP $statusCode")

internal class ResumableUpdateDownloader(
    private val client: OkHttpClient,
) {
    fun download(
        url: HttpUrl,
        target: File,
        expectedSize: Long,
        expectedSha256: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) {
        require(expectedSize > 0L)
        if (target.isFile && target.length() == expectedSize && sha256Matches(target, expectedSha256)) {
            onProgress(expectedSize, expectedSize)
            return
        }

        val parent = target.parentFile ?: throw IOException("Update path has no parent directory")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Update directory cannot be created")
        }
        val partial = File(parent, "${target.name}.part")
        normalizePartial(partial, target, expectedSize, expectedSha256, onProgress)?.let { return }

        var restarted = false
        while (true) {
            val resumeFrom = partial.length().takeIf { it in 1 until expectedSize } ?: 0L
            val request = Request.Builder()
                .url(url)
                .get()
                .apply { if (resumeFrom > 0L) header("Range", "bytes=$resumeFrom-") }
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (response.code == 416 && resumeFrom > 0L) {
                        throw InvalidRangeResponse("Server rejected the saved byte range")
                    }
                    if (!response.isSuccessful) throw UpdateDownloadHttpException(response.code)

                    val append = when {
                        resumeFrom == 0L && response.code == 200 -> false
                        resumeFrom == 0L && response.code == 206 -> {
                            validateContentRange(response.header("Content-Range"), 0L, expectedSize)
                            false
                        }
                        resumeFrom > 0L && response.code == 206 -> {
                            validateContentRange(response.header("Content-Range"), resumeFrom, expectedSize)
                            true
                        }
                        resumeFrom > 0L && response.code == 200 -> false
                        else -> throw InvalidRangeResponse("Unexpected HTTP ${response.code}")
                    }
                    val initialBytes = if (append) resumeFrom else 0L
                    val expectedResponseBytes = expectedSize - initialBytes
                    response.body?.contentLength()?.takeIf { it >= 0L }?.let { contentLength ->
                        if (contentLength != expectedResponseBytes) {
                            throw InvalidRangeResponse("Update response length changed")
                        }
                    }
                    val body = response.body ?: throw IOException("Update download is empty")
                    onProgress(initialBytes, expectedSize)
                    var downloaded = initialBytes
                    body.byteStream().use { input ->
                        FileOutputStream(partial, append).buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                if (Thread.currentThread().isInterrupted) {
                                    throw InterruptedIOException("Update download was interrupted")
                                }
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                downloaded += read
                                if (downloaded > expectedSize) {
                                    throw InvalidRangeResponse("Update download exceeded expected size")
                                }
                                onProgress(downloaded, expectedSize)
                            }
                        }
                    }
                }
            } catch (error: InvalidRangeResponse) {
                if (restarted) {
                    deleteCorruptPartial(partial)
                    throw IOException("Update server returned an invalid byte range", error)
                }
                deleteCorruptPartial(partial)
                restarted = true
                continue
            }

            if (partial.length() != expectedSize) {
                if (partial.length() > expectedSize) deleteCorruptPartial(partial)
                throw IOException("Update download is incomplete")
            }
            if (!sha256Matches(partial, expectedSha256)) {
                deleteCorruptPartial(partial)
                throw IOException("Downloaded APK failed SHA-256 verification")
            }
            finalizeDownload(partial, target)
            onProgress(expectedSize, expectedSize)
            return
        }
    }

    private fun normalizePartial(
        partial: File,
        target: File,
        expectedSize: Long,
        expectedSha256: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Unit? {
        if (!partial.exists()) return null
        if (partial.length() > expectedSize) {
            deleteCorruptPartial(partial)
            return null
        }
        if (partial.length() != expectedSize) return null
        if (!sha256Matches(partial, expectedSha256)) {
            deleteCorruptPartial(partial)
            return null
        }
        finalizeDownload(partial, target)
        onProgress(expectedSize, expectedSize)
        return Unit
    }

    private fun finalizeDownload(partial: File, target: File) {
        if (target.exists() && !target.delete()) throw IOException("Old update cannot be replaced")
        if (!partial.renameTo(target)) throw IOException("Update cannot be finalized")
    }

    private fun deleteCorruptPartial(partial: File) {
        if (partial.exists() && !partial.delete()) {
            throw IOException("Corrupt update fragment cannot be removed")
        }
    }

    private fun validateContentRange(value: String?, expectedStart: Long, expectedTotal: Long) {
        val match = value?.let(CONTENT_RANGE_PATTERN::matchEntire)
            ?: throw InvalidRangeResponse("Missing Content-Range")
        val start = match.groupValues[1].toLongOrNull()
            ?: throw InvalidRangeResponse("Invalid Content-Range start")
        val end = match.groupValues[2].toLongOrNull()
            ?: throw InvalidRangeResponse("Invalid Content-Range end")
        val total = match.groupValues[3].toLongOrNull()
            ?: throw InvalidRangeResponse("Invalid Content-Range total")
        if (start != expectedStart || total != expectedTotal || end < start || end >= total) {
            throw InvalidRangeResponse("Mismatched Content-Range")
        }
    }

    private class InvalidRangeResponse(message: String) : IOException(message)

    private companion object {
        val CONTENT_RANGE_PATTERN = Regex("bytes (\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)
    }
}
