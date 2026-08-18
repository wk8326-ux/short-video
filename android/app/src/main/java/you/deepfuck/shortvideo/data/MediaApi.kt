package you.deepfuck.shortvideo.data

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import you.deepfuck.shortvideo.update.ResumableUpdateDownloader
import you.deepfuck.shortvideo.update.UpdateDownloadHttpException

class ApiException(val statusCode: Int, message: String) : IOException(message)

class MediaApi(private val preferences: PlaybackPreferences) {
    private val baseUrl = BASE_URL.toHttpUrl()
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val original = chain.request()
            val request = if (original.url.host == baseUrl.host) {
                original.newBuilder().apply {
                    preferences.sessionCookie?.let { header("Cookie", it) }
                }.build()
            } else {
                original
            }
            val response = chain.proceed(request)
            if (request.url.host == baseUrl.host) {
                response.headers("Set-Cookie")
                    .firstOrNull { it.startsWith("short_session=") }
                    ?.substringBefore(';')
                    ?.let { cookie ->
                        preferences.sessionCookie = cookie.takeUnless { it == "short_session=" }
                    }
            }
            response
        }
        .build()

    fun hasSession(): Boolean = !preferences.sessionCookie.isNullOrBlank()

    fun authenticationStatus(): Boolean =
        getJson("/api/auth/status").optBoolean("authenticated")

    fun login(password: String) {
        val body = JSONObject().put("password", password).toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        executeJson(
            Request.Builder()
                .url(url("/api/auth/login"))
                .post(body)
                .build(),
        )
    }

    fun logout() {
        runCatching {
            executeJson(
                Request.Builder()
                    .url(url("/api/auth/logout"))
                    .post(ByteArray(0).toRequestBody(null))
                    .build(),
            )
        }
        preferences.clearSession()
    }

    fun feed(
        surface: MediaSurface,
        mode: FeedMode,
        cursor: String?,
        excludedIds: List<Long>,
        startId: Long?,
    ): FeedPage {
        val builder = url("/api/feed").newBuilder()
            .addQueryParameter("limit", "18")
            .addQueryParameter("mode", mode.apiValue)
            .addQueryParameter("category", surface.apiValue)
        cursor?.let { builder.addQueryParameter("cursor", it) }
        if (excludedIds.isNotEmpty()) {
            builder.addQueryParameter("exclude", excludedIds.take(100).joinToString(","))
        }
        startId?.let { builder.addQueryParameter("start", it.toString()) }
        val payload = getJson(builder.build().toString())
        val items = payload.optJSONArray("items")
        return FeedPage(
            items = buildList {
                if (items != null) for (index in 0 until items.length()) {
                    items.optJSONObject(index)?.let { add(MediaEntry.fromJson(it)) }
                }
            },
            nextCursor = payload.optNullableString("nextCursor"),
            total = payload.optInt("total"),
            scanRunning = payload.optJSONObject("scan")?.optBoolean("running") == true,
        )
    }

    fun asmrAuthors(query: String = "", limit: Int? = null, offset: Int = 0): AsmrPage<AsmrAuthor> {
        val target = url("/api/asmr/authors").newBuilder().apply {
            if (query.isNotBlank()) addQueryParameter("q", query)
            if (limit != null) {
                addQueryParameter("limit", limit.toString())
                addQueryParameter("offset", offset.toString())
            }
        }.build()
        val payload = getJson(target.toString())
        val items = payload.optJSONArray("items")
        val parsed = buildList {
            if (items == null) return@buildList
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                add(AsmrAuthor.fromJson(item))
            }
        }
        return AsmrPage(parsed, payload.optInt("total", parsed.size), payload.optIntOrNull("nextOffset"))
    }

    fun asmrItems(
        author: String,
        kind: String = "all",
        query: String = "",
        limit: Int = ASMR_PAGE_SIZE,
        offset: Int = 0,
    ): AsmrPage<MediaEntry> {
        val target = baseUrl.newBuilder()
            .addPathSegments("api/asmr/authors")
            .addPathSegment(author)
            .addPathSegment("items")
            .addQueryParameter("kind", kind)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())
            .apply { if (query.isNotBlank()) addQueryParameter("q", query) }
            .build()
        val payload = getJson(target.toString())
        val items = payload.optJSONArray("items")
        val parsed = buildList {
            if (items == null) return@buildList
            for (index in 0 until items.length()) {
                items.optJSONObject(index)?.let { add(MediaEntry.fromJson(it)) }
            }
        }
        return AsmrPage(parsed, payload.optInt("total", parsed.size), payload.optIntOrNull("nextOffset"))
    }

    fun adminStatus(): AdminStatus {
        val payload = getJson("/api/admin/status")
        val library = payload.getJSONObject("library")
        val scan = payload.getJSONObject("scan")
        val fastStart = payload.getJSONObject("fastStart")
        val summary = fastStart.getJSONObject("summary")
        return AdminStatus(
            totalItems = library.optInt("videos"),
            totalBytes = library.optLong("bytes"),
            guangyaItems = library.optJSONObject("guangya")?.optInt("videos") ?: 0,
            asmrItems = library.optJSONObject("asmr")?.optInt("videos") ?: 0,
            scanRunning = scan.optBoolean("running"),
            scanLastSuccess = scan.optLong("lastSuccess").takeIf { it > 0L },
            scanLastError = scan.optNullableString("lastError"),
            fastStartRunning = fastStart.optBoolean("running"),
            fastStartOptimized = summary.optInt("optimized"),
            fastStartIssues = summary.optInt("notOptimized") + summary.optInt("errors"),
            fastStartPending = summary.optInt("pending") + summary.optInt("inconclusive"),
        )
    }

    fun startScan() = post("/api/admin/scan")

    fun startFastStartCheck() = post("/api/admin/fast-start")

    fun appUpdate(): AppUpdateInfo = AppUpdateInfo.fromJson(getJson("/api/app/update"))

    fun downloadAppUpdate(
        update: AppUpdateInfo,
        target: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) {
        val targetUrl = baseUrl.resolve(update.downloadUrl)
            ?: throw IOException("Update download URL is invalid")
        if (
            targetUrl.scheme != baseUrl.scheme ||
            targetUrl.host != baseUrl.host ||
            targetUrl.port != baseUrl.port ||
            targetUrl.encodedPath != "/api/app/update/apk"
        ) {
            throw IOException("Update download URL is not trusted")
        }

        try {
            ResumableUpdateDownloader(client).download(
                url = targetUrl,
                target = target,
                expectedSize = update.size,
                expectedSha256 = update.sha256,
                onProgress = onProgress,
            )
        } catch (error: UpdateDownloadHttpException) {
            throw ApiException(error.statusCode, "HTTP ${error.statusCode}")
        }
    }

    fun resolvePlayUrl(playPath: String): String {
        val target = if (playPath.startsWith("http")) playPath.toHttpUrl() else url(playPath)
        client.newCall(Request.Builder().url(target).get().build()).execute().use { response ->
            if (response.code == 401) throw ApiException(401, "Authentication required")
            if (response.code !in 300..399) {
                throw ApiException(response.code, "Media resolve returned HTTP ${response.code}")
            }
            val location = response.header("Location")
                ?: throw IOException("Media resolve did not return a location")
            return response.request.url.resolve(location)?.toString()
                ?: throw IOException("Media resolve returned an invalid location")
        }
    }

    fun absoluteUrl(path: String): String =
        if (path.startsWith("http://") || path.startsWith("https://")) path else url(path).toString()

    private fun post(path: String) {
        executeJson(
            Request.Builder()
                .url(url(path))
                .post(ByteArray(0).toRequestBody(null))
                .build(),
        )
    }

    private fun getJson(pathOrUrl: String): JSONObject {
        val target = if (pathOrUrl.startsWith("http")) pathOrUrl.toHttpUrl() else url(pathOrUrl)
        return executeJson(Request.Builder().url(target).get().build())
    }

    private fun executeJson(request: Request): JSONObject = client.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val detail = runCatching { JSONObject(body).optString("detail") }.getOrNull()
            throw ApiException(response.code, detail ?: "HTTP ${response.code}")
        }
        return if (body.isBlank()) JSONObject() else JSONObject(body)
    }

    private fun url(path: String) = baseUrl.resolve(path)
        ?: throw IllegalArgumentException("Invalid API path: $path")

    private companion object {
        const val ASMR_PAGE_SIZE = 24
        const val BASE_URL = "https://short.deepfuck.you/"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

private fun JSONObject.optIntOrNull(name: String): Int? =
    if (isNull(name) || !has(name)) null else optInt(name)
