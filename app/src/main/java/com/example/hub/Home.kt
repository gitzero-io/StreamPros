@file:OptIn(ExperimentalTvMaterial3Api::class)
package com.example.hub

import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.background
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.delay
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*

// Horizontal room left around shelf content so the focus glow and scale aren't clipped.
internal val ShelfInset = 24.dp

// Every app tile is this size, so rows and grids line up. Wide and tall enough for the name and
// version, a one-line description, and two category chips plus "+N".
internal val AppCardWidth = 240.dp
internal val AppCardHeight = 196.dp

// One Home row. `content` gets the FocusRequester to attach to its first item when this is the
// first row on screen (initial focus), otherwise null.
private class HomeRow(val key: String, val content: @Composable (FocusRequester?) -> Unit)

@Composable
fun HomeScreen(
    resumeTick: Int,
    onOpenCategory: (Category) -> Unit,
    onOpenDetail: (HubApp) -> Unit,
    onCloseApps: () -> Unit
) {
    val context = LocalContext.current
    val catalog = remember { Catalog.get(context) }
    val states by InstallCoordinator.states.collectAsState()
    // packageName -> installed versionCode, for catalog apps that are installed.
    val installedVersions = remember(resumeTick, states) {
        catalog.mapNotNull { app -> AppLauncher.installedVersionCode(context, app.packageName)?.let { app.packageName to it } }.toMap()
    }
    val installedPkgs = installedVersions.keys
    val updates = catalog.filter { needsUpdate(installedVersions[it.packageName], it.versionCode) }
    // Null until DataStore has loaded, so rows don't appear above the focused one after the fact.
    val favoritePkgs: Set<String>? by remember { UserPrefs.favorites(context) }.collectAsState(initial = null)
    val recentPkgs: List<String>? by remember { UserPrefs.recents(context) }.collectAsState(initial = null)
    val freshPkgs by remember { UserPrefs.freshInstalls(context) }.collectAsState(initial = emptySet())
    val favoriteOrder: List<String>? by remember { UserPrefs.favoriteOrder(context) }.collectAsState(initial = null)
    val loaded = favoritePkgs != null && recentPkgs != null && favoriteOrder != null
    // My Apps: recently opened apps first (newest first), then the other installed apps in catalog order.
    val myApps = myAppsOrder(catalog.filter { it.packageName in installedPkgs }, recentPkgs.orEmpty())
    val favorites = orderedFavorites(catalog, favoritePkgs.orEmpty(), favoriteOrder.orEmpty())
    // Favorite being moved (hold OK on it): Left/Right move it, OK or Back puts it down.
    var moving by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = moving != null) { moving = null }
    val moveFavorite: (HubApp, Int) -> Unit = { app, by ->
        val moved = moveItem(favorites, favorites.indexOf(app), by).map { it.packageName }
        // Favorites not shown here (hidden 18+ apps, apps this TV can't run) keep their place after.
        val rest = (favoriteOrder.orEmpty() + favoritePkgs.orEmpty()).distinct().filter { it !in moved }
        UserPrefs.setFavoriteOrder(context, moved + rest)
    }
    val recent = remember(catalog) { recentlyUpdated(catalog, System.currentTimeMillis()) }

    // Installed apps open straight away; anything else (and a long press) opens the detail page.
    val open: (HubApp) -> Unit = { app ->
        if (app.packageName !in installedPkgs || !AppLauncher.launch(context, app.packageName)) onOpenDetail(app)
    }
    val trending = remember { Trending.get(context) }
    val ranked = remember(catalog, trending) { trendingApps(trending, catalog).numbered() }
    val trendingPkgs = remember(ranked) { ranked.map { it.app.packageName }.toSet() }

    // Cards outside the Trending row get a TRENDING tag; inside it the tag would just repeat.
    val appCard: @Composable (HubApp, Modifier, Boolean) -> Unit = { app, mod, tagTrending ->
        val isInstalled = app.packageName in installedPkgs
        AppCard(
            app, { open(app) }, mod, onLongClick = { onOpenDetail(app) },
            subtitle = stringResource(if (isInstalled) R.string.card_open else R.string.card_get_app),
            subtitleColor = if (isInstalled) HubColors.Muted else HubColors.GlowBlue,
            tag = tileTag(app.packageName, isInstalled, freshPkgs, trendingPkgs.takeIf { tagTrending }.orEmpty())
        )
    }
    val card: @Composable (HubApp, Modifier) -> Unit = { app, mod -> appCard(app, mod, true) }

    val featured = remember(catalog, ranked) { heroApps(catalog, ranked) }
    val hubUpdate by HubUpdater.state.collectAsState()
    val batch by InstallCoordinator.batch.collectAsState()

    val rows = buildList {
        if (featured.isNotEmpty()) add(HomeRow("hero") { fr ->
            HeroBanner(featured, installedPkgs, fr, label = stringResource(if (featured.any { it.featured }) R.string.tag_featured else R.string.tag_trending), onOpen = open)
        })
        if (hubUpdate is HubUpdateState.Available) add(HomeRow("hubupdate") { fr ->
            Box(Modifier.padding(horizontal = ShelfInset, vertical = 8.dp)) {
                HubUpdateRow(if (fr != null) Modifier.focusRequester(fr) else Modifier)
            }
        })
        if (updates.isNotEmpty()) add(HomeRow("updates") { fr ->
            // Update all leads the row when there's more than one update (or it's running).
            val updateAll: (@Composable (Modifier) -> Unit)? = if (updates.size < 2 && batch == null) null else { mod ->
                UpdateAllCard(updates.size, batch, mod) {
                    if (batch != null) InstallCoordinator.stopUpdateAll()
                    else PinLock.guard(context) { InstallCoordinator.updateAll(context, updates) }
                }
            }
            Shelf(stringResource(R.string.shelf_updates), updates, fr, leading = updateAll) { app, mod ->
                AppCard(
                    app, { onOpenDetail(app) }, mod, onLongClick = { onOpenDetail(app) },
                    subtitle = app.version?.let { stringResource(R.string.card_update_to, it) } ?: stringResource(R.string.card_update), subtitleColor = HubColors.GlowBlue,
                    tag = tileTag(app.packageName, true, freshPkgs, trendingPkgs)
                )
            }
        })
        if (myApps.isNotEmpty()) add(HomeRow("myapps") { fr ->
            Shelf(stringResource(R.string.shelf_my_apps), myApps, fr, trailing = { mod -> CloseAppsCard(onCloseApps, mod) }, itemContent = card)
        })
        if (favorites.isNotEmpty()) add(HomeRow("favorites") { fr ->
            val canMove = favorites.size > 1
            Shelf(
                stringResource(R.string.shelf_favorites), favorites, fr,
                subtitle = if (!canMove) null else stringResource(if (moving != null) R.string.favorites_moving else R.string.favorites_move_hint),
                key = { it.packageName }
            ) { app, mod ->
                // One AppCard whether or not it's moving, so it keeps focus when the mode changes.
                val isMoving = moving == app.packageName
                val isInstalled = app.packageName in installedPkgs
                AppCard(
                    app, { if (!isMoving) open(app) },
                    mod.moveKeys(isMoving, onMove = { moveFavorite(app, it) }, onDone = { moving = null }),
                    onLongClick = if (canMove) ({ moving = app.packageName }) else ({ onOpenDetail(app) }),
                    subtitle = stringResource(
                        when { isMoving -> R.string.favorites_move_arrows; isInstalled -> R.string.card_open; else -> R.string.card_get_app }
                    ),
                    subtitleColor = when { isMoving -> HubColors.GlowPurple; isInstalled -> HubColors.Muted; else -> HubColors.GlowBlue },
                    tag = if (isMoving) null else tileTag(app.packageName, isInstalled, freshPkgs, trendingPkgs)
                )
            }
        })
        // Apps with a new version in the catalog. Installed ones that need it open App Detail
        // (UPDATE is the main action there); the rest behave like any other tile.
        if (recent.isNotEmpty()) add(HomeRow("recent") { fr ->
            Shelf(stringResource(R.string.shelf_recently_updated), recent, fr, subtitle = stringResource(R.string.shelf_recent_note)) { app, mod ->
                if (needsUpdate(installedVersions[app.packageName], app.versionCode)) AppCard(
                    app, { onOpenDetail(app) }, mod, onLongClick = { onOpenDetail(app) },
                    subtitle = app.version?.let { stringResource(R.string.card_update_to, it) } ?: stringResource(R.string.card_update),
                    subtitleColor = HubColors.GlowBlue,
                    tag = tileTag(app.packageName, true, freshPkgs, trendingPkgs)
                ) else card(app, mod)
            }
        })
        if (ranked.isNotEmpty()) add(HomeRow("trending") { fr ->
            Shelf(stringResource(R.string.shelf_trending), ranked, fr, subtitle = trending.updated?.let { stringResource(R.string.shelf_updated, shortDate(it)) }) { r, mod ->
                RankedItem(r.rank) { appCard(r.app, mod, false) }
            }
        })
        add(HomeRow("categories") { fr -> CategoryShelf(fr, onOpenCategory) })
        add(HomeRow("disclaimer") { fr -> DisclaimerCard(fr) })
    }

    val initial = rememberInitialFocus(enabled = loaded)
    if (!loaded) return
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        itemsIndexed(rows, key = { _, r -> r.key }) { i, row -> row.content(if (i == 0) initial else null) }
    }
}

