package com.example.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadTest {
    private val mb = 1024L * 1024

    @Test
    fun enoughSpaceIsFine() {
        assertNull(spaceProblem(needed = 100 * mb, free = 100 * mb))
        assertNull(spaceProblem(needed = 100 * mb, free = 500 * mb))
    }

    @Test
    fun notEnoughSpaceSaysHowMuch() {
        assertEquals(uiText(R.string.error_space, 86L, 40L), spaceProblem(needed = 85 * mb + 1, free = 40 * mb + 5))
    }

    @Test
    fun spaceNeededCountsDownloadCopyAndHeadroom() {
        // 30 MB APK with 10 MB still to download: 10 + 30 + 20 headroom.
        assertEquals(60 * mb, spaceNeeded(total = 30 * mb, remaining = 10 * mb))
    }

    @Test
    fun retriesBackOff() {
        assertEquals(listOf(2_000L, 4_000L, 8_000L), (1..3).map(::retryDelayMs))
    }

    @Test
    fun contentRangeStartIsRead() {
        assertEquals(1000L, contentRangeStart("bytes 1000-1999/2000"))
        assertEquals(0L, contentRangeStart("bytes 0-9/*"))
        assertNull(contentRangeStart(null))
        assertNull(contentRangeStart("bytes */2000"))
    }
}
