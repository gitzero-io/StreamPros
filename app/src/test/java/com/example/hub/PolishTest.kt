package com.example.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolishTest {
    private fun app(pkg: String) = HubApp(pkg, pkg, listOf(Category.MOVIES))
    private val catalog = listOf(app("a"), app("b"), app("c"), app("d"))

    @Test
    fun favoritesFollowSavedOrderThenCatalogOrder() {
        val ordered = orderedFavorites(catalog, favorites = setOf("a", "c", "d"), order = listOf("d", "x", "a"))
        assertEquals(listOf("d", "a", "c"), ordered.map { it.packageName })
    }

    @Test
    fun removedFavoritesLeaveTheOrder() {
        assertEquals(listOf("b"), orderedFavorites(catalog, setOf("b"), listOf("a", "b")).map { it.packageName })
    }

    @Test
    fun moveItemStaysInsideTheList() {
        val list = listOf(1, 2, 3)
        assertEquals(listOf(2, 1, 3), moveItem(list, 0, 1))
        assertEquals(listOf(1, 3, 2), moveItem(list, 2, -1))
        assertEquals(list, moveItem(list, 0, -1))
        assertEquals(list, moveItem(list, 2, 1))
        assertEquals(list, moveItem(list, 5, 1))
    }

    @Test
    fun pinsAreFourDigits() {
        assertTrue(isValidPin("0123"))
        assertFalse(isValidPin("123"))
        assertFalse(isValidPin("12a4"))
        assertFalse(isValidPin("12345"))
    }

    @Test
    fun pinHashDependsOnSalt() {
        assertEquals(pinHash("1234", "s1"), pinHash("1234", "s1"))
        assertNotEquals(pinHash("1234", "s1"), pinHash("1234", "s2"))
        assertNotEquals(pinHash("1234", "s1"), pinHash("1235", "s1"))
    }

    @Test
    fun appsAreSortedBySizeAndUnknownSizesDropped() {
        val sizes = mapOf("a" to 10L, "b" to 30L, "c" to null, "d" to 20L)
        assertEquals(listOf("b", "d", "a"), bySize(catalog) { sizes[it.packageName] }.map { it.first.packageName })
    }

    @Test
    fun downloadFilesMapToTheirPackage() {
        assertEquals("com.example.player", downloadFilePackage("com.example.player.apk"))
        assertEquals("com.example.player", downloadFilePackage("com.example.player.part"))
        assertEquals("com.example.player", downloadFilePackage("com.example.player.part.info"))
        assertNull(downloadFilePackage("notes.txt"))
        assertNull(downloadFilePackage(".apk"))
    }
}
