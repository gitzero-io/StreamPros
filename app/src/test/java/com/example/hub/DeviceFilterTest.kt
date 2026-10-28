package com.example.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceFilterTest {
    private val arm = listOf("arm64-v8a", "armeabi-v7a", "armeabi")
    private val x86 = listOf("x86_64", "x86")
    private fun app(minSdk: Int? = null, abis: List<String> = emptyList(), tvStatus: String = "untested") =
        HubApp("A", "p.a", listOf(Category.MOVIES), minSdk = minSdk, abis = abis, tvStatus = tvStatus)

    @Test
    fun minSdkIsRespected() {
        assertTrue(app(minSdk = 24).runsOn(24, arm))
        assertFalse(app(minSdk = 24).runsOn(23, arm))
        assertTrue(app().runsOn(23, arm))
    }

    @Test
    fun needsOneSupportedAbi() {
        assertTrue(app(abis = listOf("arm64-v8a", "armeabi-v7a")).runsOn(30, arm))
        assertFalse(app(abis = listOf("arm64-v8a", "armeabi-v7a")).runsOn(30, x86))
        assertTrue(app(abis = emptyList()).runsOn(30, x86))
    }

    @Test
    fun notWorkingAppsAreHidden() {
        assertFalse(app(tvStatus = "not working").runsOn(30, arm))
        assertTrue(app(tvStatus = "tested").runsOn(30, arm))
    }

    @Test
    fun heroPrefersFeaturedThenTrendingWithBanners() {
        val a = HubApp("A", "a", listOf(Category.MOVIES), banner = "artwork/a.banner.png")
        val b = HubApp("B", "b", listOf(Category.MOVIES))
        val c = HubApp("C", "c", listOf(Category.MOVIES), banner = "artwork/c.banner.png")
        val ranked = listOf(RankedApp(1, b), RankedApp(2, c), RankedApp(3, a))
        assertEquals(listOf("c", "a"), heroApps(listOf(a, b, c), ranked).map { it.packageName })
        val featured = b.copy(featured = true)
        assertEquals(listOf("b"), heroApps(listOf(a, featured, c), ranked).map { it.packageName })
    }
}
