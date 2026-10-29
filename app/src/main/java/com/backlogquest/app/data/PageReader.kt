package com.backlogquest.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import kotlin.coroutines.resume

/**
 * Loads a web page in an invisible browser and runs a script on it.
 *
 * Used for sites like PSNProfiles and TrueAchievements, which only work in a
 * real browser (they check for one before showing the page). The script should
 * return "" until the content it wants has appeared; it's retried every 1.5 s.
 */
object PageReader {

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun read(context: Context, url: String, script: String, timeoutMs: Long = 25_000): String? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val web = WebView(context.applicationContext)
                val handler = Handler(Looper.getMainLooper())
                val timeout = Handler(Looper.getMainLooper())
                val started = System.currentTimeMillis()
                var finished = false

                fun finish(result: String?) {
                    if (finished) return
                    finished = true
                    handler.removeCallbacksAndMessages(null)
                    timeout.removeCallbacksAndMessages(null)
                    try {
                        web.stopLoading()
                        web.destroy()
                    } catch (e: Exception) {
                    }
                    if (cont.isActive) cont.resume(result)
                }

                fun poll() {
                    if (finished) return
                    if (System.currentTimeMillis() - started > timeoutMs) {
                        finish(null)
                        return
                    }
                    web.evaluateJavascript(script) { raw ->
                        val text = try {
                            if (raw == null || raw == "null") "" else JSONArray("[$raw]").optString(0)
                        } catch (e: Exception) {
                            ""
                        }
                        if (text.isNotBlank()) finish(text) else handler.postDelayed({ poll() }, 1500)
                    }
                }

                web.settings.javaScriptEnabled = true
                web.settings.domStorageEnabled = true
                web.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, u: String?) {
                        handler.removeCallbacksAndMessages(null)
                        handler.postDelayed({ poll() }, 1000)
                    }
                }
                timeout.postDelayed({ finish(null) }, timeoutMs + 2000)
                web.loadUrl(url)
                cont.invokeOnCancellation { handler.post { finish(null) } }
            }
        }
}
