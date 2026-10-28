package com.example.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HubUpdateTest {
    private val sha = "a".repeat(64)
    private val ourKey = "c".repeat(64)
    private val otherKey = "d".repeat(64)

    private fun release(code: Long = 2, signer: String = ourKey, minSdk: Int? = 23) =
        HubRelease(code, "1.1", "https://example.com/StreamProsHub.apk", sha, signer, minSdk, null)

    // ---- hub-version.json ----

    @Test
    fun parsesAValidVersionFile() {
        val r = parseHubRelease(
            """{"versionCode":2,"versionName":"1.1","apkUrl":"https://example.com/StreamProsHub.apk",
               "sha256":"${sha.uppercase()}","signerSha256":"${ourKey.uppercase().chunked(2).joinToString(":")}",
               "minSdk":23,"notes":"Faster home screen"}"""
        )
        assertEquals(HubRelease(2, "1.1", "https://example.com/StreamProsHub.apk", sha, ourKey, 23, "Faster home screen"), r)
    }

    @Test
    fun rejectsVersionFilesThatCantBeVerified() {
        val ok = """"apkUrl":"https://e.com/a.apk","sha256":"$sha","signerSha256":"$ourKey""""
        assertNotNull(parseHubRelease("""{"versionCode":2,$ok}"""))
        assertNull(parseHubRelease("""{"versionCode":2,"apkUrl":"https://e.com/a.apk","sha256":"$sha"}"""))   // no signer
        assertNull(parseHubRelease("""{"versionCode":2,"apkUrl":"https://e.com/a.apk","signerSha256":"$ourKey"}""")) // no hash
        assertNull(parseHubRelease("""{"versionCode":2,"apkUrl":"https://e.com/a.apk","sha256":"abc","signerSha256":"$ourKey"}"""))
        assertNull(parseHubRelease("""{"versionCode":2,"apkUrl":"https://e.com/a.apk","sha256":"$sha","signerSha256":"xyz"}"""))
        assertNull(parseHubRelease("""{"versionCode":2,"apkUrl":"http://e.com/a.apk","sha256":"$sha","signerSha256":"$ourKey"}"""))
        assertNull(parseHubRelease("""{"versionCode":0,$ok}"""))
        assertNull(parseHubRelease("<html>not json</html>"))
    }

    // ---- version comparison ----

    @Test
    fun onlyAHigherVersionCodeIsNewer() {
        assertTrue(isNewerHub(release(code = 2), installedVersionCode = 1))
        assertFalse(isNewerHub(release(code = 2), installedVersionCode = 2))
        assertFalse(isNewerHub(release(code = 2), installedVersionCode = 3))
    }

    // ---- checks before download ----

    @Test
    fun releaseSignedWithOurKeyIsAccepted() {
        assertNull(hubReleaseProblem(release(), listOf(ourKey), sdk = 34))
        // A key-rotation history that includes our key also counts.
        assertNull(hubReleaseProblem(release(), listOf("e".repeat(64), ourKey), sdk = 34))
    }

    @Test
    fun releaseSignedWithAnotherKeyIsRefused() {
        assertEquals(
            uiText(R.string.error_update_not_ours),
            hubReleaseProblem(release(signer = otherKey), listOf(ourKey), sdk = 34)
        )
    }

    @Test
    fun unreadableOwnSignatureIsRefused() {
        assertNotNull(hubReleaseProblem(release(), emptyList(), sdk = 34))
    }

    @Test
    fun releaseNeedingNewerAndroidIsRefused() {
        assertNotNull(hubReleaseProblem(release(minSdk = 35), listOf(ourKey), sdk = 34))
        assertNull(hubReleaseProblem(release(minSdk = null), listOf(ourKey), sdk = 23))
    }

    // ---- checks after download (shared with catalog installs) ----

    @Test
    fun downloadedApkMustBeOurPackageWithMatchingSigner() {
        val app = HubApp("StreamPros Hub", "com.streampros.hub", emptyList(), signerSha256 = ourKey)
        assertNull(apkProblem(app, "com.streampros.hub", listOf(ourKey)))
        assertEquals(uiText(R.string.error_different_app), apkProblem(app, "com.evil.hub", listOf(ourKey)))
        assertEquals(uiText(R.string.error_signature), apkProblem(app, "com.streampros.hub", listOf(otherKey)))
        assertEquals(uiText(R.string.error_not_valid_app), apkProblem(app, null, emptyList()))
    }

    @Test
    fun fileHashMustMatch() {
        assertTrue(fileHashMatches(sha, sha.uppercase()))
        assertFalse(fileHashMatches(sha, "b".repeat(64)))
        assertTrue(fileHashMatches(null, "b".repeat(64))) // catalog "latest" apps have no hash
    }
}
