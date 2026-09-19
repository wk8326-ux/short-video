package you.deepfuck.shortvideo.data

import coil.intercept.Interceptor
import coil.request.ImageResult
import coil.size.Dimension
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Asks the poster proxy for the pixel width the widget will actually paint into.
 *
 * The shared catalogue stores 1080px originals that weigh about a megabyte. A
 * wall cell is roughly 170dp, so shipping the original wasted the transfer and
 * the phone's decode. Coil has already resolved the layout size by the time an
 * interceptor runs, so the width is appended once here instead of at every call
 * site, and it stays correct when the grid recomputes its column count.
 *
 * The steps mirror the ladder the proxy snaps onto; sending a raw pixel count
 * would give every device its own cache entry on both ends.
 */
internal class ArtworkWidthInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val target = (request.data as? String)?.toHttpUrlOrNull()
            ?: return chain.proceed(request)
        if (!target.isResizableArtwork() || target.queryParameter("w") != null) {
            return chain.proceed(request)
        }
        val width = (chain.size.width as? Dimension.Pixels)?.px
            ?: return chain.proceed(request)
        val sized = target.newBuilder()
            .setQueryParameter("w", artworkWidthStep(width).toString())
            .build()
        return chain.proceed(request.newBuilder().data(sized.toString()).build())
    }
}

/** The widths the proxy is willing to cache, mirrored from its own ladder. */
private val ARTWORK_WIDTH_STEPS = intArrayOf(240, 360, 480, 720, 1080, 1440)

internal fun artworkWidthStep(width: Int): Int {
    for (step in ARTWORK_WIDTH_STEPS) {
        if (width <= step) return step
    }
    return ARTWORK_WIDTH_STEPS.last()
}

/**
 * Only the artwork the proxy can resize: `/api/movies/{id}/poster`,
 * `/api/movies/{id}/backdrop` and `/api/dramas/{id}/poster`. The AList
 * thumbnail at `/api/videos/{id}/poster` is a plain redirect and has to be left
 * untouched.
 */
private fun HttpUrl.isResizableArtwork(): Boolean {
    val segments = pathSegments
    if (segments.size != 4 || segments[0] != "api") return false
    if (segments[1] != "movies" && segments[1] != "dramas") return false
    return segments[3] == "poster" || segments[3] == "backdrop"
}
