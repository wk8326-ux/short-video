package you.deepfuck.shortvideo.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.Closeable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Resolves parse:1 pages on the device. The native backend's session is never supplied. */
@SuppressLint("SetJavaScriptEnabled")
internal class TvBoxWebSniffer(private val context: Context) : Closeable {
    private val main = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap.newKeySet<CompletableFuture<JSONObject>>()

    fun resolve(url: String, headers: Map<String, String>): JSONObject {
        val result = CompletableFuture<JSONObject>()
        pending += result
        var browser: WebView? = null
        main.post {
            if (result.isDone) return@post
            try {
                browser = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mediaPlaybackRequiresUserGesture = false
                    headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.let { settings.userAgentString = it.value }
                    webViewClient = object : WebViewClient() {
                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                            result.completeExceptionally(TvBoxPlaybackException("网页解析引擎已退出，请重试"))
                            view.destroy()
                            browser = null
                            return true
                        }
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val address = request.url.toString()
                            if (isMedia(address)) {
                                val captured = headers + request.requestHeaders
                                result.complete(JSONObject().put("parse", 0).put("url", address)
                                    .put("header", JSONObject(captured.filterKeys { !it.equals("Host", true) })))
                            }
                            return null
                        }
                    }
                    loadUrl(url, headers)
                }
            } catch (error: Throwable) { result.completeExceptionally(error) }
        }
        try { return result.get(25, TimeUnit.SECONDS) }
        catch (error: java.util.concurrent.TimeoutException) {
            throw TvBoxPlaybackException("网页在 25 秒内没有提供视频地址，请切换线路或重试")
        } finally {
            result.cancel(true)
            pending -= result
            main.post { browser?.apply { stopLoading(); destroy() }; browser = null }
        }
    }

    override fun close() { pending.toList().forEach { it.cancel(true) } }

    private fun isMedia(url: String): Boolean =
        url.startsWith("http", true) && Regex("\\.(m3u8|mp4|mpd|m4v|webm)(?:[?#]|$)", RegexOption.IGNORE_CASE).containsMatchIn(url)
}
