@file:OptIn(ExperimentalTvMaterial3Api::class)
package com.example.hub

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*

class MainActivity : ComponentActivity() {
    private var resumeTick by mutableIntStateOf(0)
    private var searchRequest by mutableStateOf<SearchRequest?>(null)
    // Set when a language change from Settings restarts the activity, so Settings opens again.
    private var reopenSettings = false

    // Every screen reads its text through this context, so it follows the Hub's language.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withHubLanguage())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startInSettings = savedInstanceState?.getBoolean(KEY_REOPEN_SETTINGS) == true
        handleDebugIntent(intent)
        handleSearchIntent(intent)
        License.start(this)
        HubUpdater.checkIfDue(this)
        HubDownloads.pruneStale(this)
        setContent {
            MaterialTheme(colorScheme = HubColorScheme) {
                HubRoot(resumeTick, searchRequest, startInSettings, ::changeLanguage) { searchRequest = null }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_REOPEN_SETTINGS, reopenSettings)
    }

    // Saves the language and restarts the activity so every screen redraws in it.
    private fun changeLanguage(language: HubLanguage, fromSettings: Boolean) {
        LanguagePrefs.choose(this, language)
        reopenSettings = fromSettings
        recreate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugIntent(intent)
        handleSearchIntent(intent)
    }

    // ACTION_SEARCH (e.g. from the Assistant) opens Search with the spoken/typed text filled in.
    private fun handleSearchIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEARCH) return
        searchRequest = SearchRequest(intent.getStringExtra(SearchManager.QUERY).orEmpty())
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
        License.refreshSoon()
        CatalogUpdater.checkIfDue(this)
    }

    // Debug builds only: `adb shell am start -n com.streampros.app.debug/com.example.hub.MainActivity --es hub_debug_installed <pkg>`
    // shows the "Open now" prompt without a real install, for testing.
    private fun handleDebugIntent(intent: Intent?) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        intent?.getStringExtra("hub_debug_installed")?.let { InstallCoordinator.markInstalled(it) }
    }

    private companion object {
        const val KEY_REOPEN_SETTINGS = "reopen_settings"
    }
}

sealed interface Screen {
    data object Home : Screen
    data class CategoryList(val category: Category) : Screen
    data class Detail(val packageName: String) : Screen
    data object Settings : Screen
    data object Trending : Screen
    data object Categories : Screen
    data object About : Screen
    data object Language : Screen
    data object CloseApps : Screen
    data object Storage : Screen
    data object Troubleshoot : Screen
    data object Plans : Screen
    // Not a data class: each search is its own entry, and it holds the query so returning from
    // App Detail shows the same results.
    class Search(initialQuery: String = "") : Screen {
        var query by mutableStateOf(initialQuery)
    }
}

// A request to open Search from an intent. A plain class, so the same text twice is two requests.
class SearchRequest(val query: String)

