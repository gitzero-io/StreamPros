package com.example.hub

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeInstallsTest {
    private val trialApp = HubApp("A", "p.a", listOf(Category.MOVIES))           // trial catalog: no link
    private val paidApp = trialApp.copy(apkUrl = "https://example.com/a.apk")

    @Test
    fun blocksOnlyNewAppsOnceAllAreUsed() {
        val none = FreeInstalls(2, emptyList())
        val one = FreeInstalls(2, listOf("p.x"))
        val full = FreeInstalls(2, listOf("p.x", "p.y"))
        assertFalse(none.blocks("p.a"))
        assertFalse(one.blocks("p.a"))
        assertTrue(full.blocks("p.a"))
        assertFalse(full.blocks("p.x"))
        assertEquals(listOf(2, 1, 0), listOf(none.left, one.left, full.left))
    }

    @Test
    fun parsesServerAnswer() {
        assertEquals(FreeInstalls(2, listOf("p.x")), parseFreeInstalls(JSONObject("""{"limit":2,"used":["p.x"]}""")))
        assertEquals(FreeInstalls(2, emptyList()), parseFreeInstalls(JSONObject("""{"limit":2}""")))
        assertNull(parseFreeInstalls(null))
        assertNull(parseFreeInstalls(JSONObject("{}")))
    }

    @Test
    fun usedUpTrialOffersAPlan() {
        val full = FreeInstalls(2, listOf("p.x", "p.y"))
        assertEquals(R.string.action_get_plan, appAction(trialApp, null, null, freeInstalls = full).label)
        assertEquals(R.string.action_get_plan, appAction(trialApp, InstallState.Failed(UiText.Raw("x")), null, freeInstalls = full).label)
        assertEquals(R.string.btn_cancel, appAction(trialApp, InstallState.Downloading(5), null, freeInstalls = full).label)
        assertEquals(R.string.status_needs_plan, (statusText(trialApp, null, null, freeInstalls = full) as UiText.Res).id)
    }

    @Test
    fun freeInstallsLeftAllowInstalls() {
        val one = FreeInstalls(2, listOf("p.x"))
        assertEquals(R.string.action_get_app, appAction(trialApp, null, null, freeInstalls = one).label)
        assertEquals(UiText.Res(R.string.status_free_installs_left, listOf(1, 2)), statusText(trialApp, null, null, freeInstalls = one))
    }

    @Test
    fun installedAppsAndLinkedAppsAreNotGated() {
        val full = FreeInstalls(2, listOf("p.x", "p.y"))
        // Installed and up to date: OPEN, whatever the count.
        assertEquals(R.string.btn_open, appAction(trialApp, null, 1L, freeInstalls = full).label)
        // A used app can still update.
        val used = trialApp.copy(packageName = "p.x", versionCode = 5)
        assertEquals(R.string.action_update, appAction(used, null, 1L, freeInstalls = full).label)
        // Apps with a link (paid catalog, the Hub's own update) never count.
        assertEquals(R.string.action_get_app, appAction(paidApp, null, null, freeInstalls = full).label)
        assertEquals(R.string.action_get_app, appAction(trialApp, null, null, freeInstalls = null).label)
    }
}
