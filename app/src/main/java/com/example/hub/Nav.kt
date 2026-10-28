@file:OptIn(ExperimentalTvMaterial3Api::class)
package com.example.hub

import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import java.util.Date

// Top-level destinations shown in the nav bar.
enum class NavItem(@StringRes val label: Int) {
    HOME(R.string.nav_home), MOVIES(R.string.category_movies), SHOWS(R.string.category_shows),
    SPORTS(R.string.category_sports), LIVE_TV(R.string.category_live_tv), ANIME(R.string.category_anime),
    LATIN(R.string.category_latin), PLAYER(R.string.category_player), MEDIA_CLIENT(R.string.category_media_client),
    IPTV_PLAYER(R.string.category_iptv_player), TRENDING(R.string.nav_trending), SETTINGS(R.string.nav_settings);

    val screen: Screen
        get() = when (this) {
            HOME -> Screen.Home
            MOVIES -> Screen.CategoryList(Category.MOVIES)
            SHOWS -> Screen.CategoryList(Category.SHOWS)
            SPORTS -> Screen.CategoryList(Category.SPORTS)
            LIVE_TV -> Screen.CategoryList(Category.LIVE_TV)
            ANIME -> Screen.CategoryList(Category.ANIME)
            LATIN -> Screen.CategoryList(Category.LATIN)
            PLAYER -> Screen.CategoryList(Category.PLAYER)
            MEDIA_CLIENT -> Screen.CategoryList(Category.MEDIA_CLIENT)
            IPTV_PLAYER -> Screen.CategoryList(Category.IPTV_PLAYER)
            TRENDING -> Screen.Trending
            SETTINGS -> Screen.Settings
        }

    companion object {
        fun forScreen(screen: Screen): NavItem? = values().firstOrNull { it.screen == screen }
    }
}

@Composable
fun TopNav(selected: NavItem?, onSearch: () -> Unit, onSelect: (NavItem) -> Unit) {
    // Moving into the bar from below always lands on the selected item, not the nearest one.
    val selectedFocus = remember { FocusRequester() }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = ShelfInset, vertical = 6.dp)
            .focusProperties { onEnter = { if (selected != null) selectedFocus.requestFocus() } }
            .focusGroup(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Image(
            painterResource(R.drawable.logo_wordmark), contentDescription = stringResource(R.string.app_name),
            modifier = Modifier.padding(end = 2.dp).height(52.dp)
        )
        val context = LocalContext.current
        val catalogRevision by Catalog.revision.collectAsState()
        val items = remember(catalogRevision) {
            val inUse = categoriesInUse(Catalog.get(context))
            NavItem.values().filter { item ->
                item != NavItem.SETTINGS && (item.screen as? Screen.CategoryList)?.category.let { it == null || it in inUse }
            }
        }
        val chip: @Composable (NavItem) -> Unit = { item ->
            NavChip(
                stringResource(item.label), item == selected,
                if (item == selected) Modifier.focusRequester(selectedFocus) else Modifier,
                gearIcon = item == NavItem.SETTINGS
            ) { onSelect(item) }
        }
        // Scrolls sideways when the tabs don't fit (many categories on a narrow TV), so the settings
        // and search buttons and the clock are never pushed off screen; focus brings the tab into
        // view. The inner padding keeps the end chips' focus glow inside the scroll clip.
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items.forEach { chip(it) }
        }
        // Settings stays put beside Search instead of scrolling with the tabs.
        chip(NavItem.SETTINGS)
        SearchButton(onSearch)
        Clock()
    }
}

// Nav entry: muted text at rest, white with a purple underline when selected,
// red border + glow when focused (red is focus only). gearIcon: shows a gear instead of the label.
@Composable
private fun NavChip(label: String, selected: Boolean, modifier: Modifier, gearIcon: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick,
        modifier = modifier.glow(HubColors.GlowRed, cornerRadius = 50.dp, spread = 12.dp)
            .semantics { contentDescription = label },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (selected) HubColors.Text else HubColors.Muted,
            focusedContainerColor = HubColors.SurfaceRaised,
            focusedContentColor = HubColors.Text,
            pressedContainerColor = HubColors.SurfaceRaised
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = focusBorder(shape)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f)
    ) {
        Column(
            // 10dp sides leave room for the search button and clock on a 960dp-wide TV.
            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (gearIcon) GearIcon(LocalContentColor.current, Modifier.size(22.dp))
            else Text(label, fontSize = 15.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            Box(
                Modifier.padding(top = 3.dp).size(width = 16.dp, height = 3.dp)
                    .background(if (selected) HubColors.GlowPurple else Color.Transparent, RoundedCornerShape(2.dp))
            )
        }
    }
}

// Magnifier icon button that opens Search. Same focus treatment as the nav chips.
@Composable
private fun SearchButton(onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val description = stringResource(R.string.cd_search)
    Surface(
        onClick = onClick,
        modifier = Modifier.size(44.dp).glow(HubColors.GlowRed, cornerRadius = 50.dp, spread = 12.dp)
            .semantics { contentDescription = description },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = HubColors.Muted,
            focusedContainerColor = HubColors.SurfaceRaised,
            focusedContentColor = HubColors.Text,
            pressedContainerColor = HubColors.SurfaceRaised
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = focusBorder(shape)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            SearchIcon(LocalContentColor.current, Modifier.size(20.dp))
        }
    }
}

// Gear: ring body, eight teeth and a center hole, drawn so the app needs no icon library.
@Composable
private fun GearIcon(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val d = size.minDimension
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(color, d * 0.27f, c, style = Stroke(d * 0.14f))
        for (i in 0 until 8) {
            val a = i * Math.PI / 4
            val dir = Offset(cos(a).toFloat(), sin(a).toFloat())
            drawLine(color, c + dir * (d * 0.3f), c + dir * (d * 0.47f), d * 0.15f, StrokeCap.Butt)
        }
    }
}

// Current time in the device's 12/24-hour format, refreshed on each minute boundary.
@Composable
private fun Clock() {
    val context = LocalContext.current
    val format = remember { DateFormat.getTimeFormat(context) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000 - now % 60_000)
            now = System.currentTimeMillis()
        }
    }
    // Never wraps: on a crowded bar "3:55 PM" split onto two lines (seen on an onn 4K Plus).
    Text(format.format(Date(now)), fontSize = 18.sp, color = HubColors.Text, maxLines = 1, softWrap = false)
}
