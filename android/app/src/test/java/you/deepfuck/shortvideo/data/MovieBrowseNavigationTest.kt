package you.deepfuck.shortvideo.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MovieBrowseNavigationTest {
    @Test
    fun detailMetadataDetailBackTrackKeepsItsOrigin() {
        val actor = MovieMetadataEntity(7, "performer", "演员甲", 12)
        val navigation = MovieBrowseNavigation()
            .push(MovieBrowseDestination.Detail(101))
            .push(MovieBrowseDestination.Metadata(actor))
            .push(MovieBrowseDestination.Detail(202))

        val actorPage = navigation.back()
        assertEquals(MovieBrowseDestination.Metadata(actor), actorPage.current)

        val originalDetail = actorPage.back()
        assertEquals(MovieBrowseDestination.Detail(101), originalDetail.current)

        assertEquals(MovieBrowseDestination.Catalog, originalDetail.back().current)
    }
}
