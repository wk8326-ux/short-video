package you.deepfuck.shortvideo.data

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/** Small, account-scoped JSON cache for catalogue screens. */
internal class ApiResponseCache(
    private val directory: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    fun fresh(url: String, account: String, ttlMs: Long, nowMs: Long = System.currentTimeMillis()): JSONObject? =
        synchronized(lock) {
            read(url, account)?.takeIf { nowMs - it.savedAtMs in 0..ttlMs }?.also {
                it.file.setLastModified(nowMs)
            }?.payload
        }

    fun stale(url: String, account: String, maxAgeMs: Long, nowMs: Long = System.currentTimeMillis()): JSONObject? =
        synchronized(lock) {
            read(url, account)?.takeIf { nowMs - it.savedAtMs in 0..maxAgeMs }?.also {
                it.file.setLastModified(nowMs)
            }?.payload
        }

    fun put(url: String, account: String, payload: JSONObject, nowMs: Long = System.currentTimeMillis()) {
        val content = JSONObject()
            .put("account", digest(account))
            .put("url", url)
            .put("savedAt", nowMs)
            .put("payload", payload)
            .toString()
            .toByteArray(Charsets.UTF_8)
        if (content.size > MAX_ENTRY_BYTES) return
        synchronized(lock) {
            if (!directory.exists() && !directory.mkdirs()) return
            val target = file(url, account)
            val temporary = File(directory, "${target.name}.tmp")
            runCatching {
                temporary.writeBytes(content)
                if (!temporary.renameTo(target)) {
                    temporary.copyTo(target, overwrite = true)
                    temporary.delete()
                }
                target.setLastModified(nowMs)
                prune()
            }.onFailure { temporary.delete() }
        }
    }

    fun invalidate(account: String, matches: (String) -> Boolean) {
        synchronized(lock) {
            directory.listFiles { candidate -> candidate.isFile && candidate.extension == "json" }
                .orEmpty()
                .forEach { candidate ->
                    val entry = read(candidate)
                    if (entry?.accountHash == digest(account) && matches(entry.url)) candidate.delete()
                }
        }
    }

    fun clear(account: String) = invalidate(account) { true }

    private fun read(url: String, account: String): CacheEntry? =
        read(file(url, account))?.takeIf { it.accountHash == digest(account) && it.url == url }

    private fun read(candidate: File): CacheEntry? = runCatching {
        val stored = JSONObject(candidate.readText(Charsets.UTF_8))
        CacheEntry(
            file = candidate,
            accountHash = stored.getString("account"),
            url = stored.getString("url"),
            savedAtMs = stored.getLong("savedAt"),
            payload = stored.getJSONObject("payload"),
        )
    }.getOrNull()

    private fun file(url: String, account: String): File =
        File(directory, "${digest(account + "\n" + url)}.json")

    private fun prune() {
        val entries = directory.listFiles { candidate -> candidate.isFile && candidate.extension == "json" }
            .orEmpty()
            .sortedBy(File::lastModified)
        var size = entries.sumOf(File::length)
        for (candidate in entries) {
            if (size <= maxBytes) break
            size -= candidate.length()
            candidate.delete()
        }
        directory.listFiles { candidate -> candidate.isFile && candidate.extension == "tmp" }
            .orEmpty()
            .forEach(File::delete)
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private data class CacheEntry(
        val file: File,
        val accountHash: String,
        val url: String,
        val savedAtMs: Long,
        val payload: JSONObject,
    )

    private companion object {
        val lock = Any()
        const val DEFAULT_MAX_BYTES = 64L * 1024 * 1024
        const val MAX_ENTRY_BYTES = 8 * 1024 * 1024
    }
}
