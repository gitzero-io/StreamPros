package com.example.hub

import android.content.Context
import androidx.annotation.StringRes
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

// label: the English name catalog.json uses; title: the name shown on screen, translated.
enum class Category(val label: String, @StringRes val title: Int) {
    MOVIES("Movies", R.string.category_movies),
    SHOWS("Shows", R.string.category_shows),
    SPORTS("Sports", R.string.category_sports),
    LIVE_TV("Live TV", R.string.category_live_tv),
    ANIME("Anime", R.string.category_anime),
    LATIN("Español", R.string.category_latin),
    PLAYER("Player", R.string.category_player),
    MEDIA_CLIENT("Media Client", R.string.category_media_client),
    IPTV_PLAYER("IPTV Player", R.string.category_iptv_player)
}

data class HubApp(
    val name: String,
    val packageName: String,
    val categories: List<Category>,   // one or more
    val apkUrl: String? = null,       // must be https
    val sha256: String? = null,       // optional file check; strongly recommended
    val version: String? = null,      // display version, e.g. "1.2"
    val versionCode: Long? = null,    // compared with the installed versionCode for updates
    val artwork: String? = null,      // square icon asset path, e.g. "artwork/app.png"; null = initials
    val featured: Boolean = false,    // shown in the Home hero banner
    val signerSha256: String? = null, // approved signing certificate; installs with another signer are refused
    val minSdk: Int? = null,          // hidden on devices older than this
    val abis: List<String> = emptyList(), // native ABIs the APK ships; empty = runs anywhere
    val banner: String? = null,       // 16:9 TV banner asset path, used by the hero
    val tvStatus: String = "untested", // tested, untested or "not working" (hidden)
    val notes: String? = null,        // curator notes; only used by Search
    val description: String? = null, // one or two sentences for App Detail (max 300 chars)
    val mature: Boolean = false,      // shows an 18+ label on its tiles and App Detail
    val updatedAt: String? = null     // when the catalog last got a newer version, e.g. "2026-10-22T06:10:41Z"
)

// Whether this app can install on a device with this SDK level and these supported ABIs.
fun HubApp.runsOn(sdk: Int, supportedAbis: List<String>): Boolean =
    (minSdk == null || sdk >= minSdk) && (abis.isEmpty() || abis.any { it in supportedAbis }) &&
        !tvStatus.equals("not working", ignoreCase = true)

// Parses catalog.json (schema checked by scripts/validate_catalog.py). Entries without a name or
// packageName are skipped, unknown categories are ignored, and an entry left with no known category
// is skipped. Categories match the label ("Live TV") or the enum name ("LIVE_TV"), ignoring case.
fun parseCatalog(json: String): List<HubApp> {
    val apps = JSONObject(json).optJSONArray("apps") ?: return emptyList()
    return (0 until apps.length()).mapNotNull { i ->
        val o = apps.optJSONObject(i) ?: return@mapNotNull null
        val name = o.optStringOrNull("name") ?: return@mapNotNull null
        val pkg = o.optStringOrNull("packageName") ?: return@mapNotNull null
        val categories = o.optJSONArray("categories").strings()
            .mapNotNull { c ->
                Category.values().firstOrNull { it.label.equals(c, ignoreCase = true) || it.name.equals(c, ignoreCase = true) }
            }
        if (categories.isEmpty()) return@mapNotNull null
        HubApp(
            name = name,
            packageName = pkg,
            categories = categories,
            apkUrl = o.optStringOrNull("apkUrl"),
            sha256 = o.optStringOrNull("sha256"),
            version = o.optStringOrNull("versionName") ?: o.optStringOrNull("version"),
            versionCode = if (o.has("versionCode") && !o.isNull("versionCode")) o.optLong("versionCode") else null,
            artwork = o.optStringOrNull("artwork"),
            featured = o.optBoolean("featured", false),
            signerSha256 = o.optStringOrNull("signerSha256"),
            minSdk = if (o.has("minSdk") && !o.isNull("minSdk")) o.optInt("minSdk") else null,
            abis = o.optJSONArray("abis").strings(),
            banner = o.optStringOrNull("banner"),
            tvStatus = o.optStringOrNull("tvStatus") ?: "untested",
            notes = o.optStringOrNull("notes"),
            description = o.optStringOrNull("description"),
            mature = o.optBoolean("mature", false),
            updatedAt = o.optStringOrNull("updatedAt")
        )
    }
}

