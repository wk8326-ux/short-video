package you.deepfuck.shortvideo.data

import android.content.Context
import dalvik.system.DexClassLoader
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/** The same catalog contract feeds the wall for both CMS and client-side spiders. */
interface TvBoxSpiderTransport : Closeable {
    fun request(site: TvBoxSite, params: Map<String, String>): String
    fun player(site: TvBoxSite, flag: String, id: String): JSONObject
    fun cancel()
    fun clearCache() { cancel() }
}

internal interface TvBoxSpider : Closeable {
    fun call(method: String, vararg args: Any): String
    fun cancel() { }
    fun proxy(params: Map<String, String>): TvBoxLocalProxy.Response = throw TvBoxProtocolException("此爬虫未实现本地代理")
}

internal class TvBoxSpiderRuntime(context: Context) : TvBoxSpiderTransport {
    private val context = context.applicationContext
    private val assets = TvBoxSpiderAssets(this.context)
    private val sessions = ConcurrentHashMap<String, Session>()
    private val sessionLock = Any()
    private val proxyServer = lazy { TvBoxLocalProxy() }
    private val sniffer = TvBoxWebSniffer(this.context)
    @Volatile private var closed = false

    private inner class Session(val site: TvBoxSite) : Closeable {
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "TVBox-${site.key}").apply { isDaemon = true } }
        @Volatile var spider: TvBoxSpider? = null
        val pending = ConcurrentHashMap.newKeySet<Future<*>>()
        @Volatile var closed = false
        val proxyUrl = proxyServer.value.register(site.id) { params -> invoke { it.proxy(params) } }

        private fun <T> invoke(action: (TvBoxSpider) -> T): T {
            check(!closed) { "爬虫已关闭" }
            val future = executor.submit<T> {
                check(!closed) { "爬虫已关闭" }
                val implementation = spider ?: (if (site.api.startsWith("csp_")) jarSpider(site)
                    else TvBoxJsSpider(context, site, assets, proxyUrl)).also {
                    if (closed) { it.close(); throw java.util.concurrent.CancellationException() }
                    spider = it
                }
                action(implementation)
            }
            pending += future
            try {
                return future.get(40, TimeUnit.SECONDS)
            } catch (error: TimeoutException) {
                future.cancel(true)
                synchronized(sessionLock) { sessions.remove(site.id, this); close() }
                throw TvBoxProtocolException("片源「${site.name}」爬虫执行超时，请重试或切换片源", error)
            } catch (error: InterruptedException) {
                future.cancel(true)
                Thread.currentThread().interrupt()
                throw java.util.concurrent.CancellationException("TVBox spider request cancelled")
            } catch (error: ExecutionException) {
                synchronized(sessionLock) { sessions.remove(site.id, this); close() }
                var cause: Throwable = error.cause ?: error
                while (cause is InvocationTargetException || cause is java.util.concurrent.ExecutionException) cause = cause.cause ?: break
                if (cause is java.util.concurrent.CancellationException) throw cause
                throw TvBoxProtocolException("片源「${site.name}」爬虫执行失败：${cause.message ?: cause.javaClass.simpleName}", cause)
            } finally {
                pending -= future
            }
        }

        fun call(method: String, vararg args: Any): String = invoke { it.call(method, *args) }

        fun cancel() {
            pending.forEach { it.cancel(true) }
            spider?.cancel()
        }

        override fun close() {
            if (closed) return
            closed = true
            proxyServer.value.remove(site.id)
            cancel()
            executor.submit { spider?.close() }
            executor.shutdown()
        }
    }

    private fun session(site: TvBoxSite): Session = synchronized(sessionLock) {
        check(!closed) { "爬虫运行时已关闭" }
        sessions[site.id]?.let {
            if (it.site == site) return@synchronized it
            sessions.remove(site.id)?.close()
        }
        // Keep a bounded number of script engines, including during rapid source switching.
        if (sessions.size >= 3) sessions.keys.firstOrNull()?.let { sessions.remove(it)?.close() }
        Session(site).also { sessions[site.id] = it }
    }

    override fun request(site: TvBoxSite, params: Map<String, String>): String {
        val spider = session(site)
        return when {
            params.containsKey("ids") -> spider.call("detailContent", listOf(params.getValue("ids")))
            params.containsKey("wd") -> spider.call("searchContent", params.getValue("wd"), false, params["pg"] ?: "1")
            params.containsKey("t") -> spider.call("categoryContent", params.getValue("t"), params["pg"] ?: "1", false, hashMapOf<String, String>())
            else -> {
                val home = JSONObject(spider.call("homeContent", false))
                if (home.optJSONArray("list") == null || home.optJSONArray("list")!!.length() == 0) {
                    val recommendations = spider.call("homeVideoContent")
                    if (recommendations.isNotBlank()) home.put("list", JSONObject(recommendations).optJSONArray("list") ?: JSONArray())
                }
                if (!home.has("class")) home.put("class", JSONArray())
                if (!home.has("list")) home.put("list", JSONArray())
                home.toString()
            }
        }
    }

    override fun player(site: TvBoxSite, flag: String, id: String): JSONObject {
        val session = session(site)
        val result = JSONObject(session.call("playerContent", flag, id, emptyList<String>()))
        val raw = result.optString("url")
        if (raw.isBlank()) throw TvBoxPlaybackException("爬虫没有返回播放地址")
        var url = result.optString("playUrl").takeUnless { it == "null" }.orEmpty() + raw
        val parsed = url.toHttpUrlOrNull()
        if (parsed != null && parsed.host in setOf("127.0.0.1", "localhost") && parsed.encodedPath == "/proxy") {
            url = session.proxyUrl + (parsed.encodedQuery?.let { "?$it" } ?: "")
        }
        result.put("url", url)
        if (result.optInt("parse", 0) != 0) return sniffer.resolve(url, tvBoxHeaders(result.opt("header")))
        if (url.toHttpUrlOrNull()?.encodedPath?.substringAfterLast('.') !in setOf("m3u8", "mpd", "mp4", "mkv", "webm", "ts")) {
            // Proxy URLs often have no extension; Media3 needs their actual content type to choose HLS.
            runCatching {
                val request = Request.Builder().url(url).head().apply {
                    tvBoxHeaders(result.opt("header")).forEach { (name, value) -> header(name, value) }
                }.build()
                assets.withResponse(request) { response ->
                    response.header("Content-Type")?.substringBefore(';')?.let { result.put("format", it) }
                }
            }
        }
        return result
    }

    private fun jarSpider(site: TvBoxSite): TvBoxSpider {
        val jar = assets.jar(requireNotNull(site.spider))
        val optimized = File(context.codeCacheDir, "tvbox-dex").apply { mkdirs() }
        val loader = DexClassLoader(jar.absolutePath, optimized.absolutePath, assets.nativeLibraries(jar), context.classLoader)
        try {
            val init = loader.loadClass("com.github.catvod.spider.Init")
            init.getMethod("init", Context::class.java).invoke(null, context)
        } catch (_: ClassNotFoundException) { /* Optional CatVod initializer. */ }
        val name = "com.github.catvod.spider.${site.api.removePrefix("csp_")}"
        val instance = loader.loadClass(name).getDeclaredConstructor().newInstance()
        val type = instance.javaClass
        type.getMethod("init", Context::class.java, String::class.java).invoke(instance, context, site.ext)
        return object : TvBoxSpider {
            override fun call(method: String, vararg args: Any): String {
                val candidates = type.methods.filter { it.name == method && it.parameterCount == args.size }
                val candidate = candidates.firstOrNull() ?: if (method == "searchContent")
                    type.methods.first { it.name == method && it.parameterCount == 2 } else
                    throw TvBoxProtocolException("爬虫缺少 $method")
                try {
                    return candidate.invoke(instance, *args.take(candidate.parameterCount).toTypedArray())?.toString().orEmpty()
                } catch (error: InvocationTargetException) {
                    throw error.targetException
                }
            }
            override fun cancel() { runCatching { type.getMethod("cancelByTag").invoke(instance) } }
            override fun close() { runCatching { type.getMethod("destroy").invoke(instance) } }
            override fun proxy(params: Map<String, String>): TvBoxLocalProxy.Response {
                val values = type.getMethod("proxyLocal", Map::class.java).invoke(instance, params) as? Array<*>
                    ?: throw TvBoxProtocolException("JAR 未返回代理响应")
                val body = values.getOrNull(2)
                return TvBoxLocalProxy.Response((values[0] as Number).toInt(), values[1].toString(),
                    when (body) { is InputStream -> body; is ByteArray -> ByteArrayInputStream(body)
                        else -> ByteArrayInputStream(body?.toString().orEmpty().toByteArray()) },
                    (values.getOrNull(3) as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value.toString() }.orEmpty())
            }
        }
    }

    override fun cancel() {
        assets.cancel()
        sniffer.close()
        sessions.values.forEach { it.cancel() }
    }

    override fun clearCache() = synchronized(sessionLock) {
        cancel()
        sessions.values.forEach { it.close() }
        sessions.clear()
        assets.clearCache()
    }

    override fun close() = synchronized(sessionLock) {
        closed = true
        assets.cancel()
        sniffer.close()
        sessions.values.forEach { it.close() }
        sessions.clear()
        if (proxyServer.isInitialized()) proxyServer.value.close()
    }
}

