package you.deepfuck.shortvideo.data

/**
 * The order a movie library is browsed in.
 *
 * It travels as one string so it can be stored in a preference and handed to
 * the wall as-is. "封面优先" carries no direction because artwork-first has no
 * meaningful reverse; the other two keep whichever arrow the user last tapped,
 * which is why tapping the selected option again has to be able to flip it.
 */
internal const val MOVIE_SORT_DEFAULT = "cover"

internal const val MOVIE_SORT_FIELD_COVER = "cover"
internal const val MOVIE_SORT_FIELD_TITLE = "title"
internal const val MOVIE_SORT_FIELD_TIME = "time"

internal const val MOVIE_SORT_ASC = "asc"
internal const val MOVIE_SORT_DESC = "desc"

/** The option list, in the order the dropdown shows it. */
internal val MOVIE_SORT_FIELDS = listOf(
    MOVIE_SORT_FIELD_COVER,
    MOVIE_SORT_FIELD_TITLE,
    MOVIE_SORT_FIELD_TIME,
)

internal data class MovieSortValue(val field: String, val direction: String) {
    /** The single string the API and the preference store both use. */
    val token: String = if (field == MOVIE_SORT_FIELD_COVER) {
        field
    } else {
        field + ":" + direction
    }
}

/** Splits a stored token into the field and direction the API expects. */
internal fun movieSortParts(token: String): Pair<String, String> {
    val field = token.substringBefore(':')
    if (field !in MOVIE_SORT_FIELDS) return MOVIE_SORT_FIELD_COVER to ""
    if (field == MOVIE_SORT_FIELD_COVER) return field to ""
    val direction = token.substringAfter(':', missingDelimiterValue = "")
    return field to direction.takeIf { it == MOVIE_SORT_ASC || it == MOVIE_SORT_DESC }.orEmpty()
}

/** The query parameters a sort token turns into. */
internal fun movieSortQuery(token: String): Pair<String, String> = movieSortParts(token)

/**
 * The order for a freshly opened library: newest first, the way a library page
 * has always defaulted.
 */
internal fun movieSortDefaultFor(field: String): MovieSortValue = when (field) {
    MOVIE_SORT_FIELD_TITLE -> MovieSortValue(field, MOVIE_SORT_ASC)
    MOVIE_SORT_FIELD_TIME -> MovieSortValue(field, MOVIE_SORT_DESC)
    else -> MovieSortValue(MOVIE_SORT_FIELD_COVER, "")
}

/**
 * What tapping an option in the dropdown means.
 *
 * Choosing a different option selects it in its natural direction; tapping the
 * option that is already selected flips the arrow instead, which is the only
 * way to reach the reverse of a list without a second control.
 */
internal fun movieSortOnSelect(current: String, tapped: String): String {
    if (tapped !in MOVIE_SORT_FIELDS) return MOVIE_SORT_DEFAULT
    val (field, direction) = movieSortParts(current)
    if (tapped == MOVIE_SORT_FIELD_COVER) return MOVIE_SORT_FIELD_COVER
    if (field != tapped) return movieSortDefaultFor(tapped).token
    val flipped = if (direction == MOVIE_SORT_ASC) MOVIE_SORT_DESC else MOVIE_SORT_ASC
    return MovieSortValue(tapped, flipped).token
}

/** Whether this token is the artwork-first order, which the client also re-sorts. */
internal fun movieSortIsCover(token: String): Boolean =
    movieSortParts(token).first == MOVIE_SORT_FIELD_COVER