@Composable
fun HubRoot(
    resumeTick: Int,
    searchRequest: SearchRequest?,
    startInSettings: Boolean,
    onLanguage: (HubLanguage, fromSettings: Boolean) -> Unit,
    onSearchHandled: () -> Unit
) {
    val context = LocalContext.current
    var setupDone by remember { mutableStateOf(SetupPrefs.isSetupDone(context)) }
    // First launch asks for a language before the setup guide.
    var languageChosen by remember { mutableStateOf(LanguagePrefs.chosen(context) != null) }
    // Guided Installation from Settings: the wizard runs over the current stack, so leaving it
    // returns to Settings. Saved setup state, favorites and recents are untouched.
    var guidedInstall by remember { mutableStateOf(false) }
    val stack = remember {
        mutableStateListOf<Screen>(Screen.Home).apply { if (startInSettings && setupDone) add(Screen.Settings) }
    }
    val push: (Screen) -> Unit = { stack.add(it) }
    // Nothing but the language picker shows until the device is unlocked (paid or free trial).
    val license by License.state.collectAsState()
    val unlocked = license is LicenseState.Unlocked
    // A monthly plan or trial ending while the app is open: ask the server then, which locks it.
    LaunchedEffect(license) {
        val expiresAt = (license as? LicenseState.Unlocked)?.expiresAt ?: return@LaunchedEffect
        kotlinx.coroutines.delay((expiresAt - System.currentTimeMillis()).coerceAtLeast(0) + 1_000)
        License.refreshSoon(force = true)
    }
    BackHandler(enabled = unlocked && setupDone && !guidedInstall && stack.size > 1) { stack.removeAt(stack.lastIndex) }
    // An Assistant search waits until setup (or a guided install) is finished.
    LaunchedEffect(searchRequest, setupDone, guidedInstall, unlocked) {
        val request = searchRequest ?: return@LaunchedEffect
        if (!unlocked || !setupDone || guidedInstall) return@LaunchedEffect
        stack.clear(); stack.add(Screen.Home); stack.add(Screen.Search(request.query))
        onSearchHandled()
    }

    Box(Modifier.fillMaxSize().background(HubColors.Black).padding(horizontal = 28.dp, vertical = 28.dp)) {
        if (!setupDone && !languageChosen) {
            Box(Modifier.padding(horizontal = ShelfInset)) {
                LanguageScreen(LanguagePrefs.current(context), onPick = { onLanguage(it, false) }, onBack = null)
            }
        } else if (!unlocked) {
            Box(Modifier.padding(horizontal = ShelfInset)) { LicenseGate(license) }
        } else if (!setupDone || guidedInstall) {
            Box(Modifier.padding(horizontal = ShelfInset)) {
                SetupWizard(resumeTick, onExit = if (setupDone) ({ guidedInstall = false }) else null) {
                    SetupPrefs.markSetupDone(context); setupDone = true; guidedInstall = false
                }
            }
        } else {
            val current = stack.last()
            // Top-level screens show the nav bar; picking an item resets the stack to Home + that screen.
            val navItem = NavItem.forScreen(current)
            // A newly downloaded catalog rebuilds the current screen with the new app list.
            val catalogRevision by Catalog.revision.collectAsState()
            Column {
                if (navItem != null) TopNav(navItem, onSearch = { push(Screen.Search()) }) { item ->
                    stack.clear(); stack.add(Screen.Home)
                    if (item != NavItem.HOME) stack.add(item.screen)
                }
                Box(Modifier.weight(1f).padding(top = if (navItem != null) 8.dp else 0.dp)) {
                    key(stack.size, current, catalogRevision) {
                        ScreenContent(
                            current, resumeTick, push,
                            onGuidedInstall = { guidedInstall = true },
                            onResetAppData = {
                                // Back to a true first run: language, the wizard, then Home.
                                AppData.reset(context)
                                stack.clear(); stack.add(Screen.Home)
                                setupDone = false
                                languageChosen = false
                            },
                            onLanguage = { language ->
                                if (language == LanguagePrefs.current(context)) stack.removeAt(stack.lastIndex)
                                else onLanguage(language, true)
                            },
                            pop = { stack.removeAt(stack.lastIndex) }
                        )
                    }
                }
            }
        }
    }

    val justInstalled by InstallCoordinator.justInstalled.collectAsState()
    // "What's new" once on the first launch of a Hub update, if the install prompt didn't show it.
    var whatsNew by remember { mutableStateOf(HubUpdater.notesForThisVersion(context) != null) }
    val installedPkg = justInstalled
    when {
        installedPkg != null -> OpenNowPrompt(installedPkg, onHubUpdatedSeen = { whatsNew = false })
        whatsNew && setupDone && unlocked -> HubUpdatedPrompt(onDismiss = { whatsNew = false })
    }

    val pinRequest by PinLock.request.collectAsState()
    if (pinRequest != null) PinEntryDialog()

    // GET A PLAN on an app once the trial's free installs are used: the plans over whatever is
    // showing (the setup guide included). Closes itself once a plan replaces the trial.
    val plansRequested by License.plansRequested.collectAsState()
    val trial = (license as? LicenseState.Unlocked)?.takeIf { it.plan == Plan.TRIAL }
    if (plansRequested && trial?.code != null) {
        Dialog(onDismissRequest = License::plansShown, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(HubColors.Black).padding(horizontal = 28.dp + ShelfInset, vertical = 28.dp)) {
                PlansScreen(
                    trial.code, trial = null, onBack = License::plansShown, backLabel = R.string.btn_not_now,
                    note = trial.freeInstalls?.let { stringResource(R.string.plans_free_installs_used, it.limit) }
                )
            }
        }
    } else if (plansRequested) LaunchedEffect(Unit) { License.plansShown() }
}

