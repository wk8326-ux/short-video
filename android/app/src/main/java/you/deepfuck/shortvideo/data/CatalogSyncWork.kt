package you.deepfuck.shortvideo.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class CatalogSyncProgress(
    val section: String = "",
    val sourceId: String? = null,
    val sourceName: String? = null,
    val sourceIndex: Int = 0,
    val sourceCount: Int = 0,
    val phase: String = "",
    val pages: Int = 0,
    val records: Int = 0,
) {
    fun toData(): Data = Data.Builder()
        .putString(KEY_SECTION, section)
        .putString(KEY_SOURCE_ID, sourceId)
        .putString(KEY_SOURCE_NAME, sourceName)
        .putInt(KEY_SOURCE_INDEX, sourceIndex)
        .putInt(KEY_SOURCE_COUNT, sourceCount)
        .putString(KEY_PHASE, phase)
        .putInt(KEY_PAGES, pages)
        .putInt(KEY_RECORDS, records)
        .build()

    companion object {
        private const val KEY_SECTION = "section"
        private const val KEY_SOURCE_ID = "sourceId"
        private const val KEY_SOURCE_NAME = "sourceName"
        private const val KEY_SOURCE_INDEX = "sourceIndex"
        private const val KEY_SOURCE_COUNT = "sourceCount"
        private const val KEY_PHASE = "phase"
        private const val KEY_PAGES = "pages"
        private const val KEY_RECORDS = "records"

        fun fromData(data: Data): CatalogSyncProgress = CatalogSyncProgress(
            section = data.getString(KEY_SECTION).orEmpty(),
            sourceId = data.getString(KEY_SOURCE_ID).orEmpty().ifBlank { null },
            sourceName = data.getString(KEY_SOURCE_NAME).orEmpty().ifBlank { null },
            sourceIndex = data.getInt(KEY_SOURCE_INDEX, 0),
            sourceCount = data.getInt(KEY_SOURCE_COUNT, 0),
            phase = data.getString(KEY_PHASE).orEmpty(),
            pages = data.getInt(KEY_PAGES, 0),
            records = data.getInt(KEY_RECORDS, 0),
        )
    }
}

/** Consume every bootstrap page while keeping the caller's write operation incremental. */
internal suspend fun walkCatalogBootstrapPages(
    fetch: (offset: Int) -> CatalogSyncPage,
    consume: suspend (CatalogSyncPage) -> Unit,
) {
    var offset = 0
    while (true) {
        val page = fetch(offset)
        consume(page)
        val nextOffset = page.nextOffset ?: return
        check(nextOffset > offset) { "Catalog bootstrap returned a non-advancing offset" }
        offset = nextOffset
    }
}

/** Consume all change pages and return the cursor that was applied last. */
internal suspend fun walkCatalogChangePages(
    initialCursor: Long,
    fetch: (cursor: Long) -> CatalogSyncPage,
    consume: suspend (CatalogSyncPage) -> Unit,
): Long {
    var cursor = initialCursor.coerceAtLeast(0L)
    while (true) {
        val page = fetch(cursor)
        check(page.nextCursor >= cursor) { "Catalog changes returned a decreasing cursor" }
        consume(page)
        if (!page.hasMore || page.nextCursor <= cursor) return page.nextCursor
        cursor = page.nextCursor
    }
}

