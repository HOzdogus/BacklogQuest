package com.backlogquest.app.data

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

class BacklogViewModel(app: Application) : AndroidViewModel(app) {
    private val dir: File = app.filesDir
    private val saveLock = Mutex()

    var state by mutableStateOf(AppState())
        private set
    var ref by mutableStateOf(RefData.EMPTY)
        private set
    /** Your games as they are in the data file, for "replace my games". */
    var fileState by mutableStateOf<ImportedState?>(null)
        private set
    var loaded by mutableStateOf(false)
        private set
    var syncing by mutableStateOf(false)
        private set
    var syncMessage by mutableStateOf("")
        private set
    /** A message shown briefly at the top of the screen. */
    var toast by mutableStateOf<String?>(null)

    init {
        viewModelScope.launch {
            val (saved, file) = withContext(Dispatchers.IO) {
                Store.loadState(dir) to Store.loadDataFile(dir)
            }
            if (saved != null) state = saved
            if (file != null) {
                ref = file.ref
                fileState = file.state
            }
            loaded = true
            // First time on this phone: fetch your data straight away.
            if (file == null) sync()
        }
    }

    /** Change your state and save it on the phone. */
    fun update(change: (AppState) -> AppState) {
        state = change(state)
        viewModelScope.launch(Dispatchers.IO) {
            saveLock.withLock { Store.saveState(dir, state) }
        }
    }

    fun showToast(text: String) {
        toast = text
    }

    /** Downloads your data file and refreshes history, wishlist and catalog. */
    fun sync() {
        if (syncing) return
        syncing = true
        syncMessage = "Downloading your data…"
        val address = state.dataUrl
        viewModelScope.launch {
            try {
                val parsed = withContext(Dispatchers.IO) {
                    val text = Net.get(address)
                    val p = Store.parseExport(text)
                    Store.saveDataFile(dir, text)
                    p
                }
                ref = parsed.ref
                fileState = parsed.state
                val imported = parsed.state
                // On a fresh install, take your games and devices from the file too.
                val firstImport = state.games.isEmpty() && state.baseline == 0 && imported != null
                update { s ->
                    var n = s.copy(lastSync = today())
                    if (firstImport && imported != null) {
                        n = n.copy(
                            baseline = imported.baseline,
                            devices = imported.devices.ifEmpty { s.devices },
                            games = imported.games,
                        )
                    }
                    n
                }
                syncMessage = "Updated. ${"%,d".format(parsed.ref.history.size)} past completions, " +
                    "${"%,d".format(parsed.ref.wishlist.size)} wishlist and catalog games."
            } catch (e: FileNotFoundException) {
                syncMessage = "No data file at that address. Upload backlog-data.json to your repo, " +
                    "then tap Sync."
            } catch (e: Exception) {
                syncMessage = "Couldn't download your data. Check your connection and the address in Setup."
            } finally {
                syncing = false
            }
        }
    }

    /** Replaces your games, devices and extra count with the ones in the data file. */
    fun replaceGamesFromFile() {
        val f = fileState ?: return
        update { s ->
            s.copy(baseline = f.baseline, devices = f.devices.ifEmpty { s.devices }, games = f.games)
        }
        showToast("GAMES REPLACED\nfrom your data file")
    }

    /** Copies a picked image into the app so it keeps working. Returns its path. */
    suspend fun importCover(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val folder = File(dir, "covers").apply { mkdirs() }
            val out = File(folder, "${newId()}.img")
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { input.copyTo(it) }
            } ?: return@withContext null
            out.absolutePath
        } catch (e: Exception) {
            null
        }
    }
}
