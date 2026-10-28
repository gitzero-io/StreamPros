package com.example.hub

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchTest {
    private val clipdeck = HubApp("Clipdeck", "a", listOf(Category.MOVIES, Category.SHOWS))
    private val hd = HubApp("HD Streamio", "b", listOf(Category.LIVE_TV), notes = "confirm host is official")
    private val sportsBox = HubApp("Box47 Sports", "c", listOf(Category.SPORTS))
    private val liveSports = HubApp("Arena", "d", listOf(Category.SPORTS, Category.LIVE_TV), notes = "Movies too")
    private val apps = listOf(clipdeck, hd, sportsBox, liveSports)

    private fun names(query: String) = searchApps(query, apps).map { it.name }

    @Test
    fun matchesNameIgnoringCase() {
        assertEquals(listOf("Clipdeck"), names("CLIP"))
    }

    @Test
    fun nameMatchesComeBeforeCategoryMatches() {
        // "Box47 Sports" matches by name; "Arena" only by its Sports category.
        assertEquals(listOf("Box47 Sports", "Arena"), names("sports"))
    }

    @Test
    fun matchesCategoryLabel() {
        assertEquals(listOf("HD Streamio", "Arena"), names("live tv"))
    }

    @Test
    fun notesMatchComesLast() {
        // Clipdeck is a Movies app (category); Arena only mentions movies in its notes.
        assertEquals(listOf("Clipdeck", "Arena"), names("movies"))
        assertEquals(listOf("HD Streamio"), names("official"))
    }

    @Test
    fun ignoresPunctuationAndSpacing() {
        assertEquals(listOf("HD Streamio"), names("hd-streamio!"))
        assertEquals(listOf("HD Streamio"), names("HDStreamio"))
        assertEquals(listOf("Box47 Sports"), names("box-47"))
        assertEquals(listOf("HD Streamio", "Arena"), names("Live-TV"))
    }

    @Test
    fun emptyOrPunctuationOnlyQueryMatchesNothing() {
        assertEquals(emptyList<String>(), names(""))
        assertEquals(emptyList<String>(), names("   "))
        assertEquals(emptyList<String>(), names("?!-"))
    }

    @Test
    fun noMatchGivesEmptyList() {
        assertEquals(emptyList<String>(), names("zzz"))
    }

    @Test
    fun matchesTranslatedCategoryNames() {
        val spanish = mapOf(Category.SPORTS to "Deportes", Category.MOVIES to "Películas")
        val found = searchApps("deportes", apps) { listOfNotNull(it.label, spanish[it]) }.map { it.name }
        assertEquals(listOf("Box47 Sports", "Arena"), found)
        // The English name still works with the Hub in another language.
        assertEquals(listOf("Box47 Sports", "Arena"), searchApps("sports", apps) { listOfNotNull(it.label, spanish[it]) }.map { it.name })
    }
}
