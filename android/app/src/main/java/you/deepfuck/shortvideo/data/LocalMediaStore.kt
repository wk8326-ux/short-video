package you.deepfuck.shortvideo.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small account-scoped read model for catalogue UX.
 *
 * This is deliberately SQLiteOpenHelper instead of an ORM: the sync payload is
 * versioned JSON and the app only needs a compact local projection. Playback
 * addresses are never stored here; they are server routes and are resolved at
 * the moment playback starts.
 */
internal class LocalMediaStore(
    context: Context,
    name: String = DATABASE_NAME,
) {
    private val helper = Helper(context.applicationContext, name)

    fun cacheLibraryResults(account: String, items: JSONArray) {
        if (account.isBlank()) return
        transaction { database ->
            for (index in 0 until items.length()) {
                items.optJSONObject(index)?.let { upsert(database, account, it) }
            }
        }
    }

    fun cacheFavoriteResults(account: String, items: List<JSONObject>) {
        if (account.isBlank() || items.isEmpty()) return
        transaction { database ->
            items.forEach { item ->
                val favoriteItem = JSONObject(item.toString()).put("favorite", true)
                upsert(database, account, favoriteItem)
                val section = favoriteItem.optString("section")
                val targetId = favoriteTargetId(favoriteItem) ?: return@forEach
                putFavorite(database, account, section, targetId, favoriteItem, true, synced = true)
            }
        }
    }

    fun cacheCatalogItem(account: String, section: String, item: JSONObject) {
        if (account.isBlank() || section.isBlank()) return
        val wrapper = JSONObject()
            .put("type", entityType(section))
            .put("section", section)
            .put("id", itemKey(section, item))
            .put("title", item.optString("title"))
            .put("subtitle", section)
            .put("posterUrl", item.opt("posterUrl"))
            .put(sectionPayloadKey(section), item)
        transaction { database -> upsert(database, account, wrapper) }
    }

    fun cacheAsmrAuthors(account: String, items: JSONArray) {
        if (account.isBlank()) return
        transaction { database ->
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val name = item.optString("name").trim()
                if (name.isEmpty()) continue
                upsert(database, account, JSONObject()
                    .put("type", "author")
                    .put("section", "asmr")
                    .put("id", "author:$name")
                    .put("title", name)
                    .put("subtitle", "ASMR 作者目录 · ${item.optInt("itemCount")} 条媒体")
                    .put("author", name)
                    .put("count", item.optInt("itemCount")))
            }
        }
    }

    fun cachedAsmrAuthors(account: String): List<JSONObject> {
        if (account.isBlank()) return emptyList()
        val result = mutableListOf<JSONObject>()
        readable { database ->
            database.rawQuery(
                "SELECT payload FROM catalog_items WHERE account = ? AND " +
                    "section = 'asmr' AND entity_type = 'author' AND deleted = 0 " +
                    "ORDER BY title COLLATE NOCASE",
                arrayOf(account),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    runCatching { result += JSONObject(cursor.getString(0)) }
                }
            }
        }
        return result
    }

    fun recordRecent(account: String, section: String, item: JSONObject) {
        if (account.isBlank() || section.isBlank()) return
        val wrapper = when (section) {
            "movie" -> JSONObject()
                .put("type", "movie")
                .put("section", section)
                .put("id", "movie:${item.optString("id")}")
                .put("title", item.optString("title"))
                .put("posterUrl", item.opt("posterUrl"))
                .put("movie", item)
            "drama" -> JSONObject()
                .put("type", "drama")
                .put("section", section)
                .put("id", "drama:${item.optString("id")}")
                .put("title", item.optString("title"))
                .put("posterUrl", item.opt("posterUrl"))
                .put("drama", item)
            else -> return
        }
        if (wrapper.optString("id").endsWith(":")) return
        transaction { database ->
            upsert(database, account, wrapper)
            database.insertWithOnConflict(
                "local_recent",
                null,
                ContentValues().apply {
                    put("account", account)
                    put("section", section)
                    put("item_key", wrapper.optString("id"))
                    put("source_id", sourceId(wrapper))
                    put("payload", wrapper.toString())
                    put("updated_at", System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            database.execSQL(
                "DELETE FROM local_recent WHERE account = ? AND section = ? AND item_key NOT IN " +
                    "(SELECT item_key FROM local_recent WHERE account = ? AND section = ? " +
                    "ORDER BY updated_at DESC LIMIT 100)",
                arrayOf(account, section, account, section),
            )
        }
    }

    fun cacheRecentItems(account: String, section: String, items: List<JSONObject>) {
        items.forEach { recordRecent(account, section, it) }
    }

    fun recentItems(account: String, section: String, limit: Int): List<JSONObject> {
        if (account.isBlank() || section.isBlank()) return emptyList()
        val result = mutableListOf<JSONObject>()
        readable { database ->
            database.rawQuery(
                "SELECT payload FROM local_recent WHERE account = ? AND section = ? " +
                    "ORDER BY updated_at DESC LIMIT ?",
                arrayOf(account, section, limit.coerceIn(1, 100).toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    runCatching { result += JSONObject(cursor.getString(0)) }
                }
            }
        }
        return result
    }

    fun cacheSources(account: String, sources: List<JSONObject>) {
        if (account.isBlank() || sources.isEmpty()) return
        transaction { database -> sources.forEach { upsertSource(database, account, it) } }
    }

    fun cacheSourceGroups(account: String, section: String, groups: JSONArray?) {
        if (account.isBlank() || section.isBlank() || groups == null) return
        val sources = buildList {
            for (index in 0 until groups.length()) {
                val group = groups.optJSONObject(index) ?: continue
                val groupId = group.optString("id").trim()
                val groupName = group.optString("name").trim()
                val provider = group.optString("provider").trim().ifBlank { "unknown" }
                val libraryIds = group.optJSONArray("libraryIds") ?: continue
                for (libraryIndex in 0 until libraryIds.length()) {
                    val libraryId = libraryIds.optString(libraryIndex).trim()
                    if (libraryId.isBlank()) continue
                    add(JSONObject()
                        .put("id", libraryId)
                        .put("section", section)
                        .put("name", groupName)
                        .put("provider", provider)
                        .put("sourceGroupId", groupId)
                        .put("sourceGroupName", groupName)
                        .apply {
                            groupId.removePrefix("parent:")
                                .takeIf { groupId.startsWith("parent:") && it.isNotBlank() }
                                ?.let { put("parentSourceId", it) }
                        })
                }
            }
        }
        if (sources.isEmpty()) return
        transaction { database -> sources.forEach { mergeSource(database, account, it, preserveName = true) } }
    }

    fun cacheSourceLabels(account: String, section: String, groups: JSONArray?) {
        if (account.isBlank() || section.isBlank() || groups == null) return
        val labels = buildList {
            for (index in 0 until groups.length()) {
                val group = groups.optJSONObject(index) ?: continue
                val sourceId = group.optString("sourceId").trim()
                val name = group.optString("name").trim()
                if (sourceId.isBlank() || name.isBlank()) continue
                add(JSONObject()
                    .put("id", sourceId)
                    .put("section", section)
                    .put("name", name))
            }
        }
        if (labels.isEmpty()) return
        transaction { database -> labels.forEach { mergeSource(database, account, it) } }
    }

    fun cachedSources(account: String, section: String? = null): List<JSONObject> {
        if (account.isBlank()) return emptyList()
        val result = mutableListOf<JSONObject>()
        val selection = if (section.isNullOrBlank()) "account = ?" else "account = ? AND section = ?"
        val args = if (section.isNullOrBlank()) arrayOf(account) else arrayOf(account, section)
        readable { database ->
            database.rawQuery(
                "SELECT payload FROM catalog_sources WHERE $selection ORDER BY name COLLATE NOCASE",
                args,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    runCatching { result += JSONObject(cursor.getString(0)) }
                }
            }
        }
        return result
    }

    /** Return cached source ids before a sync overwrites the source snapshot. */
    fun cachedSourceIds(account: String, section: String? = null): Set<String> =
        cachedSources(account, section).mapNotNull { source ->
            source.optString("id").trim().takeIf(String::isNotEmpty)
        }.toSet()

    /**
     * Remove sources that disappeared from the server's current source list.
     * Feed sources are exposed through both the short and long sections, while
     * the other sections map one-to-one to the source payload's section.
     */
    fun deleteMissingSources(account: String, section: String, presentIds: Set<String>) {
        if (account.isBlank()) return
        val sourceSection = if (section == "feed" || section == "short" || section == "long") {
            "feed"
        } else {
            section
        }
        val staleIds = cachedSourceIds(account, sourceSection) - presentIds
        staleIds.forEach { deleteSource(account, it) }
    }

    fun applySyncSource(account: String, source: JSONObject) {
        if (account.isBlank()) return
        transaction { database -> upsertSource(database, account, source) }
    }

    fun search(account: String, query: String, section: String, limit: Int): List<JSONObject> {
        if (account.isBlank() || query.isBlank()) return emptyList()
        val bounded = limit.coerceIn(1, 100)
        val normalizedSection = section.ifBlank { "all" }
        val tokens = query.trim().split(Regex("\\s+"))
            .map { it.replace(Regex("[^\\p{L}\\p{N}_]"), "") }
            .filter(String::isNotBlank)
        val tokenQuery = tokens.joinToString(" AND ") { "\"$it\"*" }
        val result = mutableListOf<JSONObject>()
        readable { database ->
            if (tokenQuery.isNotBlank()) {
                val sectionClause = if (normalizedSection == "all") "" else " AND section = ?"
                val args = mutableListOf(account, tokenQuery)
                if (sectionClause.isNotEmpty()) args += normalizedSection
                args += bounded.toString()
                runCatching {
                    database.rawQuery(
                        """
                        SELECT payload FROM catalog_fts
                        WHERE account = ? AND catalog_fts MATCH ? $sectionClause
                        ORDER BY rowid DESC LIMIT ?
                        """.trimIndent(),
                        args.toTypedArray(),
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            runCatching { result += JSONObject(cursor.getString(0)) }
                        }
                    }
                }
            }
            if (result.isEmpty()) {
                val like = "%${query.trim()}%"
                val sql = "SELECT payload FROM catalog_items WHERE account = ? AND deleted = 0 " +
                    if (normalizedSection == "all") {
                        "AND search_text LIKE ? COLLATE NOCASE ORDER BY updated_at DESC LIMIT ?"
                    } else {
                        "AND section = ? AND search_text LIKE ? COLLATE NOCASE " +
                            "ORDER BY updated_at DESC LIMIT ?"
                    }
                val fallbackArgs = if (normalizedSection == "all") {
                    arrayOf(account, like, bounded.toString())
                } else {
                    arrayOf(account, normalizedSection, like, bounded.toString())
                }
                database.rawQuery(sql, fallbackArgs).use { cursor ->
                    while (cursor.moveToNext()) {
                        runCatching { result += JSONObject(cursor.getString(0)) }
                    }
                }
            }
        }
        return result.distinctBy { it.optString("id") }.take(bounded)
    }

    fun setFavorite(
        account: String,
        section: String,
        targetId: String,
        payload: JSONObject?,
        favorite: Boolean,
    ) {
        if (account.isBlank() || section.isBlank() || targetId.isBlank()) return
        transaction { database ->
            val key = "$section:$targetId"
            val cachedPayload = payload?.toString() ?: database.rawQuery(
                "SELECT payload FROM catalog_items WHERE account = ? AND item_key = ?",
                arrayOf(account, key),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            val favoritePayload = cachedPayload?.let {
                runCatching {
                    JSONObject(it).put("favorite", favorite).apply {
                        optJSONObject("movie")?.put("favorite", favorite)
                    }
                }.getOrNull()
            }
            putFavorite(database, account, section, targetId, favoritePayload, favorite, synced = false)
            favoritePayload?.let { upsert(database, account, it) }
        }
    }

    fun favoritePage(account: String, section: String, offset: Int, limit: Int): LocalPage {
        if (account.isBlank()) return LocalPage(emptyList(), 0, null)
        val allowed = if (section == "all") null else section
        val result = mutableListOf<JSONObject>()
        var total = 0
        readable { database ->
            val where = if (allowed == null) "account = ? AND favorite = 1" else {
                "account = ? AND section = ? AND favorite = 1"
            }
            val countArgs = if (allowed == null) arrayOf(account) else arrayOf(account, allowed)
            total = database.rawQuery(
                "SELECT COUNT(*) FROM local_favorites WHERE $where",
                countArgs,
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
            val args = if (allowed == null) {
                arrayOf(account, limit.coerceIn(1, 100).toString(), offset.coerceAtLeast(0).toString())
            } else {
                arrayOf(account, allowed, limit.coerceIn(1, 100).toString(), offset.coerceAtLeast(0).toString())
            }
            val sql = if (allowed == null) {
                "SELECT payload, section, target_id FROM local_favorites " +
                    "WHERE account = ? AND favorite = 1 ORDER BY updated_at DESC LIMIT ? OFFSET ?"
            } else {
                "SELECT payload, section, target_id FROM local_favorites " +
                    "WHERE account = ? AND section = ? AND favorite = 1 " +
                    "ORDER BY updated_at DESC LIMIT ? OFFSET ?"
            }
            database.rawQuery(sql, args).use { cursor ->
                while (cursor.moveToNext()) {
                    val sectionValue = cursor.getString(1)
                    val targetId = cursor.getString(2)
                    val payload = cursor.getString(0) ?: database.rawQuery(
                        "SELECT payload FROM catalog_items WHERE account = ? AND item_key = ?",
                        arrayOf(account, "$sectionValue:$targetId"),
                    ).use { catalog -> if (catalog.moveToFirst()) catalog.getString(0) else null }
                    if (!payload.isNullOrBlank()) {
                        runCatching {
                            result += JSONObject(payload).put("favorite", true)
                        }
                    }
                }
            }
        }
        val next = offset + result.size
        return LocalPage(result, total, next.takeIf { it < total })
    }

    fun cachedPage(
        account: String,
        section: String,
        offset: Int,
        limit: Int,
        sourceId: String? = null,
    ): LocalPage {
        if (account.isBlank()) return LocalPage(emptyList(), 0, null)
        val allowed = section.takeUnless { it == "all" }
        val result = mutableListOf<JSONObject>()
        var total = 0
        readable { database ->
            val conditions = mutableListOf("account = ?", "deleted = 0")
            val countArgs = mutableListOf(account)
            if (allowed != null) {
                conditions += "section = ?"
                countArgs += allowed
            }
            if (!sourceId.isNullOrBlank()) {
                conditions += "source_id = ?"
                countArgs += sourceId
            }
            val where = conditions.joinToString(" AND ")
            total = database.rawQuery(
                "SELECT COUNT(*) FROM catalog_items WHERE $where",
                countArgs.toTypedArray(),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
            val args = countArgs + listOf(
                limit.coerceIn(1, 100).toString(),
                offset.coerceAtLeast(0).toString(),
            )
            database.rawQuery(
                "SELECT payload FROM catalog_items WHERE $where ORDER BY updated_at DESC LIMIT ? OFFSET ?",
                args.toTypedArray(),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    runCatching { result += JSONObject(cursor.getString(0)) }
                }
            }
        }
        val next = offset + result.size
        return LocalPage(result, total, next.takeIf { it < total })
    }

    fun cachedPagesBySource(account: String, section: String, limit: Int): Map<String, LocalPage> {
        if (account.isBlank()) return emptyMap()
        val sourceIds = mutableListOf<String>()
        readable { database ->
            database.rawQuery(
                "SELECT DISTINCT source_id FROM catalog_items " +
                    "WHERE account = ? AND section = ? AND deleted = 0 " +
                    "AND source_id IS NOT NULL AND source_id <> ''",
                arrayOf(account, section),
            ).use { cursor ->
                while (cursor.moveToNext()) sourceIds += cursor.getString(0)
            }
        }
        return sourceIds.associateWith { sourceId ->
            cachedPage(account, section, offset = 0, limit = limit, sourceId = sourceId)
        }
    }

    /** Return one locally cached catalogue wrapper before the network refresh. */
    fun cachedItem(account: String, section: String, targetId: String): JSONObject? {
        if (account.isBlank() || section.isBlank() || targetId.isBlank()) return null
        val key = "$section:$targetId"
        return readableValue { database ->
            database.rawQuery(
                "SELECT payload FROM catalog_items WHERE account = ? AND item_key = ? AND deleted = 0",
                arrayOf(account, key),
            ).use { cursor ->
                if (!cursor.moveToFirst()) null
                else runCatching { JSONObject(cursor.getString(0)) }.getOrNull()
            }
        }
    }

    fun pendingFavoriteChanges(account: String): List<JSONObject> {
        if (account.isBlank()) return emptyList()
        val result = mutableListOf<JSONObject>()
        readable { database ->
            database.rawQuery(
                "SELECT section, target_id, favorite, payload FROM local_favorites " +
                    "WHERE account = ? AND synced = 0 ORDER BY updated_at",
                arrayOf(account),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result += JSONObject()
                        .put("section", cursor.getString(0))
                        .put("targetId", cursor.getString(1))
                        .put("favorite", cursor.getInt(2) == 1)
                        .put("payload", cursor.getString(3))
                }
            }
        }
        return result
    }

    fun favoriteIds(account: String, section: String, targetIds: List<String>): Set<String> {
        if (account.isBlank() || section.isBlank() || targetIds.isEmpty()) return emptySet()
        val ids = targetIds.distinct().filter(String::isNotBlank).take(100)
        val placeholders = ids.joinToString(",") { "?" }
        val args = arrayOf(account, section, *ids.toTypedArray())
        val result = mutableSetOf<String>()
        readable { database ->
            database.rawQuery(
                "SELECT target_id FROM local_favorites WHERE account = ? AND section = ? " +
                    "AND favorite = 1 AND target_id IN ($placeholders)",
                args,
            ).use { cursor ->
                while (cursor.moveToNext()) result += cursor.getString(0)
            }
        }
        return result
    }

    fun markFavoriteSynced(account: String, section: String, targetId: String, favorite: Boolean) {
        if (account.isBlank()) return
        transaction { database ->
            database.execSQL(
                "UPDATE local_favorites SET synced = 1 WHERE account = ? AND section = ? " +
                    "AND target_id = ? AND favorite = ?",
                arrayOf(account, section, targetId, if (favorite) 1 else 0),
            )
        }
    }

    fun saveCursor(account: String, scope: String, cursor: Long) {
        if (account.isBlank() || scope.isBlank()) return
        transaction { database ->
            database.execSQL(
                "INSERT OR REPLACE INTO sync_state(account, scope, cursor) VALUES(?, ?, ?)",
                arrayOf(account, scope, cursor),
            )
        }
    }

    fun cursor(account: String, scope: String): Long = readableValue { database ->
        database.rawQuery(
            "SELECT cursor FROM sync_state WHERE account = ? AND scope = ?",
            arrayOf(account, scope),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
    }

    fun hasCursor(account: String, scope: String): Boolean = readableValue { database ->
        database.rawQuery(
            "SELECT 1 FROM sync_state WHERE account = ? AND scope = ?",
            arrayOf(account, scope),
        ).use { it.moveToFirst() }
    }

    fun applySyncItem(account: String, section: String, item: JSONObject) {
        if (account.isBlank()) return
        val normalizedItem = JSONObject(item.toString()).apply {
            if (section in setOf("short", "long", "asmr") && !has("playUrl")) {
                put("playUrl", "/api/videos/${optString("id")}/play")
            }
            if (section == "movie" && !has("playUrl")) {
                put("playUrl", "/api/videos/${optString("videoId")}/play")
            }
        }
        val wrapper = when (section) {
            "movie" -> JSONObject()
                .put("type", "movie").put("section", section)
                .put("id", "movie:${item.optString("id")}")
                .put("title", item.optString("title"))
                .put("posterUrl", item.opt("posterUrl"))
                .put("movie", normalizedItem)
            "drama" -> JSONObject()
                .put("type", "drama").put("section", section)
                .put("id", "drama:${item.optString("id")}")
                .put("title", item.optString("title"))
                .put("posterUrl", item.opt("posterUrl"))
                .put("drama", normalizedItem)
            else -> JSONObject()
                .put("type", "media").put("section", section)
                .put("id", "$section:${item.optString("id")}")
                .put("title", item.optString("title"))
                .put("posterUrl", item.opt("posterUrl"))
                .put("media", normalizedItem)
        }
        transaction { database ->
            if (section == "short" || section == "long") {
                val previousSection = if (section == "short") "long" else "short"
                val previousKey = "$previousSection:${normalizedItem.optString("id")}"
                database.delete("catalog_items", "account = ? AND item_key = ?", arrayOf(account, previousKey))
                database.delete("catalog_fts", "account = ? AND item_key = ?", arrayOf(account, previousKey))
            }
            upsert(database, account, wrapper)
            if (section == "asmr") {
                val author = normalizedItem.optString("author").trim()
                if (author.isNotEmpty()) {
                    upsert(database, account, JSONObject()
                        .put("type", "author")
                        .put("section", "asmr")
                        .put("id", "author:$author")
                        .put("title", author)
                        .put("subtitle", "ASMR 作者目录")
                        .put("author", author))
                }
            }
        }
    }

    fun deleteItem(account: String, section: String, entityId: String) {
        if (account.isBlank()) return
        val keys: List<String> = when (section) {
            "feed" -> listOf("short:$entityId", "long:$entityId")
            "movie" -> listOf("movie:$entityId")
            "drama" -> listOf("drama:$entityId")
            else -> listOf("$section:$entityId")
        }
        transaction { database ->
            keys.forEach { key ->
                database.delete("catalog_items", "account = ? AND item_key = ?", arrayOf(account, key))
                database.delete("catalog_fts", "account = ? AND item_key = ?", arrayOf(account, key))
                database.delete("local_recent", "account = ? AND item_key = ?", arrayOf(account, key))
            }
        }
    }

    fun deleteSource(account: String, sourceId: String) {
        if (account.isBlank() || sourceId.isBlank()) return
        transaction { database ->
            val itemKeys = mutableListOf<String>()
            database.rawQuery(
                "SELECT item_key FROM catalog_items WHERE account = ? AND source_id = ?",
                arrayOf(account, sourceId),
            ).use { cursor ->
                while (cursor.moveToNext()) itemKeys += cursor.getString(0)
            }
            itemKeys.forEach { itemKey ->
                database.delete(
                    "catalog_fts",
                    "account = ? AND item_key = ?",
                    arrayOf(account, itemKey),
                )
                database.delete(
                    "local_favorites",
                    "account = ? AND item_key = ?",
                    arrayOf(account, itemKey),
                )
                database.delete(
                    "local_recent",
                    "account = ? AND item_key = ?",
                    arrayOf(account, itemKey),
                )
            }
            database.delete(
                "catalog_items",
                "account = ? AND source_id = ?",
                arrayOf(account, sourceId),
            )
            database.delete("catalog_sources", "account = ? AND source_id = ?", arrayOf(account, sourceId))
            database.delete("local_recent", "account = ? AND source_id = ?", arrayOf(account, sourceId))
        }
    }

    fun clearAccount(account: String) {
        transaction { database ->
            database.delete("catalog_items", "account = ?", arrayOf(account))
            database.delete("catalog_fts", "account = ?", arrayOf(account))
            database.delete("local_favorites", "account = ?", arrayOf(account))
            database.delete("local_recent", "account = ?", arrayOf(account))
            database.delete("catalog_sources", "account = ?", arrayOf(account))
            database.delete("sync_state", "account = ?", arrayOf(account))
        }
    }

    fun clearCatalog(account: String) {
        if (account.isBlank()) return
        transaction { database ->
            database.delete("catalog_items", "account = ?", arrayOf(account))
            database.delete("catalog_fts", "account = ?", arrayOf(account))
            database.delete("catalog_sources", "account = ?", arrayOf(account))
            database.delete("sync_state", "account = ?", arrayOf(account))
        }
    }

    private fun upsertSource(database: SQLiteDatabase, account: String, source: JSONObject) {
        val sourceId = source.optString("id").trim()
        if (sourceId.isEmpty()) return
        val values = ContentValues().apply {
            put("account", account)
            put("source_id", sourceId)
            put("section", source.optString("section"))
            put("name", source.optString("name"))
            put("payload", source.toString())
            put("updated_at", System.currentTimeMillis())
        }
        database.insertWithOnConflict("catalog_sources", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Add display-only group fields without discarding source hierarchy metadata. */
    private fun mergeSource(
        database: SQLiteDatabase,
        account: String,
        source: JSONObject,
        preserveName: Boolean = false,
    ) {
        val sourceId = source.optString("id").trim()
        if (sourceId.isEmpty()) return
        val merged = database.rawQuery(
            "SELECT payload FROM catalog_sources WHERE account = ? AND source_id = ?",
            arrayOf(account, sourceId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) JSONObject(source.toString())
            else runCatching { JSONObject(cursor.getString(0)) }.getOrElse { JSONObject(source.toString()) }
        }
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (preserveName && key == "name" && merged.optString("name").isNotBlank()) continue
            merged.put(key, source.opt(key))
        }
        upsertSource(database, account, merged)
    }

    private fun upsert(database: SQLiteDatabase, account: String, wrapper: JSONObject) {
        val key = wrapper.optString("id").trim()
        if (key.isEmpty()) return
        val favoriteState = database.rawQuery(
            "SELECT favorite FROM local_favorites WHERE account = ? AND item_key = ?",
            arrayOf(account, key),
        ).use { cursor ->
            if (cursor.moveToFirst()) true to (cursor.getInt(0) == 1) else false to false
        }
        if (favoriteState.first) {
            wrapper.put("favorite", favoriteState.second)
            wrapper.optJSONObject("movie")?.put("favorite", favoriteState.second)
        }
        val merged = mergeCatalogPayload(database, account, key, wrapper)
        val section = merged.optString("section").trim()
        val payload = merged.toString()
        val values = ContentValues().apply {
            put("account", account)
            put("item_key", key)
            put("section", section)
            put("entity_type", merged.optString("type"))
            put("source_id", sourceId(merged))
            put("title", merged.optString("title"))
            put("search_text", searchText(merged))
            put("payload", payload)
            put("deleted", 0)
            put("updated_at", System.currentTimeMillis())
        }
        database.insertWithOnConflict("catalog_items", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        database.delete("catalog_fts", "account = ? AND item_key = ?", arrayOf(account, key))
        database.insert("catalog_fts", null, ContentValues().apply {
            put("account", account)
            put("item_key", key)
            put("section", section)
            put("search_text", searchText(merged))
            put("payload", payload)
        })
    }

    private fun mergeCatalogPayload(
        database: SQLiteDatabase,
        account: String,
        key: String,
        incoming: JSONObject,
    ): JSONObject {
        val existing = database.rawQuery(
            "SELECT payload FROM catalog_items WHERE account = ? AND item_key = ?",
            arrayOf(account, key),
        ).use { cursor ->
            if (!cursor.moveToFirst()) null
            else runCatching { JSONObject(cursor.getString(0)) }.getOrNull()
        } ?: return incoming

        val merged = JSONObject(existing.toString())
        val incomingKeys = incoming.keys()
        while (incomingKeys.hasNext()) {
            val name = incomingKeys.next()
            if (name !in PAYLOAD_KEYS) merged.put(name, incoming.opt(name))
        }
        PAYLOAD_KEYS.forEach { name ->
            val incomingChild = incoming.optJSONObject(name) ?: return@forEach
            val existingChild = merged.optJSONObject(name)
            if (existingChild == null) {
                merged.put(name, incomingChild)
                return@forEach
            }
            val child = JSONObject(existingChild.toString())
            val childKeys = incomingChild.keys()
            while (childKeys.hasNext()) {
                val childName = childKeys.next()
                child.put(childName, incomingChild.opt(childName))
            }
            merged.put(name, child)
        }
        return merged
    }

    private fun sourceId(wrapper: JSONObject): String? = findSourceId(wrapper)

    private fun searchText(value: JSONObject): String = buildString { appendSearchFields(value) }

    private fun StringBuilder.appendSearchFields(value: JSONObject) {
        val indexedKeys = setOf(
            "title", "subtitle", "author", "path", "folder", "code", "year", "overview",
            "category", "tags", "genreEntities", "performerEntities", "studioEntity", "yearEntity",
        )
        val keys = value.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val child = value.opt(key)
            when {
                child is JSONObject -> {
                    if (key in indexedKeys) appendSearchValue(child)
                    appendSearchFields(child)
                }
                key in indexedKeys -> appendSearchValue(child)
            }
        }
    }

    private fun StringBuilder.appendSearchValue(value: Any?) {
        when (value) {
            is JSONObject -> {
                value.optString("name").takeIf(String::isNotBlank)?.let { append(it).append(' ') }
                value.optString("title").takeIf(String::isNotBlank)?.let { append(it).append(' ') }
            }
            is JSONArray -> for (index in 0 until value.length()) appendSearchValue(value.opt(index))
            null, JSONObject.NULL -> Unit
            else -> append(value.toString()).append(' ')
        }
    }

    private fun entityType(section: String): String = when (section) {
        "movie" -> "movie"
        "drama" -> "drama"
        else -> "media"
    }

    private fun sectionPayloadKey(section: String): String = when (section) {
        "movie" -> "movie"
        "drama" -> "drama"
        else -> "media"
    }

    private fun itemKey(section: String, item: JSONObject): String =
        "$section:${item.optString("id")}".takeIf { item.optString("id").isNotBlank() }
            ?: ""

    private inline fun transaction(block: (SQLiteDatabase) -> Unit) {
        val database = helper.writableDatabase
        database.beginTransaction()
        try {
            block(database)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    private inline fun readable(block: (SQLiteDatabase) -> Unit) {
        block(helper.readableDatabase)
    }

    private inline fun <T> readableValue(block: (SQLiteDatabase) -> T): T =
        block(helper.readableDatabase)

    private fun putFavorite(
        database: SQLiteDatabase,
        account: String,
        section: String,
        targetId: String,
        payload: JSONObject?,
        favorite: Boolean,
        synced: Boolean,
    ) {
        val values = ContentValues().apply {
            put("account", account)
            put("section", section)
            put("target_id", targetId)
            put("item_key", "$section:$targetId")
            if (payload != null) put("payload", payload.toString()) else putNull("payload")
            put("favorite", if (favorite) 1 else 0)
            put("updated_at", System.currentTimeMillis())
            put("synced", if (synced) 1 else 0)
        }
        database.insertWithOnConflict("local_favorites", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun favoriteTargetId(item: JSONObject): String? = when {
        item.optJSONObject("movie") != null -> item.getJSONObject("movie").optString("id")
        item.optJSONObject("drama") != null -> item.getJSONObject("drama").optString("id")
        item.optJSONObject("media") != null -> item.getJSONObject("media").optString("id")
        else -> null
    }.takeIf { !it.isNullOrBlank() }

    data class LocalPage(val items: List<JSONObject>, val total: Int, val nextOffset: Int?)

    private class Helper(context: Context, name: String) : SQLiteOpenHelper(context, name, null, DATABASE_VERSION) {
        override fun onCreate(database: SQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE catalog_items(
                    account TEXT NOT NULL,
                    item_key TEXT NOT NULL,
                    section TEXT NOT NULL,
                    entity_type TEXT NOT NULL,
                    source_id TEXT,
                    title TEXT NOT NULL DEFAULT '',
                    search_text TEXT NOT NULL,
                    payload TEXT NOT NULL,
                    deleted INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY(account, item_key)
                )
                """.trimIndent(),
            )
            database.execSQL("CREATE INDEX idx_catalog_items_account_section ON catalog_items(account, section, updated_at DESC)")
            database.execSQL("CREATE INDEX idx_catalog_items_account_source ON catalog_items(account, source_id)")
            database.execSQL(CREATE_CATALOG_SOURCES)
            database.execSQL(CREATE_CATALOG_SOURCES_INDEX)
            database.execSQL(
                "CREATE VIRTUAL TABLE catalog_fts USING fts4(account, item_key, section, search_text, payload, tokenize=unicode61)",
            )
            database.execSQL(
                """
                CREATE TABLE local_favorites(
                    account TEXT NOT NULL,
                    section TEXT NOT NULL,
                    target_id TEXT NOT NULL,
                    item_key TEXT NOT NULL,
                    payload TEXT,
                    favorite INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    synced INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(account, section, target_id)
                )
                """.trimIndent(),
            )
            database.execSQL("CREATE INDEX idx_local_favorites_list ON local_favorites(account, section, favorite, updated_at DESC)")
            database.execSQL(CREATE_LOCAL_RECENT)
            database.execSQL(CREATE_LOCAL_RECENT_INDEX)
            database.execSQL(
                "CREATE TABLE sync_state(account TEXT NOT NULL, scope TEXT NOT NULL, cursor INTEGER NOT NULL, PRIMARY KEY(account, scope))",
            )
        }

        override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                database.execSQL("ALTER TABLE catalog_items ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
            }
            if (oldVersion < 3) {
                database.execSQL("ALTER TABLE catalog_items ADD COLUMN source_id TEXT")
                val rows = mutableListOf<Triple<String, String, String>>()
                database.rawQuery(
                    "SELECT account, item_key, payload FROM catalog_items",
                    null,
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val sourceId = runCatching {
                            findSourceId(JSONObject(cursor.getString(2)))
                        }.getOrNull()
                        if (!sourceId.isNullOrBlank()) {
                            rows += Triple(cursor.getString(0), cursor.getString(1), sourceId)
                        }
                    }
                }
                rows.forEach { (account, itemKey, sourceId) ->
                    database.execSQL(
                        "UPDATE catalog_items SET source_id = ? WHERE account = ? AND item_key = ?",
                        arrayOf(sourceId, account, itemKey),
                    )
                }
                database.execSQL("CREATE INDEX idx_catalog_items_account_source ON catalog_items(account, source_id)")
            }
            if (oldVersion < 4) {
                database.execSQL(CREATE_CATALOG_SOURCES)
                database.execSQL(CREATE_CATALOG_SOURCES_INDEX)
            }
            if (oldVersion < 5) {
                database.execSQL(CREATE_LOCAL_RECENT)
                database.execSQL(CREATE_LOCAL_RECENT_INDEX)
            }
        }
    }

    companion object {
        private val PAYLOAD_KEYS = setOf("media", "movie", "drama")

        private fun findSourceId(wrapper: JSONObject): String? {
            val direct = wrapper.optNullableString("sourceId")
                ?: wrapper.optNullableString("source")
            if (direct != null) return direct
            return sequenceOf("media", "movie", "drama")
                .mapNotNull {
                    wrapper.optJSONObject(it)?.let { child ->
                        child.optNullableString("sourceId")
                            ?: child.optNullableString("source")
                    }
                }
                .firstOrNull()
        }

        private const val DATABASE_NAME = "local-media.db"
        private const val DATABASE_VERSION = 5

        private const val CREATE_CATALOG_SOURCES = """
            CREATE TABLE catalog_sources(
                account TEXT NOT NULL,
                source_id TEXT NOT NULL,
                section TEXT NOT NULL,
                name TEXT NOT NULL,
                payload TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(account, source_id)
            )
        """
        private const val CREATE_CATALOG_SOURCES_INDEX =
            "CREATE INDEX idx_catalog_sources_account_section ON catalog_sources(account, section, name COLLATE NOCASE)"

        private const val CREATE_LOCAL_RECENT = """
            CREATE TABLE local_recent(
                account TEXT NOT NULL,
                section TEXT NOT NULL,
                item_key TEXT NOT NULL,
                source_id TEXT,
                payload TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(account, section, item_key)
            )
        """
        private const val CREATE_LOCAL_RECENT_INDEX =
            "CREATE INDEX idx_local_recent_list ON local_recent(account, section, updated_at DESC)"
    }
}
