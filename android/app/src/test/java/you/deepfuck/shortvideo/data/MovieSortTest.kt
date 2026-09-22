package you.deepfuck.shortvideo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MovieSortTest {
    @Test
    fun coverTokenCarriesNoDirection() {
        assertEquals("cover", movieSortParts("cover").first)
        assertEquals("", movieSortParts("cover").second)
        assertEquals("cover", MovieSortValue(MOVIE_SORT_FIELD_COVER, MOVIE_SORT_DESC).token)
    }

    @Test
    fun fieldTokenRoundTripsItsDirection() {
        assertEquals("title" to "asc", movieSortParts("title:asc"))
        assertEquals("time" to "desc", movieSortParts("time:desc"))
        assertEquals("title:desc", MovieSortValue("title", "desc").token)
    }

    @Test
    fun anUnknownFieldFallsBackToCoverAndAnUnknownDirectionIsDropped() {
        assertEquals("cover" to "", movieSortParts("nonsense"))
        // A known field with a direction the server does not understand keeps
        // the field and lets the server apply that field's default arrow,
        // which is exactly what the API does with the same token.
        assertEquals("title" to "", movieSortParts("title:sideways"))
        assertTrue(movieSortIsCover(""))
    }

    @Test
    fun tappingANewOptionPicksItsNaturalDirection() {
        assertEquals("title:asc", movieSortOnSelect("cover", "title"))
        assertEquals("time:desc", movieSortOnSelect("cover", "time"))
        assertEquals("cover", movieSortOnSelect("title:asc", "cover"))
    }

    @Test
    fun tappingTheSelectedOptionFlipsTheArrow() {
        assertEquals("title:desc", movieSortOnSelect("title:asc", "title"))
        assertEquals("title:asc", movieSortOnSelect("title:desc", "title"))
        assertEquals("time:asc", movieSortOnSelect("time:desc", "time"))
        assertEquals("time:desc", movieSortOnSelect("time:asc", "time"))
    }

    @Test
    fun onlyCoverOrderIsReSortedOnTheClient() {
        assertTrue(movieSortIsCover("cover"))
        assertFalse(movieSortIsCover("title:asc"))
        assertFalse(movieSortIsCover("time:desc"))
    }
}
