package com.example.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    @Test
    fun newerCatalogVersionNeedsUpdate() {
        assertTrue(needsUpdate(installedVersionCode = 1, catalogVersionCode = 2))
    }

    @Test
    fun sameOrOlderCatalogVersionDoesNot() {
        assertFalse(needsUpdate(2, 2))
        assertFalse(needsUpdate(3, 2))
    }

    @Test
    fun notInstalledOrUnknownVersionDoesNot() {
        assertFalse(needsUpdate(null, 2))
        assertFalse(needsUpdate(1, null))
    }

    private val app = HubApp("A", "p.a", listOf(Category.MOVIES), versionCode = 5)

    @Test
    fun actionLabelsFollowState() {
        assertEquals(R.string.action_get_app, appAction(app, null, null).label)
        assertEquals(R.string.btn_open, appAction(app, null, 5).label)
        assertEquals(R.string.action_update, appAction(app, null, 4).label)
        assertEquals(R.string.btn_cancel, appAction(app, InstallState.Downloading(10), 4).label)
        assertEquals(R.string.action_installing, appAction(app, InstallState.Installing, 4).label)
        assertEquals(R.string.action_try_again, appAction(app, InstallState.Failed(UiText.Raw("x")), 4).label)
        assertEquals(R.string.action_try_again, appAction(app, InstallState.Failed(UiText.Raw("x")), null).label)
        // The setup guide never offers updates: an installed app is just Installed / OPEN.
        assertEquals(R.string.btn_open, appAction(app, null, 4, offerUpdates = false).label)
        assertEquals(uiText(R.string.status_installed), statusText(app, null, 4, offerUpdates = false))
        assertEquals(R.string.action_get_app, appAction(app, null, null, offerUpdates = false).label)
        // A stale failure on an up-to-date app shouldn't hide OPEN.
        assertEquals(R.string.btn_open, appAction(app, InstallState.Failed(UiText.Raw("x")), 5).label)
    }
}
