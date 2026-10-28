package com.backlogquest.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.backlogquest.app.data.BUCKETS
import com.backlogquest.app.data.BacklogViewModel
import com.backlogquest.app.data.Game
import com.backlogquest.app.data.WISH_TAGS
import com.backlogquest.app.data.deviceName
import com.backlogquest.app.data.fmtHours
import com.backlogquest.app.data.fmtNum
import com.backlogquest.app.data.inBucket
import com.backlogquest.app.data.rotation
import com.backlogquest.app.data.wishTagOk
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** "19h (HLTB)", "~12h" for an estimate, or "" when unknown. */
fun estText(g: Game): String {
    if (!(g.est > 0)) return ""
    return when (g.estSource) {
        "hltb" -> "${fmtNum(g.est)}h (HLTB)"
        "ai", "rawg" -> "~${fmtNum(g.est)}h"
        else -> "${fmtNum(g.est)}h"
    }
}

fun platformList(p: List<String>): String = when {
    p.isEmpty() -> "Platform unknown"
    p.size > 4 -> p.take(4).joinToString(", ") + " +${p.size - 4} more"
    else -> p.joinToString(", ")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayScreen(
    vm: BacklogViewModel,
    f: Filters,
    onAdd: () -> Unit,
    onOpenWish: (String) -> Unit,
) {
    val s = vm.state
    val ref = vm.ref
    val scope = rememberCoroutineScope()
    val rot = remember(s) { rotation(s) }

    var pickedDevice by rememberSaveable { mutableStateOf<String?>(null) }
    var pickedGame by rememberSaveable { mutableStateOf<String?>(null) }
    var wishPick by rememberSaveable { mutableStateOf<String?>(null) }
    var slotText by remember { mutableStateOf<String?>(null) }
    var slot2Text by remember { mutableStateOf<String?>(null) }
    var spinning by remember { mutableStateOf(false) }

    fun pool(dev: String): List<Game> = s.games.filter {
        it.device == dev && (it.status == "backlog" || it.status == "playing") && inBucket(it.est, f.time)
    }
    val eligible = s.devices.filter { d -> (rot[d.id]?.cool ?: 0) == 0 && pool(d.id).isNotEmpty() }
    val wishPool = remember(ref, s.hiddenWish, f.time, f.wishTag) {
        val tag = if (f.wishTag == "unreleased") "all" else f.wishTag
        ref.wishlist.filter {
            it.id !in s.hiddenWish && "unreleased" !in it.tags && wishTagOk(it, tag) && inBucket(it.hours, f.time)
        }
    }

    /** Flicks through names for about a second, then lands on a random one. */
    fun <T> spin(items: List<T>, label: (T) -> String, show: (String?) -> Unit, done: (T) -> Unit) {
        if (items.isEmpty() || spinning) return
        val final = items.random()
        if (items.size < 2) {
            done(final)
            return
        }
        spinning = true
        scope.launch {
            repeat(15) {
                show(label(items.random()))
                delay(70)
            }
            show(null)
            spinning = false
            done(final)
        }
    }

    val game = pickedGame?.let { id -> s.games.firstOrNull { it.id == id } }
    val wish = wishPick?.let { id -> ref.wishlist.firstOrNull { it.id == id } }
    val hasBacklog = s.games.any { it.status == "backlog" || it.status == "playing" }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { StatusPanel(vm) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionTitle("WHAT SHOULD I PLAY?")
                Hint("Pick a device yourself, or let the wheel choose one. Devices on cooldown are locked by your two-for-two rule.")
            }
        }
        item {
            ChipRow(BUCKETS, f.time) {
                f.time = it
                pickedGame = null
            }
        }
        item {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 2,
            ) {
                s.devices.forEach { d ->
                    val r = rot[d.id]
                    val n = pool(d.id).size
                    val cool = (r?.cool ?: 0) > 0
                    val usable = !cool && n > 0
                    val selected = pickedDevice == d.id
                    Surface(
                        onClick = {
                            pickedDevice = d.id
                            pickedGame = null
                            slotText = null
                        },
                        enabled = usable,
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) Color(0xFF2F2A20) else BQ.Shell,
                        border = BorderStroke(2.dp, if (selected) BQ.B else BQ.Edge),
                        modifier = Modifier.weight(1f).alpha(if (usable) 1f else 0.5f),
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(d.name, color = BQ.Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            when {
                                d.exempt -> StatusLabel("EXEMPT", BQ.OwnBg, BQ.B)
                                cool -> StatusLabel("COOLDOWN · ${r?.cool} TO GO", BQ.CoolBg, BQ.CoolFg)
                                else -> StatusLabel(
                                    "READY" + if ((r?.count ?: 0) > 0) " · 1 DONE" else "",
                                    BQ.OkBg, BQ.Ok,
                                )
                            }
                            Hint("$n game${if (n == 1) "" else "s"} ready")
                        }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelButton("SPIN DEVICE", kind = BtnKind.B, enabled = eligible.isNotEmpty() && !spinning) {
                    spin(eligible, { it.name }, { slotText = it }) { d ->
                        pickedDevice = d.id
                        pickedGame = null
                        slotText = "${d.name} it is. Now spin a game."
                    }
                }
                val dev = pickedDevice
                PixelButton(
                    "SPIN GAME", kind = BtnKind.A,
                    enabled = dev != null && pool(dev).isNotEmpty() && !spinning,
                ) {
                    if (dev != null) {
                        spin(pool(dev), { it.title }, { slotText = it }) { g ->
                            pickedGame = g.id
                            slotText = null
                        }
                    }
                }
            }
        }
        item {
            val text = slotText ?: game?.title ?: pickedDevice?.let { "Spin for a game on ${deviceName(s, it)}" }
                ?: "Choose or spin a device"
            Slot(text, dim = spinning || (slotText == null && game == null))
        }
        if (game != null) {
            item {
                Panel {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Cover(
                            game.title, game.genre.ifBlank { deviceName(s, game.device) }, game.cover,
                            Modifier.width(96.dp),
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                listOf(deviceName(s, game.device), estText(game), game.genre)
                                    .filter { it.isNotBlank() }.joinToString(" · "),
                                color = BQ.Ink,
                            )
                            if (game.desc.isNotBlank()) Hint(game.desc)
                            if (game.status == "playing") {
                                StatusLabel("ALREADY PLAYING", BQ.OkBg, BQ.Ok)
                            } else {
                                PixelButton("START QUEST", kind = BtnKind.A) {
                                    vm.update { st ->
                                        st.copy(games = st.games.map { if (it.id == game.id) it.copy(status = "playing") else it })
                                    }
                                    vm.showToast("QUEST STARTED\n${game.title}")
                                }
                            }
                        }
                    }
                }
            }
        }
        if (!hasBacklog && vm.loaded) {
            item {
                Panel {
                    Hint("Your backlog is empty. Add games in GAMES and the wheel will pick from them.")
                    PixelButton("ADD A GAME", kind = BtnKind.B, onClick = onAdd)
                }
            }
        }
        item { Box(Modifier.fillMaxWidth().height(2.dp).background(BQ.Edge)) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("PICK SOMETHING NEW", Modifier.weight(1f))
                    StatusLabel("${"%,d".format(wishPool.size)} MATCH")
                }
                Hint("Spins your wishlist using the same time filter. Unreleased games are skipped.")
            }
        }
        item { ChipRow(WISH_TAGS, f.wishTag) { f.wishTag = it } }
        item {
            PixelButton("SPIN WISHLIST", kind = BtnKind.A, enabled = wishPool.isNotEmpty() && !spinning) {
                spin(wishPool, { it.title }, { slot2Text = it }) { w -> wishPick = w.id }
            }
        }
        item {
            val text = slot2Text ?: wish?.title
                ?: if (ref.wishlist.isEmpty()) "Your wishlist is empty. Sync it in Setup." else "Spin for something new"
            Slot(text, dim = spinning || (slot2Text == null && wish == null))
        }
        if (wish != null) {
            item {
                Panel {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Cover(wish.title, wish.platforms.firstOrNull().orEmpty(), "", Modifier.width(96.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "${fmtHours(wish.hours)} main story",
                                color = BQ.B, fontFamily = BQ.Mono, fontWeight = FontWeight.Bold,
                            )
                            Hint(platformList(wish.platforms))
                            TagRow(wish.tags)
                            PixelButton("DETAILS", kind = BtnKind.A) { onOpenWish(wish.id) }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}
