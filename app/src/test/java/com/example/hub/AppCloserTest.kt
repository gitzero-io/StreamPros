package com.example.hub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCloserTest {
    @Test
    fun closesBackgroundAppsOnlyBeforeAndroid14() {
        assertTrue(AppCloser.canCloseBackground(23))
        assertTrue(AppCloser.canCloseBackground(33))
        assertFalse(AppCloser.canCloseBackground(34))
        assertFalse(AppCloser.canCloseBackground(36))
    }
}