// Header fields of catalog.json, shown in Settings. total counts every entry in the file.
// catalogVersion is stamped by scripts/sign_catalog.py (0 if missing); a downloaded catalog is only
// used when it is higher than the one in use.
data class CatalogMeta(val schemaVersion: Int?, val updated: String?, val total: Int, val catalogVersion: Long = 0)

fun parseCatalogMeta(json: String): CatalogMeta {
    val o = JSONObject(json)
    return CatalogMeta(
        schemaVersion = if (o.has("schemaVersion") && !o.isNull("schemaVersion")) o.optInt("schemaVersion") else null,
        updated = o.optStringOrNull("updated"),
        total = o.optJSONArray("apps")?.length() ?: 0,
        catalogVersion = if (o.has("catalogVersion") && !o.isNull("catalogVersion")) o.optLong("catalogVersion", 0) else 0
    )
}

// org.json returns "null" for JSON null with optString, so check explicitly.
internal fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

internal fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }

// The catalog minus 18+ apps when the viewer has turned on "Hide 18+ apps".
fun visibleApps(apps: List<HubApp>, hideMature: Boolean): List<HubApp> =
    if (hideMature) apps.filterNot { it.mature } else apps

// The catalog in use: the signed copy CatalogUpdater downloaded from the license server. The APK
// ships no catalog, so it is empty until the device is unlocked (License). Loaded once per process
// and kept to apps that can run on this device; CatalogUpdater swaps it when a newer catalog arrives
// (or clears it when the license ends) and bumps revision so screens rebuild.
object Catalog {
    private class Loaded(val apps: List<HubApp>, val meta: CatalogMeta)
    @Volatile private var loaded: Loaded? = null
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision

    // What every screen shows: apps for this device, without 18+ apps if the viewer hid them.
    fun get(context: Context): List<HubApp> = visibleApps(all(context), HubSettings.hideMature(context))

    // Every app that runs on this device, whatever the viewer's settings.
    fun all(context: Context): List<HubApp> = load(context).apps

    fun meta(context: Context): CatalogMeta = load(context).meta

    // Makes a newly saved (or just cleared) catalog the one in use.
    internal fun reload(context: Context) {
        synchronized(this) { loaded = null; load(context) }
        _revision.update { it + 1 }
    }

    private fun load(context: Context): Loaded = loaded ?: synchronized(this) {
        loaded ?: run {
            val saved = CatalogUpdater.saved(context)
            val apps = saved?.let { parseCatalog(it.first) }.orEmpty()
            Loaded(
                apps.filter { it.runsOn(Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toList()) },
                saved?.second ?: CatalogMeta(null, null, 0)
            ).also { loaded = it }
        }
    }
}

// Categories that have at least one app, in enum order. Empty categories are hidden from the
// nav bar, Browse and the setup picks so nothing leads to an empty screen.
fun categoriesInUse(apps: List<HubApp>): List<Category> =
    Category.values().filter { c -> apps.any { c in it.categories } }

// How long an app stays in Home's "Recently Updated" row after a new version.
const val RECENT_UPDATE_DAYS = 14

// Apps that got a newer version in the catalog within the last `days` days of `nowMillis`, newest
// first. Apps without a (readable) updatedAt are left out; a date ahead of the clock still counts.
fun recentlyUpdated(apps: List<HubApp>, nowMillis: Long, days: Int = RECENT_UPDATE_DAYS): List<HubApp> {
    val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
    val since = nowMillis - days * 24L * 60 * 60 * 1000
    return apps.mapNotNull { app ->
        val at = app.updatedAt?.let { runCatching { format.parse(it)?.time }.getOrNull() } ?: return@mapNotNull null
        if (at >= since) app to at else null
    }.sortedByDescending { it.second }.map { it.first }
}

// Apps matching any picked category, each listed once.
fun appsFor(interests: Set<Category>, apps: List<HubApp>): List<HubApp> =
    apps.filter { app -> app.categories.any { it in interests } }.distinctBy { it.packageName }
