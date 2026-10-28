package you.deepfuck.shortvideo.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.LinkedHashMap
import org.json.JSONObject

/** Dedicated app-files storage. It never reads backend sessions or backend catalog tables. */
internal class TvBoxStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "tvbox-v1")

    fun config(): TvBoxConfig? = synchronized(lock) {
        read(File(root, "config.json"))?.let { value ->
            val origin = value.optString("origin").takeUnless { it == "null" || it.isBlank() }
            if (value.optJSONArray("sites")?.length() == 0) {
                val origins = value.optJSONArray("origins")?.let { entries ->
                    (0 until entries.length()).map { entries.getString(it) }
                } ?: listOfNotNull(origin)
                val removed = value.optJSONArray("removedSites")?.let { entries ->
                    (0 until entries.length()).map { entries.getString(it) }
                }.orEmpty()
                TvBoxConfig(emptyList(), origin, TvBoxConfigCodec.savedUnsupportedSites(value), origins, removed)
            }
            else TvBoxConfigCodec.decode(value.toString(), origin)
        }
    }

    fun saveConfig(config: TvBoxConfig) = synchronized(lock) {
        write(File(root, "config.json"), TvBoxConfigCodec.encode(config))
    }

    fun updateConfig(update: (TvBoxConfig) -> TvBoxConfig): TvBoxConfig = synchronized(lock) {
        val next = update(config() ?: TvBoxConfig(emptyList()))
        saveConfig(next)
        next
    }

    fun settings(): TvBoxSettings = synchronized(lock) {
        val value = read(File(root, "settings.json")) ?: JSONObject()
        TvBoxSettings(
            selectedSourceId = value.optString("selectedSourceId").takeUnless { it == "null" || it.isBlank() },
            wallView = MovieWallView.fromValue(value.optString("wallView")),
            muted = value.optBoolean("muted", false),
            skin = value.optString("skin", "obsidian-coral"),
        )
    }

    fun saveSettings(settings: TvBoxSettings) = synchronized(lock) {
        write(File(root, "settings.json"), JSONObject()
            .put("selectedSourceId", settings.selectedSourceId ?: JSONObject.NULL)
            .put("wallView", settings.wallView.apiValue).put("muted", settings.muted).put("skin", settings.skin))
    }

    fun state(sourceId: String): JSONObject = synchronized(lock) {
        read(sourceFile(sourceId, "state.json")) ?: JSONObject()
    }

    fun updateState(sourceId: String, generation: Long? = null, update: (JSONObject) -> Unit) = synchronized(lock) {
        if (generation != null && generation != cacheGeneration) return@synchronized
        val file = sourceFile(sourceId, "state.json")
        val value = read(file) ?: JSONObject()
        update(value)
        write(file, value)
    }

    fun cached(sourceId: String, requestUrl: String): JSONObject? = synchronized(lock) {
        read(sourceFile(sourceId, "responses/${tvBoxDigest(requestUrl)}.json"))
    }

    fun beginResponse(sourceId: String, requestUrl: String, generation: Long, cachedTicket: Long): Long? = synchronized(lock) {
        if (generation != cacheGeneration) return@synchronized null
        nextResponseTicket = maxOf(nextResponseTicket, cachedTicket) + 1L
        nextResponseTicket
    }

    fun cache(
        sourceId: String,
        requestUrl: String,
        payload: JSONObject,
        nowMs: Long,
        generation: Long,
        ticket: Long,
    ) = synchronized(lock) {
        if (generation != cacheGeneration) return@synchronized
        val responseKey = responseKey(sourceId, requestUrl)
        val previousTicket = cacheResponseTickets[responseKey] ?: Long.MIN_VALUE
        if (ticket < previousTicket) return@synchronized
        val file = sourceFile(sourceId, "responses/${tvBoxDigest(requestUrl)}.json")
        write(file, JSONObject().put("savedAt", nowMs).put("ticket", ticket).put("payload", payload))
        cacheResponseTickets[responseKey] = ticket

        val index = responseCacheIndex()
        val oldLength = index.files.remove(file.absolutePath) ?: 0L
        index.bytes -= oldLength
        val length = file.length()
        index.files[file.absolutePath] = length
        index.bytes += length
        val iterator = index.files.entries.iterator()
        while (index.bytes > MAX_CACHE_BYTES && iterator.hasNext()) {
            val (path, size) = iterator.next()
            if (File(path).delete()) {
                index.bytes -= size
                iterator.remove()
            }
        }
    }

    fun updateResponseState(
        sourceId: String,
        requestUrl: String,
        generation: Long,
        ticket: Long,
        update: (JSONObject) -> Unit,
    ): Boolean = synchronized(lock) {
        if (generation != cacheGeneration) return@synchronized false
        val key = responseKey(sourceId, requestUrl)
        if (ticket < (stateResponseTickets[key] ?: Long.MIN_VALUE)) return@synchronized false
        val file = sourceFile(sourceId, "state.json")
        val value = read(file) ?: JSONObject()
        update(value)
        write(file, value)
        stateResponseTickets[key] = ticket
        true
    }

    fun generation(): Long = synchronized(lock) { cacheGeneration }

    fun clearCache() = synchronized(lock) {
        cacheGeneration += 1L
        cacheResponseTickets.clear()
        stateResponseTickets.clear()
        responseFiles().forEach { file ->
            if (!file.delete()) throw java.io.IOException("无法清理 TVBox 缓存")
        }
        responseIndexes.remove(root.absolutePath)
        File(root, "sources").listFiles().orEmpty().forEach { directory ->
            val file = File(directory, "state.json")
            val state = read(file) ?: return@forEach
            state.remove("categories")
            state.remove("total")
            val items = state.optJSONObject("items")
            val protectedIds = listOf("favorites", "progress", "recent").flatMap { key ->
                state.optJSONObject(key)?.keys()?.asSequence()?.toList().orEmpty()
            }.toSet()
            items?.keys()?.asSequence()?.toList()?.filter { it !in protectedIds }?.forEach(items::remove)
            write(file, state)
        }
    }

    private fun responseFiles(): List<File> = File(root, "sources").listFiles().orEmpty().flatMap { source ->
        File(source, "responses").listFiles().orEmpty().filter { it.isFile }.toList()
    }

    private fun responseKey(sourceId: String, requestUrl: String) = "$sourceId/${tvBoxDigest(requestUrl)}"

    private fun responseCacheIndex(): ResponseCacheIndex = responseIndexes.getOrPut(root.absolutePath) {
        val files = LinkedHashMap<String, Long>()
        responseFiles().sortedBy { it.lastModified() }.forEach { files[it.absolutePath] = it.length() }
        ResponseCacheIndex(files, files.values.sum())
    }

    private fun sourceFile(sourceId: String, suffix: String) = File(root, "sources/${tvBoxDigest(sourceId)}/$suffix")

    private fun read(file: File): JSONObject? {
        val atomic = AtomicFile(file)
        return try {
            atomic.openRead().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        } catch (_: java.io.FileNotFoundException) {
            null
        }
    }

    private fun write(file: File, value: JSONObject) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "Cannot create TVBox storage directory" }
        val temporary = Files.createTempFile(file.parentFile!!.toPath(), file.name, ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { stream ->
                stream.write(value.toString().toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            try {
                Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    companion object {
        // Separate repositories for the same application must not overwrite one another's local changes.
        private val lock = Any()
        private var cacheGeneration = 0L
        private var nextResponseTicket = 0L
        private val cacheResponseTickets = mutableMapOf<String, Long>()
        private val stateResponseTickets = mutableMapOf<String, Long>()
        private val responseIndexes = mutableMapOf<String, ResponseCacheIndex>()
        private const val MAX_CACHE_BYTES = 32L * 1024 * 1024
    }

    private data class ResponseCacheIndex(val files: LinkedHashMap<String, Long>, var bytes: Long)
}
