@file:OptIn(ExperimentalTvMaterial3Api::class)
package com.example.hub

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*

// Full Trending list (nav destination): rank number plus the usual app row.
@Composable
fun TrendingScreen(resumeTick: Int) {
    val context = LocalContext.current
    val trending = remember { Trending.get(context) }
    val ranked = remember { trendingApps(trending, Catalog.get(context)).numbered() }
    val states by InstallCoordinator.states.collectAsState()
    val first = rememberInitialFocus(enabled = ranked.isNotEmpty())

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.shelf_trending), fontSize = 34.sp, lineHeight = 40.sp, color = HubColors.Text)
            trending.updated?.let { Text(stringResource(R.string.shelf_updated, shortDate(it)), fontSize = 16.sp, color = HubColors.Muted) }
        }
        if (ranked.isEmpty()) Text(stringResource(R.string.trending_empty), fontSize = 18.sp, color = HubColors.Muted)
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            itemsIndexed(ranked, key = { _, r -> r.app.packageName }) { i, r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        r.rank.toString(), fontSize = 44.sp, fontWeight = FontWeight.Black,
                        color = HubColors.GlowPurple, textAlign = TextAlign.Center, modifier = Modifier.width(76.dp)
                    )
                    AppRow(
                        r.app, states[r.app.packageName], resumeTick, Modifier.weight(1f),
                        actionModifier = if (i == 0) Modifier.focusRequester(first) else Modifier
                    )
                }
            }
        }
    }
}
