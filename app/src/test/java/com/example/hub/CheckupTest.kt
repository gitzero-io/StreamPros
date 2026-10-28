package com.example.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckupTest {
    private fun app(name: String, signer: String? = "aa") =
        HubApp(name, "p.$name", listOf(Category.MOVIES), signerSha256 = signer)

    @Test
    fun clockWithinFiveMinutesIsFine() {
        val now = 1_800_000_000_000L
        assertFalse(clockWrong(now + 4 * 60_000, now))
        assertTrue(clockWrong(now + 6 * 60_000, now))
        assertTrue(clockWrong(now - 365L * 24 * 3_600_000, now))
        assertFalse(clockWrong(null, now))
    }

    @Test
    fun memoryIsLowBelowFifteenPercentOrWhenSystemSaysSo() {
        assertTrue(memoryLow(100, 1_000, false))
        assertFalse(memoryLow(200, 1_000, false))
        assertTrue(memoryLow(900, 1_000, true))
        assertFalse(memoryLow(0, 0, false))
    }

    @Test
    fun otherSourceAppsAreInstalledAppsWithADifferentSigner() {
        val apps = listOf(app("Same"), app("Other"), app("Missing"), app("Unreadable"), app("NoSigner", signer = null))
        val signers = mapOf("p.Same" to listOf("AA"), "p.Other" to listOf("bb"), "p.Unreadable" to emptyList(), "p.NoSigner" to listOf("cc"))
        assertEquals(listOf("Other"), otherSourceApps(apps) { signers[it] }.map { it.name })
    }

    @Test
    fun shortNamesListsThreeThenEllipsis() {
        assertEquals("A, B", shortNames(listOf(app("A"), app("B"))))
        assertEquals("A, B, C…", shortNames(listOf(app("A"), app("B"), app("C"), app("D"))))
    }
}
