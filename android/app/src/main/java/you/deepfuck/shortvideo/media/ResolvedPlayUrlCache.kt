package you.deepfuck.shortvideo.media

import java.util.concurrent.ConcurrentHashMap

internal class ResolvedPlayUrlCache(
    private val ttlMs: Long,
    private val maxEntries: Int = 64,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val url: String, val expiresAtMs: Long)

    private val entries = LinkedHashMap<String, Entry>(maxEntries, 0.75f, true)
    private val keyLocks = ConcurrentHashMap<String, Any>()

    fun resolve(key: String, resolver: () -> String): String {
        cached(key)?.let { return it }
        val keyLock = keyLocks.computeIfAbsent(key) { Any() }
        return try {
            synchronized(keyLock) {
                cached(key) ?: resolver().also { put(key, it) }
            }
        } finally {
            keyLocks.remove(key, keyLock)
        }
    }

    fun invalidate(key: String) {
        synchronized(entries) { entries.remove(key) }
    }

    private fun cached(key: String): String? = synchronized(entries) {
        val entry = entries[key] ?: return@synchronized null
        if (entry.expiresAtMs <= clock()) {
            entries.remove(key)
            null
        } else {
            entry.url
        }
    }

    private fun put(key: String, url: String) = synchronized(entries) {
        entries[key] = Entry(url, clock() + ttlMs)
        while (entries.size > maxEntries) {
            entries.remove(entries.entries.first().key)
        }
    }
}
