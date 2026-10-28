package you.deepfuck.shortvideo.data

import java.io.IOException
import org.json.JSONObject

data class TvBoxSite(
    val id: String,
    val key: String,
    val name: String,
    val api: String,
    val searchable: Boolean = true,
    val enabled: Boolean = true,
    val type: Int = 1,
    val spider: String? = null,
    val ext: String = "",
    val origin: String? = null,
)

data class TvBoxConfig(
    val sites: List<TvBoxSite>,
    val origin: String? = null,
    val unsupportedSites: List<String> = emptyList(),
    val origins: List<String> = listOfNotNull(origin),
    val removedSites: List<String> = emptyList(),
)

/** Identity and metadata retained by a displayed movie independently of the evictable catalog cache. */
data class TvBoxMovieRef(
    val sourceId: String,
    val remoteId: String,
    internal val payloadJson: String,
) {
    internal fun toJson(): JSONObject = JSONObject()
        .put("sourceId", sourceId).put("remoteId", remoteId).put("payload", payloadJson)

    companion object {
        internal fun fromJson(value: JSONObject): TvBoxMovieRef = TvBoxMovieRef(
            value.getString("sourceId"), value.getString("remoteId"), value.getString("payload"),
        )
    }
}

/** [id] is the CMS's original category identifier, not its position in the list. */
data class TvBoxCategory(val id: String, val name: String)

data class TvBoxPage(
    val sourceId: String,
    val categoryId: String?,
    val page: Int,
    val items: List<MovieItem>,
    val total: Int,
    val pageCount: Int,
    val nextPage: Int?,
    val fromCache: Boolean = false,
)

data class TvBoxCatalog(
    val source: MovieSource,
    val categories: List<TvBoxCategory>,
    val page: TvBoxPage,
)

data class TvBoxEpisode(val id: Long, val name: String, val url: String, val index: Int)

data class TvBoxPlayLine(val id: String, val name: String, val episodes: List<TvBoxEpisode>)

data class TvBoxDetail(
    val sourceId: String,
    val remoteId: String,
    val movie: MovieItem,
    val playLines: List<TvBoxPlayLine>,
    val fromCache: Boolean = false,
)

data class TvBoxProgress(
    val sourceId: String,
    val movieId: Long,
    val lineId: String,
    val episodeId: Long,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMs: Long,
)

data class TvBoxSettings(
    val selectedSourceId: String? = null,
    val wallView: MovieWallView = MovieWallView.SHELVES,
    val muted: Boolean = false,
    val skin: String = "obsidian-coral",
)

class TvBoxConfigException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

class TvBoxProtocolException(message: String, cause: Throwable? = null) : IOException(message, cause)

class TvBoxHttpException(val statusCode: Int, message: String) : IOException(message)

class TvBoxPlaybackException(message: String) : IllegalArgumentException(message)
