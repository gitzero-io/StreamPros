package com.example.hub

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchTest {
    private val clipbox = HubApp("Clipbox", "a", listOf(Category.MOVIES, Category.SHOWS))
    private val hd = HubApp("HD Streamz", "b", listOf(Category.LIVE_TV), notes = "confirm host is official")
    private val sportsBox = HubApp("AK47 Sports", "c", listOf(Category.SPORTS))
    private val liveSports = HubApp("Arena", "d", listOf(Category.SPORTS, Category.LIVE_TV), notes = "Movies too")
    private val apps = listOf(clipbox, hd, sportsBox, liveSports)

    private fun names(query: String) = searchApps(query, apps).map { it.name }

    @Test
    fun matchesNameIgnoringCase() {
        assertEquals(listOf("Clipbox"), names("CLIP"))
    }

    @Test
    fun nameMatchesComeBeforeCategoryMatches() {
        // "AK47 Sports" matches by name; "Arena" only by its Sports category.
        assertEquals(listOf("AK47 Sports", "Arena"), names("sports"))
    }

    @Test
    fun matchesCategoryLabel() {
        assertEquals(listOf("HD Streamz", "Arena"), names("live tv"))
    }

    @Test
    fun notesMatchComesLast() {
        // Clipbox is a Movies app (category); Arena only mentions movies in its notes.
        assertEquals(listOf("Clipbox", "Arena"), names("movies"))
        assertEquals(listOf("HD Streamz"), names("official"))
    }

    @Test
    fun ignoresPunctuationAndSpacing() {
        assertEquals(listOf("HD Streamz"), names("hd-streamz!"))
        assertEquals(listOf("HD Streamz"), names("HDStreamz"))
        assertEquals(listOf("AK47 Sports"), names("ak-47"))
        assertEquals(listOf("HD Streamz", "Arena"), names("Live-TV"))
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
        assertEquals(listOf("AK47 Sports", "Arena"), found)
        // The English name still works with the Hub in another language.
        assertEquals(listOf("AK47 Sports", "Arena"), searchApps("sports", apps) { listOfNotNull(it.label, spanish[it]) }.map { it.name })
    }
}
