package com.backlogquest.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.backlogquest.app.data.BacklogViewModel
import com.backlogquest.app.data.Game
import com.backlogquest.app.data.Hltb
import com.backlogquest.app.data.historyKey
import com.backlogquest.app.data.Trophy
import com.backlogquest.app.data.Achievements
import com.backlogquest.app.data.Prices
import com.backlogquest.app.data.deviceName
import androidx.compose.ui.platform.LocalContext
import com.backlogquest.app.data.knownPlatforms
import com.backlogquest.app.data.Rawg
import com.backlogquest.app.data.STATUSES
import com.backlogquest.app.data.fmtHours
import com.backlogquest.app.data.fmtNum
import com.backlogquest.app.data.gameXp
import com.backlogquest.app.data.newId
import com.backlogquest.app.data.numberOf
import com.backlogquest.app.data.today
import com.backlogquest.app.data.totals
import com.backlogquest.app.data.unlocked
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditSheet(vm: BacklogViewModel, target: EditTarget, onDismiss: () -> Unit) {
    val s = vm.state
    // A game from your imported history: edit your copy if you've made one, otherwise start one.
    val histIndex = (target as? EditTarget.History)?.index
    val histEntry = histIndex?.let { vm.ref.history.getOrNull(it) }
    val histKey = if (histIndex != null && histEntry != null) historyKey(histIndex, histEntry) else ""
    val existing = when (target) {
        is EditTarget.Existing -> s.games.firstOrNull { it.id == target.id }
        is EditTarget.History -> s.games.firstOrNull { histKey.isNotEmpty() && it.histKey == histKey }
        else -> null
    }
    if ((target is EditTarget.Existing && existing == null) || (target is EditTarget.History && histEntry == null)) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val newStatus = (target as? EditTarget.New)?.status ?: "backlog"
    val start = existing ?: if (histEntry != null) {
        // Match the history platform to one of your devices if possible.
        val dev = s.devices.firstOrNull { it.name.equals(histEntry.platform, ignoreCase = true) } ?: s.devices.first()
        Game(
            id = "", title = histEntry.title, device = dev.id, status = "beaten",
            platform = if (histEntry.platform.equals(dev.name, ignoreCase = true)) "" else histEntry.platform,
            beatenAt = histEntry.date, histKey = histKey,
        )
    } else Game(
        id = "", title = "", device = s.devices.first().id, status = newStatus, beatenAt = today(),
    )

    var title by remember(target) { mutableStateOf(start.title) }
    var device by remember(target) { mutableStateOf(start.device) }
    var platform by remember(target) { mutableStateOf(start.platform) }
    var foundPlatforms by remember(target) { mutableStateOf(listOf<String>()) }
    var status by remember(target) { mutableStateOf(start.status) }
    var genre by remember(target) { mutableStateOf(start.genre) }
    var est by remember(target) { mutableStateOf(if (start.est > 0) fmtNum(start.est) else "") }
    var estSource by remember(target) { mutableStateOf(if (start.est > 0) start.estSource.ifBlank { "manual" } else "") }
    var series by remember(target) { mutableStateOf(start.series) }
    var desc by remember(target) { mutableStateOf(start.desc) }
    var hours by remember(target) { mutableStateOf(if (start.hours > 0) fmtNum(start.hours) else "") }
    var date by remember(target) { mutableStateOf(start.beatenAt.ifBlank { today() }) }
    var cover by remember(target) { mutableStateOf(start.cover) }
    var note by remember(target) { mutableStateOf("") }
    var coverNote by remember(target) { mutableStateOf("") }
    var busy by remember(target) { mutableStateOf(false) }
    var confirmDelete by remember(target) { mutableStateOf(false) }
    var achievements by remember(target) { mutableStateOf(start.achievements) }
    var achSource by remember(target) { mutableStateOf(start.achSource) }
    var showAchievements by remember(target) { mutableStateOf(false) }
    var achBusy by remember(target) { mutableStateOf(false) }
    var achNote by remember(target) { mutableStateOf("") }
    val ctx = LocalContext.current

    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            coverNote = "Adding cover…"
            scope.launch {
                val path = vm.importCover(uri)
                if (path != null) {
                    cover = path
                    coverNote = "Cover added. Save to keep it."
                } else {
                    coverNote = "Couldn't use that image. Try another one."
                }
            }
        }
    }

    fun autoFill() {
        val t = title.trim()
        if (t.isEmpty()) {
            note = "Type a title first."
            return
        }
        busy = true
        note = "Looking up $t…"
        scope.launch {
            val parts = mutableListOf<String>()
            var hltbCover = ""
            var lookupTitle = t
            // Time to beat from HowLongToBeat.
            try {
                val h = Hltb.lookup(t)
                if (h == null) {
                    parts += "Not found on HowLongToBeat."
                } else {
                    title = h.title.take(120)
                    lookupTitle = h.title
                    hltbCover = h.cover
                    foundPlatforms = h.platforms
                    if (h.main > 0) {
                        est = fmtNum((h.main * 2).roundToInt() / 2.0)
                        estSource = "hltb"
                    }
                    val times = listOfNotNull(
                        if (h.main > 0) "${fmtHours(h.main)} main" else null,
                        if (h.extras > 0) "${fmtHours(h.extras)} + extras" else null,
                        if (h.completionist > 0) "${fmtHours(h.completionist)} 100%" else null,
                    )
                    parts += if (times.isEmpty()) "Found on HowLongToBeat, but it has no times yet."
                    else "HowLongToBeat: " + times.joinToString(" · ")
                }
            } catch (e: Hltb.LookupError) {
                parts += e.message ?: "Couldn't reach HowLongToBeat."
            } catch (e: Exception) {
                parts += "Couldn't reach HowLongToBeat. Check your connection."
            }

            // Genre, description and cover from RAWG, if you've added a key.
            if (vm.state.rawgKey.isNotBlank()) {
                try {
                    val r = Rawg.lookup(vm.state.rawgKey, lookupTitle)
                    if (r == null) {
                        parts += "Not found on RAWG."
                    } else {
                        if (genre.isBlank()) genre = r.genre.take(40)
                        if (desc.isBlank()) desc = r.description
                        if (cover.isBlank() && r.cover.isNotBlank()) cover = r.cover
                        if (r.hours > 0 && estSource != "manual" && estSource != "hltb") {
                            est = fmtNum((r.hours * 2).roundToInt() / 2.0)
                            estSource = "rawg"
                        }
                    }
                } catch (e: Rawg.LookupError) {
                    parts += e.message ?: "RAWG lookup failed."
                } catch (e: Exception) {
                    parts += "RAWG lookup failed."
                }
            }
            if (cover.isBlank() && hltbCover.isNotBlank()) cover = hltbCover

            // Achievements / trophies, from the site that suits the platform.
            val draft = Game(id = "", title = lookupTitle, device = device, status = status, platform = platform,
                source = existing?.source.orEmpty())
            val source = Achievements.pickSource(draft, deviceName(vm.state, device))
            if (source != null) {
                note = (parts + "Finding achievements on $source…").joinToString("\n")
                try {
                    val found = Achievements.find(ctx, source, lookupTitle, platform.ifBlank { deviceName(vm.state, device) }, vm.state)
                    achievements = Achievements.mergeEarned(achievements, found.list)
                    achSource = source
                    parts += "${found.list.size} achievements from $source."
                } catch (e: Achievements.AchError) {
                    parts += e.message ?: "Couldn't find achievements."
                } catch (e: Exception) {
                    parts += "Couldn't reach $source for achievements."
                }
            }

            // Price, for wishlist games.
            if (status == "wishlist" && vm.state.itadKey.isNotBlank()) vm.updatePrices(listOf(lookupTitle))

            parts += "Check it over, then save."
            note = parts.joinToString("\n")
            busy = false
        }
    }

    fun save() {
        val t = title.trim()
        if (t.isEmpty()) {
            note = "Add a title before saving."
            return
        }
        val before = unlocked(s, vm.ref)
        val levelBefore = totals(s, vm.ref).level
        val wasBeaten = existing?.status == "beaten"
        val estNum = est.toDoubleOrNull() ?: 0.0
        val baseGame = existing ?: Game(
            id = newId(), title = t, device = device, status = status,
            addedAt = today(), order = System.currentTimeMillis(), histKey = start.histKey,
        )
        var rec = baseGame.copy(
            title = t, device = device, status = status, genre = genre.trim(), est = estNum,
            series = series.trim(), desc = desc.trim(), platform = platform.trim(),
            estSource = if (estNum > 0) estSource.ifBlank { "manual" } else "",
            cover = cover,
            achievements = achievements,
            achSource = if (achievements.isEmpty()) "" else achSource,
        )
        if (status == "beaten") {
            rec = rec.copy(
                hours = hours.toDoubleOrNull() ?: 0.0,
                beatenAt = date.trim().ifBlank { today() },
                order = if (rec.order == 0L) System.currentTimeMillis() else rec.order,
            )
        }
        val saved = rec
        // Tidy up a cover image we replaced.
        val oldCover = existing?.cover.orEmpty()
        if (oldCover.startsWith("/") && oldCover != saved.cover) File(oldCover).delete()

        vm.update { st ->
            if (existing != null) st.copy(games = st.games.map { if (it.id == saved.id) saved else it })
            else st.copy(games = st.games + saved)
        }
        onDismiss()

        if (saved.status == "beaten" && !wasBeaten && saved.histKey.isEmpty()) {
            val ns = vm.state
            val fresh = unlocked(ns, vm.ref).filter { it !in before }
            val level = totals(ns, vm.ref).level
            val others = if (saved.series.isNotBlank()) {
                ns.games.count { it.id != saved.id && it.series.equals(saved.series, ignoreCase = true) && it.status != "beaten" }
            } else 0
            val lines = mutableListOf(
                "GAME #${numberOf(ns, vm.ref, saved) ?: "?"} BEATEN",
                "+${gameXp(saved)} XP",
            )
            if (level > levelBefore) lines += "LEVEL UP! LV $level"
            if (fresh.isNotEmpty()) lines += "★ " + fresh.joinToString(", ")
            if (saved.series.isNotBlank()) {
                lines += "${saved.series} series" + if (others > 0) " · $others more in your backlog" else ""
            }
            vm.showToast(lines.joinToString("\n"))
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BQ.Shell2,
        contentColor = BQ.Ink,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle(if (existing != null || histEntry != null) "EDIT GAME" else "ADD A GAME")
            if (start.histKey.isNotEmpty()) {
                Hint(
                    "From your HowLongToBeat history. Saving keeps your own copy with the details you add; " +
                        "its number and date stay the same." + if (existing != null) " Delete it to go back to the original." else "",
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text("Title") }, singleLine = true, colors = bqFieldColors(),
                    modifier = Modifier.weight(1f),
                )
                PixelButton("AUTO-FILL", kind = BtnKind.B, enabled = !busy) { autoFill() }
            }
            Hint(
                note.ifBlank {
                    if (vm.state.rawgKey.isNotBlank()) "Type a title and tap Auto-fill for the HowLongToBeat time, genre, description and cover."
                    else "Type a title and tap Auto-fill for the HowLongToBeat time. Add a free RAWG key in Setup for genre and description too."
                },
            )

            Text("Device", color = BQ.Muted, fontSize = 12.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                s.devices.forEach { d -> TabChip(d.name, device == d.id) { device = d.id } }
            }
            OutlinedTextField(
                value = platform, onValueChange = { platform = it.take(40) },
                label = { Text("Platform") },
                placeholder = { Text("Leave blank if it's the same as the device") },
                singleLine = true, colors = bqFieldColors(), modifier = Modifier.fillMaxWidth(),
            )
            val platformChoices = (foundPlatforms + knownPlatforms(s)).distinctBy { it.lowercase() }.take(10)
            if (platformChoices.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    platformChoices.forEach { p ->
                        TabChip(p, platform.equals(p, ignoreCase = true)) {
                            platform = if (platform.equals(p, ignoreCase = true)) "" else p
                        }
                    }
                }
            }
            Hint("The system the game is for, e.g. PS2 when you play it on PC through an emulator.")
            Text("Status", color = BQ.Muted, fontSize = 12.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                STATUSES.forEach { (key, label) -> TabChip(label, status == key) { status = key } }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = genre, onValueChange = { genre = it },
                    label = { Text("Genre") }, singleLine = true, colors = bqFieldColors(),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = est,
                    onValueChange = {
                        est = it
                        estSource = if ((it.toDoubleOrNull() ?: 0.0) > 0) "manual" else ""
                    },
                    label = { Text("Hours to beat") }, singleLine = true, colors = bqFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }
            Hint(
                when {
                    (est.toDoubleOrNull() ?: 0.0) <= 0 -> "Main story time. Leave blank if you don't know."
                    estSource == "hltb" -> "Time from HowLongToBeat."
                    estSource == "rawg" || estSource == "ai" -> "Time is an estimate. Change it if you know better."
                    else -> "Time entered by you."
                },
            )
            OutlinedTextField(
                value = series, onValueChange = { series = it },
                label = { Text("Series") }, placeholder = { Text("Leave blank if standalone") },
                singleLine = true, colors = bqFieldColors(), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = desc, onValueChange = { desc = it },
                label = { Text("Description") }, minLines = 2, colors = bqFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            // ---- Achievements ----
            Text("Achievements", color = BQ.Muted, fontSize = 12.sp)
            if (achievements.isEmpty()) {
                Hint("None yet. Auto-fill looks them up, or pick a site:")
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${achievements.count { it.earned }} / ${achievements.size} earned",
                        color = BQ.B, fontFamily = BQ.Mono, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                        modifier = Modifier.weight(1f),
                    )
                    PixelButton("VIEW LIST", kind = BtnKind.B) { showAchievements = true }
                }
                Hint("From $achSource. Look up again on:")
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Achievements.SOURCES.forEach { src ->
                    TabChip(src, src == achSource && achievements.isNotEmpty()) {
                        val t = title.trim()
                        if (t.isEmpty() || achBusy) return@TabChip
                        achBusy = true
                        achNote = "Looking on $src…"
                        scope.launch {
                            try {
                                val found = Achievements.find(ctx, src, t, platform.ifBlank { deviceName(vm.state, device) }, vm.state)
                                achievements = Achievements.mergeEarned(achievements, found.list)
                                achSource = src
                                achNote = "Found ${found.list.size} on $src (\"${found.title}\"). Save to keep them."
                            } catch (e: Achievements.AchError) {
                                achNote = e.message ?: "Couldn't find achievements."
                            } catch (e: Exception) {
                                achNote = "Couldn't reach $src."
                            } finally {
                                achBusy = false
                            }
                        }
                    }
                }
            }
            if (achNote.isNotBlank()) Hint(achNote)

            // ---- Price (wishlist) ----
            if (status == "wishlist") {
                Text("Price", color = BQ.Muted, fontSize = 12.sp)
                val price = vm.priceFor(title)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        price?.let { Prices.describe(it) } ?: "Not checked yet",
                        color = if (price != null) BQ.Ink else BQ.Muted, fontSize = 14.sp, modifier = Modifier.weight(1f),
                    )
                    if (vm.state.itadKey.isNotBlank()) {
                        PixelButton(if (vm.priceBusy) "…" else "CHECK", enabled = !vm.priceBusy && title.isNotBlank()) {
                            vm.updatePrices(listOf(title.trim()))
                        }
                    }
                }
                if (vm.state.itadKey.isBlank()) Hint("Add a free IsThereAnyDeal key in Setup to see prices.")
                else if (vm.priceMessage.isNotBlank() && !vm.priceBusy) Hint(vm.priceMessage)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Cover(title.ifBlank { "?" }, genre, cover, Modifier.width(96.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Hint("Cover image")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PixelButton(if (cover.isBlank()) "ADD COVER" else "CHANGE", kind = BtnKind.B) {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                        if (cover.isNotBlank()) {
                            PixelButton("REMOVE") {
                                cover = ""
                                coverNote = "Cover will be removed when you save."
                            }
                        }
                    }
                    if (coverNote.isNotBlank()) Hint(coverNote)
                }
            }

            if (status == "beaten") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = hours, onValueChange = { hours = it },
                        label = { Text("Hours you took") }, singleLine = true, colors = bqFieldColors(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = date, onValueChange = { date = it },
                        label = { Text("Date beaten") }, placeholder = { Text("YYYY-MM-DD") },
                        singleLine = true, colors = bqFieldColors(), modifier = Modifier.weight(1f),
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (existing != null) {
                    if (confirmDelete) {
                        PixelButton("CONFIRM DELETE", kind = BtnKind.A) {
                            if (existing.cover.startsWith("/")) File(existing.cover).delete()
                            vm.update { st -> st.copy(games = st.games.filter { it.id != existing.id }) }
                            onDismiss()
                        }
                    } else {
                        PixelButton("DELETE") { confirmDelete = true }
                    }
                }
                Spacer(Modifier.weight(1f))
                PixelButton("CANCEL", onClick = onDismiss)
                Spacer(Modifier.width(8.dp))
                PixelButton("SAVE", kind = BtnKind.A) { save() }
            }
        }
    }

    if (showAchievements) {
        AchievementList(
            list = achievements,
            source = achSource,
            onToggle = { i -> achievements = achievements.mapIndexed { j, a -> if (j == i) a.copy(earned = !a.earned) else a } },
            onDismiss = { showAchievements = false },
        )
    }
}

