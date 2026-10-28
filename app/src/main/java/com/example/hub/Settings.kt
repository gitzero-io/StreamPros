@file:OptIn(ExperimentalTvMaterial3Api::class)
package com.example.hub

import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.*

@Composable
fun SettingsScreen(
    onGuidedInstall: () -> Unit,
    onResetAppData: () -> Unit,
    onAbout: () -> Unit,
    onLanguage: () -> Unit,
    onCloseApps: () -> Unit,
    onStorage: () -> Unit,
    onTroubleshoot: () -> Unit,
    onPlans: () -> Unit
) {
    val context = LocalContext.current
    val focus = rememberInitialFocus()
    var confirmReset by remember { mutableStateOf(false) }
    var hideMature by remember { mutableStateOf(HubSettings.hideMature(context)) }
    val catalogInfo = remember { catalogSummary(context) }
    var pinSet by remember { mutableStateOf(PinLock.isSet(context)) }
    var choosePin by remember { mutableStateOf(false) }
    // Setting or turning off the PIN swaps the row's buttons; focus stays on the row.
    val pinFocus = remember { FocusRequester() }
    var refocusPin by remember { mutableStateOf(false) }
    LaunchedEffect(pinSet, choosePin, refocusPin) {
        if (refocusPin && !choosePin) { runCatching { pinFocus.requestFocus() }; refocusPin = false }
    }
    var leftovers by remember { mutableLongStateOf(HubDownloads.leftoverBytes(context)) }
    var freed by remember { mutableStateOf<Long?>(null) }
    val size = { bytes: Long -> android.text.format.Formatter.formatShortFileSize(context, bytes) }

    // Scrolls so the last row stays reachable on small or overscanned screens.
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.settings_title), fontSize = 38.sp, lineHeight = 44.sp, color = HubColors.Text)
        MembershipRow(onPlans)
        SettingRow(stringResource(R.string.troubleshoot_title), stringResource(R.string.settings_troubleshoot_desc)) {
            HubButton(stringResource(R.string.btn_open), onTroubleshoot, Modifier.focusRequester(focus))
        }
        SettingRow(stringResource(R.string.settings_guided), stringResource(R.string.settings_guided_desc)) {
            HubButton(stringResource(R.string.btn_start), onGuidedInstall)
        }
        SettingRow(stringResource(R.string.settings_language), LanguagePrefs.current(context).nativeName) {
            HubButton(stringResource(R.string.btn_change), onLanguage)
        }
        SettingRow(
            stringResource(R.string.settings_hide_mature),
            stringResource(if (hideMature) R.string.settings_hide_mature_on else R.string.settings_hide_mature_off)
        ) {
            HubButton(
                stringResource(if (hideMature) R.string.btn_turn_off else R.string.btn_turn_on),
                { hideMature = !hideMature; HubSettings.setHideMature(context, hideMature) }
            )
        }
        SettingRow(stringResource(R.string.close_title), stringResource(R.string.settings_close_desc)) {
            HubButton(stringResource(R.string.btn_open), onCloseApps)
        }
        SettingRow(stringResource(R.string.storage_title), stringResource(R.string.settings_storage_desc)) {
            HubButton(stringResource(R.string.btn_open), onStorage)
        }
        SettingRow(
            stringResource(R.string.settings_free_space),
            when {
                freed != null -> stringResource(R.string.free_space_done, size(freed!!))
                leftovers > 0 -> stringResource(R.string.free_space_desc, size(leftovers))
                else -> stringResource(R.string.free_space_none)
            },
            if (freed != null) HubColors.GlowGreen else HubColors.Muted
        ) {
            HubButton(
                stringResource(R.string.btn_clear),
                { freed = HubDownloads.clear(context); leftovers = HubDownloads.leftoverBytes(context) },
                enabled = leftovers > 0
            )
        }
        SettingRow(
            stringResource(R.string.settings_pin),
            stringResource(if (pinSet) R.string.pin_on_desc else R.string.pin_off_desc),
            if (pinSet) HubColors.GlowGreen else HubColors.Muted
        ) {
            if (pinSet) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HubButton(stringResource(R.string.btn_change), { PinLock.guard(context) { choosePin = true } }, Modifier.focusRequester(pinFocus))
                HubButton(
                    stringResource(R.string.btn_turn_off),
                    { PinLock.guard(context) { PinLock.clear(context); pinSet = false; refocusPin = true } },
                    color = HubColors.Muted
                )
            } else HubButton(stringResource(R.string.btn_set_pin), { choosePin = true }, Modifier.focusRequester(pinFocus))
        }
        HubUpdateRow()
        SettingRow(stringResource(R.string.settings_catalog), catalogInfo) {}
        SettingRow(stringResource(R.string.settings_about), stringResource(R.string.settings_about_desc)) {
            HubButton(stringResource(R.string.btn_open), onAbout)
        }
        SettingRow(stringResource(R.string.settings_reset), stringResource(R.string.settings_reset_desc)) {
            HubButton(stringResource(R.string.btn_reset), { PinLock.guard(context) { confirmReset = true } }, color = HubColors.Warning)
        }
    }

    if (choosePin) PinSetupDialog(
        onDone = { PinLock.set(context, it); pinSet = true; choosePin = false; refocusPin = true },
        onDismiss = { choosePin = false; refocusPin = true }
    )
    if (confirmReset) ResetDialog(onConfirm = { confirmReset = false; onResetAppData() }, onDismiss = { confirmReset = false })
}

