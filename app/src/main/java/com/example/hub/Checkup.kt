@file:OptIn(ExperimentalTvMaterial3Api::class)
package com.example.hub

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.SSLException
import kotlin.math.abs

// Troubleshoot (from Settings): RUN CHECKUP checks the internet, the clock, the Hub, the app list,
// the install permission, storage, memory and the installed apps, one after another. Each row says
// what's fine or what needs attention; a row with a fix runs it on OK. Nothing leaves the TV except
// the Hub's usual update and catalog requests.

enum class CheckId(@StringRes val title: Int) {
    INTERNET(R.string.check_internet),
    CLOCK(R.string.check_clock),
    HUB(R.string.check_hub),
    CATALOG(R.string.check_catalog),
    PERMISSION(R.string.check_permission),
    STORAGE(R.string.check_storage),
    MEMORY(R.string.check_memory),
    UPDATES(R.string.check_updates),
    SOURCES(R.string.check_sources),
    DEVICE(R.string.check_device)
}

// ATTENTION is a problem and counts in the summary; INFO is a note or a tip.
enum class CheckStatus { CHECKING, OK, ATTENTION, INFO }

sealed interface CheckFix {
    data object NetworkSettings : CheckFix
    data object DateSettings : CheckFix
    data object InstallPermission : CheckFix
    data class UpdateHub(val release: HubRelease) : CheckFix
    data object FreeSpace : CheckFix
    data object OpenStorage : CheckFix
    data object CloseApps : CheckFix
    data class UpdateAll(val apps: List<HubApp>) : CheckFix
    data class Uninstall(val app: HubApp) : CheckFix
}

data class CheckResult(val status: CheckStatus, val detail: UiText, val fix: CheckFix? = null)

// A clock this far off breaks secure downloads (certificates look expired or not yet valid).
const val CLOCK_TOLERANCE_MS = 5 * 60 * 1000L
// Below this, bigger apps can't download and install.
const val LOW_STORAGE_BYTES = 1024L * 1024 * 1024
// Reaching GitHub slower than this makes downloads crawl.
const val SLOW_CONNECTION_MS = 3_000L

fun clockWrong(serverMillis: Long?, deviceMillis: Long): Boolean =
    serverMillis != null && abs(serverMillis - deviceMillis) > CLOCK_TOLERANCE_MS

fun memoryLow(available: Long, total: Long, systemSaysLow: Boolean): Boolean =
    systemSaysLow || (total > 0 && available * 100 / total < 15)

// Installed apps signed by someone other than the catalog's approved signer: they came from
// another source, and Android refuses the Hub's updates for them. installedSigners returns null
// when the app isn't installed and an empty list when its signer can't be read (not counted).
fun otherSourceApps(apps: List<HubApp>, installedSigners: (String) -> List<String>?): List<HubApp> =
    apps.filter { app ->
        val signers = installedSigners(app.packageName)
        app.signerSha256 != null && !signers.isNullOrEmpty() && !signerMatches(app.signerSha256, signers)
    }

// "A, B, C…": the first three names, so a long list still fits on one row.
fun shortNames(apps: List<HubApp>): String =
    apps.take(3).joinToString(", ") { it.name } + if (apps.size > 3) "…" else ""

// Runs the checks and keeps the results, so they survive opening Storage or Close running apps
// from a row and coming back.
object Checkup {
    private const val TIMEOUT_MS = 10_000
    private const val HUB_CHECK_WAIT_MS = 20_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _results = MutableStateFlow<Map<CheckId, CheckResult>>(emptyMap())
    val results: StateFlow<Map<CheckId, CheckResult>> = _results
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    fun run(context: Context) {
        if (_running.value) return
        val ctx = context.applicationContext
        _running.value = true
        _results.value = CheckId.values().associateWith { CheckResult(CheckStatus.CHECKING, uiText(R.string.check_checking)) }
        scope.launch {
            try {
                val reached = safely(CheckId.INTERNET, CheckId.CLOCK) { checkConnection(ctx) } ?: false
                safely(CheckId.HUB) { checkHub(ctx, reached) }
                safely(CheckId.CATALOG) { checkCatalog(ctx, reached) }
                safely(CheckId.PERMISSION) {
                    post(
                        CheckId.PERMISSION,
                        if (canInstallApps(ctx)) ok(R.string.check_permission_ok)
                        else CheckResult(CheckStatus.ATTENTION, uiText(R.string.check_permission_off), CheckFix.InstallPermission)
                    )
                }
                safely(CheckId.STORAGE) { checkStorage(ctx) }
                safely(CheckId.MEMORY) { checkMemory(ctx) }
                safely(CheckId.UPDATES, CheckId.SOURCES) { checkApps(ctx) }
                safely(CheckId.DEVICE) { checkDevice(ctx) }
            } finally {
                _running.value = false
            }
        }
    }

