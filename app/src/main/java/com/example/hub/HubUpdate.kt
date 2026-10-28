package com.example.hub

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// A published StreamPros release, read from hub-version.json (made by scripts/make_hub_version.py).
data class HubRelease(
    val versionCode: Long,
    val versionName: String?,
    val apkUrl: String,
    val sha256: String,
    val signerSha256: String,
    val minSdk: Int?,
    val notes: String?
)

private val HEX64 = Regex("[0-9a-fA-F]{64}")

// Null unless the file has a positive versionCode, an https apkUrl, and 64-hex sha256 and
// signerSha256 (colons allowed): an update is only offered when the download can be checked.
fun parseHubRelease(json: String): HubRelease? = runCatching {
    val o = JSONObject(json)
    val code = if (o.has("versionCode") && !o.isNull("versionCode")) o.optLong("versionCode", -1) else -1
    val url = o.optStringOrNull("apkUrl")
    val sha = o.optStringOrNull("sha256")
    val signer = o.optStringOrNull("signerSha256")?.let(::normalizeFingerprint)
    val minSdk = if (o.has("minSdk") && !o.isNull("minSdk")) o.optInt("minSdk", 0).takeIf { it > 0 } else null
    if (code <= 0 || url == null || !url.startsWith("https://") || sha == null || !sha.matches(HEX64) ||
        signer == null || !signer.matches(HEX64)
    ) null
    else HubRelease(code, o.optStringOrNull("versionName"), url, sha.lowercase(), signer, minSdk, o.optStringOrNull("notes"))
}.getOrNull()

fun isNewerHub(release: HubRelease, installedVersionCode: Long): Boolean = release.versionCode > installedVersionCode

// Checked before anything is downloaded. The release must name this installed Hub's own signing
// certificate (so the APK, which must match signerSha256 after download, is signed by our key too)
// and support this Android version. Null means OK; otherwise a message for the user.
fun hubReleaseProblem(release: HubRelease, ownSigners: List<String>, sdk: Int): UiText? = when {
    ownSigners.isEmpty() -> uiText(R.string.error_own_signature)
    !signerMatches(release.signerSha256, ownSigners) -> uiText(R.string.error_update_not_ours)
    release.minSdk != null && sdk < release.minSdk -> uiText(R.string.error_update_android)
    else -> null
}

sealed interface HubUpdateState {
    data object NotConfigured : HubUpdateState // no update link built in
    data object Idle : HubUpdateState
    data object Checking : HubUpdateState
    data object UpToDate : HubUpdateState
    data class Available(val release: HubRelease) : HubUpdateState
    data class Refused(val message: UiText) : HubUpdateState // newer release that fails hubReleaseProblem
    data class Failed(val message: UiText) : HubUpdateState
}

// Checks BuildConfig.HUB_UPDATE_URL for a newer Hub and installs it through InstallCoordinator,
// which verifies the download's package, sha256 and signer before PackageInstaller sees it.
object HubUpdater {
    private const val PREFS = "hub_update"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_NOTES_VERSION = "notes_version"
    private const val KEY_NOTES = "notes"
    private const val AUTO_CHECK_EVERY_MS = 12 * 60 * 60 * 1000L
    private const val TIMEOUT_MS = 10_000
    private const val MAX_BYTES = 64 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow<HubUpdateState>(
        if (BuildConfig.HUB_UPDATE_URL.isBlank()) HubUpdateState.NotConfigured else HubUpdateState.Idle
    )
    val state: StateFlow<HubUpdateState> = _state

    // On launch: checks at most every 12 hours, quietly.
    fun checkIfDue(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0) < AUTO_CHECK_EVERY_MS) return
        check(context, quiet = true)
    }

    // quiet: offline or a bad file keeps the previous result instead of showing an error.
    fun check(context: Context, quiet: Boolean = false) {
        val before = _state.value
        if (before is HubUpdateState.NotConfigured || before is HubUpdateState.Checking) return
        val ctx = context.applicationContext
        _state.value = HubUpdateState.Checking
        scope.launch {
            _state.value = try {
                val release = withContext(Dispatchers.IO) { fetch(BuildConfig.HUB_UPDATE_URL) }
                    ?: throw IOException("The update file isn't valid")
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putLong(KEY_LAST_CHECK, System.currentTimeMillis()) }
                if (isNewerHub(release, currentVersionCode(ctx))) saveNotes(ctx, release)
                when {
                    !isNewerHub(release, currentVersionCode(ctx)) -> HubUpdateState.UpToDate
                    else -> hubReleaseProblem(release, ownSigners(ctx), Build.VERSION.SDK_INT)
                        ?.let { HubUpdateState.Refused(it) } ?: HubUpdateState.Available(release)
                }
            } catch (e: Exception) {
                if (quiet) before else HubUpdateState.Failed(uiText(R.string.error_update_check))
            }
        }
    }

    // The update as a catalog-style app, so the normal download/verify/install flow handles it:
    // the APK must be this package, match sha256, and be signed by signerSha256 (= our own key).
    fun asApp(context: Context, release: HubRelease): HubApp = HubApp(
        name = "StreamPros",
        packageName = context.packageName,
        categories = emptyList(),
        apkUrl = release.apkUrl,
        sha256 = release.sha256,
        version = release.versionName,
        versionCode = release.versionCode,
        signerSha256 = release.signerSha256
    )

    // Release notes of a newer Hub, kept so "What's new" can show them once that version runs.
    private fun saveNotes(context: Context, release: HubRelease) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putLong(KEY_NOTES_VERSION, release.versionCode)
            if (release.notes != null) putString(KEY_NOTES, release.notes) else remove(KEY_NOTES)
        }
    }

    // The saved notes when this installed Hub is the version they were written for, else null.
    fun notesForThisVersion(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val version = prefs.getLong(KEY_NOTES_VERSION, -1)
        return prefs.getString(KEY_NOTES, null)?.takeIf { version == currentVersionCode(context) }
    }

    // After "What's new" was shown, so it shows once.
    fun clearNotes(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove(KEY_NOTES_VERSION); remove(KEY_NOTES) }
    }

    fun currentVersionCode(context: Context): Long =
        AppLauncher.installedVersionCode(context, context.packageName) ?: 0

    private fun fetch(url: String): HubRelease? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        try {
            if (conn.responseCode != 200) throw IOException("Server returned ${conn.responseCode}")
            val body = conn.inputStream.use { it.readAtMost(MAX_BYTES) }
            return parseHubRelease(String(body, Charsets.UTF_8))
        } finally {
            conn.disconnect()
        }
    }

    private fun java.io.InputStream.readAtMost(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    // SHA-256 of this installed Hub's signing certificate(s): GET_SIGNING_CERTIFICATES on API 28+
    // (including any key-rotation history), GET_SIGNATURES below. Empty if it can't be read.
    @Suppress("DEPRECATION")
    fun ownSigners(context: Context): List<String> = runCatching {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info: PackageInfo = context.packageManager.getPackageInfo(context.packageName, flags)
        signerDigests(info)
    }.getOrDefault(emptyList())
}
