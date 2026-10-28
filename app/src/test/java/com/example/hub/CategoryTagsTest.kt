package com.example.hub

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryTagsTest {
    @Test
    fun oneCategoryShowsOneChip() {
        assertEquals(TagSet(listOf(Category.SPORTS), 0), tagSet(listOf(Category.SPORTS)))
    }

    @Test
    fun twoCategoriesShowBothInCatalogOrder() {
        assertEquals(
            TagSet(listOf(Category.SHOWS, Category.MOVIES), 0),
            tagSet(listOf(Category.SHOWS, Category.MOVIES))
        )
    }

    @Test
    fun threeCategoriesShowTwoPlusOne() {
        assertEquals(
            TagSet(listOf(Category.LIVE_TV, Category.MOVIES), 1),
            tagSet(listOf(Category.LIVE_TV, Category.MOVIES, Category.SHOWS))
        )
    }

    @Test
    fun allCategoriesShowTwoPlusThree() {
        assertEquals(TagSet(listOf(Category.ANIME, Category.SPORTS), 3), tagSet(listOf(Category.ANIME, Category.SPORTS, Category.MOVIES, Category.SHOWS, Category.LIVE_TV)))
    }

    @Test
    fun duplicatesCountOnce() {
        assertEquals(TagSet(listOf(Category.MOVIES), 0), tagSet(listOf(Category.MOVIES, Category.MOVIES)))
    }

    @Test
    fun detailPageLimitShowsEverything() {
        val all = listOf(Category.MOVIES, Category.SHOWS, Category.LIVE_TV)
        assertEquals(TagSet(all, 0), tagSet(all, Int.MAX_VALUE))
    }

    // tagsThatFit: chips 80 wide, 8 gap, "+N" chip 30 wide.
    private fun fit(count: Int, extra: Int, maxWidth: Int) =
        tagsThatFit(List(count) { 80 }, extra, 8, maxWidth) { 30 }

    @Test
    fun everythingFitsOnAWideTile() {
        assertEquals(2, fit(2, 1, 206)) // 80 + 8 + 80 + 8 + 30
    }

    @Test
    fun narrowTileFoldsTheSecondChipIntoPlusN() {
        assertEquals(1, fit(2, 1, 205))
        assertEquals(1, fit(2, 0, 150)) // one chip plus "+1"
    }

    @Test
    fun primaryChipIsAlwaysKept() {
        assertEquals(1, fit(2, 1, 10))
        assertEquals(0, fit(0, 0, 100))
    }

    @Test
    fun matureAppGetsEighteenPlusLabel() {
        assertEquals("18+", matureLabel(HubApp("A", "a", listOf(Category.MOVIES), mature = true)))
    }

    @Test
    fun appsAreNotMatureByDefault() {
        assertEquals(null, matureLabel(HubApp("A", "a", listOf(Category.MOVIES))))
    }

    @Test
    fun catalogMatureFlagParsesWithFalseDefault() {
        val apps = parseCatalog(
            """{"apps":[
               {"name":"A","packageName":"a.a","categories":["Movies"],"mature":true},
               {"name":"B","packageName":"b.b","categories":["Movies"]},
               {"name":"C","packageName":"c.c","categories":["Movies"],"mature":false}]}"""
        )
        assertEquals(listOf("18+", null, null), apps.map { matureLabel(it) })
    }

    @Test
    fun matureLabelRoomComesOutOfTheChips() {
        // Two 80-wide chips plus "+1" need 206; reserving 40 for the label leaves room for one chip.
        assertEquals(2, tagsThatFit(List(2) { 80 }, 1, 8, 206) { 30 })
        assertEquals(1, tagsThatFit(List(2) { 80 }, 1, 8, 206 - 40) { 30 })
    }
}