/** The full achievement / trophy list, with ticks for the ones you've earned. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AchievementList(list: List<Trophy>, source: String, onToggle: (Int) -> Unit, onDismiss: () -> Unit) {
    var leftOnly by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = BQ.Shell2,
        contentColor = BQ.Ink,
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            SectionTitle("ACHIEVEMENTS · ${list.count { it.earned }}/${list.size}")
            Hint("From $source. Tick the ones you've earned, then save the game.")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                TabChip("All", !leftOnly) { leftOnly = false }
                TabChip("Still to get", leftOnly) { leftOnly = true }
            }
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth()) {
                items(list.size) { i ->
                    val a = list[i]
                    if (leftOnly && a.earned) return@items
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(i) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = a.earned, onCheckedChange = { onToggle(i) },
                            colors = androidx.compose.material3.CheckboxDefaults.colors(
                                checkedColor = BQ.B, uncheckedColor = BQ.Muted, checkmarkColor = BQ.Shell,
                            ),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(a.name, color = if (a.earned) BQ.Muted else BQ.Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            if (a.desc.isNotBlank()) Text(a.desc, color = BQ.Muted, fontSize = 13.sp)
                        }
                        if (a.kind.isNotBlank()) {
                            Text(a.kind, color = trophyColour(a.kind), fontSize = 12.sp, fontFamily = BQ.Mono,
                                modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

private fun trophyColour(kind: String) = when (kind.lowercase()) {
    "platinum" -> BQ.Silver
    "gold" -> BQ.Gold
    "silver" -> BQ.Silver
    "bronze" -> BQ.Bronze
    else -> BQ.Muted
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WishSheet(vm: BacklogViewModel, id: String, onDismiss: () -> Unit) {
    val w = vm.ref.wishlist.firstOrNull { it.id == id }
    if (w == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val s = vm.state
    var device by remember(id) { mutableStateOf(s.devices.first().id) }
    var platform by remember(id) { mutableStateOf(w.platforms.singleOrNull().orEmpty()) }
    var confirmNo by remember(id) { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = BQ.Shell2,
        contentColor = BQ.Ink,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle(w.title)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fmtHours(w.hours), color = BQ.B, fontFamily = BQ.Mono, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.width(6.dp))
                Hint("main story" + if (w.coopHours > 0) " · ${fmtHours(w.coopHours)} co-op" else "")
            }
            Hint(w.platforms.joinToString(", ").ifBlank { "Platform unknown" })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val price = vm.priceFor(w.title)
                Text(
                    price?.let { Prices.describe(it) } ?: if (s.itadKey.isBlank()) "Add an IsThereAnyDeal key in Setup for prices" else "Price not checked yet",
                    color = if (price != null) BQ.Ink else BQ.Muted, fontSize = 14.sp, modifier = Modifier.weight(1f),
                )
                if (s.itadKey.isNotBlank()) {
                    PixelButton(if (vm.priceBusy) "…" else "CHECK PRICE", enabled = !vm.priceBusy) { vm.updatePrices(listOf(w.title)) }
                }
            }
            if (vm.priceMessage.isNotBlank() && !vm.priceBusy && vm.priceFor(w.title) == null) Hint(vm.priceMessage)
            TagRow(w.tags)
            if (w.onDrive.isNotBlank()) {
                Hint(
                    "On your drives: " + w.onDrive.split(";").filter { it.isNotBlank() }.joinToString(", ") { part ->
                        val p = part.split("|")
                        if (p.size > 1) "${p[0]} (${p[1]})" else p[0]
                    },
                )
            }
            Text("Play it on", color = BQ.Muted, fontSize = 12.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                s.devices.forEach { d -> TabChip(d.name, device == d.id) { device = d.id } }
            }
            if (w.platforms.isNotEmpty()) {
                Text("Platform version", color = BQ.Muted, fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    w.platforms.forEach { p ->
                        TabChip(p, platform == p) { platform = if (platform == p) "" else p }
                    }
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PixelButton(
                    if (confirmNo) "CONFIRM REMOVE" else "NOT INTERESTED",
                    kind = if (confirmNo) BtnKind.A else BtnKind.PLAIN,
                ) {
                    if (!confirmNo) {
                        confirmNo = true
                    } else {
                        vm.update { st -> st.copy(hiddenWish = st.hiddenWish + w.id) }
                        vm.showToast("Removed ${w.title}")
                        onDismiss()
                    }
                }
                PixelButton("CLOSE", onClick = onDismiss)
                PixelButton("GOT IT → BACKLOG", kind = BtnKind.A) {
                    val g = Game(
                        id = newId(), title = w.title, device = device, status = "backlog", platform = platform,
                        est = if (w.hours > 0) w.hours else 0.0,
                        estSource = if (w.hours > 0) "hltb" else "",
                        addedAt = today(), order = System.currentTimeMillis(),
                    )
                    vm.update { st -> st.copy(hiddenWish = st.hiddenWish + w.id, games = st.games + g) }
                    vm.showToast("ADDED TO BACKLOG\n${w.title}")
                    onDismiss()
                }
            }
        }
    }
}
