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
            if (uri.host.equals("short.deepfuck.you", ignoreCase = true)) {
                playPath.matchEntire(uri.path)?.groupValues?.get(1)?.let { return "media:$it" }
            }
            URI(uri.scheme, uri.authority, uri.path, null, null).toASCIIString()
        }.getOrElse {
            rawUrl.substringBefore('#').substringBefore('?')
        }
    }
}
