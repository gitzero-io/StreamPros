package com.example.hub

import org.junit.Assert.assertEquals
import org.junit.Test

class TileTagTest {
    @Test
    fun freshInstallShowsNewEvenIfTrending() {
        assertEquals(TileTag.NEW, tileTag("a", installed = true, fresh = setOf("a"), trending = setOf("a")))
    }

    @Test
    fun freshButUninstalledFallsBackToTrending() {
        // Uninstalled outside the Hub: the stale "fresh" entry must not show NEW.
        assertEquals(TileTag.TRENDING, tileTag("a", installed = false, fresh = setOf("a"), trending = setOf("a")))
        assertEquals(null, tileTag("a", installed = false, fresh = setOf("a"), trending = emptySet()))
    }

    @Test
    fun trendingOnlyAndNothing() {
        assertEquals(TileTag.TRENDING, tileTag("a", installed = false, fresh = emptySet(), trending = setOf("a")))
        assertEquals(null, tileTag("a", installed = true, fresh = emptySet(), trending = emptySet()))
    }
}