    private fun post(id: CheckId, result: CheckResult) = _results.update { it + (id to result) }
    private fun ok(@StringRes id: Int, vararg args: Any) = CheckResult(CheckStatus.OK, uiText(id, *args))
    private fun info(@StringRes id: Int, vararg args: Any) = CheckResult(CheckStatus.INFO, uiText(id, *args))

    // A check failing unexpectedly shows "couldn't check" on its rows that it hadn't filled yet,
    // and the rest carry on.
    private suspend fun <T> safely(vararg ids: CheckId, block: suspend () -> T): T? = try {
        block()
    } catch (e: Exception) {
        ids.filter { _results.value[it]?.status == CheckStatus.CHECKING }.forEach { post(it, info(R.string.check_unknown)) }
        null
    }

    // Internet and clock in one request: reaches the Hub's update link on GitHub and reads the
    // server's clock. True when GitHub answered.
    private suspend fun checkConnection(ctx: Context): Boolean {
        val url = BuildConfig.HUB_UPDATE_URL.ifBlank { BuildConfig.LICENSE_URL }
        if (!isOnline(ctx)) {
            post(CheckId.INTERNET, CheckResult(CheckStatus.ATTENTION, uiText(R.string.check_internet_off), CheckFix.NetworkSettings))
            post(CheckId.CLOCK, info(R.string.check_needs_internet))
            return false
        }
        if (url.isBlank()) {
            post(CheckId.INTERNET, ok(R.string.check_internet_connected))
            post(CheckId.CLOCK, info(R.string.check_clock_unknown))
            return false
        }
        val started = SystemClock.elapsedRealtime()
        val serverTime = try {
            withContext(Dispatchers.IO) { serverTime(url) }
        } catch (e: SSLException) {
            // A wrong clock is the usual reason secure connections fail on a TV.
            post(CheckId.INTERNET, CheckResult(CheckStatus.ATTENTION, uiText(R.string.check_internet_secure), CheckFix.DateSettings))
            post(CheckId.CLOCK, CheckResult(CheckStatus.ATTENTION, uiText(R.string.check_clock_suspect), CheckFix.DateSettings))
            return false
        } catch (e: Exception) {
            post(CheckId.INTERNET, CheckResult(CheckStatus.ATTENTION, uiText(R.string.check_internet_unreachable), CheckFix.NetworkSettings))
            post(CheckId.CLOCK, info(R.string.check_needs_internet))
            return false
        }
        val ms = SystemClock.elapsedRealtime() - started
        post(
            CheckId.INTERNET,
            if (ms > SLOW_CONNECTION_MS) CheckResult(CheckStatus.ATTENTION, uiText(R.string.check_internet_slow, ms / 1000.0), CheckFix.NetworkSettings)
            else ok(R.string.check_internet_ok)
        )
        post(
            CheckId.CLOCK, when {
                serverTime == null -> info(R.string.check_clock_unknown)
                clockWrong(serverTime, System.currentTimeMillis()) ->
                    CheckResult(CheckStatus.ATTENTION, uiText(R.string.check_clock_wrong), CheckFix.DateSettings)
                else -> ok(R.string.check_clock_ok)
            }
        )
        return true
    }

