@file:OptIn(ExperimentalTvMaterial3Api::class)
package com.example.hub

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*

enum class SetupStep(@StringRes val title: Int, @StringRes val guide: Int) {
    WELCOME(R.string.step_welcome_title, R.string.step_welcome_guide),
    REMOTE(R.string.step_remote_title, R.string.step_remote_guide),
    PERMISSION(R.string.step_permission_title, R.string.step_permission_guide),
    INTERESTS(R.string.step_interests_title, R.string.step_interests_guide),
    INSTALL(R.string.step_install_title, R.string.step_install_guide),
    DONE(R.string.step_done_title, R.string.step_done_guide)
}

// onExit: when set (Guided Installation from Settings), Back on the first step or NOT NOW leaves
// the guide, and the last step returns to Settings instead of starting the app.
@Composable
fun SetupWizard(resumeTick: Int, onExit: (() -> Unit)? = null, onFinish: () -> Unit) {
    val context = LocalContext.current
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    var pickNames by rememberSaveable { mutableStateOf(SetupPrefs.getInterests(context).map { it.name }) }
    val picks = Category.values().filter { it.name in pickNames }.toSet()
    val step = SetupStep.values()[stepIndex]
    val next: () -> Unit = { stepIndex++ }

    BackHandler(enabled = stepIndex > 0 || onExit != null) { if (stepIndex > 0) stepIndex-- else onExit?.invoke() }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        GuidePanel(step, Modifier.fillMaxWidth(0.32f).fillMaxHeight())
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
            key(step) {
                when (step) {
                    SetupStep.WELCOME -> WelcomeStep(next, onExit)
                    SetupStep.REMOTE -> RemoteStep(next)
                    SetupStep.PERMISSION -> PermissionStep(resumeTick, next)
                    SetupStep.INTERESTS -> InterestsStep(
                        picks,
                        onToggle = { c -> pickNames = if (c.name in pickNames) pickNames - c.name else pickNames + c.name },
                        onNext = { SetupPrefs.saveInterests(context, picks); next() }
                    )
                    SetupStep.INSTALL -> InstallStep(picks, resumeTick, next)
                    SetupStep.DONE -> DoneStep(
                        picks, stringResource(if (onExit != null) R.string.btn_back_to_settings else R.string.btn_start_watching), onFinish
                    )
                }
            }
        }
    }
}

@Composable
private fun GuidePanel(step: SetupStep, modifier: Modifier) {
    val total = SetupStep.values().size
    Column(
        modifier.background(HubColors.Surface, RoundedCornerShape(20.dp)).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(R.string.setup_step_count, step.ordinal + 1, total), fontSize = 13.sp, color = HubColors.GlowPurple)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(total) { i ->
                Box(
                    Modifier.size(10.dp)
                        .background(if (i <= step.ordinal) HubColors.GlowBlue else HubColors.Line, CircleShape)
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Text(stringResource(step.title), fontSize = 30.sp, lineHeight = 36.sp, color = HubColors.Text)
        Text(stringResource(step.guide), fontSize = 18.sp, color = HubColors.Muted, lineHeight = 26.sp)
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit, onExit: (() -> Unit)?) {
    val focus = rememberInitialFocus()
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(stringResource(R.string.welcome_heading), fontSize = 38.sp, lineHeight = 44.sp, color = HubColors.Text)
        Text(stringResource(R.string.welcome_body), fontSize = 20.sp, color = HubColors.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            HubButton(stringResource(R.string.btn_lets_go), onNext, Modifier.focusRequester(focus))
            if (onExit != null) HubButton(stringResource(R.string.btn_not_now), onExit, color = HubColors.Muted)
        }
    }
}

@Composable
private fun RemoteStep(onNext: () -> Unit) {
    val focus = rememberInitialFocus()
    val keys = listOf(
        R.string.remote_arrows to R.string.remote_arrows_desc,
        R.string.remote_ok to R.string.remote_ok_desc,
        R.string.remote_back to R.string.remote_back_desc,
        R.string.remote_home to R.string.remote_home_desc
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.remote_heading), fontSize = 38.sp, lineHeight = 44.sp, color = HubColors.Text)
        keys.forEach { (key, meaning) ->
            Row(
                Modifier.fillMaxWidth().background(HubColors.Surface, RoundedCornerShape(14.dp))
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            ) {
                Text(stringResource(key), fontSize = 18.sp, color = HubColors.GlowPurple, modifier = Modifier.width(220.dp))
                Text(stringResource(meaning), fontSize = 18.sp, color = HubColors.Text)
            }
        }
        HubButton(stringResource(R.string.btn_continue), onNext, Modifier.focusRequester(focus))
    }
}

