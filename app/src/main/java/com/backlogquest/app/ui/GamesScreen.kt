package com.backlogquest.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.backlogquest.app.data.playedOn
import com.backlogquest.app.data.achText
import com.backlogquest.app.data.historyKey
import com.backlogquest.app.data.historyEdits
import com.backlogquest.app.data.Prices
import com.backlogquest.app.data.normTitle
import com.backlogquest.app.data.AppState
import com.backlogquest.app.data.BUCKETS
import com.backlogquest.app.data.BacklogViewModel
import com.backlogquest.app.data.Completion
import com.backlogquest.app.data.Game
import com.backlogquest.app.data.RefData
import com.backlogquest.app.data.WISH_TAGS
import com.backlogquest.app.data.WishItem
import com.backlogquest.app.data.beaten
import com.backlogquest.app.data.deviceName
import com.backlogquest.app.data.fmtDate
import com.backlogquest.app.data.fmtHours
import com.backlogquest.app.data.fmtNum
import com.backlogquest.app.data.gameXp
import com.backlogquest.app.data.inBucket
import com.backlogquest.app.data.wishTagOk

/** One row in the Games list: your game, an imported completion, or a wishlist game. */
private sealed class LibItem(val key: String) {
    class Own(val g: Game, val number: Int?) : LibItem("g:${g.id}")
    class Hist(val c: Completion, val number: Int, val index: Int) : LibItem("h:$index")
    class Wish(val w: WishItem, index: Int) : LibItem("w:$index:${w.id}")
}

private val LIB_TABS = listOf(
    "backlog" to "Backlog", "playing" to "Playing", "wishlist" to "Wishlist",
    "beaten" to "Beaten", "dropped" to "Dropped",
)

private fun buildList(
    s: AppState, ref: RefData, tab: String, search: String, time: String, wishTag: String, sort: String,
): List<LibItem> {
    val q = search.trim().lowercase()
    fun match(title: String, extra: String) =
        q.isEmpty() || title.lowercase().contains(q) || extra.lowercase().contains(q)

    return when (tab) {
        "beaten" -> {
            val base = s.baseline
            val edits = historyEdits(s)
            // History games you've edited show as your own (editable) copy, with the same number.
            val hist: List<LibItem> = ref.history.mapIndexed { i, c ->
                edits[historyKey(i, c)]?.let { LibItem.Own(it, base + i + 1) } ?: LibItem.Hist(c, base + i + 1, i)
            }
            val app: List<LibItem> = beaten(s).mapIndexed { i, g -> LibItem.Own(g, base + ref.history.size + i + 1) }
            (hist + app).asReversed().filter {
                when (it) {
                    is LibItem.Hist -> match(it.c.title, it.c.platform)
                    is LibItem.Own -> match(it.g.title, playedOn(s, it.g))
                    else -> true
                }
            }
        }
        "wishlist" -> {
            val own: List<LibItem> = s.games
                .filter { it.status == "wishlist" && match(it.title, playedOn(s, it)) && inBucket(it.est, time) }
                .map { LibItem.Own(it, null) }
            val imported: List<LibItem> = ref.wishlist.withIndex()
                .filter { (_, w) ->
                    w.id !in s.hiddenWish && wishTagOk(w, wishTag) && inBucket(w.hours, time) &&
                        match(w.title, w.platforms.joinToString(" "))
                }
                .map { (i, w) -> LibItem.Wish(w, i) }
            val all: List<LibItem> = own + imported
            fun hours(i: LibItem) = when (i) {
                is LibItem.Own -> i.g.est
                is LibItem.Wish -> i.w.hours
                else -> 0.0
            }
            fun title(i: LibItem) = when (i) {
                is LibItem.Own -> i.g.title
                is LibItem.Wish -> i.w.title
                else -> ""
            }
            if (sort == "short") {
                all.sortedWith(compareBy<LibItem>({ hours(it).let { h -> if (h > 0) h else 1e9 } }, { title(it).lowercase() }))
            } else if (sort == "price") {
                // Cheapest first; games without a price go to the end.
                all.sortedWith(compareBy<LibItem>({ s.prices[normTitle(title(it))]?.amount ?: 1e9 }, { title(it).lowercase() }))
            } else {
                all.sortedBy { title(it).lowercase() }
            }
        }
        else -> {
            val list = s.games.filter {
                it.status == tab && match(it.title, playedOn(s, it)) &&
                    (tab != "backlog" || inBucket(it.est, time))
            }
            val sorted = if (tab == "backlog" && sort == "short") {
                list.sortedWith(compareBy<Game>({ if (it.est > 0) it.est else 1e9 }, { it.title.lowercase() }))
            } else {
                list.sortedBy { it.title.lowercase() }
            }
            sorted.map { LibItem.Own(it, null) }
        }
    }
}