/** Downloads code without first-party cookies; executable JARs are digest-checked and read-only. */
internal class TvBoxSpiderAssets(context: Context) {
    private val root = File(context.filesDir, "tvbox-runtime").apply { mkdirs() }
    val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).build()
    private val pending = ConcurrentHashMap.newKeySet<Call>()

    fun cancel() { pending.toList().forEach(Call::cancel) }

    fun clearCache() { cancel(); root.listFiles()?.forEach { it.deleteRecursively() } }

    fun bytes(url: String, limit: Int = 8 * 1024 * 1024): ByteArray {
        return withResponse(Request.Builder().url(url).header("User-Agent", "okhttp/4.12.0").build()) { response ->
            if (!response.isSuccessful) throw TvBoxHttpException(response.code, "HTTP ${response.code}")
            val body = response.body ?: throw TvBoxProtocolException("爬虫资源为空")
            body.byteStream().use { readBounded(it, limit) }
        }
    }

    fun <T> withResponse(request: Request, block: (Response) -> T): T {
        val call = client.newCall(request)
        pending += call
        try {
            return call.execute().use(block)
        } finally { pending -= call }
    }

    fun text(url: String): String = bytes(url).toString(Charsets.UTF_8).removePrefix("\uFEFF")

    fun nativeLibraries(jar: File): String {
        val directory = File(root, "${jar.nameWithoutExtension}-native").apply { mkdirs() }
        java.util.zip.ZipFile(jar).use { zip ->
            val entries = zip.entries().asSequence().toList()
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull { candidate -> entries.any { it.name.startsWith("lib/$candidate/") } }
            val armGuard = entries.any { it.name.endsWith("guard_v8.so") || it.name.endsWith("guard_v7.so") }
            if (abi == null && armGuard && android.os.Build.SUPPORTED_ABIS.none { it.startsWith("arm") })
                throw TvBoxProtocolException("此加密 JAR 只提供 ARM 原生库，需要在 ARM 安卓设备上运行")
            if (abi != null) entries.filter { !it.isDirectory && it.name.startsWith("lib/$abi/") && it.name.endsWith(".so") }.forEach { entry ->
                val target = File(directory, entry.name.substringAfterLast('/'))
                if (!target.exists()) {
                    val bytes = zip.getInputStream(entry).use { readBounded(it, 32 * 1024 * 1024) }
                    FileOutputStream(target).use { stream -> check(target.setReadOnly()); stream.write(bytes) }
                }
            }
        }
        return directory.absolutePath
    }

    @Synchronized fun jar(spec: String): File {
        val parts = spec.split(";md5;", limit = 2)
        val url = parts[0]
        val expected = parts.getOrNull(1)?.let { value ->
            if (value.toHttpUrlOrNull() != null) text(value).trim() else value
        }?.lowercase()
        if (expected != null && !expected.matches(Regex("[0-9a-f]{32}"))) throw TvBoxConfigException("JAR MD5 无效")
        val target = File(root, "${tvBoxDigest(spec)}.jar")
        if (target.isFile && (expected == null || digest(target.readBytes(), "MD5") == expected)) return target
        val data = bytes(url, 32 * 1024 * 1024)
        if (expected != null && digest(data, "MD5") != expected) throw TvBoxProtocolException("爬虫 JAR 的 MD5 校验失败")
        if (data.size < 4 || data[0] != 'P'.code.toByte() || data[1] != 'K'.code.toByte())
            throw TvBoxProtocolException("爬虫地址没有返回 dex JAR 文件")
        val temporary = File.createTempFile("spider-", ".jar", root)
        try {
            FileOutputStream(temporary).use { stream ->
                check(temporary.setReadOnly()) { "Cannot mark TVBox JAR read-only" }
                stream.write(data)
                stream.fd.sync()
            }
            java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
        return target
    }

    private fun digest(bytes: ByteArray, algorithm: String): String = MessageDigest.getInstance(algorithm)
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    companion object {
        internal fun readBounded(stream: java.io.InputStream, limit: Int): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (output.size() + read > limit) throw TvBoxProtocolException("爬虫资源超过大小限制")
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }
}

internal fun tvBoxHeaders(raw: Any?): Map<String, String> {
    val headers = when (raw) { is JSONObject -> raw; is String -> runCatching { JSONObject(raw) }.getOrNull(); else -> null }
    return headers?.keys()?.asSequence()?.associateWith { headers.optString(it) }.orEmpty()
}