// Hub update: CHECK, then UPDATE (with progress, CANCEL and TRY AGAIN from the install flow) when a
// newer release is published. Shared by Settings and the Home notice. One button that changes
// label, so focus stays on it.
@Composable
fun HubUpdateRow(buttonModifier: Modifier = Modifier) {
    val context = LocalContext.current
    val hub by HubUpdater.state.collectAsState()
    val installs by InstallCoordinator.states.collectAsState()
    val install = installs[context.packageName]
    val version = remember { appVersion(context) }
    val release = (hub as? HubUpdateState.Available)?.release
    val updateApp = remember(release) { release?.let { HubUpdater.asApp(context, it) } }
    val current = remember { HubUpdater.currentVersionCode(context) }

    // Opening the screen refreshes quietly (offline keeps the last result), unless an update is
    // already offered or installing.
    LaunchedEffect(Unit) {
        if (hub !is HubUpdateState.Available && install == null) HubUpdater.check(context, quiet = true)
    }

    val (description, color) = when {
        hub is HubUpdateState.NotConfigured -> stringResource(R.string.update_not_configured, version) to HubColors.Muted
        updateApp != null && install != null ->
            statusText(updateApp, install, current).text() to statusColor(updateApp, install, current)
        release != null -> {
            val name = release.versionName ?: release.versionCode.toString()
            val notes = release.notes
            (if (notes != null) stringResource(R.string.update_available_notes, name, notes) else stringResource(R.string.update_available, name)) to
                HubColors.GlowBlue
        }
        hub is HubUpdateState.Checking -> stringResource(R.string.update_checking) to HubColors.Muted
        hub is HubUpdateState.UpToDate -> stringResource(R.string.update_latest, version) to HubColors.GlowGreen
        hub is HubUpdateState.Refused -> (hub as HubUpdateState.Refused).message.text() to HubColors.Warning
        hub is HubUpdateState.Failed -> (hub as HubUpdateState.Failed).message.text() to HubColors.Warning
        else -> stringResource(R.string.update_current, version) to HubColors.Muted
    }
    SettingRow(stringResource(R.string.update_title), description, color, progress = install) {
        when {
            hub is HubUpdateState.NotConfigured -> {}
            updateApp != null -> {
                val action = appAction(updateApp, install, current)
                val label = if (action.label == R.string.action_update) R.string.btn_install_update else action.label
                HubButton(stringResource(label), { action.run(context) }, buttonModifier, color = action.color, enabled = action.enabled)
            }
            else -> HubButton(
                stringResource(if (hub is HubUpdateState.Checking) R.string.btn_checking else R.string.btn_check),
                { HubUpdater.check(context) }, buttonModifier,
                enabled = hub !is HubUpdateState.Checking
            )
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    description: String,
    descriptionColor: Color = HubColors.Muted,
    progress: InstallState? = null,
    action: @Composable () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().background(HubColors.Surface, RoundedCornerShape(14.dp))
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 22.sp, color = HubColors.Text)
            Text(description, fontSize = 15.sp, color = descriptionColor)
            InstallProgress(progress, Modifier.padding(top = 8.dp).fillMaxWidth(0.6f))
        }
        action()
    }
}

