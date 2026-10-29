package com.backlogquest.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.backlogquest.app.data.ACHIEVEMENTS
import com.backlogquest.app.data.BacklogViewModel
import com.backlogquest.app.data.today
import com.backlogquest.app.data.totals
import com.backlogquest.app.data.unlocked
import kotlinx.coroutines.delay

enum class Tab(val icon: String, val label: String) {
    PLAY("▶", "PLAY"),
    GAMES("≡", "GAMES"),
    TROPHIES("★", "TROPHY"),
    STATS("▦", "STATS"),
    SETUP("⚙", "SETUP"),
}

/** Filters shared between the Play and Games screens, as in the web version. */
class Filters {
    var time by mutableStateOf("any")
    var wishTag by mutableStateOf("all")
    var sort by mutableStateOf("az")
    var libTab by mutableStateOf("backlog")
    var grid by mutableStateOf(true)
    var search by mutableStateOf("")
}

/** What the edit sheet is showing: a new game, or an existing one by id. */
sealed class EditTarget {
    data class New(val status: String) : EditTarget()
    data class Existing(val id: String) : EditTarget()
    /** A game from your imported history, by its position in the history list. */
    data class History(val index: Int) : EditTarget()
}

@Composable
fun BacklogApp(vm: BacklogViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.PLAY) }
    val filters = remember { Filters() }
    var editing by remember { mutableStateOf<EditTarget?>(null) }
    var wishOpen by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = tab != Tab.PLAY) { tab = Tab.PLAY }

    Box(Modifier.fillMaxSize().background(BQ.Shell)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().statusBarsPadding()) {
                when (tab) {
                    Tab.PLAY -> PlayScreen(
                        vm, filters,
                        onAdd = { editing = EditTarget.New("backlog") },
                        onOpenWish = { wishOpen = it },
                    )
                    Tab.GAMES -> GamesScreen(
                        vm, filters,
                        onAdd = { editing = EditTarget.New(if (filters.libTab == "beaten") "beaten" else "backlog") },
                        onEdit = { editing = EditTarget.Existing(it) },
                        onEditHistory = { editing = EditTarget.History(it) },
                        onOpenWish = { wishOpen = it },
                    )
                    Tab.TROPHIES -> TrophiesScreen(vm)
                    Tab.STATS -> StatsScreen(vm)
                    Tab.SETUP -> SetupScreen(vm)
                }
            }
            BottomNav(tab) { tab = it }
        }
        ToastHost(vm)
    }

    editing?.let { target -> EditSheet(vm, target, onDismiss = { editing = null }) }
    wishOpen?.let { id -> WishSheet(vm, id, onDismiss = { wishOpen = null }) }
    SessionDialog(vm)
}

@Composable
private fun BottomNav(current: Tab, onSelect: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(BQ.Shell2)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(BQ.Edge))
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Tab.entries.forEach { t ->
                val on = t == current
                Surface(
                    onClick = { onSelect(t) },
                    color = Color.Transparent,
                    modifier = Modifier.weight(1f),
                ) {
                    Column(
                        Modifier.padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.size(30.dp).clip(CircleShape).background(if (on) BQ.A else BQ.Edge),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(t.icon, color = if (on) Color.White else BQ.Ink, fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            t.label, color = if (on) BQ.Ink else BQ.Muted,
                            fontFamily = BQ.Mono, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToastHost(vm: BacklogViewModel) {
    val text = vm.toast ?: return
    LaunchedEffect(text) {
        delay(2600)
        if (vm.toast == text) vm.toast = null
    }
    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp), contentAlignment = Alignment.TopCenter) {
        Text(
            text,
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(BQ.Lcd)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            color = BQ.LcdInk, fontFamily = BQ.Mono, fontSize = 17.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

/** The console bezel with the LCD status screen, shown at the top of every screen. */
@Composable
fun StatusPanel(vm: BacklogViewModel) {
    val s = vm.state
    val ref = vm.ref
    val t = remember(s, ref) { totals(s, ref) }
    val trophies = remember(s, ref) { unlocked(s, ref).size }
    val playing = s.games.filter { it.status == "playing" }
    val backlog = s.games.count { it.status == "backlog" }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomEnd = 34.dp, bottomStart = 14.dp))
            .background(BQ.Shell2)
            .border(
                2.dp, BQ.Edge,
                RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomEnd = 34.dp, bottomStart = 14.dp),
            )
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(BQ.A))
            Spacer(Modifier.width(6.dp))
            Text(
                "PLAYER ONE", Modifier.weight(1f),
                color = BQ.Muted, fontFamily = BQ.Mono, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            )
            Text(
                when {
                    !vm.loaded -> "LOADING…"
                    vm.syncing -> "SYNCING…"
                    else -> "SAVED ON PHONE"
                },
                color = BQ.Muted, fontFamily = BQ.Mono, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(BQ.Lcd)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("LV ", color = BQ.LcdInk, fontFamily = BQ.Mono, fontSize = 18.sp)
                Text(
                    "${t.level}", Modifier.weight(1f),
                    color = BQ.LcdInk, fontFamily = BQ.Mono, fontSize = 38.sp, fontWeight = FontWeight.Bold,
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text(t.rank, color = BQ.LcdInk, fontFamily = BQ.Mono, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("${"%,d".format(t.xp)} XP", color = BQ.LcdDim, fontFamily = BQ.Mono, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            // XP bar, drawn as chunky segments.
            Box(
                Modifier.fillMaxWidth().height(12.dp).border(2.dp, BQ.LcdInk).padding(2.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(t.pct.coerceIn(0.02f, 1f))
                        .fillMaxHeight()
                        .background(BQ.LcdInk),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${"%,d".format(t.toNext)} XP to level ${t.level + 1}",
                color = BQ.LcdDim, fontFamily = BQ.Mono, fontSize = 14.sp,
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                LcdStat("${t.beaten}", "BEATEN", Modifier.weight(1f))
                LcdStat("${t.year}", "IN ${today().take(4)}", Modifier.weight(1f))
                LcdStat("$backlog", "BACKLOG", Modifier.weight(1f))
                LcdStat("$trophies/${ACHIEVEMENTS.size}", "TROPHIES", Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(BQ.LcdDim))
            Spacer(Modifier.height(6.dp))
            Text(
                "▶ " + if (playing.isEmpty()) "NO ACTIVE QUEST"
                else "NOW PLAYING: " + playing.joinToString(", ") { it.title },
                color = BQ.LcdInk, fontFamily = BQ.Mono, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LcdStat(value: String, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = BQ.LcdInk, fontFamily = BQ.Mono, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = BQ.LcdDim, fontFamily = BQ.Mono, fontSize = 10.sp, maxLines = 1)
    }
}

