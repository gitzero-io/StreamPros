@file:OptIn(ExperimentalTvMaterial3Api::class)
package com.example.hub

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*

// Chips shown on an app tile before the rest collapse into "+N".
const val MAX_TILE_TAGS = 2

// Label shown after the category chips for apps marked "mature" in the catalog; null otherwise.
const val MATURE_LABEL = "18+"

fun matureLabel(app: HubApp): String? = if (app.mature) MATURE_LABEL else null

// The categories a tile shows as chips, in the app's catalog order (the first is the primary
// one), plus how many more there are for the "+N" chip.
data class TagSet(val shown: List<Category>, val more: Int)

fun tagSet(categories: List<Category>, max: Int = MAX_TILE_TAGS): TagSet {
    val unique = categories.distinct()
    val shown = unique.take(max.coerceAtLeast(1))
    return TagSet(shown, unique.size - shown.size)
}

// How many chips (from the front) fit in maxWidth, leaving room for the "+N" chip that counts
// the rest. Keeps tiles from ever wrapping or growing, e.g. with a large system font. Always
// keeps at least the primary chip (clipped if even that doesn't fit).
internal fun tagsThatFit(chipWidths: List<Int>, extra: Int, gap: Int, maxWidth: Int, moreWidth: (Int) -> Int): Int {
    for (k in chipWidths.size downTo 1) {
        val hidden = extra + chipWidths.size - k
        var width = chipWidths.take(k).sum() + gap * (k - 1)
        if (hidden > 0) width += gap + moreWidth(hidden)
        if (width <= maxWidth) return k
    }
    return minOf(1, chipWidths.size)
}

private val TagGap = 5.dp
private val TagShape = RoundedCornerShape(50)

// One row of uniform category chips, used on every app tile and on App Detail. Chips aren't
// focusable (the tile is), always stay on one line and never change the tile's size.
// max = how many chips before "+N"; App Detail passes Int.MAX_VALUE to list them all.
// The app's 18+ label, if any, always shows last; category chips fold into "+N" to make room.
@Composable
fun CategoryTags(app: HubApp, modifier: Modifier = Modifier, max: Int = MAX_TILE_TAGS) {
    val tags = remember(app.categories, max) { tagSet(app.categories, max) }
    val label = matureLabel(app)
    SubcomposeLayout(modifier.clipToBounds()) { constraints ->
        val loose = Constraints(maxHeight = constraints.maxHeight)
        val gap = TagGap.roundToPx()
        val chips = subcompose("tags") {
            tags.shown.forEach { c -> TagChip(stringResource(c.title), icon = { CategoryIcon(c, HubColors.Muted, Modifier.size(13.dp)) }) }
        }.map { it.measure(loose) }
        val moreChips = mutableMapOf<Int, Placeable>()
        val more = { n: Int -> moreChips.getOrPut(n) { subcompose("more$n") { TagChip("+$n") }.single().measure(loose) } }
        val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val mature = label?.let { subcompose("mature") { TagChip(it, HubColors.Warning) }.single().measure(loose) }
        val reserved = mature?.let { it.width + gap } ?: 0
        val fit = tagsThatFit(chips.map { it.width }, tags.more, gap, maxWidth - reserved) { more(it).width }
        val hidden = tags.more + chips.size - fit
        val items = chips.take(fit) + listOfNotNull(if (hidden > 0) more(hidden) else null, mature)
        val height = items.maxOfOrNull { it.height } ?: 0
        val width = (items.sumOf { it.width } + gap * (items.size - 1).coerceAtLeast(0))
            .coerceIn(constraints.minWidth, maxWidth)
        layout(width, height) {
            var x = 0
            items.forEach { it.placeRelative(x, (height - it.height) / 2); x += it.width + gap }
        }
    }
}

// Muted label on SurfaceRaised with a Line border. Same look for every category: the label and
// icon tell them apart, not color. The 18+ label passes Warning for its text and border.
@Composable
private fun TagChip(label: String, color: Color = HubColors.Muted, icon: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.background(HubColors.SurfaceRaised, TagShape)
            .border(1.dp, if (color == HubColors.Muted) HubColors.Line else color, TagShape)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            icon()
            Spacer(Modifier.width(4.dp))
        }
        Text(label, fontSize = 14.sp, lineHeight = 18.sp, color = color, maxLines = 1, softWrap = false)
    }
}

