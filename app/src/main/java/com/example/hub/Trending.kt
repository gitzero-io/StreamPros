package com.example.hub

import android.content.Context
import org.json.JSONObject

data class TrendingEntry(val rank: Int, val packageName: String)
data class TrendingList(val updated: String?, val entries: List<TrendingEntry>)
data class RankedApp(val rank: Int, val app: HubApp)

const val TRENDING_MAX_RANK = 10

// Parses trending.json ({updated, items[{rank, packageName}]}). Entries without a positive rank or a packageName are skipped.
fun parseTrending(json: String): TrendingList {
    val o = JSONObject(json)
    val arr = o.optJSONArray("items") ?: o.optJSONArray("entries")
    val entries = if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i ->
        val e = arr.optJSONObject(i) ?: return@mapNotNull null
        val rank = e.optInt("rank", 0)
        val pkg = e.optStringOrNull("packageName")
        if (rank <= 0 || pkg == null) null else TrendingEntry(rank, pkg)
    }
    return TrendingList(o.optStringOrNull("updated"), entries)
}

// Trending apps in rank order (1-10), skipping packages that aren't in the catalog and any
// package listed twice (the better rank wins).
fun trendingApps(trending: TrendingList, catalog: List<HubApp>): List<RankedApp> {
    val byPackage = catalog.associateBy { it.packageName }
    return trending.entries
        .filter { it.rank in 1..TRENDING_MAX_RANK }
        .sortedBy { it.rank }
        .distinctBy { it.packageName }
        .mapNotNull { e -> byPackage[e.packageName]?.let { RankedApp(e.rank, it) } }
}

// Numbers the list 1, 2, 3... for display, so a skipped entry doesn't leave a gap.
fun List<RankedApp>.numbered(): List<RankedApp> = mapIndexed { i, r -> r.copy(rank = i + 1) }

// Loads the trending.json CatalogUpdater downloaded with the catalog. Kept until the catalog changes
// (reset). A missing or broken file means no trending row.
object Trending {
    @Volatile private var cached: TrendingList? = null

    fun get(context: Context): TrendingList = cached ?: synchronized(this) {
        cached ?: runCatching { parseTrending(CatalogUpdater.trendingFile(context).readText()) }
            .getOrDefault(TrendingList(null, emptyList())).also { cached = it }
    }

    internal fun reset() { cached = null }
}
