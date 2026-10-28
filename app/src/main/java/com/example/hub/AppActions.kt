package com.example.hub

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color

// What an app's single action button shows and does for its current state. Every state maps to
// one button so the same composable stays in place (and keeps D-pad focus) as the state changes.
data class AppAction(
    @StringRes val label: Int,
    val color: Color,
    val enabled: Boolean,
    val run: (Context) -> Unit
)

// True when the app is installed and the catalog has a newer versionCode.
fun needsUpdate(installedVersionCode: Long?, catalogVersionCode: Long?): Boolean =
    installedVersionCode != null && catalogVersionCode != null && catalogVersionCode > installedVersionCode

// installedVersion is null when the app isn't installed. An active download/install/failure wins
// over "installed" so an update in progress shows its progress instead of OPEN. With
// offerUpdates = false (the setup guide) an installed app always shows Installed / OPEN.
// Installs and updates ask for the PIN first when one is set (PinLock).
fun appAction(app: HubApp, state: InstallState?, installedVersion: Long?, offerUpdates: Boolean = true): AppAction {
    val installed = installedVersion != null
    val update = offerUpdates && needsUpdate(installedVersion, app.versionCode)
    return when {
        state is InstallState.Downloading ->
            AppAction(R.string.btn_cancel, HubColors.Muted, true) { InstallCoordinator.cancel(app.packageName) }
        state is InstallState.Installing -> AppAction(R.string.action_installing, HubColors.Muted, false) {}
        state is InstallState.Failed && (!installed || update) ->
            AppAction(R.string.action_try_again, HubColors.Warning, true) { ctx -> PinLock.guard(ctx) { InstallCoordinator.start(ctx, app) } }
        update -> AppAction(R.string.action_update, HubColors.GlowBlue, true) { ctx -> PinLock.guard(ctx) { InstallCoordinator.start(ctx, app) } }
        installed -> AppAction(R.string.btn_open, HubColors.GlowGreen, true) { AppLauncher.launch(it, app.packageName) }
        else -> AppAction(R.string.action_get_app, HubColors.GlowBlue, true) { ctx -> PinLock.guard(ctx) { InstallCoordinator.start(ctx, app) } }
    }
}

fun statusText(app: HubApp, state: InstallState?, installedVersion: Long?, offerUpdates: Boolean = true): UiText {
    val installed = installedVersion != null
    val update = offerUpdates && needsUpdate(installedVersion, app.versionCode)
    return when {
        state is InstallState.Downloading ->
            if (state.percent >= 0) uiText(R.string.status_downloading_percent, state.percent) else uiText(R.string.status_downloading)
        state is InstallState.Installing -> uiText(R.string.status_confirm)
        state is InstallState.Failed && (!installed || update) -> state.message
        update -> app.version?.let { uiText(R.string.status_update_available_version, it) } ?: uiText(R.string.status_update_available)
        installed -> uiText(R.string.status_installed)
        else -> uiText(R.string.status_not_installed)
    }
}

fun statusColor(app: HubApp, state: InstallState?, installedVersion: Long?, offerUpdates: Boolean = true): Color {
    val installed = installedVersion != null
    val update = offerUpdates && needsUpdate(installedVersion, app.versionCode)
    return when {
        state is InstallState.Failed && (!installed || update) -> HubColors.Warning
        state is InstallState.Downloading || state is InstallState.Installing -> HubColors.Muted
        update -> HubColors.GlowBlue
        installed -> HubColors.GlowGreen
        else -> HubColors.Muted
    }
}