@Composable
fun GamesScreen(
    vm: BacklogViewModel,
    f: Filters,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onEditHistory: (Int) -> Unit,
    onOpenWish: (String) -> Unit,
) {
    val s = vm.state
    val ref = vm.ref
    val rows = remember(s, ref, f.libTab, f.search, f.time, f.wishTag, f.sort) {
        buildList(s, ref, f.libTab, f.search, f.time, f.wishTag, f.sort)
    }
    val counts = remember(s, ref) {
        LIB_TABS.associate { (id, _) ->
            id to when (id) {
                "beaten" -> ref.history.size + beaten(s).size
                "wishlist" -> ref.wishlist.count { it.id !in s.hiddenWish } + s.games.count { it.status == "wishlist" }
                else -> s.games.count { it.status == id }
            }
        }
    }
    val full: androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan =
        { GridItemSpan(maxLineSpan) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(100.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = full) { StatusPanel(vm) }
        item(span = full) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionTitle("GAMES", Modifier.weight(1f))
                TabChip("Covers", f.grid) { f.grid = true }
                TabChip("List", !f.grid) { f.grid = false }
                PixelButton("+ ADD", kind = BtnKind.B, onClick = onAdd)
            }
        }
        item(span = full) {
            ChipRow(
                LIB_TABS.map { (id, label) -> id to "$label ${"%,d".format(counts[id] ?: 0)}" },
                f.libTab,
            ) { f.libTab = it }
        }
        if (f.libTab == "wishlist" || f.libTab == "backlog") {
            item(span = full) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChipRow(BUCKETS, f.time) { f.time = it }
                    if (f.libTab == "wishlist") ChipRow(WISH_TAGS, f.wishTag) { f.wishTag = it }
                    ChipRow(
                        listOf("az" to "A–Z", "short" to "Shortest first") +
                            if (f.libTab == "wishlist") listOf("price" to "Cheapest first") else emptyList(),
                        f.sort,
                    ) { f.sort = it }
                    if (f.libTab == "wishlist") {
                        if (s.itadKey.isBlank()) {
                            Hint("Add a free IsThereAnyDeal key in Setup to see prices.")
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PixelButton(if (vm.priceBusy) "UPDATING…" else "UPDATE PRICES", enabled = !vm.priceBusy) {
                                    vm.updatePrices(vm.wishlistTitles())
                                }
                                Hint(vm.priceMessage, Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
        item(span = full) {
            OutlinedTextField(
                value = f.search,
                onValueChange = { f.search = it },
                placeholder = { Text("Search titles or platforms") },
                singleLine = true,
                colors = bqFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (f.libTab == "wishlist" || f.libTab == "backlog") {
            item(span = full) {
                val bucket = BUCKETS.firstOrNull { it.first == f.time }?.second
                Hint("${"%,d".format(rows.size)} game${if (rows.size == 1) "" else "s"}" +
                    if (f.time != "any" && bucket != null) " · $bucket" else "")
            }
        }
        if (rows.isEmpty()) {
            item(span = full) {
                Panel {
                    Hint(
                        if (f.search.isNotBlank() || f.time != "any" || f.wishTag != "all") "No games match these filters."
                        else if (f.libTab == "backlog") "Nothing here yet. Add the games you own but haven't finished."
                        else "Nothing here yet."
                    )
                }
            }
        }
        items(
            rows,
            key = { it.key },
            span = { if (f.grid) GridItemSpan(1) else GridItemSpan(maxLineSpan) },
        ) { item ->
            when (item) {
                is LibItem.Own -> {
                    val g = item.g
                    val n = item.number
                    val meta = if (g.status == "beaten") {
                        listOf(
                            playedOn(s, g),
                            if (g.hours > 0) "${fmtNum(g.hours)}h played" else "",
                            fmtDate(g.beatenAt),
                            if (g.histKey.isEmpty()) "+${gameXp(g)} XP" else "",
                            achText(g),
                        ).filter { it.isNotBlank() }.joinToString(" · ")
                    } else {
                        listOf(playedOn(s, g), g.genre, if (g.series.isNotBlank()) "${g.series} series" else "", estText(g), playedText(g.hours), achText(g))
                            .filter { it.isNotBlank() }.joinToString(" · ")
                    }
                    if (f.grid) {
                        GridCard(
                            g.title, g.genre.ifBlank { deviceName(s, g.device) }, g.cover,
                            badge = n?.let { "#$it" },
                            meta = listOf(
                                playedOn(s, g),
                                if (g.est > 0 && g.status != "beaten") "${fmtNum(g.est)}h" else "",
                                achText(g),
                            ).filter { it.isNotBlank() }.joinToString(" · "),
                        ) { onEdit(g.id) }
                    } else {
                        ListRow(
                            g.title, g.genre.ifBlank { deviceName(s, g.device) }, g.cover, meta,
                            trailing = n?.let { "#$it" }
                                ?: s.prices[normTitle(g.title)]?.takeIf { g.status == "wishlist" }?.let { Prices.format(it) }
                                ?: deviceName(s, g.device).uppercase(),
                            desc = g.desc,
                        ) { onEdit(g.id) }
                    }
                }
                is LibItem.Hist -> {
                    val c = item.c
                    val meta = listOf(c.platform, fmtDate(c.date)).filter { it.isNotBlank() }.joinToString(" · ")
                    if (f.grid) {
                        GridCard(c.title, c.platform, "", badge = "#${item.number}", meta = meta) { onEditHistory(item.index) }
                    } else {
                        ListRow(c.title, c.platform, "", meta, trailing = "#${item.number}", desc = "") { onEditHistory(item.index) }
                    }
                }
                is LibItem.Wish -> {
                    val w = item.w
                    if (f.grid) {
                        GridCard(
                            w.title, w.platforms.firstOrNull().orEmpty(), "",
                            badge = s.prices[normTitle(w.title)]?.let { Prices.format(it) } ?: fmtHours(w.hours),
                            meta = w.platforms.take(2).joinToString(", "),
                        ) { onOpenWish(w.id) }
                    } else {
                        ListRow(
                            w.title, w.platforms.firstOrNull().orEmpty(), "", platformList(w.platforms),
                            trailing = s.prices[normTitle(w.title)]?.let { p ->
                                Prices.format(p) + if (p.cut > 0) " −${p.cut}%" else ""
                            } ?: fmtHours(w.hours),
                            desc = "", tags = w.tags,
                        ) { onOpenWish(w.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GridCard(
    title: String,
    label: String,
    cover: String,
    badge: String?,
    meta: String,
    onClick: (() -> Unit)?,
) {
    val base = Modifier.fillMaxWidth()
    Column(
        if (onClick != null) base.clickable(onClick = onClick) else base,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Cover(title, label, cover, Modifier.fillMaxWidth(), badge = badge)
        Text(
            title, color = BQ.Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp, lineHeight = 16.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        if (meta.isNotBlank()) {
            Text(meta, color = BQ.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ListRow(
    title: String,
    label: String,
    cover: String,
    meta: String,
    trailing: String,
    desc: String,
    tags: List<String> = emptyList(),
    onClick: (() -> Unit)?,
) {
    val shape = RoundedCornerShape(10.dp)
    val base = Modifier
        .fillMaxWidth()
        .clip(shape)
        .background(BQ.Shell2)
        .border(1.dp, BQ.Edge, shape)
    Row(
        (if (onClick != null) base.clickable(onClick = onClick) else base).padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Cover(title, label, cover, Modifier.width(48.dp), small = true)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title, Modifier.weight(1f),
                    color = BQ.Ink, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    trailing, Modifier.padding(start = 8.dp),
                    color = BQ.B, fontFamily = BQ.Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1,
                )
            }
            if (meta.isNotBlank()) Text(meta, color = BQ.Muted, fontSize = 13.sp)
            if (tags.isNotEmpty()) TagRow(tags)
            if (desc.isNotBlank()) Text(desc, color = BQ.Ink.copy(alpha = 0.8f), fontSize = 13.sp)
        }
    }
}
