@file:OptIn(ExperimentalTvMaterial3Api::class)
package com.example.hub

import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*

// Main action buttons share one width so the icon buttons beside them line up from row to row.
private val RowActionWidth = 150.dp

// One app with its status, then icon buttons (uninstall when installed, report, favorite) and a
// single action button. The action button is one composable whose label and action follow the
// install state, so it keeps D-pad focus through GET APP -> CANCEL -> OPEN. Uninstall sits
// furthest left so rows without it keep report and favorite in the same place.
@Composable
fun AppRow(
    app: HubApp,
    state: InstallState?,
    resumeTick: Int,
    modifier: Modifier = Modifier,
    actionModifier: Modifier = Modifier,
    offerUpdates: Boolean = true,
    showExtras: Boolean = true // uninstall and report buttons; off in the setup guide
) {
    val context = LocalContext.current
    val installedVersion = remember(app, resumeTick, state) { AppLauncher.installedVersionCode(context, app.packageName) }
    val favorites by remember { UserPrefs.favorites(context) }.collectAsState(initial = emptySet())
    val action = appAction(app, state, installedVersion, offerUpdates)
    val actionFocus = remember { FocusRequester() }
    var uninstallFailed by remember { mutableStateOf(false) }
    val installed = installedVersion != null
    Row(
        modifier.fillMaxWidth().background(HubColors.Surface, RoundedCornerShape(14.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppArtwork(app, app.categories.first().tint, Modifier.padding(end = 18.dp).size(56.dp))
        Column(Modifier.weight(1f)) {
            AppNameWithVersion(app, nameSize = 22.sp, versionSize = 15.sp)
            app.description?.let {
                Text(it, fontSize = 15.sp, color = HubColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            CategoryTags(app, Modifier.padding(vertical = 6.dp))
            if (uninstallFailed && installed) Text(stringResource(R.string.uninstall_failed), fontSize = 15.sp, color = HubColors.Warning)
            else Text(
                statusText(app, state, installedVersion, offerUpdates).text(), fontSize = 15.sp,
                color = statusColor(app, state, installedVersion, offerUpdates)
            )
            InstallProgress(state, Modifier.padding(top = 8.dp).fillMaxWidth(0.6f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showExtras) UninstallButton(
                app, canUninstall(state, installedVersion), installed, actionFocus,
                onOpenFailed = { uninstallFailed = true }
            )
            if (showExtras) ReportButton(app, installedVersion)
            val favorite = app.packageName in favorites
            val favoriteDescription = stringResource(R.string.cd_favorite)
            HubIconButton(
                { UserPrefs.toggleFavorite(context, app.packageName) },
                Modifier.semantics { contentDescription = favoriteDescription }, color = HubColors.GlowPurple
            ) {
                Text(if (favorite) "★" else "☆", fontSize = 18.sp)
            }
            HubButton(
                { action.run(context) },
                actionModifier.focusRequester(actionFocus).width(RowActionWidth),
                color = action.color, enabled = action.enabled
            ) {
                Text(
                    stringResource(action.label), Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 1,
                    fontWeight = FontWeight.SemiBold, fontSize = 15.sp
                )
            }
        }
    }
}

// App name with its catalog version after it, e.g. "Clipdeck  v1.6.2". A long name is cut short
// (ellipsis) so the version always shows.
@Composable
internal fun AppNameWithVersion(
    app: HubApp,
    nameSize: TextUnit,
    versionSize: TextUnit,
    modifier: Modifier = Modifier,
    nameWeight: FontWeight? = null
) {
    Row(modifier) {
        Text(
            app.name, Modifier.weight(1f, fill = false).alignByBaseline(), fontSize = nameSize, fontWeight = nameWeight,
            color = HubColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        app.version?.let {
            Text(
                stringResource(R.string.version_short, it), Modifier.padding(start = 8.dp).alignByBaseline(),
                fontSize = versionSize, color = HubColors.Muted, maxLines = 1, softWrap = false
            )
        }
    }
}

// Download progress bar; draws nothing unless a download with a known size is running.
@Composable
fun InstallProgress(state: InstallState?, modifier: Modifier = Modifier) {
    if (state !is InstallState.Downloading || state.percent < 0) return
    Box(modifier.height(8.dp).background(HubColors.Line, RoundedCornerShape(4.dp))) {
        Box(
            Modifier.fillMaxWidth(state.percent / 100f).fillMaxHeight()
                .background(HubColors.GlowBlue, RoundedCornerShape(4.dp))
        )
    }
}