    private fun serverTime(url: String): Long? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "HEAD"
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        try {
            if (conn.responseCode >= 500) throw IOException("Server returned ${conn.responseCode}")
            return conn.getHeaderFieldDate("Date", 0L).takeIf { it > 0 }
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun checkHub(ctx: Context, reached: Boolean) {
        val version = BuildConfig.VERSION_NAME
        if (HubUpdater.state.value is HubUpdateState.NotConfigured || !reached) {
            post(CheckId.HUB, info(R.string.check_hub_version, version))
            return
        }
        HubUpdater.check(ctx)
        val state = withTimeoutOrNull(HUB_CHECK_WAIT_MS) { HubUpdater.state.first { it !is HubUpdateState.Checking } }
        post(
            CheckId.HUB, when (state) {
                is HubUpdateState.Available -> CheckResult(
                    CheckStatus.ATTENTION,
                    uiText(R.string.check_hub_update, state.release.versionName ?: state.release.versionCode.toString(), version),
                    CheckFix.UpdateHub(state.release)
                )
                is HubUpdateState.UpToDate -> ok(R.string.check_hub_latest, version)
                is HubUpdateState.Refused -> CheckResult(CheckStatus.ATTENTION, state.message)
                else -> info(R.string.check_hub_unknown, version)
            }
        )
    }

    private suspend fun checkCatalog(ctx: Context, reached: Boolean) {
        if (!reached) {
            post(CheckId.CATALOG, info(R.string.check_catalog_count, Catalog.all(ctx).size))
            return
        }
        val result = try {
            if (CatalogUpdater.refreshNow(ctx)) ok(R.string.check_catalog_updated, Catalog.all(ctx).size)
            else ok(R.string.check_catalog_latest, Catalog.all(ctx).size)
        } catch (e: Exception) {
            info(R.string.check_catalog_unknown, Catalog.all(ctx).size)
        }
        post(CheckId.CATALOG, result)
    }

    private fun checkStorage(ctx: Context) {
        val size = { bytes: Long -> Formatter.formatShortFileSize(ctx, bytes) }
        val free = freeBytes(ctx, ctx.cacheDir)
        val leftovers = HubDownloads.leftoverBytes(ctx)
        post(
            CheckId.STORAGE, when {
                free == null -> info(R.string.check_unknown)
                free < LOW_STORAGE_BYTES -> CheckResult(
                    CheckStatus.ATTENTION, uiText(R.string.check_storage_low, size(free)),
                    if (leftovers > 0) CheckFix.FreeSpace else CheckFix.OpenStorage
                )
                leftovers > 0 -> CheckResult(CheckStatus.INFO, uiText(R.string.check_storage_leftovers, size(free), size(leftovers)), CheckFix.FreeSpace)
                else -> ok(R.string.check_storage_ok, size(free))
            }
        )
    }

    private fun checkMemory(ctx: Context) {
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return post(CheckId.MEMORY, info(R.string.check_unknown))
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val free = Formatter.formatShortFileSize(ctx, mem.availMem)
        val total = Formatter.formatShortFileSize(ctx, mem.totalMem)
        post(
            CheckId.MEMORY,
            if (memoryLow(mem.availMem, mem.totalMem, mem.lowMemory))
                CheckResult(CheckStatus.ATTENTION, uiText(R.string.check_memory_low, free, total), CheckFix.CloseApps)
            else ok(R.string.check_memory_ok, free, total)
        )
    }

    // App updates and apps from other sources, from one pass over the installed catalog apps.
    private fun checkApps(ctx: Context) {
        val installed = Catalog.all(ctx).mapNotNull { app -> AppLauncher.installedVersionCode(ctx, app.packageName)?.let { app to it } }
        if (installed.isEmpty()) {
            post(CheckId.UPDATES, info(R.string.check_apps_none))
            post(CheckId.SOURCES, info(R.string.check_apps_none))
            return
        }
        val other = otherSourceApps(installed.map { it.first }) { installedSigners(ctx, it) }
        val updates = installed.filter { (app, code) -> app !in other && needsUpdate(code, app.versionCode) }.map { it.first }
        post(
            CheckId.UPDATES,
            if (updates.isEmpty()) ok(R.string.check_updates_ok, installed.size)
            else CheckResult(CheckStatus.ATTENTION, uiText(R.string.check_updates_some, updates.size, shortNames(updates)), CheckFix.UpdateAll(updates))
        )
        post(
            CheckId.SOURCES,
            if (other.isEmpty()) ok(R.string.check_sources_ok)
            else CheckResult(CheckStatus.ATTENTION, uiText(R.string.check_sources_other, shortNames(other)), CheckFix.Uninstall(other.first()))
        )
    }

    @Suppress("DEPRECATION")
    private fun installedSigners(ctx: Context, pkg: String): List<String>? = try {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        signerDigests(ctx.packageManager.getPackageInfo(pkg, flags))
    } catch (e: PackageManager.NameNotFoundException) { null }

    private fun checkDevice(ctx: Context) {
        val model = listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() && !it.equals("unknown", ignoreCase = true) }
            .distinct().joinToString(" ")
        val bits = ctx.getString(if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) R.string.check_device_64 else R.string.check_device_32)
        post(
            CheckId.DEVICE,
            info(R.string.check_device_detail, model, Build.VERSION.RELEASE, bits, Catalog.all(ctx).size, Catalog.meta(ctx).total)
        )
    }
}

// Opens the first of these system screens the TV has. False when it has none of them.
private fun openSystemScreen(context: Context, vararg actions: String): Boolean = actions.any { action ->
    try {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: Exception) { false }
}

@StringRes
private fun fixLabel(fix: CheckFix): Int = when (fix) {
    CheckFix.NetworkSettings -> R.string.btn_network_settings
    CheckFix.DateSettings -> R.string.btn_date_settings
    CheckFix.InstallPermission -> R.string.btn_allow
    is CheckFix.UpdateHub -> R.string.btn_install_update
    CheckFix.FreeSpace -> R.string.btn_free_space
    CheckFix.OpenStorage -> R.string.btn_open_storage
    CheckFix.CloseApps -> R.string.btn_close_apps
    is CheckFix.UpdateAll -> R.string.btn_update_all
    is CheckFix.Uninstall -> R.string.btn_uninstall
}