/** Incrementally fills the account-scoped catalogue read model. */
internal class CatalogSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val preferences = PlaybackPreferences(applicationContext)
        val account = preferences.apiCacheAccount ?: return@withContext Result.success()
        val requestedAccount = inputData.getString(KEY_ACCOUNT).orEmpty()
        if (requestedAccount.isNotBlank() && requestedAccount != account) {
            return@withContext Result.success()
        }
        val store = LocalMediaStore(applicationContext)
        val api = MediaApi(preferences)
        val requestedSection = inputData.getString(KEY_SECTION).orEmpty()
        val requestedSourceId = inputData.getString(KEY_SOURCE_ID).orEmpty().trim().ifBlank { null }
        val isFavoritesOnly = requestedSection == FAVORITES_SCOPE
        val sections = when (requestedSection) {
            FAVORITES_SCOPE -> emptyList()
            "feed" -> listOf("short", "long")
            "short", "long", "asmr", "movie", "drama" -> listOf(requestedSection)
            else -> emptyList()
        }
        val scope = if (requestedSection == "feed") "feed" else requestedSection
        if (!isFavoritesOnly && sections.isEmpty()) return@withContext Result.success()

        var pages = 0
        var records = 0
        var lastSourceId: String? = requestedSourceId
        var lastSourceName: String? = null
        var lastSourceIndex = 0
        var sourceCount = 0
        var lastSnapshot = CatalogSyncProgress()
        var lastReportedAt = 0L
        suspend fun report(
            phase: String,
            section: String = requestedSection,
            sourceId: String? = lastSourceId,
            sourceName: String? = lastSourceName,
            sourceIndex: Int = lastSourceIndex,
            page: CatalogSyncPage? = null,
        ) {
            page?.let {
                pages += 1
                records += it.upserts.size + it.deletes.size
            }
            lastSourceId = sourceId
            lastSourceName = sourceName
            lastSourceIndex = sourceIndex
            val progress = CatalogSyncProgress(
                section = section,
                sourceId = sourceId,
                sourceName = sourceName,
                sourceIndex = sourceIndex,
                sourceCount = sourceCount,
                phase = phase,
                pages = pages,
                records = records,
            )
            val now = System.nanoTime()
            val samePhase = progress.copy(pages = lastSnapshot.pages, records = lastSnapshot.records) == lastSnapshot
            if (samePhase && now - lastReportedAt < PROGRESS_INTERVAL_NS) return
            setProgress(progress.toData())
            lastSnapshot = progress
            lastReportedAt = now
        }

        try {
            report("favorites")
            flushFavorites(store, api, account)
            if (isFavoritesOnly) return@withContext Result.success()
            report("discovering")
            syncSources(
                store = store,
                api = api,
                account = account,
                requestedSection = requestedSection,
                sections = sections,
                legacyScope = scope,
                requestedSourceId = requestedSourceId,
                report = { phase, section, sourceId, sourceName, sourceIndex, count, page ->
                    sourceCount = count
                    report(phase, section, sourceId, sourceName, sourceIndex, page)
                },
            )
            report("complete")
            Result.success()
        } catch (error: ApiException) {
            report("retrying")
            if (error.statusCode == 401) Result.success() else Result.retry()
        } catch (_: IOException) {
            report("retrying")
            Result.retry()
        }
    }

    private suspend fun syncSources(
        store: LocalMediaStore,
        api: MediaApi,
        account: String,
        requestedSection: String,
        sections: List<String>,
        legacyScope: String,
        requestedSourceId: String?,
        report: suspend (
            phase: String,
            section: String,
            sourceId: String?,
            sourceName: String?,
            sourceIndex: Int,
            sourceCount: Int,
            page: CatalogSyncPage?,
        ) -> Unit,
    ) {
        // A bootstrap with one row is used only as a cheap source snapshot.
        // The actual media bootstrap/change pass is then isolated per source.
        val discoverySection = sections.first()
        val cachedSection = if (discoverySection == "short" || discoverySection == "long") {
            "feed"
        } else {
            discoverySection
        }
        val knownSourceIds = store.cachedSourceIds(account, cachedSection)
        val discovery = api.syncBootstrap(
            discoverySection,
            sourceId = requestedSourceId,
            limit = 1,
        )
        report("discovering", requestedSection, requestedSourceId, null, 0, 0, discovery)
        store.cacheSources(account, discovery.sources)
        val sourceNames = discovery.sources.mapNotNull { source ->
            val id = source.optString("id").trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
            id to source.optString("name").trim().ifBlank { id }
        }.toMap()
        val sourceIds = sourceNames.keys
        report("discovering", requestedSection, null, null, 0, sourceIds.size, null)
        if (requestedSourceId == null) {
            store.deleteMissingSources(account, discoverySection, sourceIds)
        }

        val legacyCursor = store.cursor(account, legacyScope).takeIf {
            store.hasCursor(account, legacyScope)
        }
        sourceIds.forEachIndexed { sourceOffset, sourceId ->
            val sourceName = sourceNames[sourceId]
            val sourceIndex = sourceOffset + 1
            val sourceScope = sourceScope(requestedSection, sourceId)
            val sourceCursor = store.cursor(account, sourceScope).takeIf {
                store.hasCursor(account, sourceScope)
            }
            val isKnownSource = sourceId in knownSourceIds
            if (sourceCursor == null && (!isKnownSource || legacyCursor == null)) {
                var bootstrapRevision = discovery.serverRevision
                sections.forEach { section ->
                    walkCatalogBootstrapPages(
                        fetch = { offset ->
                            api.syncBootstrap(
                                section = section,
                                sourceId = sourceId,
                                limit = BOOTSTRAP_LIMIT,
                                offset = offset,
                            )
                        },
                        consume = { page ->
                            // Keep the earliest snapshot revision so changes made
                            // while paging are replayed by the following pass.
                            bootstrapRevision = minOf(bootstrapRevision, page.serverRevision)
                            store.cacheSources(account, page.sources)
                            page.upserts.forEach { upsert -> applyUpsert(store, account, upsert) }
                            report(
                                "bootstrap",
                                section,
                                sourceId,
                                sourceName,
                                sourceIndex,
                                sourceIds.size,
                                page,
                            )
                        },
                    )
                }
                store.saveCursor(account, sourceScope, bootstrapRevision)
            } else {
                val initialCursor = sourceCursor ?: legacyCursor ?: discovery.serverRevision
                walkCatalogChangePages(
                    initialCursor = initialCursor,
                    fetch = { cursor ->
                        api.syncChanges(
                            cursor = cursor,
                            section = requestedSection,
                            sourceId = sourceId,
                            limit = CHANGE_LIMIT,
                        )
                    },
                    consume = { page ->
                        applyPage(
                            store,
                            account,
                            if (requestedSection == "feed") "feed" else requestedSection,
                            page,
                        )
                        store.saveCursor(account, sourceScope, page.nextCursor)
                        report(
                            "changes",
                            requestedSection,
                            sourceId,
                            sourceName,
                            sourceIndex,
                            sourceIds.size,
                            page,
                        )
                    },
                )
            }
        }

        // Keep the old section cursor as a migration marker. New sources are
        // detected from the snapshot above and always bootstrap, so advancing
        // this marker cannot make a newly-created source skip its first rows.
        store.saveCursor(account, legacyScope, discovery.serverRevision)
    }

    private fun applyPage(
        store: LocalMediaStore,
        account: String,
        scope: String,
        page: CatalogSyncPage,
    ) {
        store.cacheSources(account, page.sources)
        page.upserts.forEach { upsert -> applyUpsert(store, account, upsert) }
        page.deletes.forEach { delete ->
            if (delete.entityType == "source") {
                store.deleteSource(account, delete.sourceId ?: delete.entityId)
            } else {
                val deleteScope = when (delete.entityType) {
                    "media" -> if (scope == "feed") "feed" else delete.section
                    "movie" -> "movie"
                    "drama" -> "drama"
                    else -> ""
                }
                if (deleteScope.isNotBlank()) store.deleteItem(account, deleteScope, delete.entityId)
            }
        }
    }

    private fun sourceScope(section: String, sourceId: String): String =
        "source:${if (section == "feed" || section == "short" || section == "long") "feed" else section}:$sourceId"

    private fun applyUpsert(store: LocalMediaStore, account: String, upsert: CatalogSyncItem) {
        if (upsert.entityType == "source") {
            store.applySyncSource(account, upsert.item)
        } else if (upsert.section in SYNC_SECTIONS) {
            store.applySyncItem(account, upsert.section, upsert.item)
        }
    }

    private fun flushFavorites(
        store: LocalMediaStore,
        api: MediaApi,
        account: String,
    ) {
        store.pendingFavoriteChanges(account).forEach { change ->
            val section = change.optString("section")
            val targetId = change.optString("targetId")
            if (section !in FAVORITE_SECTIONS || targetId.isBlank()) return@forEach
            val wanted = change.optBoolean("favorite")
            try {
                api.setFavorite(section, targetId, wanted)
                store.markFavoriteSynced(account, section, targetId, wanted)
            } catch (error: ApiException) {
                if (error.statusCode != 404) throw error
                store.setFavorite(account, section, targetId, null, false)
                store.markFavoriteSynced(account, section, targetId, false)
            }
        }
    }

    companion object {
        const val TAG = "catalog-sync"
        const val KEY_ACCOUNT = "catalog_account"
        const val KEY_SECTION = "catalog_section"
        const val KEY_SOURCE_ID = "catalog_source_id"
        private const val BOOTSTRAP_LIMIT = 300
        private const val CHANGE_LIMIT = 500
        private const val FAVORITES_SCOPE = "favorites"
        private val SYNC_SECTIONS = setOf("short", "long", "asmr", "movie", "drama")
        private val FAVORITE_SECTIONS = SYNC_SECTIONS

        fun uniqueName(account: String, section: String, sourceId: String? = null): String =
            "catalog-sync-${account.take(16)}-$section-${sourceId?.take(32) ?: "all"}"

        fun refreshTag(refreshId: String): String = "catalog-sync-refresh:$refreshId"

        fun inputData(
            account: String,
            section: String,
            sourceId: String? = null,
            refreshId: String? = null,
        ): Data = Data.Builder()
            .putString(KEY_ACCOUNT, account)
            .putString(KEY_SECTION, section)
            .apply { sourceId?.let { putString(KEY_SOURCE_ID, it) } }
            .apply { refreshId?.let { putString(KEY_REFRESH_ID, it) } }
            .build()

        fun request(
            account: String,
            section: String,
            sourceId: String? = null,
            refreshId: String? = null,
        ): OneTimeWorkRequest = OneTimeWorkRequestBuilder<CatalogSyncWorker>()
                .setInputData(inputData(account, section, sourceId, refreshId))
                .apply { refreshId?.let { addTag(refreshTag(it)) } }
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .addTag(TAG)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()

        fun enqueue(
            context: Context,
            account: String,
            section: String,
            sourceId: String? = null,
            refreshId: String? = null,
        ) {
            if (account.isBlank()) return
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueName(account, section, sourceId),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request(account, section, sourceId, refreshId),
            )
        }

        const val KEY_REFRESH_ID = "catalog_refresh_id"
        private const val PROGRESS_INTERVAL_NS = 750_000_000L

    }
}
