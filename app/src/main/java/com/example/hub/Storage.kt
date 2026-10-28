@file:OptIn(ExperimentalTvMaterial3Api::class)
package com.example.hub

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import java.io.File

// Installed size of an app's files (base APK plus splits). Android only reports data and cache
// sizes of other apps to apps with usage access, so those are left to the App info page.
fun installedBytes(context: Context, pkg: String): Long? = runCatching {
    val info = context.packageManager.getApplicationInfo(pkg, 0)
    (listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()).sumOf { File(it).length() }
}.getOrNull()

// Apps with their sizes, biggest first.
fun bySize(apps: List<HubApp>, size: (HubApp) -> Long?): List<Pair<HubApp, Long>> =
    apps.mapNotNull { app -> size(app)?.let { app to it } }.sortedByDescending { it.second }

// Free and total bytes on the storage apps install to.
fun tvStorage(): Pair<Long, Long>? = runCatching {
    val stat = StatFs(Environment.getDataDirectory().path)
    stat.availableBytes to stat.totalBytes
}.getOrNull()

// Package a file in the Hub's download folder belongs to: <pkg>.apk, <pkg>.part or <pkg>.part.info.
fun downloadFilePackage(name: String): String? =
    listOf(".part.info", ".part", ".apk").firstOrNull { name.endsWith(it) }?.let { name.removeSuffix(it) }?.takeIf { it.isNotEmpty() }

// Downloads the Hub keeps in cache/apks: an APK is deleted once installed, but a download that
// stopped keeps its partial file so TRY AGAIN can resume. Files of a download in progress are
// never touched.
object HubDownloads {
    private const val STALE_MS = 7L * 24 * 60 * 60 * 1000

    private fun leftovers(context: Context): List<File> {
        val active = InstallCoordinator.states.value
            .filterValues { it is InstallState.Downloading || it is InstallState.Installing }.keys
        return File(context.cacheDir, "apks").listFiles().orEmpty()
            .filter { it.isFile && downloadFilePackage(it.name) !in active }
    }

    fun leftoverBytes(context: Context): Long = leftovers(context).sumOf { it.length() }

    // Deletes every leftover download. Returns the bytes freed.
    fun clear(context: Context): Long = leftovers(context).sumOf { f -> f.length().takeIf { f.delete() } ?: 0L }

    // On launch: drops downloads untouched for a week.
    fun pruneStale(context: Context, now: Long = System.currentTimeMillis()) {
        leftovers(context).filter { now - it.lastModified() > STALE_MS }.forEach { it.delete() }
    }
}

// Storage (from Settings): free space on the TV, then installed catalog apps biggest first, each
// with APP INFO (data and cache, Clear cache) and UNINSTALL. The first button has focus; Back
// returns to Settings like DONE.
@Composable
fun StorageScreen(resumeTick: Int, onDone: () -> Unit) {
    val context = LocalContext.current
    val catalog = remember { Catalog.get(context) }
    val apps = remember(resumeTick) { bySize(catalog) { installedBytes(context, it.packageName) } }
    val storage = remember(resumeTick) { tvStorage() }
    var openFailed by remember { mutableStateOf<String?>(null) }
    val focus = rememberInitialFocus()
    // After an uninstall the app's row is gone, so focus goes back to the top of the list.
    var lastCount by remember { mutableIntStateOf(apps.size) }
    LaunchedEffect(apps.size) {
        if (apps.size < lastCount) runCatching { focus.requestFocus() }
        lastCount = apps.size
    }
    val size = { bytes: Long -> Formatter.formatShortFileSize(context, bytes) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.storage_title), fontSize = 38.sp, lineHeight = 44.sp, color = HubColors.Text)
                storage?.let { (free, total) ->
                    Text(stringResource(R.string.storage_free, size(free), size(total)), fontSize = 18.sp, color = HubColors.Text)
                }
                Text(
                    stringResource(R.string.storage_desc), fontSize = 16.sp, lineHeight = 22.sp, color = HubColors.Muted,
                    modifier = Modifier.widthIn(max = 760.dp)
                )
            }
            HubButton(stringResource(R.string.btn_done), onDone, if (apps.isEmpty()) Modifier.focusRequester(focus) else Modifier, color = HubColors.Muted)
        }
        openFailed?.let { Text(stringResource(R.string.close_info_failed, it), fontSize = 16.sp, color = HubColors.Warning) }
        if (apps.isEmpty()) Text(stringResource(R.string.storage_none), fontSize = 18.sp, color = HubColors.Muted)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(apps, key = { it.first.packageName }) { (app, bytes) ->
                StorageRow(
                    app, size(bytes), if (app == apps.first().first) focus else null,
                    onAppInfo = { openFailed = if (AppCloser.openAppInfo(context, app.packageName)) null else app.name }
                )
            }
        }
    }
}

@Composable
private fun StorageRow(app: HubApp, size: String, focus: FocusRequester?, onAppInfo: () -> Unit) {
    val infoFocus = remember { FocusRequester() }
    var uninstallFailed by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(HubColors.Surface, RoundedCornerShape(14.dp))
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AppArtwork(app, app.categories.first().tint, Modifier.padding(end = 6.dp).size(48.dp))
        Column(Modifier.weight(1f)) {
            AppNameWithVersion(app, nameSize = 20.sp, versionSize = 14.sp)
            if (uninstallFailed) Text(stringResource(R.string.uninstall_failed), fontSize = 14.sp, color = HubColors.Warning)
            else app.description?.let {
                Text(it, fontSize = 14.sp, color = HubColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(size, fontSize = 20.sp, color = HubColors.Text, modifier = Modifier.padding(horizontal = 12.dp))
        val infoDescription = stringResource(R.string.cd_app_info, app.name)
        HubButton(
            stringResource(R.string.btn_app_info), onAppInfo,
            Modifier.semantics { contentDescription = infoDescription }.focusRequester(infoFocus)
                .then(if (focus != null) Modifier.focusRequester(focus) else Modifier),
            color = HubColors.Muted
        )
        // The row disappears once the app is gone (the list refreshes on resume).
        UninstallButton(
            app, visible = true, installed = true, returnFocus = infoFocus,
            onOpenFailed = { uninstallFailed = true }, label = stringResource(R.string.btn_uninstall)
        )
    }
}