@Composable
fun TroubleshootScreen(resumeTick: Int, onStorage: () -> Unit, onCloseApps: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val results by Checkup.results.collectAsState()
    val running by Checkup.running.collectAsState()
    val batch by InstallCoordinator.batch.collectAsState()
    var fixFailed by remember { mutableStateOf(false) }
    val focus = rememberInitialFocus()

    // Back from a fix (a system screen, Storage or Close running apps): check again so the rows
    // show what changed. Not while Update all is running, since every install prompt resumes the Hub.
    LaunchedEffect(resumeTick) {
        if (results.isNotEmpty() && batch == null) Checkup.run(context)
    }

    val runFix = { fix: CheckFix ->
        fixFailed = false
        when (fix) {
            CheckFix.NetworkSettings ->
                fixFailed = !openSystemScreen(context, Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS)
            CheckFix.DateSettings -> fixFailed = !openSystemScreen(context, Settings.ACTION_DATE_SETTINGS, Settings.ACTION_SETTINGS)
            CheckFix.InstallPermission -> fixFailed = !openInstallPermissionSettings(context)
            is CheckFix.UpdateHub -> PinLock.guard(context) { InstallCoordinator.start(context, HubUpdater.asApp(context, fix.release)) }
            CheckFix.FreeSpace -> { HubDownloads.clear(context); Checkup.run(context) }
            CheckFix.OpenStorage -> onStorage()
            CheckFix.CloseApps -> onCloseApps()
            is CheckFix.UpdateAll -> PinLock.guard(context) { InstallCoordinator.updateAll(context, fix.apps) }
            is CheckFix.Uninstall -> fixFailed = !AppLauncher.uninstall(context, fix.app.packageName)
        }
    }

    val problems = results.values.count { it.status == CheckStatus.ATTENTION }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.troubleshoot_title), fontSize = 38.sp, lineHeight = 44.sp, color = HubColors.Text)
                val (summary, color) = when {
                    running -> stringResource(R.string.check_running) to HubColors.Muted
                    results.isEmpty() -> stringResource(R.string.troubleshoot_desc) to HubColors.Muted
                    problems == 0 -> stringResource(R.string.check_all_good) to HubColors.GlowGreen
                    else -> context.resources.getQuantityString(R.plurals.check_problems, problems, problems) to HubColors.Warning
                }
                Text(summary, fontSize = 18.sp, lineHeight = 24.sp, color = color, modifier = Modifier.widthIn(max = 760.dp))
            }
            HubButton(
                stringResource(
                    when {
                        running -> R.string.btn_checking
                        results.isEmpty() -> R.string.btn_run_checkup
                        else -> R.string.btn_check_again
                    }
                ),
                { Checkup.run(context) }, Modifier.focusRequester(focus), enabled = !running
            )
            HubButton(stringResource(R.string.btn_done), onDone, color = HubColors.Muted)
        }
        if (fixFailed) Text(stringResource(R.string.check_fix_failed), fontSize = 16.sp, color = HubColors.Warning)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(CheckId.values().filter { it in results }, key = { it.name }) { id ->
                val result = results.getValue(id)
                // Update all shows its progress in place of the result it fixed.
                val detail = if (id == CheckId.UPDATES && batch != null)
                    stringResource(R.string.update_all_running, batch!!.done + 1, batch!!.total)
                else result.detail.text()
                CheckRow(stringResource(id.title), detail, result.status, result.fix?.takeIf { batch == null || id != CheckId.UPDATES }) {
                    result.fix?.let(runFix)
                }
            }
        }
    }
}

// Every row is focusable so the D-pad can scroll through all results; OK runs the row's fix.
@Composable
private fun CheckRow(title: String, detail: String, status: CheckStatus, fix: CheckFix?, onFix: () -> Unit) {
    val color = when (status) {
        CheckStatus.OK -> HubColors.GlowGreen
        CheckStatus.ATTENTION -> HubColors.Warning
        CheckStatus.CHECKING, CheckStatus.INFO -> HubColors.Muted
    }
    HubCard(onClick = onFix, modifier = Modifier.fillMaxWidth(), tint = if (status == CheckStatus.ATTENTION) HubColors.Warning else HubColors.Line) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StatusDot(color, Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 20.sp, color = HubColors.Text)
                Text(detail, fontSize = 15.sp, lineHeight = 20.sp, color = if (status == CheckStatus.OK) HubColors.Muted else color)
            }
            if (fix != null) Text(
                stringResource(fixLabel(fix)), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = HubColors.GlowBlue, maxLines = 1
            )
        }
    }
}

@Composable
private fun StatusDot(color: Color, modifier: Modifier) {
    Canvas(modifier) { drawCircle(color) }
}
