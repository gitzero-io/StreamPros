package com.example.hub

import org.junit.Assert.assertEquals
import org.junit.Test

class TrendingTest {
    private fun app(pkg: String) = HubApp(pkg, pkg, listOf(Category.MOVIES))
    private val catalog = listOf(app("a"), app("b"), app("c"))

    @Test
    fun parsesUpdatedAndEntries() {
        val t = parseTrending(
            """{"updated":"2026-10-19","items":[{"rank":2,"packageName":"b"},{"rank":0,"packageName":"x"},
               {"rank":1},{"rank":1,"packageName":"a"}]}"""
        )
        assertEquals("2026-10-19", t.updated)
        assertEquals(listOf(TrendingEntry(2, "b"), TrendingEntry(1, "a")), t.entries)
    }

    @Test
    fun lookupSortsByRankAndSkipsUnknownPackages() {
        val t = TrendingList(null, listOf(TrendingEntry(3, "c"), TrendingEntry(1, "missing"), TrendingEntry(2, "a")))
        assertEquals(listOf(2 to "a", 3 to "c"), trendingApps(t, catalog).map { it.rank to it.app.packageName })
    }

    @Test
    fun lookupDropsRanksAboveTenAndDuplicates() {
        val t = TrendingList(
            null,
            listOf(TrendingEntry(11, "b"), TrendingEntry(4, "a"), TrendingEntry(1, "a"), TrendingEntry(10, "c"))
        )
        assertEquals(listOf(1 to "a", 10 to "c"), trendingApps(t, catalog).map { it.rank to it.app.packageName })
    }

    @Test
    fun displayNumbersCloseGaps() {
        val ranked = listOf(RankedApp(1, app("a")), RankedApp(3, app("b")), RankedApp(7, app("c")))
        assertEquals(listOf(1, 2, 3), ranked.numbered().map { it.rank })
    }
}
