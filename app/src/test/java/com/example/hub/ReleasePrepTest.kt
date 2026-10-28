package com.example.hub

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ReleasePrepTest {
    private val plain = HubApp("Plain", "a", listOf(Category.MOVIES))
    private val adult = HubApp("Adult", "b", listOf(Category.MOVIES), mature = true)

    @Test
    fun matureAppsShownByDefault() {
        assertEquals(listOf(plain, adult), visibleApps(listOf(plain, adult), hideMature = false))
    }

    @Test
    fun hideMatureRemovesOnlyMatureApps() {
        assertEquals(listOf(plain), visibleApps(listOf(plain, adult), hideMature = true))
    }

    @Test
    fun networkErrorsReadAsOffline() {
        assertEquals(OFFLINE_MESSAGE, downloadErrorMessage(UnknownHostException("example.com")))
        assertEquals(OFFLINE_MESSAGE, downloadErrorMessage(ConnectException("refused")))
    }

    @Test
    fun timeoutAndOtherErrors() {
        assertEquals(
            uiText(R.string.error_timeout),
            downloadErrorMessage(SocketTimeoutException())
        )
        assertEquals(UiText.Raw("Server returned 404"), downloadErrorMessage(IOException("Server returned 404")))
        assertEquals(uiText(R.string.error_hash), downloadErrorMessage(HubIOException(uiText(R.string.error_hash))))
    }
}
