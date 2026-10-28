package com.example.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogParseTest {
    @Test
    fun parsesAllFields() {
        val apps = parseCatalog(
            """{"apps":[{"name":"A","packageName":"p.a","categories":["MOVIES","SHOWS"],
               "apkUrl":"https://example.com/a.apk","sha256":"abc","version":"1.2","versionCode":12,
               "artwork":"artwork/a.png","featured":true}]}"""
        )
        assertEquals(
            listOf(
                HubApp(
                    "A", "p.a", listOf(Category.MOVIES, Category.SHOWS), "https://example.com/a.apk", "abc",
                    "1.2", 12L, "artwork/a.png", featured = true
                )
            ),
            apps
        )
    }

    @Test
    fun jsonNullsBecomeKotlinNulls() {
        val app = parseCatalog(
            """{"apps":[{"name":"A","packageName":"p.a","categories":["ANIME"],"apkUrl":null,
               "sha256":null,"version":null,"versionCode":null,"artwork":null}]}"""
        ).single()
        assertNull(app.apkUrl)
        assertNull(app.sha256)
        assertNull(app.version)
        assertNull(app.versionCode)
        assertNull(app.artwork)
        assertFalse(app.featured)
    }

    @Test
    fun skipsEntriesMissingNameOrPackageOrKnownCategory() {
        val apps = parseCatalog(
            """{"apps":[
               {"packageName":"p.noname","categories":["MOVIES"]},
               {"name":"No package","categories":["MOVIES"]},
               {"name":"Unknown cat","packageName":"p.u","categories":["COOKING"]},
               {"name":"Ok","packageName":"p.ok","categories":["cooking","live_tv"]}]}"""
        )
        assertEquals(listOf("p.ok"), apps.map { it.packageName })
        assertEquals(listOf(Category.LIVE_TV), apps.single().categories)
    }

    @Test
    fun readsCatalogHeader() {
        val meta = parseCatalogMeta("""{"schemaVersion":1,"updated":"2026-10-20","apps":[{},{},{}]}""")
        assertEquals(CatalogMeta(1, "2026-10-20", 3), meta)
        assertEquals(CatalogMeta(null, null, 0), parseCatalogMeta("{}"))
    }

    @Test
    fun missingAppsArrayGivesEmptyList() {
        assertEquals(emptyList<HubApp>(), parseCatalog("""{"sample":true}"""))
    }

    @Test
    fun readsCategoryLabelsAndVersionName() {
        val app = parseCatalog(
            """{"schemaVersion":1,"apps":[{"name":"A","packageName":"p.a","categories":["Live TV","Movies"],
               "versionName":"3.9.0","versionCode":81,"urlType":"pinned","tvStatus":"untested"}]}"""
        ).single()
        assertEquals(listOf(Category.LIVE_TV, Category.MOVIES), app.categories)
        assertEquals("3.9.0", app.version)
        assertEquals(81L, app.versionCode)
    }

    @Test
    fun readsSignerDeviceAndArtworkFields() {
        val app = parseCatalog(
            """{"apps":[{"name":"A","packageName":"p.a","categories":["Movies"],"signerSha256":"ab",
               "minSdk":24,"abis":["arm64-v8a"],"banner":"artwork/p.a.banner.png","tvStatus":"tested"}]}"""
        ).single()
        assertEquals("ab", app.signerSha256)
        assertEquals(24, app.minSdk)
        assertEquals(listOf("arm64-v8a"), app.abis)
        assertEquals("artwork/p.a.banner.png", app.banner)
        assertEquals("tested", app.tvStatus)
        val bare = parseCatalog("""{"apps":[{"name":"B","packageName":"p.b","categories":["Movies"]}]}""").single()
        assertEquals(null, bare.minSdk)
        assertEquals(emptyList<String>(), bare.abis)
        assertEquals("untested", bare.tvStatus)
    }
}
