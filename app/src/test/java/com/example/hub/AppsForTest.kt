package com.example.hub

import org.junit.Assert.assertEquals
import org.junit.Test

class AppsForTest {
    private val movie = HubApp("Movie", "a", listOf(Category.MOVIES))
    private val sports = HubApp("Sports", "b", listOf(Category.SPORTS))
    private val both = HubApp("Both", "c", listOf(Category.MOVIES, Category.SHOWS))
    private val apps = listOf(movie, sports, both)

    @Test
    fun keepsOnlyAppsInPickedCategories() {
        assertEquals(listOf(sports), appsFor(setOf(Category.SPORTS), apps))
    }

    @Test
    fun listsMultiCategoryAppOnce() {
        assertEquals(listOf(movie, both), appsFor(setOf(Category.MOVIES, Category.SHOWS), apps))
    }

    @Test
    fun noPicksMeansNoApps() {
        assertEquals(emptyList<HubApp>(), appsFor(emptySet(), apps))
    }
}
