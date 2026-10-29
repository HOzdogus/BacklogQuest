package com.backlogquest.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.backlogquest.app.data.AppState
import com.backlogquest.app.data.BacklogViewModel
import com.backlogquest.app.data.Game
import com.backlogquest.app.data.ImportError
import com.backlogquest.app.data.ImportItem
import com.backlogquest.app.data.Store
import com.backlogquest.app.data.StoreFetch
import com.backlogquest.app.data.StoreSignIn
import com.backlogquest.app.data.deviceName
import com.backlogquest.app.data.fmtNum
import com.backlogquest.app.data.newId
import com.backlogquest.app.data.normTitle
import com.backlogquest.app.data.today
import kotlinx.coroutines.launch
import org.json.JSONArray

/** Full-screen window: sign in to a store, read the library, then pick what to add. */
@Composable
fun StoreImportDialog(vm: BacklogViewModel, store: Store, onDismiss: () -> Unit) {
    val signIn = remember(store) { StoreSignIn(store) }
    var phase by remember { mutableStateOf("signin") } // signin, loading, review, error
    var progress by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var items by remember { mutableStateOf(listOf<ImportItem>()) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var gotResult by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun onResult(result: String) {
        if (gotResult) return
        gotResult = true
        phase = "loading"
        progress = "Signed in. Reading your ${store.label} library…"
        scope.launch {
            try {
                items = StoreFetch.fetch(signIn, result) { msg -> scope.launch { progress = msg } }
                phase = "review"
            } catch (e: ImportError) {
                error = e.message ?: "Something went wrong."
                phase = "error"
            } catch (e: Exception) {
                error = "Couldn't read your ${store.label} library. The store may have changed how it works; " +
                    "let Claude know so the app can be updated."
                phase = "error"
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(BQ.Shell)
                .systemBarsPadding()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("IMPORT FROM ${store.label.uppercase()}", Modifier.weight(1f))
                PixelButton("CLOSE", onClick = onDismiss)
            }
            when (phase) {
                "signin" -> {
                    Hint(
                        "Sign in to ${store.label} below. Your password goes straight to ${store.label}; " +
                            "the app only reads your list of games. Use email and password: " +
                            "\"Sign in with Google/Apple\" buttons don't work inside apps.",
                    )
                    AndroidView(
                        factory = { ctx -> signInWebView(ctx, signIn) { r -> onResult(r) }.also { webView = it } },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        PixelButton("I'VE SIGNED IN", kind = BtnKind.B) { webView?.loadUrl(signIn.retryUrl) }
                        Hint("Tap this if nothing happens after signing in.", Modifier.weight(1f))
                    }
                }
                "loading" -> Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(color = BQ.B)
                    Spacer(Modifier.height(12.dp))
                    Hint(progress)
                }
                "error" -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(error, color = BQ.CoolFg, fontSize = 15.sp)
                    PixelButton("TRY AGAIN", kind = BtnKind.B) {
                        gotResult = false
                        phase = "signin"
                    }
                }
                "review" -> ImportReview(vm, store, items, Modifier.weight(1f), onDone = onDismiss)
            }
        }
    }
}

/** The browser window used to sign in. Watches pages for the code/token that means you're in. */
@SuppressLint("SetJavaScriptEnabled")
private fun signInWebView(ctx: android.content.Context, signIn: StoreSignIn, onResult: (String) -> Unit): WebView {
    val web = WebView(ctx)
    var sentToLogin = false
    var sawSignInPage = false
    var tries = 0

    fun readPage(view: WebView, url: String) {
        view.evaluateJavascript(signIn.pageScript) { raw ->
            val text = try {
                if (raw == null || raw == "null") "" else JSONArray("[$raw]").optString(0)
            } catch (e: Exception) {
                ""
            }
            val result = signIn.resultFromText(text)
            when {
                result != null -> onResult(result)
                signIn.store == Store.PLAYSTATION && url.startsWith(StoreSignIn.PSN_SSO) && !sentToLogin -> {
                    // Not signed in yet: go to the PlayStation sign-in.
                    sentToLogin = true
                    view.loadUrl("https://my.playstation.com/")
                }
                signIn.store == Store.STEAM && tries < 3 -> {
                    tries++
                    view.postDelayed({ readPage(view, url) }, 1500)
                }
            }
        }
    }

    web.settings.javaScriptEnabled = true
    web.settings.domStorageEnabled = true
    web.settings.userAgentString = StoreSignIn.DESKTOP_UA
    web.settings.loadWithOverviewMode = true
    web.settings.useWideViewPort = true
    web.settings.builtInZoomControls = true
    web.settings.displayZoomControls = false
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

    web.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            signIn.resultFromUrl(url)?.let {
                onResult(it)
                return true
            }
            // Don't try to open app links (e.g. PlayStation's own app scheme).
            return !url.startsWith("http")
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            val u = url ?: return
            signIn.resultFromUrl(u)?.let {
                view.stopLoading()
                onResult(it)
            }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            val u = url ?: return
            signIn.resultFromUrl(u)?.let {
                onResult(it)
                return
            }
            if (signIn.store == Store.PLAYSTATION) {
                if (u.contains("account.sony.com") && (u.contains("signin") || u.contains("login"))) sawSignInPage = true
                // Back on PlayStation after signing in: fetch the sign-in token.
                if (sentToLogin && sawSignInPage && u.contains("playstation.com") && !u.contains("account.sony.com")) {
                    view.loadUrl(StoreSignIn.PSN_SSO)
                    return
                }
            }
            if (signIn.wantsPageText(u)) {
                tries = 0
                readPage(view, u)
            }
        }
    }
    web.loadUrl(signIn.startUrl)
    return web
}

