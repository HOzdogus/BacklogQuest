package com.backlogquest.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.backlogquest.app.data.ACHIEVEMENTS
import com.backlogquest.app.data.BacklogViewModel
import com.backlogquest.app.data.allDone
import com.backlogquest.app.data.beaten
import com.backlogquest.app.data.totals

@Composable
fun TrophiesScreen(vm: BacklogViewModel) {
    val s = vm.state
    val ref = vm.ref
    val results = remember(s, ref) {
        val t = totals(s, ref)
        ACHIEVEMENTS.map { it to it.check(t, s, ref) }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { StatusPanel(vm) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("TROPHY ROOM", Modifier.weight(1f))
                StatusLabel("${results.count { it.second }} / ${ACHIEVEMENTS.size}")
            }
        }
        // Two trophies per row.
        items(results.chunked(2)) { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (a, on) ->
                    val (color, code) = when (a.tier) {
                        'G' -> BQ.Gold to "GLD"
                        'S' -> BQ.Silver to "SLV"
                        else -> BQ.Bronze to "BRZ"
                    }
                    Row(
                        Modifier
                            .weight(1f)
                            .alpha(if (on) 1f else 0.45f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(BQ.Shell2)
                            .border(1.dp, BQ.Edge, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (on) color else BQ.Edge),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                code, color = if (on) BQ.Shell else BQ.Muted,
                                fontFamily = BQ.Mono, fontWeight = FontWeight.Bold, fontSize = 9.sp,
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(a.name, color = BQ.Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(a.desc, color = BQ.Muted, fontSize = 12.sp)
                        }
                    }
                }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

private class StatTile(val value: String, val label: String)

@Composable
fun StatsScreen(vm: BacklogViewModel) {
    val s = vm.state
    val ref = vm.ref
    val data = remember(s, ref) {
        val b = beaten(s)
        val back = s.games.filter { it.status == "backlog" || it.status == "playing" }
        val estTotal = back.sumOf { it.est }
        val known = back.count { it.est > 0 }
        val months = if (back.isEmpty()) 0 else (back.size + 1) / 2
        val yearAgo = java.time.LocalDate.now().minusDays(365).toString()
        val lastYear = b.count { it.beatenAt >= yearAgo }
        val withHours = b.filter { it.hours > 0 }
        val perDevice = s.devices.map { d ->
            val mine = b.filter { it.device == d.id }
            Triple(d, mine.size, mine.maxOfOrNull { it.beatenAt } ?: "")
        }
        val neglected = perDevice.filter { !it.first.exempt }.minByOrNull { it.third }

        val tiles = listOf(
            StatTile("${back.size}", "games left to beat"),
            StatTile(
                if (estTotal > 0) "${estTotal.toInt()}h" else "—",
                "estimated time left" + if (known < back.size && back.isNotEmpty()) " ($known/${back.size} have estimates)" else "",
            ),
            StatTile(
                when {
                    months == 0 -> "—"
                    months >= 12 -> "%.1f yrs".format(months / 12.0)
                    else -> "$months mo"
                },
                "to clear it at 2 games a month",
            ),
            StatTile("$lastYear", "beaten in the last 12 months (logged here)"),
            StatTile(
                if (withHours.isEmpty()) "—" else "${(withHours.sumOf { it.hours } / withHours.size).toInt()}h",
                "average time per completion",
            ),
            StatTile(neglected?.first?.name ?: "—", "most neglected device"),
        )

        val done = allDone(s, ref)
        val byYear = done.mapNotNull { it.date.take(4).ifBlank { null } }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.key }.map { it.key to it.value }
        val undated = done.count { it.date.isBlank() }
        val platforms = done.groupingBy { it.platform }.eachCount()
        val topPlatforms = platforms.entries.sortedByDescending { it.value }.take(8).map { it.key to it.value }

        StatsData(tiles, byYear, undated, topPlatforms, platforms.size, perDevice.map { it.first.name to it.second })
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { StatusPanel(vm) }
        item { SectionTitle("BACKLOG BRAIN") }
        items(data.tiles.chunked(2)) { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { tile ->
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(BQ.Shell2)
                            .border(1.dp, BQ.Edge, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Text(
                            tile.value, color = BQ.Ink, fontFamily = BQ.Mono, fontWeight = FontWeight.Bold,
                            fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(tile.label, color = BQ.Muted, fontSize = 12.sp)
                    }
                }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
        if (data.byYear.isNotEmpty()) {
            item {
                Panel {
                    SectionTitle("COMPLETIONS BY YEAR")
                    Bars(data.byYear, labelWidth = 44)
                    if (data.undated > 0) Hint("${data.undated} completions have no date.")
                }
            }
            item {
                Panel {
                    SectionTitle("TOP PLATFORMS")
                    Bars(data.topPlatforms, labelWidth = 100)
                    Hint("${data.platformCount} platforms in total.")
                }
            }
        }
        item {
            Panel {
                SectionTitle("COMPLETIONS BY DEVICE")
                Bars(data.perDevice, labelWidth = 100)
                Hint("Counts completions logged in this app since you started using it.")
            }
        }
    }
}

private class StatsData(
    val tiles: List<StatTile>,
    val byYear: List<Pair<String, Int>>,
    val undated: Int,
    val topPlatforms: List<Pair<String, Int>>,
    val platformCount: Int,
    val perDevice: List<Pair<String, Int>>,
)

@Composable
private fun Bars(rows: List<Pair<String, Int>>, labelWidth: Int) {
    val max = (rows.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { (label, n) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    label, Modifier.width(labelWidth.dp), color = BQ.Ink, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(2.dp)).background(BQ.Edge)) {
                    if (n > 0) {
                        Box(
                            Modifier
                                .fillMaxWidth((n.toFloat() / max).coerceIn(0.01f, 1f))
                                .fillMaxHeight()
                                .background(BQ.B),
                        )
                    }
                }
                Text(
                    "$n", Modifier.width(40.dp), color = BQ.B, fontFamily = BQ.Mono,
                    fontWeight = FontWeight.Bold, fontSize = 14.sp,
                )
            }
        }
    }
}
