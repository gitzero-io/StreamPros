@file:OptIn(ExperimentalTvMaterial3Api::class)
package com.example.hub

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*

// True when an UNINSTALL button belongs next to the app: it's installed and nothing is
// downloading or installing for it (the main button is CANCEL / INSTALLING then).
fun canUninstall(state: InstallState?, installedVersion: Long?): Boolean =
    installedVersion != null && state !is InstallState.Downloading && state !is InstallState.Installing

// Trash button that opens the system uninstall confirmation. `label` adds text after the icon
// (App Detail); without it the button is icon-only (app rows). Call it on every composition and
// pass `visible`, so it remembers a pending uninstall while hidden: once the app is gone the
// button disappears and focus moves to `returnFocus` (the main action) instead of being lost.
@Composable
fun UninstallButton(
    app: HubApp,
    visible: Boolean,
    installed: Boolean,
    returnFocus: FocusRequester,
    onOpenFailed: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null
) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf(false) }
    LaunchedEffect(installed) {
        if (pending && !installed) {
            pending = false
            runCatching { returnFocus.requestFocus() }
        }
    }
    if (!visible) return
    val onClick = {
        PinLock.guard(context) { if (AppLauncher.uninstall(context, app.packageName)) pending = true else onOpenFailed() }
    }
    val description = stringResource(R.string.cd_uninstall, app.name)
    val buttonModifier = modifier.semantics { contentDescription = description }
    if (label == null) HubIconButton(onClick, buttonModifier, color = HubColors.Muted) {
        TrashIcon(HubColors.Muted, Modifier.size(16.dp))
    } else HubButton(onClick, buttonModifier, color = HubColors.Muted) {
        TrashIcon(HubColors.Muted, Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, softWrap = false)
    }
}

// Trash can (lid, handle, body), drawn so the app needs no icon library.
@Composable
private fun TrashIcon(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.11f
        drawLine(color, Offset(w * 0.1f, h * 0.2f), Offset(w * 0.9f, h * 0.2f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.38f, h * 0.06f), Offset(w * 0.62f, h * 0.06f), stroke, StrokeCap.Round)
        drawRoundRect(
            color, topLeft = Offset(w * 0.2f, h * 0.3f), size = Size(w * 0.6f, h * 0.66f),
            cornerRadius = CornerRadius(w * 0.08f)
        )
    }
}
