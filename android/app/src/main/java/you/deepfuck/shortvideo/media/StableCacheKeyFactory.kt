@file:androidx.annotation.OptIn(
    markerClass = [androidx.media3.common.util.UnstableApi::class],
)

package you.deepfuck.shortvideo.media

import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheKeyFactory
import java.net.URI

class StableCacheKeyFactory : CacheKeyFactory {
    override fun buildCacheKey(dataSpec: DataSpec): String =
        dataSpec.key ?: stableCacheKey(dataSpec.uri.toString())

    companion object {
        private val playPath = Regex("^/api/videos/(\\d+)/play$")

        fun stableCacheKey(rawUrl: String): String = runCatching {
            val uri = URI(rawUrl)
            if (uri.scheme.equals("https", ignoreCase = true) &&
                uri.host.equals("short.deepfuck.you", ignoreCase = true) &&
                uri.port in setOf(-1, 443) && uri.rawUserInfo == null
            ) {
                playPath.matchEntire(uri.rawPath.orEmpty())?.groupValues?.get(1)?.let { id ->
                    val query = uri.rawQuery?.split('&')
                        ?.filterNot { it.substringBefore('=') == "refresh" }
                        ?.joinToString("&")
                        .orEmpty()
                    return "media:$id" + if (query.isEmpty()) "" else "?$query"
                }
            }
            // External query values can select episodes, variants, keys or segments.
            // Version these keys so bytes stored by the old query-stripping policy are not reused.
            "url:v2:${uri.toASCIIString().substringBefore('#')}"
        }.getOrElse {
            "url:v2:${rawUrl.substringBefore('#')}"
        }
    }
}
