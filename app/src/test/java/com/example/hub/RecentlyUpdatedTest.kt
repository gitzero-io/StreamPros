package com.example.hub

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentlyUpdatedTest {
    // 2026-10-23T12:00:00Z
    private val now = 1792756800000L

    private fun app(name: String, updatedAt: String?) = HubApp(name, "p.$name", listOf(Category.MOVIES), updatedAt = updatedAt)

    @Test
    fun newestFirstWithinTwoWeeks() {
        val apps = listOf(
            app("old", "2026-10-01T09:00:00Z"),      // 22 days ago: too old
            app("week", "2026-10-16T09:00:00Z"),
            app("today", "2026-10-23T08:00:00Z"),
            app("never", null)                       // added, never updated
        )
        assertEquals(listOf("today", "week"), recentlyUpdated(apps, now).map { it.name })
    }

    @Test
    fun aheadOfTheClockStillCountsAndBadDatesAreSkipped() {
        val apps = listOf(app("ahead", "2026-10-23T15:00:00Z"), app("bad", "yesterday"))
        assertEquals(listOf("ahead"), recentlyUpdated(apps, now).map { it.name })
    }

    @Test
    fun parsesUpdatedAtFromCatalog() {
        val parsed = parseCatalog(
            """{"apps":[{"name":"A","packageName":"p.a","categories":["Movies"],"updatedAt":"2026-10-20T10:00:00Z"},
               {"name":"B","packageName":"p.b","categories":["Movies"]}]}"""
        )
        assertEquals(listOf("2026-10-20T10:00:00Z", null), parsed.map { it.updatedAt })
    }
}
