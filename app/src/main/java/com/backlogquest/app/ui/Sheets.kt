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
    val existing = (target as? EditTarget.Existing)?.let { t -> s.games.firstOrNull { it.id == t.id } }
    if (target is EditTarget.Existing && existing == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val newStatus = (target as? EditTarget.New)?.status ?: "backlog"
    val start = existing ?: Game(
        id = "", title = "", device = s.devices.first().id, status = newStatus, beatenAt = today(),
    )

    var title by remember(target) { mutableStateOf(start.title) }
    var device by remember(target) { mutableStateOf(start.device) }
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
            try {
                val r = Rawg.lookup(vm.state.rawgKey, t)
                if (r == null) {
                    note = "Couldn't find that game. Check the spelling, or fill it in yourself."
                } else {
                    title = r.title.take(120)
                    if (genre.isBlank()) genre = r.genre.take(40)
                    if (desc.isBlank()) desc = r.description
                    if (cover.isBlank() && r.cover.isNotBlank()) cover = r.cover
                    if (r.hours > 0 && estSource != "manual" && estSource != "hltb") {
                        est = fmtNum((r.hours * 2).roundToInt() / 2.0)
                        estSource = "rawg"
                    }
                    note = "Filled in. Check it over, then save."
                }
            } catch (e: Rawg.LookupError) {
                note = e.message ?: "Lookup failed. Try again."
            } catch (e: Exception) {
                note = "Lookup failed. Check your connection and try again."
            } finally {
                busy = false
            }
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
            addedAt = today(), order = System.currentTimeMillis(),
        )
        var rec = baseGame.copy(
            title = t, device = device, status = status, genre = genre.trim(), est = estNum,
            series = series.trim(), desc = desc.trim(),
            estSource = if (estNum > 0) estSource.ifBlank { "manual" } else "",
            cover = cover,
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

        if (saved.status == "beaten" && !wasBeaten) {
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
            SectionTitle(if (existing != null) "EDIT GAME" else "ADD A GAME")

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text("Title") }, singleLine = true, colors = bqFieldColors(),
                    modifier = Modifier.weight(1f),
                )
                if (vm.state.rawgKey.isNotBlank()) {
                    PixelButton("AUTO-FILL", kind = BtnKind.B, enabled = !busy) { autoFill() }
                }
            }
            Hint(
                note.ifBlank {
                    if (vm.state.rawgKey.isNotBlank()) "Type a title and tap Auto-fill for genre, description, cover and a time estimate."
                    else "Add a free RAWG key in Setup to auto-fill game details."
                },
            )

            Text("Device", color = BQ.Muted, fontSize = 12.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                s.devices.forEach { d -> TabChip(d.name, device == d.id) { device = d.id } }
            }
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
                        id = newId(), title = w.title, device = device, status = "backlog",
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
