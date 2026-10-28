package com.example.hub

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

// Lower-case hex with any colons removed, so "AB:CD" and "abcd" compare equal.
internal fun normalizeFingerprint(value: String): String = value.replace(":", "").lowercase()

// Whether any of the APK's signer certificates (SHA-256, hex) is the approved one.
// No approved fingerprint means there is nothing to enforce.
fun signerMatches(approved: String?, actual: List<String>): Boolean =
    approved == null || actual.any { normalizeFingerprint(it) == normalizeFingerprint(approved) }

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

// Whether a downloaded file's SHA-256 is the expected one. No expected hash means nothing to check.
fun fileHashMatches(expected: String?, actual: String): Boolean =
    expected == null || expected.equals(actual, ignoreCase = true)

// What's wrong with a downloaded APK, or null if it may be installed: it must be the expected
// package, and signed by the approved certificate when one is set.
fun apkProblem(app: HubApp, actualPackage: String?, actualSigners: List<String>): UiText? = when {
    actualPackage == null -> uiText(R.string.error_not_valid_app)
    actualPackage != app.packageName -> uiText(R.string.error_different_app)
    !signerMatches(app.signerSha256, actualSigners) -> uiText(R.string.error_signature)
    else -> null
}

// Checks a downloaded APK before it is handed to the system installer. Throws with a short message.
fun verifyDownloadedApk(context: Context, app: HubApp, file: File) {
    val info = readArchive(context, file)
    apkProblem(app, info?.packageName, info?.let(::signerDigests).orEmpty())?.let { throw HubIOException(it) }
}

@Suppress("DEPRECATION")
private fun readArchive(context: Context, file: File): PackageInfo? {
    // Android 9 only reads an archive's certificates when GET_SIGNATURES is set (fixed in 10),
    // so without it signingInfo is null there, e.g. on Fire OS 7.
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        else PackageManager.GET_SIGNATURES
    return context.packageManager.getPackageArchiveInfo(file.path, flags)
}

// SHA-256 of every signing certificate. On API 28+ this includes earlier certificates in a
// key-rotation history, so an app that rotated keys still matches the fingerprint it was approved with.
@Suppress("DEPRECATION")
internal fun signerDigests(info: PackageInfo): List<String> {
    val signing = if (Build.VERSION.SDK_INT >= 28) info.signingInfo else null
    val signatures = when {
        signing == null -> info.signatures
        signing.hasMultipleSigners() -> signing.apkContentsSigners
        else -> signing.signingCertificateHistory
    }
    return signatures.orEmpty().map { sha256Hex(it.toByteArray()) }
}
