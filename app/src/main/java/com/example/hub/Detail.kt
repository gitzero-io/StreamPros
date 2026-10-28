@file:OptIn(ExperimentalTvMaterial3Api::class)
package com.example.hub

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import kotlinx.coroutines.launch

// Description is at most 4 lines tall. Longer text gets a border and becomes focusable, and
// Up/Down scroll it while focused; at either end they move focus on as usual.
private val DescriptionLine = 26.sp

@Composable
private fun Description(text: String, below: FocusRequester) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val lineHeight = with(density) { DescriptionLine.toDp() }
    val scrollable = scroll.maxValue > 0
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.widthIn(max = 640.dp)) {
        Box(
            Modifier
                .then(
                    if (scrollable) Modifier.border(if (focused) 2.dp else 1.dp, if (focused) HubColors.GlowRed else HubColors.Line, shape)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                    else Modifier
                )
                .heightIn(max = lineHeight * 4)
                .onFocusChanged { focused = it.isFocused }
                // Leaving the end of the text goes to the main action, not the nearest button.
                .focusProperties { down = below }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val step = with(density) { (lineHeight * 2).toPx() }
                    when {
                        e.key == Key.DirectionDown && scroll.canScrollForward -> { scope.launch { scroll.animateScrollBy(step) }; true }
                        e.key == Key.DirectionUp && scroll.canScrollBackward -> { scope.launch { scroll.animateScrollBy(-step) }; true }
                        else -> false
                    }
                }
                .focusable(enabled = scrollable)
                .verticalScroll(scroll)
        ) {
            Text(text, fontSize = 18.sp, lineHeight = DescriptionLine, color = HubColors.Muted)
        }
        if (scroll.canScrollForward) {
            Text(
                stringResource(if (focused) R.string.detail_more else R.string.detail_more_up), fontSize = 14.sp, color = HubColors.Muted,
                modifier = Modifier.padding(top = 4.dp, start = 14.dp)
            )
        }
    }
}

// One catalog app: artwork, details, its install/open action and a favorite toggle.
@Composable
fun DetailScreen(app: HubApp, resumeTick: Int) {
    val context = LocalContext.current
    val states by InstallCoordinator.states.collectAsState()
    val state = states[app.packageName]
    val installedVersion = remember(app, resumeTick, state) { AppLauncher.installedVersionCode(context, app.packageName) }
    val favorites by remember { UserPrefs.favorites(context) }.collectAsState(initial = emptySet())
    val favorite = app.packageName in favorites
    val action = appAction(app, state, installedVersion)
    val tint = app.categories.first().tint
    val focus = rememberInitialFocus()
    val installed = installedVersion != null
    var uninstallFailed by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(300.dp).background(HubColors.Surface, RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center
        ) { AppArtwork(app, tint, Modifier.size(150.dp)) }

        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(app.name, fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.SemiBold, color = HubColors.Text)
            CategoryTags(app, max = Int.MAX_VALUE)
            app.description?.let { Description(it, below = focus) }
            app.version?.let { Text(stringResource(R.string.detail_version, it), fontSize = 16.sp, color = HubColors.Muted) }
            if (uninstallFailed && installed) Text(stringResource(R.string.uninstall_failed), fontSize = 18.sp, color = HubColors.Warning)
            else Text(statusText(app, state, installedVersion).text(), fontSize = 18.sp, color = statusColor(app, state, installedVersion))
            InstallProgress(state, Modifier.width(320.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
                HubButton(
                    stringResource(action.label), { action.run(context) }, Modifier.focusRequester(focus),
                    color = action.color, enabled = action.enabled
                )
                HubButton(
                    (if (favorite) "★ " else "☆ ") + stringResource(R.string.btn_favorite),
                    { UserPrefs.toggleFavorite(context, app.packageName) },
                    color = HubColors.GlowPurple
                )
                ReportButton(app, installedVersion, label = stringResource(R.string.btn_report))
                UninstallButton(
                    app, canUninstall(state, installedVersion), installed, focus,
                    onOpenFailed = { uninstallFailed = true }, label = stringResource(R.string.btn_uninstall)
                )
            }
        }
    }
}
