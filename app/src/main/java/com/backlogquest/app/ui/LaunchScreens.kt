package com.backlogquest.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.backlogquest.app.data.BacklogViewModel
import com.backlogquest.app.data.EmuInput
import com.backlogquest.app.data.Emulators
import com.backlogquest.app.data.Launch
import com.backlogquest.app.data.Launcher
import com.backlogquest.app.data.fmtNum

/** "NetherSX2 · Kingdom Hearts.iso" */
fun launchSummary(ctx: android.content.Context, l: Launch): String = when (l.mode) {
    "app" -> Launcher.appLabel(ctx, l.pkg)
    else -> {
        val emu = Emulators.byKey(l.emu)?.label ?: "Emulator"
        val what = when {
            l.gameId.isNotBlank() -> l.gameId
            l.uri.isNotBlank() -> fileName(ctx, Uri.parse(l.uri))
            else -> ""
        }
        if (what.isBlank()) emu else "$emu · $what"
    }
}

fun fileName(ctx: android.content.Context, uri: Uri): String = try {
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment.orEmpty().substringAfterLast("/")
} catch (e: Exception) {
    uri.lastPathSegment.orEmpty().substringAfterLast("/")
}

/** Choose how a game starts: an emulator (with the game file or ID) or an Android app. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LaunchSetupDialog(
    current: Launch?,
    platform: String,
    onSave: (Launch?) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val emulators = remember { Emulators.installedList(ctx) }
    val suggested = remember { Emulators.suggest(ctx, platform) }
    var mode by remember { mutableStateOf(current?.mode ?: if (suggested != null || emulators.isNotEmpty()) "emulator" else "app") }
    var emu by remember { mutableStateOf(current?.emu?.takeIf { it.isNotBlank() } ?: suggested?.key ?: emulators.firstOrNull()?.key.orEmpty()) }
    var uri by remember { mutableStateOf(current?.uri.orEmpty()) }
    var gameId by remember { mutableStateOf(current?.gameId.orEmpty()) }
    var core by remember { mutableStateOf(current?.core?.takeIf { it.isNotBlank() } ?: Emulators.coreFor(platform)) }
    var pkg by remember { mutableStateOf(if (current?.mode == "app") current?.pkg.orEmpty() else "") }
    var appSearch by remember { mutableStateOf("") }
    val apps = remember { Launcher.launchableApps(ctx) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) {
            // Keep access to the file after a restart.
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    picked, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (e: Exception) {
                try {
                    ctx.contentResolver.takePersistableUriPermission(picked, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e2: Exception) {
                }
            }
            uri = picked.toString()
        }
    }

    val chosen = Emulators.byKey(emu)
    val ready = when (mode) {
        "app" -> pkg.isNotBlank()
        else -> chosen != null && when (chosen.input) {
            EmuInput.FILE -> uri.isNotBlank()
            EmuInput.ID -> gameId.isNotBlank()
            EmuInput.NONE -> true
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(BQ.Shell)
                .systemBarsPadding()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("HOW TO START IT", Modifier.weight(1f))
                PixelButton("CLOSE", onClick = onDismiss)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TabChip("Emulator", mode == "emulator") { mode = "emulator" }
                TabChip("Android app", mode == "app") { mode = "app" }
            }

            if (mode == "emulator") {
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (emulators.isEmpty()) {
                        Hint("None of the supported emulators are installed. Use Android app to just open an emulator instead.")
                    } else {
                        Text("Emulator", color = BQ.Muted, fontSize = 12.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            emulators.forEach { e -> TabChip(e.label, emu == e.key) { emu = e.key } }
                        }
                        if (suggested != null && platform.isNotBlank()) Hint("Suggested for $platform: ${suggested.label}")
                    }
                    when (chosen?.input) {
                        EmuInput.FILE -> {
                            Text("Game file", color = BQ.Muted, fontSize = 12.sp)
                            Text(
                                if (uri.isBlank()) "None picked yet" else fileName(ctx, Uri.parse(uri)),
                                color = if (uri.isBlank()) BQ.Muted else BQ.Ink, fontWeight = FontWeight.Bold,
                            )
                            PixelButton(if (uri.isBlank()) "PICK GAME FILE" else "CHOOSE ANOTHER FILE", kind = BtnKind.B) {
                                filePicker.launch(arrayOf("*/*"))
                            }
                            Hint("Pick the game's ROM, ISO or other file from your phone's storage or SD card.")
                            if (chosen.key == "retroarch") {
                                OutlinedTextField(
                                    value = core, onValueChange = { core = it.trim() },
                                    label = { Text("RetroArch core") }, singleLine = true, colors = bqFieldColors(),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Hint(
                                    "The core's name without \"_libretro_android.so\", e.g. snes9x, mgba, gambatte, " +
                                        "genesis_plus_gx, swanstation. It must be installed in RetroArch.",
                                )
                            }
                        }
                        EmuInput.ID -> {
                            OutlinedTextField(
                                value = gameId, onValueChange = { gameId = it.trim().uppercase() },
                                label = { Text("Title ID") }, placeholder = { Text(chosen.idHint) },
                                singleLine = true, colors = bqFieldColors(), modifier = Modifier.fillMaxWidth(),
                            )
                            Hint("${chosen.label} starts games by their title ID. The game must already be installed in ${chosen.label}.")
                        }
                        else -> Unit
                    }
                }
            } else {
                OutlinedTextField(
                    value = appSearch, onValueChange = { appSearch = it },
                    placeholder = { Text("Search your apps") }, singleLine = true, colors = bqFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint("For Android games, or apps like GameHub, GameNative or Shadow PC (these open on their home screen).")
                LazyColumn(Modifier.weight(1f)) {
                    items(apps.filter { appSearch.isBlank() || it.second.contains(appSearch.trim(), ignoreCase = true) }, key = { it.first }) { (p, label) ->
                        Text(
                            label,
                            color = if (p == pkg) BQ.B else BQ.Ink,
                            fontWeight = if (p == pkg) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 16.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { pkg = p }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelButton("SAVE", kind = BtnKind.A, enabled = ready, modifier = Modifier.weight(1f)) {
                    onSave(
                        if (mode == "app") Launch(mode = "app", pkg = pkg)
                        else Launch(
                            mode = "emulator", emu = emu,
                            uri = if (chosen?.input == EmuInput.FILE) uri else "",
                            gameId = if (chosen?.input == EmuInput.ID) gameId else "",
                            core = if (emu == "retroarch") core else "",
                        ),
                    )
                }
                if (current != null) PixelButton("REMOVE") { onSave(null) }
            }
        }
    }
}

/** After a game started from here: confirm the play time. */
@Composable
fun SessionDialog(vm: BacklogViewModel) {
    val r = vm.sessionResult ?: return
    var minutesText by remember(r) { mutableStateOf(r.minutes.toString()) }
    val h = r.minutes / 60
    val m = r.minutes % 60
    AlertDialog(
        onDismissRequest = { vm.sessionResult = null },
        containerColor = BQ.Shell2,
        title = { Text("Add play time?", color = BQ.Ink, fontFamily = BQ.Mono, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "You played ${r.title} for " + (if (h > 0) "${h}h ${m}m" else "${m}m") + ".",
                    color = BQ.Ink,
                )
                if (!r.measured) {
                    Hint("This is the time you were away. Allow Usage access in Setup → Play time to count only time in the game.")
                }
                OutlinedTextField(
                    value = minutesText, onValueChange = { minutesText = it.filter { c -> c.isDigit() } },
                    label = { Text("Minutes") }, singleLine = true, colors = bqFieldColors(),
                )
            }
        },
        confirmButton = {
            PixelButton("ADD", kind = BtnKind.A) {
                val mins = minutesText.toIntOrNull() ?: 0
                if (mins > 0) vm.addPlayTime(r.gameId, mins) else vm.sessionResult = null
            }
        },
        dismissButton = { PixelButton("SKIP") { vm.sessionResult = null } },
    )
}

/** "3.5h played" for a game that has play time. */
fun playedText(hours: Double): String = if (hours > 0) "${fmtNum(hours)}h played" else ""

@Suppress("unused")
private val keepHeightIn = Modifier.heightIn(min = 0.dp)
