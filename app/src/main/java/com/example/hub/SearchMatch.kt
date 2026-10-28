package com.example.hub

// Lowercase, with punctuation turned into spaces and runs of spaces collapsed:
// "HD-Streamz!" -> "hd streamz".
internal fun normalizeForSearch(text: String): String =
    text.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
        .split(' ').filter { it.isNotEmpty() }.joinToString(" ")

// Catalog apps matching the query, best first: name matches, then category matches, then
// notes matches, each group in catalog order. Case and punctuation are ignored, and spaces are
// optional ("hdstreamz" finds "HD Streamz"). A blank query matches nothing. categoryNames gives
// every name a category answers to; the screen adds the translated one ("Deportes").
// Runs on the device only; the query never leaves it.
fun searchApps(
    query: String,
    apps: List<HubApp>,
    categoryNames: (Category) -> List<String> = { listOf(it.label) }
): List<HubApp> {
    val q = normalizeForSearch(query)
    if (q.isEmpty()) return emptyList()
    val qCompact = q.replace(" ", "")
    fun matches(text: String?): Boolean {
        if (text == null) return false
        val t = normalizeForSearch(text)
        return q in t || qCompact in t.replace(" ", "")
    }
    val byName = apps.filter { matches(it.name) }
    val byCategory = apps.filter { app -> app !in byName && app.categories.any { c -> categoryNames(c).any(::matches) } }
    val byNotes = apps.filter { it !in byName && it !in byCategory && matches(it.notes) }
    return byName + byCategory + byNotes
}