// Small line icon per category, drawn so the app needs no icon library.
@Composable
fun CategoryIcon(category: Category, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(w * 0.11f, cap = StrokeCap.Round)
        when (category) {
            // Clapperboard: board plus a slanted clapper on top.
            Category.MOVIES -> {
                drawRect(color, Offset(w * 0.08f, h * 0.42f), Size(w * 0.84f, h * 0.5f), style = stroke)
                drawLine(color, Offset(w * 0.08f, h * 0.3f), Offset(w * 0.9f, h * 0.1f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.4f, h * 0.22f), Offset(w * 0.32f, h * 0.42f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.68f, h * 0.15f), Offset(w * 0.6f, h * 0.42f), stroke.width, StrokeCap.Round)
            }
            // TV set with antennas.
            Category.SHOWS -> {
                drawRoundRect(
                    color, Offset(w * 0.08f, h * 0.32f), Size(w * 0.84f, h * 0.58f),
                    CornerRadius(w * 0.1f), style = stroke
                )
                drawLine(color, Offset(w * 0.5f, h * 0.32f), Offset(w * 0.3f, h * 0.06f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.5f, h * 0.32f), Offset(w * 0.7f, h * 0.06f), stroke.width, StrokeCap.Round)
            }
            // Ball: circle with two seams.
            Category.SPORTS -> {
                drawCircle(color, w * 0.42f, Offset(w / 2, h / 2), style = stroke)
                drawArc(color, -60f, 120f, false, Offset(-w * 0.2f, h * 0.08f), Size(w * 0.84f, h * 0.84f), style = stroke)
                drawArc(color, 120f, 120f, false, Offset(w * 0.36f, h * 0.08f), Size(w * 0.84f, h * 0.84f), style = stroke)
            }
            // Broadcast: a dot with signal arcs either side.
            Category.LIVE_TV -> {
                drawCircle(color, w * 0.11f, Offset(w / 2, h / 2))
                drawArc(color, 135f, 90f, false, Offset(w * 0.2f, h * 0.2f), Size(w * 0.6f, h * 0.6f), style = stroke)
                drawArc(color, -45f, 90f, false, Offset(w * 0.2f, h * 0.2f), Size(w * 0.6f, h * 0.6f), style = stroke)
                drawArc(color, 135f, 90f, false, Offset(w * 0.02f, h * 0.02f), Size(w * 0.96f, h * 0.96f), style = stroke)
                drawArc(color, -45f, 90f, false, Offset(w * 0.02f, h * 0.02f), Size(w * 0.96f, h * 0.96f), style = stroke)
            }
            // Four-point sparkle.
            Category.ANIME -> {
                val c = w / 2
                val path = Path().apply {
                    moveTo(c, 0f)
                    quadraticTo(c, c, w, c)
                    quadraticTo(c, c, c, h)
                    quadraticTo(c, c, 0f, c)
                    quadraticTo(c, c, c, 0f)
                    close()
                }
                drawPath(path, color)
            }
            // Speech bubble with a tail, for Spanish-language apps.
            Category.LATIN -> {
                drawRoundRect(
                    color, Offset(w * 0.08f, h * 0.12f), Size(w * 0.84f, h * 0.58f),
                    CornerRadius(w * 0.16f), style = stroke
                )
                drawLine(color, Offset(w * 0.3f, h * 0.7f), Offset(w * 0.22f, h * 0.92f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.22f, h * 0.92f), Offset(w * 0.5f, h * 0.7f), stroke.width, StrokeCap.Round)
            }
            // Play button: a triangle inside a circle.
            Category.PLAYER -> {
                drawCircle(color, w * 0.42f, Offset(w / 2, h / 2), style = stroke)
                val path = Path().apply {
                    moveTo(w * 0.4f, h * 0.3f)
                    lineTo(w * 0.72f, h * 0.5f)
                    lineTo(w * 0.4f, h * 0.7f)
                    close()
                }
                drawPath(path, color)
            }
            // Library: a 2x2 grid of tiles, for apps that browse add-ons and catalogs.
            Category.MEDIA_CLIENT -> {
                val tile = Size(w * 0.34f, h * 0.34f)
                val radius = CornerRadius(w * 0.07f)
                listOf(0.1f to 0.1f, 0.56f to 0.1f, 0.1f to 0.56f, 0.56f to 0.56f).forEach { (x, y) ->
                    drawRoundRect(color, Offset(w * x, h * y), tile, radius, style = stroke)
                }
            }
            // TV screen on a stand with a play triangle, for players that load your own channel lists.
            Category.IPTV_PLAYER -> {
                drawRoundRect(
                    color, Offset(w * 0.06f, h * 0.12f), Size(w * 0.88f, h * 0.62f),
                    CornerRadius(w * 0.1f), style = stroke
                )
                drawLine(color, Offset(w * 0.32f, h * 0.92f), Offset(w * 0.68f, h * 0.92f), stroke.width, StrokeCap.Round)
                val path = Path().apply {
                    moveTo(w * 0.42f, h * 0.3f)
                    lineTo(w * 0.64f, h * 0.43f)
                    lineTo(w * 0.42f, h * 0.56f)
                    close()
                }
                drawPath(path, color)
            }
        }
    }
}
