package com.backlogquest.app.ui

import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.backlogquest.app.data.BacklogViewModel
import com.backlogquest.app.data.DEFAULT_DATA_URL
import com.backlogquest.app.data.Device
import com.backlogquest.app.data.GameStore
import com.backlogquest.app.data.fmtDate
import com.backlogquest.app.data.newId

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupScreen(vm: BacklogViewModel) {
    var importing by remember { mutableStateOf<GameStore?>(null) }
    val s = vm.state
    var baselineText by remember { mutableStateOf(if (s.baseline > 0) s.baseline.toString() else "") }
    var urlText by remember { mutableStateOf(s.dataUrl) }
    var keyText by remember { mutableStateOf(s.rawgKey) }
    var newDevice by remember { mutableStateOf("") }
    var confirmReplace by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { StatusPanel(vm) }
        item { SectionTitle("SETUP") }

        item {
            Panel {
                SectionTitle("YOUR DATA")
                Hint(
                    "History, wishlist and catalog come from backlog-data.json in your GitHub repo. " +
                        "Tap Sync whenever that file changes.",
                )
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    label = { Text("Data file address") },
                    singleLine = true,
                    colors = bqFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PixelButton(if (vm.syncing) "SYNCING…" else "SYNC NOW", kind = BtnKind.B, enabled = !vm.syncing) {
                        val clean = urlText.trim().ifBlank { DEFAULT_DATA_URL }
                        urlText = clean
                        vm.update { it.copy(dataUrl = clean) }
                        vm.sync()
                    }
                    if (urlText.trim() != DEFAULT_DATA_URL) {
                        PixelButton("RESET ADDRESS") { urlText = DEFAULT_DATA_URL }
                    }
                }
                val info = buildString {
                    if (vm.syncMessage.isNotBlank()) append(vm.syncMessage)
                    else if (s.lastSync.isNotBlank()) append("Last synced ${fmtDate(s.lastSync)}.")
                    if (vm.ref.exportedAt.isNotBlank()) append(" Data file made ${fmtDate(vm.ref.exportedAt)}.")
                }
                if (info.isNotBlank()) Hint(info.trim())
            }
        }

        item {
            Panel {
                SectionTitle("YOUR GAMES FROM THE FILE")
                Hint(
                    "Your own games list lives on this phone. On a fresh install it's copied from the data file. " +
                        "To start over from the file, replace it here. This can't be undone.",
                )
                if (vm.fileState == null) {
                    Hint("Sync first to load the data file.")
                } else if (confirmReplace) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PixelButton("CONFIRM REPLACE", kind = BtnKind.A) {
                            vm.replaceGamesFromFile()
                            baselineText = vm.state.baseline.let { if (it > 0) it.toString() else "" }
                            confirmReplace = false
                        }
                        PixelButton("CANCEL") { confirmReplace = false }
                    }
                } else {
                    PixelButton("REPLACE MY GAMES") { confirmReplace = true }
                }
            }
        }

        item {
            Panel {
                OutlinedTextField(
                    value = baselineText,
                    onValueChange = { text ->
                        baselineText = text.filter { it.isDigit() }
                        vm.update { it.copy(baseline = baselineText.toIntOrNull() ?: 0) }
                    },
                    label = { Text("Extra completions not in your history") },
                    singleLine = true,
                    colors = bqFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint(
                    "Your ${"%,d".format(vm.ref.history.size)} imported HowLongToBeat completions already count. " +
                        "Use this only for games missing from that list.",
                )
            }
        }

        item {
            Panel {
                SectionTitle("AUTO-FILL")
                Hint(
                    "Auto-fill gets times to beat from HowLongToBeat with no setup. For genre, description " +
                        "and covers it also uses RAWG, a free game database: get a free API key at " +
                        "rawg.io/apidocs and paste it here.",
                )
                OutlinedTextField(
                    value = keyText,
                    onValueChange = { text ->
                        keyText = text
                        vm.update { it.copy(rawgKey = text.trim()) }
                    },
                    label = { Text("RAWG API key") },
                    singleLine = true,
                    colors = bqFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item {
            Panel {
                SectionTitle("IMPORT FROM STORES")
                Hint(
                    "Sign in to a store to add your games to your backlog. You pick which ones before " +
                        "anything is added, and games already in your lists or history are skipped. " +
                        "PlayStation and Xbox list the games you've bought or played.",
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GameStore.values().forEach { st -> PixelButton(st.label.uppercase(), kind = BtnKind.B) { importing = st } }
                }
                PixelButton("SIGN OUT OF ALL STORES") {
                    CookieManager.getInstance().removeAllCookies(null)
                    WebStorage.getInstance().deleteAllData()
                    vm.showToast("Signed out of all stores")
                }
            }
        }

        item {
            Panel {
                SectionTitle("DEVICES")
                Hint("Exempt devices never go on cooldown and don't count toward the two-for-two rule.")
                s.devices.forEach { d ->
                    DeviceRow(vm, d)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newDevice,
                        onValueChange = { newDevice = it },
                        label = { Text("Add a device") },
                        placeholder = { Text("e.g. PS5, Steam Deck") },
                        singleLine = true,
                        colors = bqFieldColors(),
                        modifier = Modifier.weight(1f),
                    )
                    PixelButton("ADD", kind = BtnKind.B, enabled = newDevice.isNotBlank()) {
                        val name = newDevice.trim()
                        vm.update { it.copy(devices = it.devices + Device(newId(), name, false)) }
                        newDevice = ""
                    }
                }
            }
        }
    }
    importing?.let { st -> StoreImportDialog(vm, st) { importing = null } }
}

@Composable
private fun DeviceRow(vm: BacklogViewModel, d: Device) {
    var name by remember(d.id) { mutableStateOf(d.name) }
    val inUse = vm.state.games.any { it.device == d.id }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { text ->
                name = text
                if (text.isNotBlank()) {
                    vm.update { st -> st.copy(devices = st.devices.map { if (it.id == d.id) it.copy(name = text.trim()) else it }) }
                }
            },
            label = { Text("Name") },
            singleLine = true,
            colors = bqFieldColors(),
            modifier = Modifier.weight(1f),
        )
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Switch(
                checked = d.exempt,
                onCheckedChange = { on ->
                    vm.update { st -> st.copy(devices = st.devices.map { if (it.id == d.id) it.copy(exempt = on) else it }) }
                },
                colors = SwitchDefaults.colors(checkedTrackColor = BQ.B, checkedThumbColor = BQ.Shell),
            )
            Text("Exempt", color = BQ.Muted, fontSize = 11.sp)
        }
        PixelButton("✕", enabled = !inUse && vm.state.devices.size > 1) {
            vm.update { st -> st.copy(devices = st.devices.filter { it.id != d.id }) }
        }
    }
}
