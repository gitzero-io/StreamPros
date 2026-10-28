package com.example.hub

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.net.toUri
import android.os.Build
import android.os.storage.StorageManager
import android.provider.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

sealed interface InstallState {
    object Installing : InstallState
    data class Downloading(val percent: Int) : InstallState // -1 = unknown size
    data class Failed(val message: UiText) : InstallState
}

val OFFLINE_MESSAGE: UiText = uiText(R.string.error_offline)

// A download or install problem with a message ready for the screen. keepPartial: what was
// downloaded so far is kept, so TRY AGAIN resumes.
class HubIOException(val text: UiText, val keepPartial: Boolean = false) : IOException(text.toString())

// True when the TV has a network with internet. Assumes online if the system can't say.
fun isOnline(context: Context): Boolean = runCatching {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}.getOrDefault(true)

// Plain-language text for a failed download; network drops mid-download read as "offline".
fun downloadErrorMessage(e: Exception): UiText = when (e) {
    is HubIOException -> e.text
    is UnknownHostException, is ConnectException, is NoRouteToHostException -> OFFLINE_MESSAGE
    is SocketTimeoutException -> uiText(R.string.error_timeout)
    else -> e.message?.let { UiText.Raw(it) } ?: uiText(R.string.error_download_failed)
}

fun canInstallApps(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= 26) context.packageManager.canRequestPackageInstalls()
    else @Suppress("DEPRECATION")
    Settings.Secure.getInt(context.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1

// Returns false if the device has no screen for this setting.
fun openInstallPermissionSettings(context: Context): Boolean {
    val intent = if (Build.VERSION.SDK_INT >= 26)
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
    else Intent(Settings.ACTION_SECURITY_SETTINGS)
    return try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: Exception) { false }
}

// Room needed to download and install an APK of `total` bytes when `remaining` are still to
// download: the rest of the download, a copy in the install session, and some headroom.
const val INSTALL_HEADROOM_BYTES = 20L * 1024 * 1024

fun spaceNeeded(total: Long, remaining: Long): Long = remaining + total + INSTALL_HEADROOM_BYTES

// A "not enough space" message when `free` bytes can't hold `needed`, else null. Sizes in MB,
// rounded up so "needs 85 MB, 85 MB free" never shows.
fun spaceProblem(needed: Long, free: Long): UiText? {
    if (free >= needed) return null
    val mb = 1024L * 1024
    return uiText(R.string.error_space, (needed + mb - 1) / mb, free / mb)
}

// Bytes the Hub can use on the storage holding `dir`, counting cached files the system can clear
// (Android 8+). Null if unknown.
fun freeBytes(context: Context, dir: File): Long? = runCatching {
    if (Build.VERSION.SDK_INT >= 26) {
        val sm = context.getSystemService(StorageManager::class.java)
        sm.getAllocatableBytes(sm.getUuidForPath(dir))
    } else dir.usableSpace
}.getOrNull()

// Asks Android 8+ to clear other apps' caches so `bytes` fit. Best effort.
private fun reserveSpace(context: Context, dir: File, bytes: Long) {
    if (Build.VERSION.SDK_INT < 26) return
    runCatching {
        val sm = context.getSystemService(StorageManager::class.java)
        sm.allocateBytes(sm.getUuidForPath(dir), bytes)
    }
}

// Wait before retry `attempt` (1-based) of a dropped download: 2 s, 4 s, 8 s.
fun retryDelayMs(attempt: Int): Long = 2_000L shl (attempt - 1)

// Where the Range request for a resumed download must start, from the server's Content-Range
// ("bytes 1000-1999/2000"). Null if it is missing or unreadable.
fun contentRangeStart(header: String?): Long? =
    header?.removePrefix("bytes")?.trim()?.substringBefore('-')?.toLongOrNull()

// Progress of Update all: `done` of `total` apps finished, `current` is the one installing.
data class BatchProgress(val done: Int, val total: Int, val current: String?)

object InstallCoordinator {
    private const val ACTION = "com.example.hub.INSTALL_RESULT"
    private const val MAX_ATTEMPTS = 4
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val jobs = mutableMapOf<String, Job>()
    private val _states = MutableStateFlow<Map<String, InstallState>>(emptyMap())
    val states: StateFlow<Map<String, InstallState>> = _states

    // Package that just finished installing, for the "Open now" prompt. Null when nothing pending.
    private val _justInstalled = MutableStateFlow<String?>(null)
    val justInstalled: StateFlow<String?> = _justInstalled
    // Packages installed by Update all, which skip the "Open now" prompt.
    private val quiet = mutableSetOf<String>()
    fun markInstalled(pkg: String) {
        clear(pkg)
        if (!quiet.remove(pkg)) _justInstalled.value = pkg
    }
    fun dismissInstalled() { _justInstalled.value = null }