// A titled horizontal row of items.
@Composable
fun <T> Shelf(
    title: String,
    items: List<T>,
    firstFocus: FocusRequester?,
    titleColor: Color = HubColors.Text,
    subtitle: String? = null,
    leading: (@Composable (Modifier) -> Unit)? = null,  // one extra tile before the items
    trailing: (@Composable (Modifier) -> Unit)? = null, // one extra tile after the items
    key: ((T) -> Any)? = null, // stable item keys, so focus follows an item that moves
    itemContent: @Composable (T, Modifier) -> Unit
) {
    Column {
        Row(
            Modifier.padding(start = ShelfInset, top = 12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = titleColor)
            if (subtitle != null) Text(subtitle, fontSize = 14.sp, color = HubColors.Muted)
        }
        // Moving into a row lands on the item last focused there, or the first item the first time,
        // instead of whichever card happens to sit nearest.
        val first = remember { FocusRequester() }
        LazyRow(
            modifier = Modifier.focusRestorer(first),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = ShelfInset, vertical = 20.dp)
        ) {
            val firstMod = Modifier.focusRequester(first).then(if (firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier)
            if (leading != null) item { leading(firstMod) }
            itemsIndexed(items, key = key?.let { k -> { _: Int, item: T -> k(item) } }) { i, item ->
                itemContent(item, if (i == 0 && leading == null) firstMod else Modifier)
            }
            if (trailing != null) item { trailing(Modifier) }
        }
    }
}

