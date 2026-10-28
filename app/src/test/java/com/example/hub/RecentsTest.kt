package com.example.hub

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentsTest {
    @Test
    fun newLaunchGoesToFront() {
        assertEquals(listOf("c", "a", "b"), pushRecent(listOf("a", "b"), "c", 12))
    }

    @Test
    fun relaunchMovesToFrontWithoutDuplicate() {
        assertEquals(listOf("b", "a", "c"), pushRecent(listOf("a", "b", "c"), "b", 12))
    }

    @Test
    fun keepsAtMostMax() {
        assertEquals(listOf("d", "a", "b"), pushRecent(listOf("a", "b", "c"), "d", 3))
    }

    private val a = HubApp("A", "a", listOf(Category.MOVIES))
    private val b = HubApp("B", "b", listOf(Category.MOVIES))
    private val c = HubApp("C", "c", listOf(Category.MOVIES))

    @Test
    fun myAppsPutsRecentlyOpenedFirst() {
        assertEquals(listOf(c, a, b), myAppsOrder(listOf(a, b, c), listOf("c", "a")))
    }

    @Test
    fun myAppsIgnoresRecentsNoLongerInstalled() {
        assertEquals(listOf(b, a), myAppsOrder(listOf(a, b), listOf("gone", "b")))
    }

    @Test
    fun myAppsKeepsCatalogOrderWithoutRecents() {
        assertEquals(listOf(a, b, c), myAppsOrder(listOf(a, b, c), emptyList()))
    }
}