    fun set(pkg: String, s: InstallState) { _states.update { it + (pkg to s) } }
    fun clear(pkg: String) { _states.update { it - pkg } }
    fun cancel(pkg: String) { jobs[pkg]?.cancel(); clear(pkg) }

    private var batchJob: Job? = null
    private val _batch = MutableStateFlow<BatchProgress?>(null)
    val batch: StateFlow<BatchProgress?> = _batch

    // Updates the apps one after another, so Android's install prompts come one at a time. Stops
    // early when the TV is offline or the Hub may not install apps; one app failing moves on.
    fun updateAll(context: Context, apps: List<HubApp>) {
        if (batchJob?.isActive == true || apps.isEmpty()) return
        val ctx = context.applicationContext
        batchJob = scope.launch {
            try {
                apps.forEachIndexed { i, app ->
                    val pkg = app.packageName
                    // Apps that need a plan are left for the viewer to unlock one by one.
                    if (app.apkUrl == null && License.needsPlan(pkg)) return@forEachIndexed
                    _batch.value = BatchProgress(i, apps.size, pkg)
                    if (jobs[pkg]?.isActive != true) {
                        quiet += pkg
                        if (!start(ctx, app)) {
                            quiet -= pkg
                            if (!isOnline(ctx) || (Build.VERSION.SDK_INT >= 26 && !canInstallApps(ctx))) return@launch
                        }
                    }
                    states.first { it[pkg] !is InstallState.Downloading && it[pkg] !is InstallState.Installing }
                    if (states.value[pkg] is InstallState.Failed) quiet -= pkg
                }
            } finally {
                _batch.value = null
            }
        }
    }

    // Stops Update all after the app in progress; that one finishes or can be cancelled on its own.
    fun stopUpdateAll() { batchJob?.cancel() }

    // Returns false when nothing started (no link, offline or no install permission); the reason
    // is in states. True when the download started or was already running.
    fun start(context: Context, app: HubApp): Boolean {
        val ctx = context.applicationContext
        val pkg = app.packageName
        // No link: a trial catalog, so the license server gives it (License.installLink).
        val url = app.apkUrl
        if (url != null && !url.startsWith("https://")) {
            set(pkg, InstallState.Failed(uiText(R.string.error_no_link))); return false
        }
        if (jobs[pkg]?.isActive == true) return true
        if (!isOnline(ctx)) {
            set(pkg, InstallState.Failed(OFFLINE_MESSAGE)); return false
        }
        if (Build.VERSION.SDK_INT >= 26 && !canInstallApps(ctx)) {
            set(pkg, InstallState.Failed(uiText(R.string.error_allow_installs)))
            if (!openInstallPermissionSettings(ctx)) {
                set(pkg, InstallState.Failed(uiText(R.string.error_no_permission_screen)))
            }
            return false
        }
        set(pkg, InstallState.Downloading(0))
        jobs[pkg] = scope.launch {
            try {
                val target = if (url == null) app.copy(apkUrl = License.installLink(pkg)) else app
                val file = download(ctx, target) { set(pkg, InstallState.Downloading(it)) }
                set(pkg, InstallState.Installing)
                withContext(Dispatchers.IO) {
                    verifyDownloadedApk(ctx, target, file)
                    commitInstall(ctx, target, file)
                }
            } catch (e: CancellationException) {
                discard(ctx, pkg, keepPartial = false); clear(pkg); throw e
            } catch (e: Exception) {
                // A dropped connection keeps what was downloaded, so TRY AGAIN carries on from there.
                discard(ctx, pkg, keepPartial = e !is HubIOException || e.keepPartial)
                set(pkg, InstallState.Failed(downloadErrorMessage(e)))
            }
        }
        return true
    }

    private fun discard(ctx: Context, pkg: String, keepPartial: Boolean) {
        val dir = File(ctx.cacheDir, "apks")
        File(dir, "$pkg.apk").delete()
        if (!keepPartial) { File(dir, "$pkg.part").delete(); File(dir, "$pkg.part.info").delete() }
    }

    // Downloads to apks/<pkg>.part, retrying dropped connections and resuming where they stopped,
    // then checks the SHA-256 and renames it to apks/<pkg>.apk.
    private suspend fun download(ctx: Context, app: HubApp, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(ctx.cacheDir, "apks").apply { mkdirs() }
            val part = File(dir, "${app.packageName}.part")
            val info = File(dir, "${app.packageName}.part.info")
            // A partial file from another link or version starts over.
            val key = "${app.apkUrl} ${app.sha256} ${app.versionCode}"
            val saved = runCatching { info.readLines() }.getOrDefault(emptyList())
            if (saved.firstOrNull() != key) { part.delete(); info.writeText(key + "\n") }
            var attempt = 1
            while (true) {
                try {
                    downloadOnce(ctx, app, part, info, key, onProgress)
                    break
                } catch (e: HubIOException) {
                    throw e
                } catch (e: IOException) {
                    if (attempt < MAX_ATTEMPTS) { delay(retryDelayMs(attempt++)); continue }
                    // Offline and timeout messages say what to fix; anything else just stopped.
                    throw if (downloadErrorMessage(e) == OFFLINE_MESSAGE || e is SocketTimeoutException) e
                    else HubIOException(uiText(R.string.error_interrupted), keepPartial = true)
                }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            part.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!fileHashMatches(app.sha256, actual)) {
                part.delete(); info.delete(); throw HubIOException(uiText(R.string.error_hash))
            }
            val file = File(dir, "${app.packageName}.apk")
            file.delete()
            if (!part.renameTo(file)) throw IOException("Couldn't save the download")
            info.delete()
            file
        }

