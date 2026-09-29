package you.deepfuck.shortvideo.data

sealed interface MovieBrowseDestination {
    data object Catalog : MovieBrowseDestination
    data object Favorites : MovieBrowseDestination
    data class Library(val sourceId: String) : MovieBrowseDestination
    data class Detail(val movieId: Long) : MovieBrowseDestination
    data class Metadata(val entity: MovieMetadataEntity) : MovieBrowseDestination
}

data class MovieBrowseNavigation(
    val entries: List<MovieBrowseDestination> = listOf(MovieBrowseDestination.Catalog),
) {
    val current: MovieBrowseDestination
        get() = entries.lastOrNull() ?: MovieBrowseDestination.Catalog

    val canGoBack: Boolean get() = entries.size > 1

    fun push(destination: MovieBrowseDestination): MovieBrowseNavigation =
        copy(entries = entries + destination)

    fun back(): MovieBrowseNavigation =
        if (entries.size <= 1) this else copy(entries = entries.dropLast(1))

    fun root(destination: MovieBrowseDestination): MovieBrowseNavigation =
        copy(entries = listOf(destination))
}