private fun defaultDevice(s: AppState, store: Store): String {
    fun find(vararg words: String) = s.devices.firstOrNull { d -> words.any { d.name.contains(it, ignoreCase = true) } }?.id
    return when (store) {
        Store.PLAYSTATION -> find("ps5", "ps4", "playstation", "ps")
        Store.XBOX -> find("xbox")
        else -> find("pc", "steam deck", "laptop")
    } ?: s.devices.firstOrNull { !it.exempt }?.id ?: s.devices.first().id
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportReview(
    vm: BacklogViewModel,
    store: Store,
    found: List<ImportItem>,
    modifier: Modifier,
    onDone: () -> Unit,
) {
    val s = vm.state
    val have = remember {
        (s.games.map { normTitle(it.title) } + vm.ref.history.map { normTitle(it.title) }).toSet()
    }
    val all = remember(found) { found.distinctBy { normTitle(it.title) }.sortedBy { it.title.lowercase() } }
    val fresh = remember(all) { all.filter { normTitle(it.title) !in have } }
    val alreadyCount = all.size - fresh.size
    val picked = remember(fresh) { mutableStateMapOf<String, Boolean>().apply { fresh.forEach { put(it.title, it.suggested) } } }
    var device by remember { mutableStateOf(defaultDevice(s, store)) }
    var search by remember { mutableStateOf("") }
    val shown = fresh.filter { search.isBlank() || it.title.contains(search.trim(), ignoreCase = true) }
    val count = fresh.count { picked[it.title] == true }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Hint(
            "${all.size} games found. " +
                (if (alreadyCount > 0) "$alreadyCount are already in your games or history, so they're left out. " else "") +
                "Untick anything you don't want in your backlog.",
        )
        Text("Play these on", color = BQ.Muted, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            s.devices.forEach { d -> TabChip(d.name, device == d.id) { device = d.id } }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PixelButton("ALL") { fresh.forEach { picked[it.title] = true } }
            PixelButton("NONE") { fresh.forEach { picked[it.title] = false } }
            if (fresh.any { it.hours > 0 }) {
                PixelButton("UNPLAYED ONLY") { fresh.forEach { picked[it.title] = it.hours < 0.5 } }
            }
        }
        OutlinedTextField(
            value = search, onValueChange = { search = it },
            placeholder = { Text("Search") }, singleLine = true, colors = bqFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.title }) { item ->
                val on = picked[item.title] == true
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { picked[item.title] = !on }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = on, onCheckedChange = { picked[item.title] = it },
                        colors = CheckboxDefaults.colors(checkedColor = BQ.B, uncheckedColor = BQ.Muted, checkmarkColor = BQ.Shell),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(item.title, color = BQ.Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        val meta = listOf(
                            item.platform,
                            if (item.hours >= 0.1) "${fmtNum(Math.round(item.hours * 10) / 10.0)}h played" else "",
                            item.note,
                        ).filter { it.isNotBlank() }.joinToString(" · ")
                        if (meta.isNotEmpty()) Text(meta, color = BQ.Muted, fontSize = 12.sp)
                    }
                }
            }
        }
        PixelButton(
            if (count == 0) "NOTHING SELECTED" else "ADD $count TO BACKLOG",
            kind = BtnKind.A,
            enabled = count > 0,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val devName = deviceName(s, device)
            var order = System.currentTimeMillis()
            val added = fresh.filter { picked[it.title] == true }.map { item ->
                Game(
                    id = newId(),
                    title = item.title.take(120),
                    device = device,
                    status = "backlog",
                    platform = if (item.platform.equals(devName, ignoreCase = true)) "" else item.platform,
                    source = item.store,
                    addedAt = today(),
                    order = order++,
                )
            }
            vm.update { st ->
                val existing = st.games.map { normTitle(it.title) }.toSet()
                st.copy(games = st.games + added.filter { normTitle(it.title) !in existing })
            }
            vm.showToast("ADDED ${added.size} GAMES\nfrom ${store.label}")
            onDone()
        }
    }
}