@Composable
internal fun CategoryShelf(firstFocus: FocusRequester?, onOpen: (Category) -> Unit, title: String = stringResource(R.string.shelf_browse)) {
    val context = LocalContext.current
    val categories = remember { categoriesInUse(Catalog.get(context)) }
    Shelf(title, categories, firstFocus) { c, mod ->
        HubCard(onClick = { onOpen(c) }, modifier = mod.width(200.dp).height(120.dp), tint = c.tint) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Bottom) {
                Box(Modifier.size(width = 28.dp, height = 4.dp).background(c.tint, RoundedCornerShape(2.dp)))
                Spacer(Modifier.height(10.dp))
                Text(stringResource(c.title), fontSize = 22.sp, color = HubColors.Text)
            }
        }
    }
}

// Keys for a favorite being moved: Left/Right move it along the row, Up/Down are ignored so it
// stays in the row, and OK puts it down. The release of the long press that started moving is let
// through: only an OK pressed while moving ends it. Does nothing while not moving.
@Composable
private fun Modifier.moveKeys(moving: Boolean, onMove: (Int) -> Unit, onDone: () -> Unit): Modifier {
    var okPressed by remember(moving) { mutableStateOf(false) }
    val isOk = { k: Key -> k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter }
    return onPreviewKeyEvent { e ->
        when {
            !moving -> false
            isOk(e.key) && e.type == KeyEventType.KeyDown -> { okPressed = true; true }
            isOk(e.key) && e.type == KeyEventType.KeyUp -> { if (okPressed) onDone(); true }
            e.type != KeyEventType.KeyDown -> false
            e.key == Key.DirectionLeft -> { onMove(-1); true }
            e.key == Key.DirectionRight -> { onMove(1); true }
            e.key == Key.DirectionUp || e.key == Key.DirectionDown -> true
            else -> false
        }
    }
}

// First tile of the Updates row: OK updates every app in the row, one after another; while that
// runs it shows progress and OK stops after the current app.
@Composable
private fun UpdateAllCard(count: Int, batch: BatchProgress?, modifier: Modifier, onClick: () -> Unit) {
    HubCard(onClick = onClick, modifier = modifier.width(AppCardWidth).height(AppCardHeight)) {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            UpdateIcon(HubColors.GlowBlue, Modifier.size(52.dp))
            Spacer(Modifier.weight(1f))
            Text(
                if (batch != null) stringResource(R.string.update_all_running, batch.done + 1, batch.total) else stringResource(R.string.update_all),
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = HubColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                if (batch != null) stringResource(R.string.update_all_stop) else pluralStringResource(R.plurals.update_all_note, count, count),
                fontSize = 13.sp, lineHeight = 17.sp, color = if (batch != null) HubColors.Muted else HubColors.GlowBlue, maxLines = 2
            )
        }
    }
}

