package com.example.hub

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.userStore by preferencesDataStore(name = "hub_user")

// Favorites, recently opened apps and Hub installs not opened yet, stored by package name in DataStore.
object UserPrefs {
    private val FAVORITES = stringSetPreferencesKey("favorites")
    private val FAVORITE_ORDER = stringPreferencesKey("favorite_order") // newline-separated, as moved on Home
    private val RECENTS = stringPreferencesKey("recents") // newest first, newline-separated
    private val FRESH = stringSetPreferencesKey("fresh_installs") // installed by the Hub, not opened yet
    const val MAX_RECENTS = 12

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun favorites(context: Context): Flow<Set<String>> =
        context.applicationContext.userStore.data.map { it[FAVORITES] ?: emptySet() }

    // The order set by moving favorites on Home. Favorites not in it yet (added before ordering
    // existed) go after it in catalog order; see orderedFavorites.
    fun favoriteOrder(context: Context): Flow<List<String>> =
        context.applicationContext.userStore.data.map { p -> p[FAVORITE_ORDER]?.lines()?.filter { it.isNotBlank() } ?: emptyList() }

    fun setFavoriteOrder(context: Context, pkgs: List<String>) {
        scope.launch { context.applicationContext.userStore.edit { it[FAVORITE_ORDER] = pkgs.joinToString("\n") } }
    }

    fun recents(context: Context): Flow<List<String>> =
        context.applicationContext.userStore.data.map { p -> p[RECENTS]?.lines()?.filter { it.isNotBlank() } ?: emptyList() }

    fun freshInstalls(context: Context): Flow<Set<String>> =
        context.applicationContext.userStore.data.map { it[FRESH] ?: emptySet() }

    fun markFreshInstall(context: Context, pkg: String) {
        scope.launch { context.applicationContext.userStore.edit { p -> p[FRESH] = (p[FRESH] ?: emptySet()) + pkg } }
    }

    fun toggleFavorite(context: Context, pkg: String) {
        scope.launch {
            context.applicationContext.userStore.edit { p ->
                val current = p[FAVORITES] ?: emptySet()
                val order = p[FAVORITE_ORDER]?.lines()?.filter { it.isNotBlank() } ?: emptyList()
                // A new favorite goes last; a removed one leaves the order too.
                if (pkg in current) {
                    p[FAVORITES] = current - pkg
                    p[FAVORITE_ORDER] = (order - pkg).joinToString("\n")
                } else {
                    p[FAVORITES] = current + pkg
                    p[FAVORITE_ORDER] = (order - pkg + pkg).joinToString("\n")
                }
            }
        }
    }

    fun clearFavorites(context: Context) {
        scope.launch { context.applicationContext.userStore.edit { it.remove(FAVORITES); it.remove(FAVORITE_ORDER) } }
    }

    fun clearAll(context: Context) {
        scope.launch { context.applicationContext.userStore.edit { it.clear() } }
    }

    fun recordLaunch(context: Context, pkg: String) {
        scope.launch {
            context.applicationContext.userStore.edit { p ->
                val current = p[RECENTS]?.lines()?.filter { it.isNotBlank() } ?: emptyList()
                p[RECENTS] = pushRecent(current, pkg, MAX_RECENTS).joinToString("\n")
                p[FRESH]?.let { if (pkg in it) p[FRESH] = it - pkg }
            }
        }
    }
}

// Moves pkg to the front, removing any earlier copy, and keeps at most max entries.
fun pushRecent(recents: List<String>, pkg: String, max: Int): List<String> =
    (listOf(pkg) + recents.filter { it != pkg }).take(max)

// Favorite apps in the saved order; favorites missing from it follow in catalog order.
fun orderedFavorites(catalog: List<HubApp>, favorites: Set<String>, order: List<String>): List<HubApp> {
    val byPkg = catalog.associateBy { it.packageName }
    val ordered = order.filter { it in favorites }.mapNotNull { byPkg[it] }.distinctBy { it.packageName }
    val placed = ordered.map { it.packageName }.toSet()
    return ordered + catalog.filter { it.packageName in favorites && it.packageName !in placed }
}

// `list` with the item at `index` moved `by` places (negative = left), kept inside the list.
fun <T> moveItem(list: List<T>, index: Int, by: Int): List<T> {
    if (index !in list.indices) return list
    val target = (index + by).coerceIn(0, list.lastIndex)
    if (target == index) return list
    return list.toMutableList().apply { add(target, removeAt(index)) }
}
