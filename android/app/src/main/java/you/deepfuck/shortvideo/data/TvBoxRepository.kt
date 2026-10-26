package you.deepfuck.shortvideo.data

import android.content.Context
import android.text.Html
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Blocking, standalone CMS client. Call network methods from an IO dispatcher. */
class TvBoxRepository(
    context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).build(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val store = TvBoxStore(context)
    private val appContext = context.applicationContext
    private val movieSources = ConcurrentHashMap<Long, String>()
    private val pendingCalls = ConcurrentHashMap.newKeySet<Call>()
    private val requestGeneration = AtomicLong(0L)

    init {
        if (store.config() == null) {
            val bundled = bundledText()
            bundled?.let { store.saveConfig(TvBoxConfigCodec.decode(it, "asset:tvbox.json")) }
        }
    }

    fun config(): TvBoxConfig? = store.config()

    /** URL or plain/Base64/hex content. Validation succeeds before replacing the saved config. */
    fun importConfig(input: String): TvBoxConfig {
        val text = input.trim()
        val url = text.toHttpUrlOrNull()
        val requestEpoch = requestGeneration.get()
        val payload = if (url != null) download(url.toString(), TvBoxConfigCodec.MAX_CONFIG_CHARS, requestEpoch) else text
        ensureRequestCurrent(requestEpoch)
        val parsed = TvBoxConfigCodec.decode(payload, url?.toString())
        ensureRequestCurrent(requestEpoch)
        store.saveConfig(parsed)
        return parsed
    }

    fun sites(): List<TvBoxSite> = config()?.sites.orEmpty()

    fun useBundledConfig(): TvBoxConfig {
        val text = bundledText() ?: throw TvBoxConfigException("应用未包含默认 tvbox.json 配置")
        return TvBoxConfigCodec.decode(text, "asset:tvbox.json").also(store::saveConfig)
    }

    fun refreshConfig(): TvBoxConfig {
        val previous = config() ?: throw TvBoxConfigException("文本配置没有订阅地址，请重新导入或恢复默认配置")
        val origin = previous.origin
            ?: throw TvBoxConfigException("文本配置没有订阅地址，请重新导入或恢复默认配置")
        val requestEpoch = requestGeneration.get()
        val text = if (origin == "asset:tvbox.json") {
            bundledText() ?: throw TvBoxConfigException("应用未包含默认 tvbox.json 配置")
        } else {
            download(origin, TvBoxConfigCodec.MAX_CONFIG_CHARS, requestEpoch)
        }
        ensureRequestCurrent(requestEpoch)
        val refreshed = TvBoxConfigCodec.decode(text, origin)
        val previousById = previous.sites.associateBy { it.id }
        val refreshedById = refreshed.sites.associateBy { it.id }
        val orderedSites = buildList {
            previous.sites.forEach { old ->
                refreshedById[old.id]?.let { updated -> add(updated.copy(enabled = old.enabled)) }
            }
            refreshed.sites.forEach { updated ->
                if (updated.id !in previousById) add(updated)
            }
        }
        ensureRequestCurrent(requestEpoch)
        return refreshed.copy(sites = orderedSites).also(store::saveConfig)
    }

    fun removeSource(id: String) {
        store.updateConfig { it.copy(sites = it.sites.filterNot { source -> source.id == id }) }
    }

    fun setSourceEnabled(id: String, enabled: Boolean) {
        store.updateConfig { it.copy(sites = it.sites.map { source -> if (source.id == id) source.copy(enabled = enabled) else source }) }
    }

    fun disabledSourceIds(): Set<String> = sites().filterNot { it.enabled }.map { it.id }.toSet()

    fun moveSource(id: String, delta: Int) {
        store.updateConfig { config ->
            val sources = config.sites.toMutableList()
            val index = sources.indexOfFirst { it.id == id }
            if (index >= 0) {
                val target = (index.toLong() + delta).coerceIn(0L, sources.lastIndex.toLong()).toInt()
                sources.add(target, sources.removeAt(index))
            }
            config.copy(sites = sources)
        }
    }

    /** In-flight requests cannot repopulate caches belonging to an earlier generation. */
    fun clearCache() {
        store.clearCache()
        movieSources.clear()
    }

    /** Cancels this repository's active requests while preserving cached pages and local state. */
    fun cancelPendingRequests() {
        requestGeneration.incrementAndGet()
        pendingCalls.toList().forEach(Call::cancel)
    }

    /** No requests: counts are -1 until an unfiltered CMS catalog response supplies a total. */
    fun sourceSummaries(): List<MovieSource> = sites().filter { it.enabled }.map { summary(it, store.state(it.id)) }

    /** One request only; category previews are fetched independently with [categoryPage]. */
    fun catalog(sourceId: String, forceRefresh: Boolean = false): TvBoxCatalog {
        val site = site(sourceId)
        val response = request(site, mapOf("ac" to "list", "pg" to "1"), forceRefresh) { value ->
            requireArray(value, "class")
        }
        val categories = parseCategories(response.value)
        val total = count(response.value, "total")
        store.updateResponseState(sourceId, response.url, response.generation, response.ticket) { state ->
            state.put("categories", response.value.getJSONArray("class"))
            if (total >= 0) state.put("total", total)
        }
        val page = parsePage(site, null, 1, response)
        return TvBoxCatalog(summary(site, store.state(sourceId)), categories, page)
    }

    fun categories(sourceId: String): List<TvBoxCategory> {
        val state = store.state(site(sourceId).id)
        return if (state.has("categories")) {
            parseCategories(JSONObject().put("class", state.getJSONArray("categories")))
        } else catalog(sourceId).categories
    }

    fun categoryPage(
        sourceId: String,
        categoryId: String?,
        page: Int = 1,
        query: String = "",
        forceRefresh: Boolean = false,
    ): TvBoxPage {
        require(page >= 1) { "CMS pages start at 1" }
        val site = site(sourceId)
        if (query.isNotBlank() && !site.searchable) throw TvBoxProtocolException("Source '${site.name}' does not support search")
        val params = linkedMapOf("ac" to "detail", "pg" to page.toString())
        categoryId?.takeIf { it.isNotBlank() }?.let { params["t"] = it }
        query.trim().takeIf { it.isNotEmpty() }?.let { params["wd"] = it }
        val response = request(site, params, forceRefresh) { requireArray(it, "list") }
        if (categoryId.isNullOrBlank() && query.isBlank()) {
            val total = count(response.value, "total")
            if (total >= 0) store.updateResponseState(sourceId, response.url, response.generation, response.ticket) {
                it.put("total", total)
            }
        }
        return parsePage(site, categoryId, page, response)
    }

    fun page(
        sourceId: String,
        categoryId: String? = null,
        page: Int = 1,
        query: String = "",
        forceRefresh: Boolean = false,
    ): TvBoxPage = categoryPage(sourceId, categoryId, page, query, forceRefresh)

    fun search(sourceId: String, query: String, page: Int = 1): TvBoxPage =
        categoryPage(sourceId, null, page, query)

    /** Use this overload for displayed movies: their identity survives catalog eviction or clearing. */
    fun detail(movie: MovieItem, forceRefresh: Boolean = false): TvBoxDetail =
        detail(record(movie), forceRefresh)

    /** ID-only lookup requires a record still present in the local catalog or protected user data. */
    fun detail(movieId: Long, forceRefresh: Boolean = false): TvBoxDetail =
        detail(locate(movieId), forceRefresh)

    private fun detail(record: Record, forceRefresh: Boolean): TvBoxDetail {
        val remoteId = record.payload.text("vod_id")
        val response = request(record.site, mapOf("ac" to "detail", "ids" to remoteId), forceRefresh) { value ->
            val items = requireArray(value, "list")
            if ((0 until items.length()).none { items.optJSONObject(it)?.text("vod_id") == remoteId }) {
                throw TvBoxProtocolException("CMS detail response did not contain movie '$remoteId'")
            }
        }
        val items = response.value.getJSONArray("list")
        val payload = (0 until items.length()).mapNotNull { items.optJSONObject(it) }
            .first { it.text("vod_id") == remoteId }
        remember(record.site, listOf(payload), detail = true, response = response)
        return TvBoxDetail(record.site.id, remoteId, movie(record.site, payload, store.state(record.site.id)),
            playLines(record.site, payload), response.fromCache)
    }

    /** Pass the selected line and episode explicitly; parser/spider URLs are never sent to a backend. */
    fun resolvePlayback(detail: TvBoxDetail, lineId: String, episodeId: Long): MediaEntry {
        site(detail.sourceId)
        val line = detail.playLines.firstOrNull { it.id == lineId }
            ?: throw TvBoxPlaybackException("所选播放线路不存在")
        val episode = line.episodes.firstOrNull { it.id == episodeId }
            ?: throw TvBoxPlaybackException("线路「${line.name}」中没有所选剧集")
        val url = episode.url.toHttpUrlOrNull()
            ?: throw TvBoxPlaybackException("剧集「${episode.name}」需要不支持的解析器，仅支持原始 HTTP(S) 播放地址")
        return MediaEntry(
            id = episode.id,
            title = if (line.episodes.size == 1) detail.movie.title else "${detail.movie.title} - ${episode.name}",
            size = 0L,
            modified = detail.movie.modified,
            durationSeconds = null,
            playUrl = url.toString(),
            posterUrl = detail.movie.posterUrl,
            format = format(url.encodedPath),
        )
    }

    fun settings(): TvBoxSettings = store.settings()

    fun saveSettings(settings: TvBoxSettings) = store.saveSettings(settings)

    fun favorites(sourceId: String? = null): List<MovieItem> = localMovies("favorites", sourceId)

    fun recent(sourceId: String? = null): List<MovieItem> = localMovies("recent", sourceId)

    /** Retains a displayed movie offline even after its cached catalog record was evicted. */
    fun setFavorite(movie: MovieItem, favorite: Boolean): MovieItem =
        setFavorite(record(movie), movie.id, favorite)

    fun setFavorite(movieId: Long, favorite: Boolean): MovieItem =
        setFavorite(locate(movieId), movieId, favorite)

    private fun setFavorite(record: Record, movieId: Long, favorite: Boolean): MovieItem {
        var payload = record.payload
        store.updateState(record.site.id) { state ->
            val favorites = state.objectOrNew("favorites")
            if (favorite) favorites.put(movieId.toString(), nowMs()) else favorites.remove(movieId.toString())
            val items = state.objectOrNew("items")
            val cached = items.optJSONObject(movieId.toString())?.optJSONObject("payload")
            if (cached != null) {
                payload = cached
            } else if (favorite) {
                items.put(movieId.toString(), JSONObject().put("payload", payload)
                    .put("detail", false).put("cachedAt", nowMs()))
                movieSources[movieId] = record.site.id
            }
            trimCatalog(state)
        }
        return movie(record.site, payload, store.state(record.site.id))
    }

    fun recordRecent(movie: MovieItem) = recordRecent(record(movie), movie.id)

    fun recordRecent(movieId: Long) = recordRecent(locate(movieId), movieId)

    private fun recordRecent(record: Record, movieId: Long) {
        store.updateState(record.site.id) { state ->
            val recent = state.objectOrNew("recent")
            recent.put(movieId.toString(), nowMs())
            recent.keys().asSequence().toList().sortedByDescending { recent.optLong(it) }
                .drop(200).forEach(recent::remove)
            retainRecord(state, record, movieId)
            trimCatalog(state)
        }
    }

    fun progress(movie: MovieItem): TvBoxProgress? {
        val record = record(movie)
        return savedProgress(record.site.id, movie.id, store.state(record.site.id))
    }

    fun progress(movieId: Long): TvBoxProgress? {
        val record = locate(movieId)
        return savedProgress(record.site.id, movieId, store.state(record.site.id))
    }

    fun saveProgress(
        movie: MovieItem,
        lineId: String,
        episodeId: Long,
        positionMs: Long,
        durationMs: Long,
    ): TvBoxProgress = saveProgress(record(movie), movie.id, lineId, episodeId, positionMs, durationMs)

    fun saveProgress(
        movieId: Long,
        lineId: String,
        episodeId: Long,
        positionMs: Long,
        durationMs: Long,
    ): TvBoxProgress = saveProgress(locate(movieId), movieId, lineId, episodeId, positionMs, durationMs)

    private fun saveProgress(
        record: Record,
        movieId: Long,
        lineId: String,
        episodeId: Long,
        positionMs: Long,
        durationMs: Long,
    ): TvBoxProgress {
        require(positionMs >= 0L && durationMs >= 0L) { "Playback times must not be negative" }
        val lines = playLines(record.site, record.payload)
        val line = lines.firstOrNull { it.id == lineId }
            ?: throw TvBoxPlaybackException("所选播放线路不存在")
        if (line.episodes.none { it.id == episodeId }) throw TvBoxPlaybackException("线路「${line.name}」中没有所选剧集")
        val saved = TvBoxProgress(record.site.id, movieId, lineId, episodeId,
            if (durationMs > 0) positionMs.coerceAtMost(durationMs) else positionMs, durationMs, nowMs())
        store.updateState(record.site.id) { state ->
            state.objectOrNew("progress").put(movieId.toString(), JSONObject()
                .put("lineId", lineId).put("episodeId", episodeId).put("positionMs", saved.positionMs)
                .put("durationMs", durationMs).put("updatedAtMs", saved.updatedAtMs))
            state.objectOrNew("recent").put(movieId.toString(), saved.updatedAtMs)
            retainRecord(state, record, movieId)
            trimCatalog(state)
        }
        return saved
    }

    private fun retainRecord(state: JSONObject, record: Record, movieId: Long) {
        val items = state.objectOrNew("items")
        if (!items.has(movieId.toString())) {
            items.put(movieId.toString(), JSONObject().put("payload", record.payload)
                .put("detail", true).put("cachedAt", nowMs()))
            movieSources[movieId] = record.site.id
        }
    }

    private fun localMovies(kind: String, sourceId: String?): List<MovieItem> = sites()
        .filter { sourceId == null || it.id == sourceId }.flatMap { site ->
            val state = store.state(site.id)
            val selected = state.optJSONObject(kind) ?: JSONObject()
            val records = state.optJSONObject("items") ?: JSONObject()
            selected.keys().asSequence().mapNotNull { id ->
                records.optJSONObject(id)?.optJSONObject("payload")?.let { selected.optLong(id) to movie(site, it, state) }
            }.toList()
        }.sortedByDescending { it.first }.map { it.second }

    private fun savedProgress(sourceId: String, movieId: Long, state: JSONObject): TvBoxProgress? =
        state.optJSONObject("progress")?.optJSONObject(movieId.toString())?.let { value ->
            TvBoxProgress(sourceId, movieId, value.getString("lineId"), value.getLong("episodeId"),
                value.optLong("positionMs"), value.optLong("durationMs"), value.optLong("updatedAtMs"))
        }

    private fun site(sourceId: String): TvBoxSite = sites().firstOrNull { it.id == sourceId }
        ?: throw TvBoxConfigException("TVBox 站点不存在：$sourceId")

    private data class Record(val site: TvBoxSite, val payload: JSONObject)

    private fun record(movie: MovieItem): Record {
        val ref = movie.tvBoxRef ?: return locate(movie.id)
        require(ref.remoteId.isNotBlank() && movie.id == tvBoxId(ref.sourceId, "movie", ref.remoteId)) {
            "TVBox movie reference does not match its source-scoped ID"
        }
        val source = site(ref.sourceId)
        val payload = try {
            JSONObject(ref.payloadJson)
        } catch (error: Exception) {
            throw TvBoxProtocolException("Invalid retained TVBox movie metadata", error)
        }
        if (payload.text("vod_id") != ref.remoteId) {
            throw TvBoxProtocolException("Retained TVBox movie metadata does not match its remote ID")
        }
        return Record(source, payload)
    }

    private fun locate(movieId: Long): Record {
        require(movieId < 0L) { "TVBox movie IDs must be negative; backend IDs are unsupported" }
        val sources = sites()
        val known = movieSources[movieId]
        sources.sortedBy { if (it.id == known) 0 else 1 }.forEach { site ->
            store.state(site.id).optJSONObject("items")?.optJSONObject(movieId.toString())
                ?.optJSONObject("payload")?.let {
                    movieSources[movieId] = site.id
                    return Record(site, it)
                }
        }
        throw TvBoxProtocolException("Unknown local TVBox movie: $movieId; load its source page first")
    }

    private fun summary(site: TvBoxSite, state: JSONObject): MovieSource {
        val categories = state.optJSONArray("categories") ?: JSONArray()
        val ids = (0 until categories.length()).mapNotNull { categories.optJSONObject(it)?.text("type_id") }
            .filter { it.isNotBlank() }.distinct()
        return MovieSource(site.id, site.name, "tvbox", ids.map { "${site.id}/$it" },
            if (state.has("categories")) ids.size else -1, count(state, "total"))
    }

    private fun parseCategories(payload: JSONObject): List<TvBoxCategory> {
        val categories = payload.optJSONArray("class") ?: return emptyList()
        return (0 until categories.length()).mapNotNull { index ->
            categories.optJSONObject(index)?.let { value ->
                value.text("type_id").takeIf { it.isNotBlank() }?.let { TvBoxCategory(it, value.text("type_name")) }
            }
        }.distinctBy { it.id }
    }

    private data class Payload(
        val value: JSONObject,
        val fromCache: Boolean,
        val generation: Long,
        val url: String,
        val ticket: Long,
    )

    private fun parsePage(site: TvBoxSite, categoryId: String?, requestedPage: Int, response: Payload): TvBoxPage {
        val value = response.value
        val array = value.optJSONArray("list") ?: JSONArray()
        val records = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            .filter { it.text("vod_id").isNotBlank() }.distinctBy { it.text("vod_id") }
        remember(site, records, response = response)
        val state = store.state(site.id)
        val items = records.map { movie(site, it, state) }
        val page = count(value, "page").takeIf { it > 0 } ?: requestedPage
        val total = count(value, "total")
        val limit = count(value, "limit").takeIf { it > 0 }
        val pageCount = count(value, "pagecount").takeIf { it >= 0 }
            ?: if (total >= 0 && limit != null) ((total.toLong() + limit - 1) / limit).toInt() else -1
        val next = when {
            items.isEmpty() -> null
            pageCount >= 0 -> (page + 1).takeIf { page < pageCount }
            limit != null && items.size < limit -> null
            else -> page + 1
        }
        return TvBoxPage(site.id, categoryId, page, items, total, pageCount, next, response.fromCache)
    }

    private fun remember(site: TvBoxSite, payloads: List<JSONObject>, detail: Boolean = false, response: Payload) {
        if (payloads.isEmpty()) return
        store.updateResponseState(site.id, response.url, response.generation, response.ticket) { state ->
            val items = state.objectOrNew("items")
            payloads.forEach { payload ->
                val id = tvBoxId(site.id, "movie", payload.text("vod_id")).toString()
                movieSources[id.toLong()] = site.id
                val old = items.optJSONObject(id)
                if (detail || old?.optBoolean("detail") != true) {
                    items.put(id, JSONObject().put("payload", payload).put("detail", detail).put("cachedAt", nowMs()))
                }
            }
            trimCatalog(state)
        }
    }

    private fun trimCatalog(state: JSONObject) {
        val items = state.optJSONObject("items") ?: return
        if (items.length() <= MAX_CATALOG_ITEMS) return
        val protectedIds = listOf("favorites", "progress", "recent").flatMap { key ->
            state.optJSONObject(key)?.keys()?.asSequence()?.toList().orEmpty()
        }.toSet()
        val unprotected = items.keys().asSequence().filterNot { it in protectedIds }
            .sortedBy { items.optJSONObject(it)?.optLong("cachedAt") ?: 0L }.toList()
        unprotected.take(items.length() - MAX_CATALOG_ITEMS).forEach { id ->
            items.remove(id)
            movieSources.remove(id.toLong())
        }
    }

    private fun movie(site: TvBoxSite, payload: JSONObject, state: JSONObject): MovieItem {
        val remoteId = payload.text("vod_id")
        val id = tvBoxId(site.id, "movie", remoteId)
        val progress = savedProgress(site.id, id, state)
        val lines = playLines(site, payload)
        val episode = progress?.let { saved -> lines.firstOrNull { it.id == saved.lineId }
            ?.episodes?.firstOrNull { it.id == saved.episodeId } } ?: lines.firstOrNull { it.episodes.isNotEmpty() }?.episodes?.first()
        val duration = progress?.durationMs?.takeIf { it > 0 }?.div(1_000.0)
        return MovieItem(
            id = id, videoId = episode?.id ?: id, title = payload.text("vod_name"),
            originalTitle = payload.textOrNull("vod_en"), year = payload.text("vod_year").toIntOrNull(),
            overview = Html.fromHtml(payload.text("vod_content"), Html.FROM_HTML_MODE_LEGACY).toString().trim(),
            posterUrl = payload.textOrNull("vod_pic")?.toHttpUrlOrNull()?.toString(), backdropUrl = null,
            rating = payload.text("vod_score").toDoubleOrNull()?.takeIf { it.isFinite() },
            runtimeMinutes = payload.text("vod_duration").trim().toIntOrNull(),
            matchStatus = "tvbox", matchConfidence = null, playUrl = episode?.url.orEmpty(),
            modified = payload.textOrNull("vod_time"), durationSeconds = duration,
            source = site.name, path = remoteId, format = episode?.url?.let(::format), metadataProvider = "tvbox",
            resumePositionMs = progress?.positionMs ?: 0L,
            favorite = state.optJSONObject("favorites")?.has(id.toString()) == true,
            tvBoxRef = TvBoxMovieRef(site.id, remoteId, payload.toString()),
        )
    }

    private fun playLines(site: TvBoxSite, payload: JSONObject): List<TvBoxPlayLine> {
        val urls = payload.text("vod_play_url")
        if (urls.isBlank()) return emptyList()
        val names = payload.text("vod_play_from").split("\u0024\u0024\u0024")
        val groups = urls.split("\u0024\u0024\u0024")
        val movieId = payload.text("vod_id")
        return groups.mapIndexed { lineIndex, group ->
            val name = names.getOrNull(lineIndex)?.trim().takeUnless { it.isNullOrBlank() } ?: "Line ${lineIndex + 1}"
            val lineId = "$lineIndex:$name"
            val episodes = group.split('#').mapIndexedNotNull { index, part ->
                val text = part.trim()
                if (text.isEmpty()) return@mapIndexedNotNull null
                val separator = if (text.startsWith("http://", true) || text.startsWith("https://", true)) -1 else text.indexOf('$')
                val label = if (separator >= 0) text.substring(0, separator).trim() else "Episode ${index + 1}"
                val url = if (separator >= 0) text.substring(separator + 1).trim() else text
                TvBoxEpisode(tvBoxId(site.id, "episode", "$movieId\n$lineId\n$index"),
                    label.ifEmpty { "Episode ${index + 1}" }, url, index)
            }
            TvBoxPlayLine(lineId, name, episodes)
        }
    }

    private fun request(
        site: TvBoxSite,
        params: Map<String, String>,
        forceRefresh: Boolean,
        validate: (JSONObject) -> Unit,
    ): Payload {
        val requestEpoch = requestGeneration.get()
        val generation = store.generation()
        val url = site.api.toHttpUrl().newBuilder().apply {
            listOf("ac", "pg", "t", "wd", "ids").forEach(::removeAllQueryParameters)
            params.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build().toString()
        val cached = store.cached(site.id, url)
        val savedAt = cached?.optLong("savedAt") ?: 0L
        val age = nowMs() - savedAt
        val cachedValue = cached?.optJSONObject("payload")
        val isDetailLookup = params.containsKey("ids")
        if (!forceRefresh && !isDetailLookup && cachedValue != null && age in 0..CACHE_FRESH_MS) {
            validate(cachedValue)
            return Payload(cachedValue, true, generation, url, cached?.optLong("ticket") ?: 0L)
        }
        val ticket = store.beginResponse(site.id, url, generation, cached?.optLong("ticket") ?: 0L)
            ?: throw CancellationException("TVBox request invalidated before it started")
        val text = try {
            download(url, MAX_RESPONSE_CHARS, requestEpoch)
        } catch (error: IOException) {
            val canFallback = error !is TvBoxHttpException || error.statusCode >= 500 || error.statusCode in setOf(408, 429)
            if (!isDetailLookup && canFallback && cachedValue != null && age in 0..CACHE_STALE_MS) {
                validate(cachedValue)
                return Payload(cachedValue, true, generation, url, ticket)
            }
            throw error
        }
        val payload = try {
            JSONObject(text.trim().removePrefix("\uFEFF"))
        } catch (error: Exception) {
            throw TvBoxProtocolException("Source '${site.name}' returned invalid CMS JSON", error)
        }
        if (payload.has("code") && payload.text("code") !in setOf("1", "200")) {
            throw TvBoxProtocolException("Source '${site.name}' rejected the request: ${payload.text("msg")}")
        }
        validate(payload)
        ensureRequestCurrent(requestEpoch)
        if (!isDetailLookup) store.cache(site.id, url, payload, nowMs(), generation, ticket)
        return Payload(payload, false, generation, url, ticket)
    }

    private fun bundledText(): String? = try {
        appContext.assets.open("tvbox.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (_: FileNotFoundException) {
        null
    }

    private fun download(url: String, maxChars: Int, requestEpoch: Long = requestGeneration.get()): String {
        val call = client.newCall(
            Request.Builder().url(url).header("Accept", "application/json,text/plain,*/*")
                .header("User-Agent", "ShortVideo-CMS/1.0").build(),
        )
        pendingCalls += call
        try {
            ensureRequestCurrent(requestEpoch)
            return try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw TvBoxHttpException(response.code, "CMS HTTP ${response.code}")
                    val body = response.body ?: throw TvBoxProtocolException("CMS response has no body")
                    body.charStream().use { reader ->
                        val output = StringBuilder()
                        val buffer = CharArray(8_192)
                        while (true) {
                            val read = reader.read(buffer)
                            if (read == -1) break
                            if (output.length.toLong() + read > maxChars) {
                                throw TvBoxProtocolException("CMS response is too large")
                            }
                            output.append(buffer, 0, read)
                        }
                        output.toString()
                    }
                }
            } catch (error: IOException) {
                if (requestEpoch != requestGeneration.get() || Thread.currentThread().isInterrupted) {
                    throw CancellationException("TVBox request cancelled").also { it.initCause(error) }
                }
                throw error
            }
        } finally {
            pendingCalls.remove(call)
        }
    }

    private fun ensureRequestCurrent(requestEpoch: Long) {
        if (requestEpoch != requestGeneration.get() || Thread.currentThread().isInterrupted) {
            throw CancellationException("TVBox request cancelled")
        }
    }

    private fun requireArray(value: JSONObject, key: String): JSONArray = value.optJSONArray(key)
        ?: throw TvBoxProtocolException("CMS response is missing '$key' array; only standard JSON CMS type 1 is supported")

    private fun count(value: JSONObject, key: String): Int = value.text(key).toIntOrNull()?.takeIf { it >= 0 } ?: -1

    private fun format(url: String): String? = url.substringBefore('?').substringBefore('#')
        .substringAfterLast('.', "").lowercase().takeIf { it in setOf("m3u8", "mp4", "mkv", "webm", "mpd", "ts") }

    companion object {
        private const val CACHE_FRESH_MS = 5 * 60_000L
        private const val CACHE_STALE_MS = 7 * 24 * 60 * 60_000L
        private const val MAX_RESPONSE_CHARS = 16 * 1024 * 1024
        private const val MAX_CATALOG_ITEMS = 2_000
    }
}

private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key).trim()

private fun JSONObject.textOrNull(key: String): String? = text(key).takeIf { it.isNotEmpty() }

private fun JSONObject.objectOrNew(key: String): JSONObject = optJSONObject(key)
    ?: JSONObject().also { put(key, it) }