// Ring with a downward arrow, drawn so the app needs no icon library.
@Composable
private fun UpdateIcon(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val d = size.minDimension
        val c = Offset(size.width / 2, size.height / 2)
        val w = d * 0.07f
        drawCircle(color, d * 0.42f, c, style = Stroke(w))
        drawLine(color, Offset(c.x, c.y - d * 0.2f), Offset(c.x, c.y + d * 0.18f), w, StrokeCap.Round)
        drawLine(color, Offset(c.x - d * 0.14f, c.y + d * 0.04f), Offset(c.x, c.y + d * 0.18f), w, StrokeCap.Round)
        drawLine(color, Offset(c.x + d * 0.14f, c.y + d * 0.04f), Offset(c.x, c.y + d * 0.18f), w, StrokeCap.Round)
    }
}

// Last row on Home: a one-line disclaimer that OK expands to the full text and collapses again.
@Composable
private fun DisclaimerCard(firstFocus: FocusRequester?) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    // Opening scrolls Home so the whole text is on screen, not just the first lines.
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(expanded) {
        if (expanded) { delay(50); bringIntoView.bringIntoView() }
    }
    HubCard(
        onClick = { expanded = !expanded },
        modifier = Modifier.padding(horizontal = ShelfInset, vertical = 10.dp).fillMaxWidth()
            .bringIntoViewRequester(bringIntoView)
            .then(if (firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.disclaimer_title), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = HubColors.Text)
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(if (expanded) R.string.disclaimer_close else R.string.disclaimer_read),
                    fontSize = 15.sp, color = HubColors.GlowBlue
                )
            }
            if (!expanded) {
                Text(
                    stringResource(R.string.disclaimer_short),
                    fontSize = 16.sp, color = HubColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else {
                disclaimerSections().forEach { (title, body) ->
                    Text(
                        title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = HubColors.Text,
                        modifier = Modifier.padding(top = 14.dp)
                    )
                    Text(body, fontSize = 16.sp, lineHeight = 23.sp, color = HubColors.Muted, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

// Installed apps with the recently opened ones (recents, newest first) moved to the front; the rest
// keep their order. Recents for apps that are no longer installed are ignored.
fun myAppsOrder(installed: List<HubApp>, recents: List<String>): List<HubApp> {
    val rank = recents.withIndex().associate { (i, pkg) -> pkg to i }
    return installed.sortedBy { rank[it.packageName] ?: Int.MAX_VALUE }
}

// Corner tag on a Home tile. NEW (success green) marks an app the Hub installed that hasn't been
// opened from the Hub yet; it wins over TRENDING (discovery purple).
enum class TileTag(@StringRes val label: Int) {
    NEW(R.string.tag_new), TRENDING(R.string.tag_trending);

    val color: Color get() = if (this == NEW) HubColors.GlowGreen else HubColors.GlowPurple
}

fun tileTag(pkg: String, installed: Boolean, fresh: Set<String>, trending: Set<String>): TileTag? = when {
    installed && pkg in fresh -> TileTag.NEW
    pkg in trending -> TileTag.TRENDING
    else -> null
}

// App tile: artwork (or tinted initials), name, an optional subtitle, category chips and an
// optional corner tag.
@Composable
fun AppCard(
    app: HubApp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    subtitle: String? = null,
    subtitleColor: Color = HubColors.Muted,
    tag: TileTag? = null
) {
    val tint = app.categories.first().tint
    HubCard(onClick = onClick, modifier = modifier.width(AppCardWidth).height(AppCardHeight), onLongClick = onLongClick, tint = tint) {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            AppArtwork(app, tint, Modifier.size(52.dp))
            Spacer(Modifier.weight(1f))
            AppNameWithVersion(app, nameSize = 16.sp, versionSize = 12.sp)
            app.description?.let {
                Text(it, fontSize = 13.sp, color = HubColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = subtitleColor, maxLines = 1)
            CategoryTags(app, Modifier.padding(top = 8.dp))
        }
        if (tag != null) {
            val tagColor = tag.color
            Text(
                stringResource(tag.label), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = tagColor,
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
                    .background(tagColor.copy(alpha = 0.15f), RoundedCornerShape(50))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
fun AppArtwork(app: HubApp, tint: Color, modifier: Modifier) {
    val bitmap = rememberAssetBitmap(app.artwork)
    val shape = RoundedCornerShape(12.dp)
    if (bitmap != null) {
        // Launcher icons carry their own shape and transparent edges, so no tinted backdrop.
        Image(bitmap, contentDescription = null, contentScale = ContentScale.Fit, modifier = modifier.clip(shape))
    } else {
        Box(modifier.background(tint.copy(alpha = 0.18f), shape), contentAlignment = Alignment.Center) {
            Text(initials(app.name), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = tint)
        }
    }
}

// Hero carousel: apps marked featured, or else the top trending apps that have a TV banner.
internal fun heroApps(catalog: List<HubApp>, ranked: List<RankedApp>, max: Int = 5): List<HubApp> =
    catalog.filter { it.featured }.ifEmpty { ranked.map { it.app }.filter { it.banner != null } }.take(max)

// The app's 16:9 TV banner, or its square artwork when it has no banner.
@Composable
private fun AppBanner(app: HubApp, tint: Color, modifier: Modifier) {
    val bitmap = rememberAssetBitmap(app.banner)
    if (bitmap != null) {
        val shape = RoundedCornerShape(12.dp)
        Image(
            bitmap, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = modifier.aspectRatio(16f / 9f).clip(shape).background(HubColors.Black, shape)
        )
    } else {
        AppArtwork(app, tint, modifier.aspectRatio(1f))
    }
}

@Composable
private fun rememberAssetBitmap(path: String?): ImageBitmap? {
    val context = LocalContext.current
    return remember(path) {
        path?.let { p ->
            // Downloaded with the catalog (CatalogUpdater), not bundled.
            runCatching { CatalogUpdater.artworkFile(context, p)?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() } }.getOrNull()
        }
    }
}

internal fun initials(name: String): String =
    name.split(' ', '-').filter { it.isNotBlank() && it[0].isLetterOrDigit() }.take(2)
        .joinToString("") { it.take(1).uppercase() }

// Big rank number to the left of a trending card.
@Composable
internal fun RankedItem(rank: Int, card: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            rank.toString(), fontSize = 96.sp, lineHeight = 96.sp, fontWeight = FontWeight.Black,
            color = HubColors.GlowPurple.copy(alpha = 0.85f),
            modifier = Modifier.padding(end = 6.dp).offset(y = 14.dp)
        )
        card()
    }
}

// "2026-10-19" -> "Oct 19". Falls back to the raw text if it doesn't parse.
internal fun shortDate(iso: String): String = runCatching {
    val parsed = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(iso)!!
    java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(parsed)
}.getOrDefault(iso)

// Featured apps carousel. Left/Right switch slides while it's focused; it auto-advances every
// 7 seconds while focus is elsewhere. OK opens (or shows details for) the current app.
@Composable
private fun HeroBanner(
    featured: List<HubApp>,
    installedPkgs: Set<String>,
    firstFocus: FocusRequester?,
    label: String,
    onOpen: (HubApp) -> Unit
) {
    var index by remember { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(focused, index, featured.size) {
        if (!focused && featured.size > 1) {
            delay(7_000)
            index = (index + 1) % featured.size
        }
    }
    val app = featured[index.coerceIn(0, featured.lastIndex)]
    val tint = app.categories.first().tint
    val installed = app.packageName in installedPkgs

    HubCard(
        onClick = { onOpen(app) },
        modifier = Modifier.padding(horizontal = ShelfInset, vertical = 10.dp).fillMaxWidth().height(200.dp)
            .onFocusChanged { focused = it.hasFocus }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || featured.size < 2) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionRight -> { index = (index + 1) % featured.size; true }
                    Key.DirectionLeft -> { index = (index - 1 + featured.size) % featured.size; true }
                    else -> false
                }
            }
            .then(if (firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier),
        tint = tint
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = HubColors.GlowPurple)
                AppNameWithVersion(app, nameSize = 34.sp, versionSize = 18.sp, nameWeight = FontWeight.SemiBold)
                app.description?.let {
                    Text(it, fontSize = 16.sp, color = HubColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                CategoryTags(app, Modifier.padding(top = 6.dp), max = Int.MAX_VALUE)
                Text(
                    stringResource(if (installed) R.string.hero_installed else R.string.hero_get),
                    fontSize = 16.sp, color = if (installed) HubColors.GlowGreen else HubColors.GlowBlue,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Spacer(Modifier.weight(1f))
                if (featured.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    featured.indices.forEach { i ->
                        Box(
                            Modifier.size(width = if (i == index) 22.dp else 8.dp, height = 8.dp)
                                .background(if (i == index) HubColors.GlowPurple else HubColors.Line, RoundedCornerShape(50))
                        )
                    }
                }
            }
            AppBanner(app, tint, Modifier.height(124.dp))
        }
    }
}