// The plan this device is on. During the trial it offers the plans (with this device's checkout code).
@Composable
private fun MembershipRow(onPlans: () -> Unit) {
    val context = LocalContext.current
    val license by License.state.collectAsState()
    val unlocked = license as? LicenseState.Unlocked ?: return
    val until = unlocked.expiresAt?.let { at ->
        val date = android.text.format.DateFormat.getMediumDateFormat(context).format(java.util.Date(at))
        val time = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(at))
        "$date $time"
    }
    val description = when (unlocked.plan) {
        Plan.LIFETIME -> stringResource(R.string.membership_lifetime)
        Plan.MONTHLY -> stringResource(R.string.membership_monthly, until.orEmpty()) + " " + stringResource(R.string.membership_cancel_hint)
        Plan.TRIAL -> stringResource(R.string.membership_trial, until.orEmpty()) +
            unlocked.freeInstalls?.let { " " + stringResource(R.string.membership_free_installs, it.left, it.limit) }.orEmpty()
    }
    SettingRow(stringResource(R.string.settings_membership), description) {
        if (unlocked.plan == Plan.TRIAL && unlocked.code != null) HubButton(stringResource(R.string.btn_see_plans), onPlans)
    }
}

// Destructive, so CANCEL has focus and Back also cancels.
@Composable
private fun ResetDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        val focus = rememberInitialFocus()
        Column(
            Modifier.width(560.dp).background(HubColors.SurfaceRaised, RoundedCornerShape(20.dp)).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(stringResource(R.string.reset_title), fontSize = 28.sp, lineHeight = 34.sp, color = HubColors.Text)
            Text(
                stringResource(R.string.reset_body),
                fontSize = 18.sp, lineHeight = 26.sp, color = HubColors.Muted
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 8.dp)) {
                HubButton(stringResource(R.string.btn_cancel), onDismiss, Modifier.focusRequester(focus), color = HubColors.Muted)
                HubButton(stringResource(R.string.btn_reset), onConfirm, color = HubColors.Warning)
            }
        }
    }
}

// Everything the user has set in HUB. The install permission and installed apps are untouched.
object AppData {
    fun reset(context: Context) {
        UserPrefs.clearAll(context)
        SetupPrefs.clear(context)
        HubSettings.clear(context)
        LanguagePrefs.clear(context)
        PinLock.clear(context)
    }
}

private fun catalogSummary(context: Context): String {
    val meta = Catalog.meta(context)
    val shown = Catalog.all(context).size
    val apps = if (shown == meta.total) context.resources.getQuantityString(R.plurals.catalog_apps, meta.total, meta.total)
    else context.getString(R.string.catalog_apps_on_tv, meta.total, shown)
    val updated = meta.updated?.let { context.getString(R.string.catalog_updated, shortDate(it)) }
    val trending = Trending.get(context).updated?.let { context.getString(R.string.catalog_trending, shortDate(it)) }
    return listOfNotNull(apps, updated, trending).joinToString(" · ")
}

internal fun appVersion(context: Context): String = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    context.getString(R.string.app_version, info.versionName, code)
}.getOrDefault(context.getString(R.string.version_unknown))