@Composable
private fun PermissionStep(resumeTick: Int, onNext: () -> Unit) {
    val context = LocalContext.current
    val focus = rememberInitialFocus()
    val allowed = remember(resumeTick) { canInstallApps(context) }
    var noSettings by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(stringResource(R.string.permission_heading), fontSize = 38.sp, lineHeight = 44.sp, color = HubColors.Text)
        Text(
            when {
                allowed -> stringResource(R.string.permission_done)
                noSettings -> stringResource(R.string.permission_no_settings)
                else -> stringResource(R.string.permission_instructions)
            },
            fontSize = 20.sp, color = if (allowed) HubColors.GlowGreen else HubColors.Muted
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            HubButton(
                stringResource(if (allowed) R.string.btn_continue else R.string.btn_open_settings),
                { if (allowed) onNext() else noSettings = !openInstallPermissionSettings(context) },
                Modifier.focusRequester(focus)
            )
            if (!allowed) HubButton(stringResource(R.string.btn_skip_for_now), onNext, color = HubColors.Muted)
        }
    }
}

private const val INTEREST_COLUMNS = 4

@Composable
private fun InterestsStep(picks: Set<Category>, onToggle: (Category) -> Unit, onNext: () -> Unit) {
    val focus = rememberInitialFocus()
    val context = LocalContext.current
    val categories = remember { categoriesInUse(Catalog.get(context)) }
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(stringResource(R.string.interests_heading), fontSize = 38.sp, lineHeight = 44.sp, color = HubColors.Text)
        // Up to four tiles per row so every name fits; a short last row keeps the same tile width.
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            categories.chunked(INTEREST_COLUMNS).forEachIndexed { row, rowCategories ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    rowCategories.forEachIndexed { col, c ->
                        val picked = c in picks
                        HubCard(
                            onClick = { onToggle(c) },
                            modifier = Modifier.weight(1f).height(96.dp)
                                .then(if (row == 0 && col == 0) Modifier.focusRequester(focus) else Modifier),
                            tint = c.tint,
                            selected = picked
                        ) {
                            Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CategoryIcon(c, if (picked) c.tint else HubColors.Muted, Modifier.size(22.dp))
                                    Spacer(Modifier.weight(1f))
                                    if (picked) Text("✓", fontSize = 20.sp, color = c.tint)
                                }
                                Text(stringResource(c.title), fontSize = 18.sp, color = HubColors.Text, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                    repeat(INTEREST_COLUMNS - rowCategories.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Text(
            if (picks.isEmpty()) stringResource(R.string.interests_pick_one)
            else pluralStringResource(R.plurals.interests_picked, picks.size, picks.size),
            fontSize = 16.sp, color = HubColors.Muted
        )
        HubButton(stringResource(R.string.btn_continue), onNext, enabled = picks.isNotEmpty())
    }
}

@Composable
private fun InstallStep(picks: Set<Category>, resumeTick: Int, onNext: () -> Unit) {
    val focus = rememberInitialFocus()
    val context = LocalContext.current
    val states by InstallCoordinator.states.collectAsState()
    val apps = remember(picks) { appsFor(picks, Catalog.get(context)) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.install_heading), fontSize = 38.sp, lineHeight = 44.sp, color = HubColors.Text, modifier = Modifier.weight(1f))
            HubButton(stringResource(R.string.btn_continue), onNext, Modifier.focusRequester(focus))
        }
        if (apps.isEmpty()) Text(stringResource(R.string.install_empty), fontSize = 18.sp, color = HubColors.Muted)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Apps already on the TV show Installed / OPEN here, even if an update exists.
            items(apps, key = { it.packageName }) { app ->
                AppRow(app, states[app.packageName], resumeTick, offerUpdates = false, showExtras = false)
            }
        }
    }
}

@Composable
private fun DoneStep(picks: Set<Category>, finishLabel: String, onFinish: () -> Unit) {
    val focus = rememberInitialFocus()
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(stringResource(R.string.done_heading), fontSize = 38.sp, lineHeight = 44.sp, color = HubColors.Text)
        if (picks.isNotEmpty()) {
            Text(stringResource(R.string.done_picks, picks.map { stringResource(it.title) }.joinToString()), fontSize = 20.sp, color = HubColors.Muted)
        }
        HubButton(finishLabel, onFinish, Modifier.focusRequester(focus))
    }
}
