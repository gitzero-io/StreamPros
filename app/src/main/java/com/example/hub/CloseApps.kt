@file:OptIn(ExperimentalTvMaterial3Api::class)
package com.example.hub

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.tv.material3.*

// Closing other apps. Android only lets the system force stop an app, so the Hub can:
// - ask Android to end catalog apps' background processes (killBackgroundProcesses). Android 14+
//   limits that call to the caller's own processes, so it does nothing to other apps there;
// - open an app's App info page, where the user presses Force stop. That works on every TV.
// Android doesn't tell other apps which apps are running, so the list is every installed catalog app.
object AppCloser {
    fun canCloseBackground(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk < 34

    // Returns how many apps Android was asked to close, or 0 when the TV doesn't allow it.
    fun closeBackground(context: Context, pkgs: List<String>): Int {
        if (!canCloseBackground()) return 0
        val am = context.getSystemService(ActivityManager::class.java) ?: return 0
        return pkgs.count { pkg -> runCatching { am.killBackgroundProcesses(pkg) }.isSuccess }
    }

    // Opens the system App info page for `pkg`. Returns false if the TV has no screen for it.
    fun openAppInfo(context: Context, pkg: String): Boolean = try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$pkg".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (e: Exception) { false }
}

// Close running apps (from Settings and the end of the My Apps row): CLOSE BACKGROUND APPS where
// Android allows it, then a FORCE STOP button per installed app that opens its App info page.
// The first button has focus; Back returns to the previous screen (HubRoot's BackHandler) like DONE.
@Composable
fun CloseAppsScreen(resumeTick: Int, onDone: () -> Unit) {
    val context = LocalContext.current
    val catalog = remember { Catalog.get(context) }
    val installed = remember(resumeTick) { catalog.filter { AppLauncher.isInstalled(context, it.packageName) } }
    val canClose = remember { AppCloser.canCloseBackground() }
    var closed by remember { mutableStateOf<Int?>(null) }
    var openFailed by remember { mutableStateOf<String?>(null) }
    val focus = rememberInitialFocus()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.close_title), fontSize = 38.sp, lineHeight = 44.sp, color = HubColors.Text)
                Text(
                    stringResource(if (canClose) R.string.close_desc else R.string.close_desc_manual),
                    fontSize = 16.sp, lineHeight = 22.sp, color = HubColors.Muted, modifier = Modifier.widthIn(max = 760.dp)
                )
            }
            if (canClose && installed.isNotEmpty()) {
                HubButton(
                    stringResource(R.string.btn_close_background),
                    { closed = AppCloser.closeBackground(context, installed.map { it.packageName }) },
                    Modifier.focusRequester(focus)
                )
                HubButton(stringResource(R.string.btn_done), onDone, color = HubColors.Muted)
            } else {
                HubButton(stringResource(R.string.btn_done), onDone, Modifier.focusRequester(focus), color = HubColors.Muted)
            }
        }
        closed?.let {
            Text(
                context.resources.getQuantityString(R.plurals.close_done, it, it),
                fontSize = 16.sp, color = HubColors.GlowGreen
            )
        }
        openFailed?.let { Text(stringResource(R.string.close_info_failed, it), fontSize = 16.sp, color = HubColors.Warning) }
        if (installed.isEmpty()) Text(stringResource(R.string.close_none), fontSize = 18.sp, color = HubColors.Muted)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(installed, key = { it.packageName }) { app ->
                CloseAppRow(app) {
                    openFailed = if (AppCloser.openAppInfo(context, app.packageName)) null else app.name
                }
            }
        }
    }
}

@Composable
private fun CloseAppRow(app: HubApp, onForceStop: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(HubColors.Surface, RoundedCornerShape(14.dp))
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppArtwork(app, app.categories.first().tint, Modifier.padding(end = 18.dp).size(48.dp))
        Column(Modifier.weight(1f)) {
            AppNameWithVersion(app, nameSize = 20.sp, versionSize = 14.sp)
            app.description?.let {
                Text(it, fontSize = 14.sp, color = HubColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val description = stringResource(R.string.cd_force_stop, app.name)
        HubButton(
            stringResource(R.string.btn_force_stop), onForceStop,
            Modifier.semantics { contentDescription = description }, color = HubColors.Muted
        )
    }
}

// Last tile of the My Apps row: opens Close running apps.
@Composable
fun CloseAppsCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    HubCard(onClick = onClick, modifier = modifier.width(AppCardWidth).height(AppCardHeight)) {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            StopIcon(HubColors.GlowBlue, Modifier.size(52.dp))
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.close_card), fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                color = HubColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(stringResource(R.string.close_card_note), fontSize = 13.sp, lineHeight = 17.sp, color = HubColors.Muted, maxLines = 2)
        }
    }
}

// Ring with a rounded square inside (a "stop" sign), drawn so the app needs no icon library.
@Composable
private fun StopIcon(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val d = size.minDimension
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(color, d * 0.42f, c, style = Stroke(d * 0.07f))
        val s = d * 0.32f
        drawRoundRect(color, topLeft = Offset(c.x - s / 2, c.y - s / 2), size = Size(s, s), cornerRadius = CornerRadius(d * 0.05f))
    }
}