    // One request. Resumes with a Range request when part of the file is already here and the
    // server gave a validator (ETag or Last-Modified) for it; If-Range makes the server send the
    // whole file instead if it changed.
    private suspend fun downloadOnce(ctx: Context, app: HubApp, part: File, info: File, key: String, onProgress: (Int) -> Unit) {
        val validator = runCatching { info.readLines().getOrNull(1) }.getOrNull()?.takeIf { it.isNotBlank() }
        val have = if (validator != null && part.exists()) part.length() else 0L
        if (have == 0L) part.delete()
        val conn = URL(app.apkUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        if (have > 0) {
            conn.setRequestProperty("Range", "bytes=$have-")
            conn.setRequestProperty("If-Range", validator)
        }
        try {
            val code = conn.responseCode
            val resumed = code == 206 && have > 0 && contentRangeStart(conn.getHeaderField("Content-Range")) == have
            when {
                resumed || code == 200 -> {}
                code == 416 || code == 206 -> { part.delete(); info.writeText(key + "\n"); throw IOException("Range not satisfiable") }
                else -> throw HubIOException(uiText(R.string.error_server, code))
            }
            val start = if (resumed) have else 0L
            if (!resumed) {
                part.delete()
                val newValidator = conn.getHeaderField("ETag") ?: conn.getHeaderField("Last-Modified")
                info.writeText(key + "\n" + newValidator.orEmpty() + "\n")
            }
            // contentLengthLong needs API 24.
            val length = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            val total = if (length > 0) start + length else -1L
            if (length > 0) {
                val dir = part.parentFile!!
                val needed = spaceNeeded(total, length)
                spaceProblem(needed, freeBytes(ctx, dir) ?: Long.MAX_VALUE)?.let { throw HubIOException(it, keepPartial = true) }
                reserveSpace(ctx, dir, needed)
            }
            var read = start
            var last = -2
            conn.inputStream.use { input ->
                java.io.FileOutputStream(part, resumed).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        try {
                            out.write(buf, 0, n)
                        } catch (e: IOException) {
                            throw if (isOutOfSpace(e)) HubIOException(uiText(R.string.error_space_unknown)) else e
                        }
                        read += n
                        val p = if (total > 0) (read * 100 / total).toInt() else -1
                        if (p != last) { last = p; onProgress(p) }
                    }
                }
            }
            if (total > 0 && read < total) throw IOException("Connection closed early")
        } finally {
            conn.disconnect()
        }
    }

    private fun isOutOfSpace(e: IOException): Boolean =
        e.message?.let { "ENOSPC" in it || "No space left" in it } == true

    private fun commitInstall(ctx: Context, app: HubApp, file: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(app.packageName)
        // Android 12+ can update an app the Hub installed without asking again; it still asks for
        // new installs and for apps another store installed. The Hub's own update always asks.
        if (Build.VERSION.SDK_INT >= 31 && app.packageName != ctx.packageName) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            file.inputStream().use { input ->
                session.openWrite("apk", 0, file.length()).use { out ->
                    try {
                        input.copyTo(out)
                    } catch (e: IOException) {
                        throw if (isOutOfSpace(e)) HubIOException(uiText(R.string.error_space_unknown)) else e
                    }
                    session.fsync(out)
                }
            }
            val intent = Intent(ctx, InstallReceiver::class.java)
                .setAction(ACTION).setPackage(ctx.packageName)
                .putExtra("hub_pkg", app.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
        }
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra("hub_pkg") ?: return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                File(context.cacheDir, "apks/$pkg.apk").delete()
                // The Hub's own update isn't a catalog app, so it gets no "new" mark.
                if (pkg != context.packageName) UserPrefs.markFreshInstall(context, pkg)
                InstallCoordinator.markInstalled(pkg)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                File(context.cacheDir, "apks/$pkg.apk").delete()
                InstallCoordinator.set(pkg, InstallState.Failed(uiText(R.string.error_install_cancelled)))
            }
            else -> {
                File(context.cacheDir, "apks/$pkg.apk").delete()
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.let { UiText.Raw(it) }
                InstallCoordinator.set(pkg, InstallState.Failed(msg ?: uiText(R.string.error_install_failed)))
            }
        }
    }
}