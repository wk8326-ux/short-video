package you.deepfuck.shortvideo.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

/** Android's maintained JavaScript engine supports ES modules and Promise-based CatVod scripts. */
@SuppressLint("SetJavaScriptEnabled")
internal class TvBoxJsSpider(
    private val context: Context,
    private val site: TvBoxSite,
    private val assets: TvBoxSpiderAssets,
    private val proxyUrl: String,
) : TvBoxSpider {
    private val main = Handler(Looper.getMainLooper())
    private val ready = CompletableFuture<Unit>()
    private val results = ConcurrentHashMap<Long, CompletableFuture<String>>()
    private val nextId = AtomicLong()
    private val preferences = context.getSharedPreferences("tvbox-js-${tvBoxDigest(site.id)}", Context.MODE_PRIVATE)
    @Volatile private var closed = false
    private lateinit var view: WebView

    init {
        main.post {
            if (closed) return@post
            try {
                view = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.domStorageEnabled = false
                    settings.blockNetworkLoads = true
                    settings.mediaPlaybackRequiresUserGesture = true
                    addJavascriptInterface(Bridge(), "__host")
                    webViewClient = object : WebViewClient() {
                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                            val error = TvBoxProtocolException("脚本引擎已退出，请重试或切换片源")
                            ready.completeExceptionally(error)
                            results.values.forEach { it.completeExceptionally(error) }
                            closed = true
                            view.destroy()
                            return true
                        }
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                            return try {
                                val url = request.url.toString()
                                val script = if (url.startsWith(ASSET_BASE)) {
                                    val path = url.removePrefix(ASSET_BASE)
                                    if (path.contains("..")) throw TvBoxProtocolException("无效的脚本公共模块路径")
                                    context.assets.open("tvbox-js/$path").bufferedReader().use { it.readText() }
                                } else {
                                    url.toHttpUrlOrNull() ?: throw TvBoxProtocolException("脚本模块不是 HTTP(S) 地址")
                                    assets.text(url)
                                }
                                val source = script.replace("assets://", ASSET_BASE)
                                val module = Regex("""(?:^|[;\n}])\s*(?:import(?:\s|[{'\"])|export(?:\s|[{*]))""").containsMatchIn(source)
                                javascript(if (module) source else "(0,eval)(${JSONObject.quote(source)});")
                            } catch (error: Throwable) {
                                javascript("throw new Error(${JSONObject.quote("模块加载失败：${error.message}")});")
                            }
                        }
                    }
                    val html = """<!doctype html><meta charset="utf-8"><script>$BRIDGE</script>
                        <script type="module">
                        try {
                            const m = await import(${JSONObject.quote(site.api)});
                            globalThis.__spider = m.__jsEvalReturn ? m.__jsEvalReturn() :
                                (typeof m.default === 'function' ? m.default() : (m.default || globalThis.__JS_SPIDER__ || m));
                            const ext = ${jsonExt(if (site.ext.startsWith("./") || site.ext.startsWith("../")) resolve(site.origin ?: site.api, site.ext) else site.ext)};
                            if (__spider.init) await __spider.init(m.__jsEvalReturn ?
                                {stype:3,skey:${JSONObject.quote(site.key)},ext:ext} : ext);
                            __host.ready('');
                        } catch(e) { __host.ready(String(e.stack || e)); }
                        </script>""".trimIndent()
                    loadDataWithBaseURL("https://tvbox-script.invalid/", html, "text/html", "UTF-8", null)
                }
            } catch (error: Throwable) { ready.completeExceptionally(error) }
        }
        try { ready.get(35, TimeUnit.SECONDS) }
        catch (error: Throwable) { close(); throw error }
    }

    private inner class Bridge {
        @JavascriptInterface fun ready(error: String) {
            if (error.isEmpty()) ready.complete(Unit) else ready.completeExceptionally(TvBoxProtocolException(error))
        }
        @JavascriptInterface fun result(id: String, value: String, error: String) {
            val future = id.toLongOrNull()?.let(results::remove) ?: return
            if (error.isEmpty()) future.complete(value) else future.completeExceptionally(TvBoxProtocolException(error))
        }
        @JavascriptInterface fun request(path: String, options: String): String =
            runCatching { requestHttp(path, options) }.getOrElse { JSONObject().put("code", 0).put("error", it.message).put("content", "").toString() }
        @JavascriptInterface fun load(path: String): String = assets.text(resolve(site.api, path))
        @JavascriptInterface fun proxyUrl(): String = "$proxyUrl?"
        @JavascriptInterface fun get(key: String, default: String): String = preferences.getString(key, default).orEmpty()
        @JavascriptInterface fun set(key: String, value: String) { preferences.edit().putString(key, value).commit() }
        @JavascriptInterface fun remove(key: String) { preferences.edit().remove(key).commit() }
        @JavascriptInterface fun base64(text: String): String = Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP)
        @JavascriptInterface fun unbase64(text: String): String = String(Base64.decode(text, Base64.DEFAULT), Charsets.UTF_8)
        @JavascriptInterface fun md5(text: String): String = MessageDigest.getInstance("MD5").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        @JavascriptInterface fun html(source: String, selector: String, mode: String, base: String): String =
            parseHtml(source, selector, mode, base)
    }

    override fun call(method: String, vararg args: Any): String {
        check(!closed) { "脚本已关闭" }
        val names = when (method) {
            "homeContent" -> listOf("home", "homeContent")
            "homeVideoContent" -> listOf("homeVod", "homeVideoContent")
            "categoryContent" -> listOf("category", "categoryContent")
            "detailContent" -> listOf("detail", "detailContent")
            "searchContent" -> listOf("search", "searchContent")
            "playerContent" -> listOf("play", "playerContent")
            else -> listOf(method)
        }
        val arguments = JSONArray().apply { args.forEach { arg -> put(when (arg) {
            is Map<*, *> -> JSONObject(arg)
            is Collection<*> -> JSONArray(arg)
            else -> arg
        }) } }
        val id = nextId.incrementAndGet()
        val future = CompletableFuture<String>()
        results[id] = future
        val script = """
            (async function(){
                try {
                    const names = ${JSONArray(names)};
                    const name = names.find(n=>typeof __spider[n] === 'function');
                    if (!name) {
                        ${if (method == "homeVideoContent") "__host.result('$id','{\"list\":[]}','');return;" else "throw Error('脚本缺少 ${names.first()} 接口');"}
                    }
                    const args = $arguments;
                    if (name === 'detail') args[0] = args[0][0];
                    const result = await __spider[name](...args);
                    __host.result('$id', typeof result === 'string' ? result : JSON.stringify(result || {list:[]}), '');
                } catch(e) { __host.result('$id','',String(e.stack || e)); }
            })();
        """.trimIndent()
        main.post { if (!closed) view.evaluateJavascript(script, null) }
        try { return future.get(35, TimeUnit.SECONDS) }
        finally { results.remove(id) }
    }

    private fun resolve(base: String, path: String): String = path.toHttpUrlOrNull()?.toString()
        ?: base.toHttpUrl().resolve(path)?.toString() ?: throw TvBoxProtocolException("脚本地址无效：$path")

    private fun requestHttp(path: String, options: String): String {
        val opts = JSONObject(options)
        val headers = opts.optJSONObject("headers") ?: JSONObject()
        val builder = Request.Builder().url(resolve(site.api, path)).header("User-Agent", "okhttp/4.12.0")
        headers.keys().forEach { key -> builder.header(key, headers.optString(key)) }
        val method = opts.optString("method", "GET").uppercase()
        if (method !in setOf("GET", "HEAD")) {
            val contentType = headers.optString("Content-Type", "application/x-www-form-urlencoded")
            builder.method(method, (opts.opt("data") ?: opts.opt("body") ?: "").toString().toRequestBody(contentType.toMediaTypeOrNull()))
        } else builder.method(method, null)
        return assets.withResponse(builder.build()) { response ->
            val bytes = response.body?.byteStream()?.use { TvBoxSpiderAssets.readBounded(it, 16 * 1024 * 1024) } ?: byteArrayOf()
            val charset = runCatching { Charset.forName(opts.optString("encoding", "UTF-8")) }.getOrDefault(Charsets.UTF_8)
            JSONObject().put("code", response.code).put("status", response.code)
                .put("headers", JSONObject().apply { response.headers.names().forEach { put(it, response.header(it)) } })
                .put("content", if (opts.optInt("buffer") == 2) Base64.encodeToString(bytes, Base64.NO_WRAP) else String(bytes, charset)).toString()
        }
    }

    private fun parseHtml(source: String, selector: String, mode: String, base: String): String {
        val parts = selector.split("&&")
        val root: org.jsoup.nodes.Element = Jsoup.parse(source, base)
        val elements = parts.dropLast(if (mode == "array") 0 else 1).fold(listOf(root)) { current, css -> current.flatMap { it.select(css) } }
        if (mode == "array") return JSONArray(elements.map { it.outerHtml() }).toString()
        val element = elements.firstOrNull() ?: return ""
        return when (val attr = parts.last()) {
            "Text" -> element.text()
            "Html" -> element.html()
            else -> if (mode == "url") element.absUrl(attr).ifBlank { element.attr(attr) } else element.attr(attr)
        }
    }

    override fun cancel() { results.values.forEach { it.cancel(true) }; results.clear() }
    override fun proxy(params: Map<String, String>): TvBoxLocalProxy.Response {
        val values = JSONArray(call("proxy", params))
        val body = values.optString(2)
        val bytes = if (values.optInt(3, 0) == 1) Base64.decode(body, Base64.DEFAULT) else body.toByteArray()
        return TvBoxLocalProxy.Response(values.getInt(0), values.getString(1), ByteArrayInputStream(bytes), tvBoxHeaders(values.opt(4)))
    }
    override fun close() {
        closed = true
        cancel()
        main.post { if (::view.isInitialized) { view.stopLoading(); view.removeJavascriptInterface("__host"); view.destroy() } }
    }

    companion object {
        private const val ASSET_BASE = "https://tvbox-script.invalid/assets/"
        private fun javascript(script: String) = WebResourceResponse("application/javascript", "UTF-8", 200, "OK",
            mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store"), ByteArrayInputStream(script.toByteArray()))
        private fun jsonExt(text: String): String = when (val parsed = runCatching { org.json.JSONTokener(text).nextValue() }.getOrNull()) {
            is JSONObject, is JSONArray -> parsed.toString()
            else -> JSONObject.quote(text)
        }
        private val BRIDGE = """
            globalThis.global = globalThis;
            globalThis.joinUrl = function(base,path){return new URL(String(path||''),String(base||location.href)).href;};
            globalThis.req = function(url,opts){const r=JSON.parse(__host.request(url,JSON.stringify(opts||{})));if(r.error)throw Error(r.error);return r;};
            globalThis.http = req;
            globalThis.request = function(url,opts){return req(url,opts).content;};
            globalThis.base64Encode = function(s){return __host.base64(String(s));};
            globalThis.base64Decode = function(s){return __host.unbase64(String(s));};
            globalThis.md5 = function(s){return __host.md5(String(s));};
            globalThis.log = console.log.bind(console);
            globalThis.print = log;
            globalThis.getProxyUrl = function(){return __host.proxyUrl();};
            globalThis.getProxy = getProxyUrl;
            globalThis.local = {
                get:function(ns,key,def){return __host.get(ns+'\n'+key,def==null?'':String(def));},
                set:function(ns,key,value){__host.set(ns+'\n'+key,String(value));},
                delete:function(ns,key){__host.remove(ns+'\n'+key);}
            };
            globalThis.pdfh=function(html,selector){return __host.html(html,selector,'value','');};
            globalThis.pdfa=function(html,selector){return JSON.parse(__host.html(html,selector,'array',''));};
            globalThis.pd=function(html,selector,base){return __host.html(html,selector,'url',base||'');};
            globalThis.jsoup={pdfh:pdfh,pdfa:pdfa,pd:pd};
            globalThis.__modules={};
            globalThis.require=function(path){
                if(__modules[path])return __modules[path];
                var module={exports:{}};
                new Function('module','exports','require',__host.load(path))(module,module.exports,require);
                return __modules[path]=module.exports;
            };
        """.trimIndent()
    }
}
