@file:OptIn(ExperimentalTvMaterial3Api::class)
package com.example.hub

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*

// One category's apps as a list of AppRows (nav destination).
@Composable
fun CategoryScreen(cat: Category, resumeTick: Int) {
    val context = LocalContext.current
    val states by InstallCoordinator.states.collectAsState()
    val apps = remember(cat) { Catalog.get(context).filter { cat in it.categories } }
    val firstAction = rememberInitialFocus(enabled = apps.isNotEmpty())
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(cat.title), fontSize = 38.sp, lineHeight = 44.sp, color = HubColors.Text)
        if (apps.isEmpty()) Text(stringResource(R.string.category_empty), fontSize = 18.sp, color = HubColors.Muted)
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            itemsIndexed(apps, key = { _, app -> app.packageName }) { i, app ->
                AppRow(
                    app, states[app.packageName], resumeTick,
                    actionModifier = if (i == 0) Modifier.focusRequester(firstAction) else Modifier
                )
            }
        }
    }
}
