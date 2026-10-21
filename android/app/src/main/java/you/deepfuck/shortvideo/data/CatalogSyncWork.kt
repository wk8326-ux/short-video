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

/** Consume every bootstrap page while keeping the caller's write operation incremental. */
internal fun walkCatalogBootstrapPages(
    fetch: (offset: Int) -> CatalogSyncPage,
    consume: (CatalogSyncPage) -> Unit,
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
internal fun walkCatalogChangePages(
    initialCursor: Long,
    fetch: (cursor: Long) -> CatalogSyncPage,
    consume: (CatalogSyncPage) -> Unit,
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

        try {
            flushFavorites(store, api, account)
            if (isFavoritesOnly) return@withContext Result.success()
            syncSources(store, api, account, requestedSection, sections, scope, requestedSourceId)
            Result.success()
        } catch (error: ApiException) {
            if (error.statusCode == 401) Result.success() else Result.retry()
        } catch (_: IOException) {
            Result.retry()
        }
    }

    private fun syncSources(
        store: LocalMediaStore,
        api: MediaApi,
        account: String,
        requestedSection: String,
        sections: List<String>,
        legacyScope: String,
        requestedSourceId: String?,
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
        store.cacheSources(account, discovery.sources)
        val sourceIds = discovery.sources.mapNotNull { source ->
            source.optString("id").trim().takeIf(String::isNotEmpty)
        }.toSet()
        if (requestedSourceId == null) {
            store.deleteMissingSources(account, discoverySection, sourceIds)
        }

        val legacyCursor = store.cursor(account, legacyScope).takeIf {
            store.hasCursor(account, legacyScope)
        }
        sourceIds.forEach { sourceId ->
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
        const val KEY_SECTION = "catalog_section"
        const val KEY_SOURCE_ID = "catalog_source_id"
        private const val BOOTSTRAP_LIMIT = 300
        private const val CHANGE_LIMIT = 500
        private const val FAVORITES_SCOPE = "favorites"
        private val SYNC_SECTIONS = setOf("short", "long", "asmr", "movie", "drama")
        private val FAVORITE_SECTIONS = SYNC_SECTIONS

        fun uniqueName(account: String, section: String, sourceId: String? = null): String =
            "catalog-sync-${account.take(16)}-$section-${sourceId?.take(32) ?: "all"}"

        fun request(section: String, sourceId: String? = null): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<CatalogSyncWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(KEY_SECTION, section)
                        .apply { sourceId?.let { putString(KEY_SOURCE_ID, it) } }
                        .build(),
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .addTag(TAG)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()

        fun enqueue(context: Context, account: String, section: String, sourceId: String? = null) {
            if (account.isBlank()) return
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueName(account, section, sourceId),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request(section, sourceId),
            )
        }

    }
}