@Composable
private fun ScreenContent(
    screen: Screen,
    resumeTick: Int,
    push: (Screen) -> Unit,
    onGuidedInstall: () -> Unit,
    onResetAppData: () -> Unit,
    onLanguage: (HubLanguage) -> Unit,
    pop: () -> Unit
) {
    val context = LocalContext.current
    when (screen) {
        Screen.Home -> HomeScreen(
            resumeTick,
            onOpenCategory = { push(Screen.CategoryList(it)) },
            onOpenDetail = { push(Screen.Detail(it.packageName)) },
            onCloseApps = { push(Screen.CloseApps) }
        )
        Screen.Settings -> Box(Modifier.padding(horizontal = ShelfInset)) {
            SettingsScreen(
                onGuidedInstall, onResetAppData, onAbout = { push(Screen.About) }, onLanguage = { push(Screen.Language) },
                onCloseApps = { push(Screen.CloseApps) }, onStorage = { push(Screen.Storage) },
                onTroubleshoot = { push(Screen.Troubleshoot) }, onPlans = { push(Screen.Plans) }
            )
        }
        Screen.Plans -> Box(Modifier.padding(horizontal = ShelfInset)) {
            val license by License.state.collectAsState()
            // Back to Settings once a plan replaces the trial.
            val trial = license as? LicenseState.Unlocked
            if (trial?.plan == Plan.TRIAL && trial.code != null) PlansScreen(trial.code, trial = null, onBack = pop)
            else LaunchedEffect(Unit) { pop() }
        }
        Screen.CloseApps -> Box(Modifier.padding(horizontal = ShelfInset)) { CloseAppsScreen(resumeTick, onDone = pop) }
        Screen.Storage -> Box(Modifier.padding(horizontal = ShelfInset)) { StorageScreen(resumeTick, onDone = pop) }
        Screen.Troubleshoot -> Box(Modifier.padding(horizontal = ShelfInset)) {
            TroubleshootScreen(resumeTick, onStorage = { push(Screen.Storage) }, onCloseApps = { push(Screen.CloseApps) }, onDone = pop)
        }
        Screen.Language -> Box(Modifier.padding(horizontal = ShelfInset)) {
            LanguageScreen(LanguagePrefs.current(context), onPick = onLanguage, onBack = pop)
        }
        Screen.About -> Box(Modifier.padding(horizontal = ShelfInset)) { AboutScreen(onDone = pop) }
        Screen.Categories -> CategoriesScreen(onOpenCategory = { push(Screen.CategoryList(it)) })
        is Screen.Search -> SearchScreen(
            screen, resumeTick,
            onOpenDetail = { push(Screen.Detail(it.packageName)) },
            onBrowseCategories = { push(Screen.Categories) }
        )
        Screen.Trending -> Box(Modifier.padding(horizontal = ShelfInset)) { TrendingScreen(resumeTick) }
        is Screen.CategoryList -> Box(Modifier.padding(horizontal = ShelfInset)) {
            CategoryScreen(screen.category, resumeTick)
        }
        is Screen.Detail -> {
            val app = Catalog.get(context).firstOrNull { it.packageName == screen.packageName }
            if (app != null) Box(Modifier.padding(horizontal = ShelfInset)) { DetailScreen(app, resumeTick) }
            else LaunchedEffect(Unit) { pop() }
        }
    }
}

// Shown when an install finishes: offers to open the new app right away. When the Hub has just
// updated itself, it confirms the new version instead.
@Composable
private fun OpenNowPrompt(pkg: String, onHubUpdatedSeen: () -> Unit) {
    val context = LocalContext.current
    if (pkg == context.packageName) {
        HubUpdatedPrompt(onDismiss = { InstallCoordinator.dismissInstalled(); onHubUpdatedSeen() })
        return
    }
    val name = remember(pkg) { Catalog.get(context).firstOrNull { it.packageName == pkg }?.name ?: pkg }
    val dismiss = { InstallCoordinator.dismissInstalled() }
    Dialog(onDismissRequest = dismiss) {
        val focus = rememberInitialFocus()
        Column(
            Modifier.width(520.dp).background(HubColors.SurfaceRaised, RoundedCornerShape(20.dp))
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(stringResource(R.string.installed_label), fontSize = 13.sp, color = HubColors.GlowGreen)
            Text(name, fontSize = 28.sp, lineHeight = 34.sp, color = HubColors.Text)
            Text(stringResource(R.string.installed_ready), fontSize = 18.sp, color = HubColors.Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 8.dp)) {
                HubButton(
                    stringResource(R.string.btn_open_now),
                    { AppLauncher.launch(context, pkg); dismiss() },
                    Modifier.focusRequester(focus),
                    color = HubColors.GlowGreen
                )
                HubButton(stringResource(R.string.btn_later), dismiss, color = HubColors.Muted)
            }
        }
    }
}

// The Hub was updated: its new version and the release notes ("What's new") when there are any.
// Shown once; dismissing it forgets the notes.
@Composable
private fun HubUpdatedPrompt(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version = remember { appVersion(context) }
    val notes = remember { HubUpdater.notesForThisVersion(context) }
    val dismiss = { HubUpdater.clearNotes(context); onDismiss() }
    Dialog(onDismissRequest = dismiss) {
        val focus = rememberInitialFocus()
        Column(
            Modifier.width(520.dp).background(HubColors.SurfaceRaised, RoundedCornerShape(20.dp))
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(stringResource(R.string.updated_label), fontSize = 13.sp, color = HubColors.GlowGreen)
            Text(stringResource(R.string.app_name), fontSize = 28.sp, lineHeight = 34.sp, color = HubColors.Text)
            Text(stringResource(R.string.updated_version, version), fontSize = 18.sp, color = HubColors.Muted)
            if (notes != null) {
                Text(stringResource(R.string.whats_new), fontSize = 13.sp, color = HubColors.GlowPurple, modifier = Modifier.padding(top = 4.dp))
                Text(notes, fontSize = 18.sp, lineHeight = 25.sp, color = HubColors.Text)
            }
            HubButton(stringResource(R.string.btn_ok), dismiss, Modifier.padding(top = 8.dp).focusRequester(focus), color = HubColors.GlowGreen)
        }
    }
}
