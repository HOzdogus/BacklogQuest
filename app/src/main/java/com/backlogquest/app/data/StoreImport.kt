package com.backlogquest.app.data

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/**
 * Imports your game libraries from PC and console stores.
 *
 * None of these stores (except Steam's own pages) offer a public API for this, so
 * the sign-ins follow the same routes as the open-source launchers that support
 * them: Legendary (Epic), Nile (Amazon), psn-api (PlayStation) and xbox-webapi
 * (Xbox). If a store changes its sign-in, that store's import may stop working
 * until the app is updated.
 */
enum class GameStore(val label: String) {
    STEAM("Steam"),
    GOG("GOG"),
    EPIC("Epic"),
    AMAZON("Amazon"),
    PLAYSTATION("PlayStation"),
    XBOX("Xbox"),
}

/** A game found in a store library. */
data class ImportItem(
    val title: String,
    val platform: String,
    val store: String,
    /** Hours played, if the store tells us. */
    val hours: Double = 0.0,
    /** Extra info shown in the list, e.g. "PS Plus". */
    val note: String = "",
    /** Whether to tick it by default. */
    val suggested: Boolean = true,
)

class ImportError(message: String) : Exception(message)

/** Makes "The Witcher® 3: Wild Hunt" and "the witcher 3 wild hunt" match. */
fun normTitle(t: String): String =
    t.lowercase().replace("&", "and").replace(Regex("[™®©]"), "").replace(Regex("[^a-z0-9]"), "")

/** Small HTTP helper for the store APIs. */
object Http {
    class Resp(val code: Int, val body: String, val location: String?)

    fun call(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        follow: Boolean = true,
    ): Resp {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.instanceFollowRedirects = follow
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        if (!headers.keys.any { it.equals("User-Agent", true) }) {
            conn.setRequestProperty("User-Agent", StoreSignIn.DESKTOP_UA)
        }
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        try {
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return Resp(code, text, conn.getHeaderField("Location"))
        } finally {
            conn.disconnect()
        }
    }

    fun json(url: String, method: String = "GET", headers: Map<String, String> = emptyMap(), body: String? = null): JSONObject {
        val r = call(url, method, headers, body)
        if (r.code !in 200..299) throw ImportError("The store answered ${r.code}. Try signing in again.")
        return JSONObject(r.body)
    }

    fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

/**
 * What to load in the sign-in window and how to tell when sign-in has finished.
 * The result is a code or token that [StoreFetch] turns into a game list.
 */
class StoreSignIn(val store: GameStore) {
    companion object {
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/130.0.0.0 Safari/537.36"

        const val GOG_CLIENT = "46899977096215655"
        const val GOG_SECRET = "9d85c43b1482497dbbce61f6e4aa173a433796eeae2ca8c5f6129f2dc4de46d9"
        const val GOG_REDIRECT = "https://embed.gog.com/on_login_success?origin=client"

        const val EPIC_CLIENT = "34a02cf8f4414e29b15921876da36f9a"
        const val EPIC_SECRET = "daafbccc737745039dffe53d94fc76cf"
        val EPIC_REDIRECT = "https://www.epicgames.com/id/api/redirect?clientId=$EPIC_CLIENT&responseType=code"

        const val PSN_SSO = "https://ca.account.sony.com/api/v1/ssocookie"

        const val XBOX_CLIENT = "000000004C12AE6F"
        const val XBOX_REDIRECT = "https://login.live.com/oauth20_desktop.srf"
    }

    // Amazon needs a one-off device serial and PKCE pair for each sign-in.
    val amazonSerial: String = UUID.randomUUID().toString().replace("-", "").uppercase()
    val amazonClientId: String = "$amazonSerial#A2UMVHOX7UP4V7".toByteArray(Charsets.US_ASCII)
        .joinToString("") { "%02x".format(it) }
    val amazonVerifier: String = ByteArray(32).also { SecureRandom().nextBytes(it) }.let {
        Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
    private val amazonChallenge: String = MessageDigest.getInstance("SHA-256")
        .digest(amazonVerifier.toByteArray(Charsets.US_ASCII))
        .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }

    val startUrl: String = when (store) {
        GameStore.STEAM -> "https://steamcommunity.com/my/games/?tab=all"
        GameStore.GOG -> "https://auth.gog.com/auth?client_id=$GOG_CLIENT&redirect_uri=${Http.enc(GOG_REDIRECT)}" +
            "&response_type=code&layout=client2"
        GameStore.EPIC -> "https://www.epicgames.com/id/login?redirectUrl=${Http.enc(EPIC_REDIRECT)}"
        GameStore.AMAZON -> "https://www.amazon.com/ap/signin?" + listOf(
            "openid.ns" to "http://specs.openid.net/auth/2.0",
            "openid.claimed_id" to "http://specs.openid.net/auth/2.0/identifier_select",
            "openid.identity" to "http://specs.openid.net/auth/2.0/identifier_select",
            "openid.mode" to "checkid_setup",
            "openid.oa2.scope" to "device_auth_access",
            "openid.ns.oa2" to "http://www.amazon.com/ap/ext/oauth/2",
            "openid.oa2.response_type" to "code",
            "openid.oa2.code_challenge_method" to "S256",
            "openid.oa2.client_id" to "device:$amazonClientId",
            "language" to "en_US",
            "marketPlaceId" to "ATVPDKIKX0DER",
            "openid.return_to" to "https://www.amazon.com",
            "openid.pape.max_auth_age" to "0",
            "openid.assoc_handle" to "amzn_sonic_games_launcher",
            "pageId" to "amzn_sonic_games_launcher",
            "openid.oa2.code_challenge" to amazonChallenge,
        ).joinToString("&") { (k, v) -> "${Http.enc(k)}=${Http.enc(v)}" }
        GameStore.PLAYSTATION -> PSN_SSO
        GameStore.XBOX -> "https://login.live.com/oauth20_authorize.srf?client_id=$XBOX_CLIENT" +
            "&redirect_uri=${Http.enc(XBOX_REDIRECT)}&response_type=token&display=touch" +
            "&scope=${Http.enc("service::user.auth.xboxlive.com::MBI_SSL")}&locale=en"
    }

    /** Where "I've signed in" sends the window to look for the result again. */
    val retryUrl: String = when (store) {
        GameStore.PLAYSTATION -> PSN_SSO
        GameStore.EPIC -> EPIC_REDIRECT
        else -> startUrl
    }

    /** Pages whose text holds the answer (read with JavaScript once loaded). */
    fun wantsPageText(url: String): Boolean = when (store) {
        GameStore.STEAM -> Regex("steamcommunity\\.com/(id|profiles)/[^/]+/games").containsMatchIn(url)
        GameStore.EPIC -> url.startsWith("https://www.epicgames.com/id/api/redirect")
        GameStore.PLAYSTATION -> url.startsWith(PSN_SSO)
        else -> false
    }

    /** JavaScript that returns the text we need from those pages. */
    val pageScript: String = when (store) {
        // Steam's games page keeps the whole list in a data attribute (older pages: a script variable).
        GameStore.STEAM -> """
            (function(){
              var t = document.querySelector('[data-profile-gameslist]');
              if (t) return t.getAttribute('data-profile-gameslist');
              var m = document.documentElement.innerHTML.match(/var rgGames = (\[.*?\]);/);
              return m ? m[1] : '';
            })()
        """.trimIndent()
        else -> "document.body ? document.body.innerText : ''"
    }

    /**
     * Checks a page address during sign-in. Returns the code/token when the URL
     * itself carries it (GOG, Amazon, Xbox), otherwise null.
     */
    fun resultFromUrl(url: String): String? {
        val uri = Uri.parse(url)
        return when (store) {
            GameStore.GOG -> if (url.startsWith("https://embed.gog.com/on_login_success")) uri.getQueryParameter("code") else null
            GameStore.AMAZON -> if (url.contains("openid.oa2.authorization_code=")) {
                uri.getQueryParameter("openid.oa2.authorization_code")
            } else null
            GameStore.XBOX -> if (url.startsWith(XBOX_REDIRECT) && url.contains("access_token=")) {
                (uri.fragment ?: url.substringAfter("#", "")).split("&")
                    .firstOrNull { it.startsWith("access_token=") }
                    ?.substringAfter("=")?.let { Uri.decode(it) }
            } else null
            else -> null
        }
    }

    /** Turns page text into a result, or null if this page doesn't have it yet. */
    fun resultFromText(text: String): String? = when (store) {
        GameStore.STEAM -> text.takeIf { it.isNotBlank() && (it.contains("rgGames") || it.trimStart().startsWith("[")) }
        GameStore.EPIC -> try {
            JSONObject(text).optString("authorizationCode").takeIf { it.isNotBlank() && it != "null" }
        } catch (e: Exception) { null }
        GameStore.PLAYSTATION -> try {
            JSONObject(text).optString("npsso").takeIf { it.isNotBlank() }
        } catch (e: Exception) { null }
        else -> null
    }
}

/** Downloads your library once sign-in has given us a code or token. */
object StoreFetch {

    suspend fun fetch(signIn: StoreSignIn, result: String, onProgress: (String) -> Unit): List<ImportItem> =
        withContext(Dispatchers.IO) {
            when (signIn.store) {
                GameStore.STEAM -> steam(result)
                GameStore.GOG -> gog(result, onProgress)
                GameStore.EPIC -> epic(result, onProgress)
                GameStore.AMAZON -> amazon(signIn, result, onProgress)
                GameStore.PLAYSTATION -> playstation(result, onProgress)
                GameStore.XBOX -> xbox(result, onProgress)
            }
        }

    // ---------- Steam ----------

    private fun steam(text: String): List<ImportItem> {
        val trimmed = text.trim()
        val games = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed).optJSONArray("rgGames")
            ?: throw ImportError("Couldn't read your Steam games page.")
        return (0 until games.length()).mapNotNull { i ->
            val g = games.optJSONObject(i) ?: return@mapNotNull null
            val name = g.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            // Older pages give "hours_forever" as text; newer ones give minutes.
            val minutes = g.optDouble("playtime_forever", Double.NaN)
            val hours = if (!minutes.isNaN()) minutes / 60.0
            else g.optString("hours_forever").replace(",", "").toDoubleOrNull() ?: 0.0
            ImportItem(name, "PC", "Steam", hours = hours)
        }
    }

    // ---------- GOG ----------

    private fun gog(code: String, onProgress: (String) -> Unit): List<ImportItem> {
        onProgress("Signing in to GOG…")
        val token = Http.json(
            "https://auth.gog.com/token?client_id=${StoreSignIn.GOG_CLIENT}&client_secret=${StoreSignIn.GOG_SECRET}" +
                "&grant_type=authorization_code&code=${Http.enc(code)}&redirect_uri=${Http.enc(StoreSignIn.GOG_REDIRECT)}",
        ).optString("access_token").ifBlank { throw ImportError("GOG didn't accept the sign-in. Try again.") }

        val out = mutableListOf<ImportItem>()
        var page = 1
        var totalPages = 1
        do {
            onProgress("Reading your GOG library (page $page of $totalPages)…")
            val j = Http.json(
                "https://embed.gog.com/account/getFilteredProducts?mediaType=1&page=$page",
                headers = mapOf("Authorization" to "Bearer $token"),
            )
            totalPages = j.optInt("totalPages", 1)
            val products = j.optJSONArray("products") ?: JSONArray()
            for (i in 0 until products.length()) {
                val p = products.optJSONObject(i) ?: continue
                val title = p.optString("title").takeIf { it.isNotBlank() } ?: continue
                out += ImportItem(title, "PC", "GOG")
            }
            page++
        } while (page <= totalPages && page < 100)
        return out
    }

    // ---------- Epic ----------

    private suspend fun epic(code: String, onProgress: (String) -> Unit): List<ImportItem> {
        onProgress("Signing in to Epic…")
        val basic = Base64.encodeToString(
            "${StoreSignIn.EPIC_CLIENT}:${StoreSignIn.EPIC_SECRET}".toByteArray(), Base64.NO_WRAP,
        )
        val token = Http.json(
            "https://account-public-service-prod03.ol.epicgames.com/account/api/oauth/token",
            method = "POST",
            headers = mapOf("Authorization" to "Basic $basic", "Content-Type" to "application/x-www-form-urlencoded"),
            body = Http.form("grant_type" to "authorization_code", "code" to code, "token_type" to "eg1"),
        ).optString("access_token").ifBlank { throw ImportError("Epic didn't accept the sign-in. Try again.") }
        val auth = mapOf("Authorization" to "bearer $token")

        // Every item you own (games, DLC and apps), page by page.
        onProgress("Reading your Epic library…")
        val records = mutableListOf<JSONObject>()
        var cursor: String? = null
        do {
            val url = "https://library-service.live.use1a.on.epicgames.com/library/api/public/items?includeMetadata=true" +
                (cursor?.let { "&cursor=${Http.enc(it)}" } ?: "")
            val j = Http.json(url, headers = auth)
            val recs = j.optJSONArray("records") ?: JSONArray()
            for (i in 0 until recs.length()) recs.optJSONObject(i)?.let { records += it }
            cursor = j.optJSONObject("responseMetadata")?.optString("nextCursor")?.takeIf { it.isNotBlank() && it != "null" }
        } while (cursor != null && records.size < 20_000)

        val wanted = records
            .filter { it.optString("namespace") != "ue" }
            .map { it.optString("namespace") to it.optString("catalogItemId") }
            .filter { it.first.isNotBlank() && it.second.isNotBlank() }
            .distinct()

        // Look up each item's name and whether it's a game (not DLC or an app), a few at a time.
        var done = 0
        val gate = Semaphore(6)
        val items = coroutineScope {
            wanted.map { (ns, id) ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val info = try {
                            Http.json(
                                "https://catalog-public-service-prod06.ol.epicgames.com/catalog/api/shared/namespace/" +
                                    "$ns/bulk/items?id=$id&includeDLCDetails=true&includeMainGameDetails=true" +
                                    "&country=GB&locale=en-GB",
                                headers = auth,
                            ).optJSONObject(id)
                        } catch (e: Exception) {
                            null
                        }
                        synchronized(this@StoreFetch) {
                            done++
                            if (done % 10 == 0) onProgress("Looking up Epic games ($done of ${wanted.size})…")
                        }
                        if (info == null || info.has("mainGameItem")) return@withPermit null
                        val cats = info.optJSONArray("categories") ?: JSONArray()
                        val paths = (0 until cats.length()).map { cats.optJSONObject(it)?.optString("path").orEmpty() }
                        if ("games" !in paths || "addons" in paths) return@withPermit null
                        info.optString("title").takeIf { it.isNotBlank() }?.let { ImportItem(it, "PC", "Epic") }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        return items
    }

    // ---------- Amazon ----------

    private fun amazon(signIn: StoreSignIn, code: String, onProgress: (String) -> Unit): List<ImportItem> {
        onProgress("Signing in to Amazon Games…")
        val register = JSONObject()
            .put(
                "auth_data", JSONObject()
                    .put("authorization_code", code)
                    .put("client_domain", "DeviceLegacy")
                    .put("client_id", signIn.amazonClientId)
                    .put("code_algorithm", "SHA-256")
                    .put("code_verifier", signIn.amazonVerifier)
                    .put("use_global_authentication", false),
            )
            .put(
                "registration_data", JSONObject()
                    .put("app_name", "AGSLauncher for Windows")
                    .put("app_version", "1.0.0")
                    .put("device_model", "Windows")
                    .put("device_name", JSONObject.NULL)
                    .put("device_serial", signIn.amazonSerial)
                    .put("device_type", "A2UMVHOX7UP4V7")
                    .put("domain", "Device")
                    .put("os_version", "10.0.19044.0"),
            )
            .put("requested_extensions", JSONArray().put("customer_info").put("device_info"))
            .put("requested_token_type", JSONArray().put("bearer").put("mac_dms"))
            .put("user_context_map", JSONObject())
        val reg = Http.json(
            "https://api.amazon.com/auth/register", method = "POST",
            headers = mapOf("Content-Type" to "application/json", "User-Agent" to "AGSLauncher/1.0.0"),
            body = register.toString(),
        )
        val success = reg.optJSONObject("response")?.optJSONObject("success")
            ?: throw ImportError("Amazon didn't accept the sign-in. Try again.")
        val token = success.optJSONObject("tokens")?.optJSONObject("bearer")?.optString("access_token")
            ?.takeIf { it.isNotBlank() } ?: throw ImportError("Amazon didn't send a sign-in token.")
        val serial = success.optJSONObject("extensions")?.optJSONObject("device_info")
            ?.optString("device_serial_number")?.takeIf { it.isNotBlank() } ?: signIn.amazonSerial
        val hardwareHash = MessageDigest.getInstance("SHA-256").digest(serial.toByteArray())
            .joinToString("") { "%02X".format(it) }

        onProgress("Reading your Amazon Games library…")
        val seen = mutableSetOf<String>()
        val out = mutableListOf<ImportItem>()
        var next: String? = null
        var pages = 0
        do {
            val body = JSONObject()
                .put("Operation", "GetEntitlements")
                .put("clientId", "Sonic")
                .put("syncPoint", JSONObject.NULL)
                .put("nextToken", next ?: JSONObject.NULL)
                .put("maxResults", 50)
                .put("productIdFilter", JSONObject.NULL)
                .put("keyId", "d5dc8b8b-86c8-4fc4-ae93-18c0def5314d")
                .put("hardwareHash", hardwareHash)
            val j = Http.json(
                "https://gaming.amazon.com/api/distribution/entitlements", method = "POST",
                headers = mapOf(
                    "X-Amz-Target" to "com.amazon.animusdistributionservice.entitlement.AnimusEntitlementsService.GetEntitlements",
                    "x-amzn-token" to token,
                    "UserAgent" to "com.amazon.agslauncher.win/3.0.9202.1",
                    "User-Agent" to "AGSLauncher/1.0.0",
                    "Content-Type" to "application/json",
                    "Content-Encoding" to "amz-1.0",
                ),
                body = body.toString(),
            )
            val ents = j.optJSONArray("entitlements") ?: JSONArray()
            for (i in 0 until ents.length()) {
                val p = ents.optJSONObject(i)?.optJSONObject("product") ?: continue
                val id = p.optString("id")
                val title = p.optString("title").takeIf { it.isNotBlank() && it != "null" } ?: continue
                if (seen.add(id.ifBlank { title })) out += ImportItem(title, "PC", "Amazon")
            }
            next = j.optString("nextToken").takeIf { j.has("nextToken") && it.isNotBlank() && it != "null" }
            pages++
        } while (next != null && pages < 200)
        return out
    }

    // ---------- PlayStation ----------

    private fun playstation(npsso: String, onProgress: (String) -> Unit): List<ImportItem> {
        onProgress("Signing in to PlayStation…")
        val authorize = "https://ca.account.sony.com/api/authz/v3/oauth/authorize?" + Http.form(
            "access_type" to "offline",
            "client_id" to "09515159-7237-4370-9b40-3806e67c0891",
            "redirect_uri" to "com.scee.psxandroid.scecompcall://redirect",
            "response_type" to "code",
            "scope" to "psn:mobile.v2.core psn:clientapp",
        )
        val r = Http.call(authorize, headers = mapOf("Cookie" to "npsso=$npsso"), follow = false)
        val code = r.location?.let { Uri.parse(it.replace("redirect/?", "redirect?")).getQueryParameter("code") }
            ?: Regex("[?&]code=([^&]+)").find(r.location.orEmpty())?.groupValues?.get(1)
            ?: throw ImportError("PlayStation didn't accept the sign-in. Try signing in again.")
        val token = Http.json(
            "https://ca.account.sony.com/api/authz/v3/oauth/token", method = "POST",
            headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded",
                "Authorization" to "Basic MDk1MTUxNTktNzIzNy00MzcwLTliNDAtMzgwNmU2N2MwODkxOnVjUGprYTV0bnRCMktxc1A=",
            ),
            body = Http.form(
                "code" to code,
                "redirect_uri" to "com.scee.psxandroid.scecompcall://redirect",
                "grant_type" to "authorization_code",
                "token_format" to "jwt",
            ),
        ).optString("access_token").ifBlank { throw ImportError("PlayStation didn't send a sign-in token.") }
        val auth = mapOf("Authorization" to "Bearer $token")

        val byName = linkedMapOf<String, ImportItem>()

        // Games you bought or claimed (PS4/PS5).
        onProgress("Reading your PlayStation purchases…")
        var start = 0
        while (start < 5000) {
            val variables = JSONObject()
                .put("isActive", true)
                .put("platform", JSONArray().put("ps4").put("ps5"))
                .put("size", 100)
                .put("start", start)
                .put("sortBy", "ACTIVE_DATE")
                .put("sortDirection", "desc")
            val ext = JSONObject().put(
                "persistedQuery", JSONObject()
                    .put("version", 1)
                    .put("sha256Hash", "827a423f6a8ddca4107ac01395af2ec0eafd8396fc7fa204aaf9b7ed2eefa168"),
            )
            val url = "https://web.np.playstation.com/api/graphql/v1/op?operationName=getPurchasedGameList" +
                "&variables=${Http.enc(variables.toString())}&extensions=${Http.enc(ext.toString())}"
            val games = try {
                Http.json(url, headers = auth).optJSONObject("data")
                    ?.optJSONObject("purchasedTitlesRetrieve")?.optJSONArray("games")
            } catch (e: Exception) {
                null
            } ?: break
            for (i in 0 until games.length()) {
                val g = games.optJSONObject(i) ?: continue
                val name = g.optString("name").takeIf { it.isNotBlank() } ?: continue
                val plus = g.optString("membership") == "PS_PLUS"
                byName.getOrPut(normTitle(name)) {
                    ImportItem(
                        name, g.optString("platform").ifBlank { "PS5" }.uppercase(), "PlayStation",
                        note = if (plus) "PS Plus" else "", suggested = !plus,
                    )
                }
            }
            if (games.length() < 100) break
            start += 100
        }

        // Games you've played (adds play time, and older games not in purchases).
        onProgress("Reading your PlayStation play history…")
        var offset = 0
        while (offset < 5000) {
            val j = try {
                Http.json(
                    "https://m.np.playstation.com/api/gamelist/v2/users/me/titles?categories=ps4_game,ps5_native_game" +
                        "&limit=200&offset=$offset",
                    headers = auth,
                )
            } catch (e: Exception) {
                break
            }
            val titles = j.optJSONArray("titles") ?: break
            for (i in 0 until titles.length()) {
                val t = titles.optJSONObject(i) ?: continue
                val name = t.optString("name").takeIf { it.isNotBlank() } ?: continue
                val hours = isoHours(t.optString("playDuration"))
                val platform = if (t.optString("category").startsWith("ps5")) "PS5" else "PS4"
                val key = normTitle(name)
                val existing = byName[key]
                byName[key] = existing?.copy(hours = maxOf(existing.hours, hours))
                    ?: ImportItem(name, platform, "PlayStation", hours = hours, note = "Played")
            }
            val total = j.optInt("totalItemCount", 0)
            offset += 200
            if (titles.length() < 200 || offset >= total) break
        }
        return byName.values.toList()
    }

    /** "PT12H34M5S" → 12.57 */
    private fun isoHours(d: String): Double {
        val m = Regex("""P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""").find(d) ?: return 0.0
        val (days, h, min, s) = m.destructured
        return (days.toDoubleOrNull() ?: 0.0) * 24 + (h.toDoubleOrNull() ?: 0.0) +
            (min.toDoubleOrNull() ?: 0.0) / 60 + (s.toDoubleOrNull() ?: 0.0) / 3600
    }

    // ---------- Xbox ----------

    private fun xbox(accessToken: String, onProgress: (String) -> Unit): List<ImportItem> {
        onProgress("Signing in to Xbox…")
        val user = Http.json(
            "https://user.auth.xboxlive.com/user/authenticate", method = "POST",
            headers = mapOf("Content-Type" to "application/json", "x-xbl-contract-version" to "1"),
            body = JSONObject()
                .put("RelyingParty", "http://auth.xboxlive.com")
                .put("TokenType", "JWT")
                .put(
                    "Properties", JSONObject()
                        .put("AuthMethod", "RPS")
                        .put("SiteName", "user.auth.xboxlive.com")
                        .put("RpsTicket", "t=$accessToken"),
                ).toString(),
        ).optString("Token").ifBlank { throw ImportError("Xbox didn't accept the sign-in. Try again.") }

        val xsts = Http.call(
            "https://xsts.auth.xboxlive.com/xsts/authorize", method = "POST",
            headers = mapOf("Content-Type" to "application/json", "x-xbl-contract-version" to "1"),
            body = JSONObject()
                .put("RelyingParty", "http://xboxlive.com")
                .put("TokenType", "JWT")
                .put("Properties", JSONObject().put("UserTokens", JSONArray().put(user)).put("SandboxId", "RETAIL"))
                .toString(),
        )
        if (xsts.code == 401) throw ImportError("Xbox refused access. Child accounts can't be read this way.")
        if (xsts.code !in 200..299) throw ImportError("Xbox answered ${xsts.code}. Try again.")
        val x = JSONObject(xsts.body)
        val claims = x.optJSONObject("DisplayClaims")?.optJSONArray("xui")?.optJSONObject(0)
            ?: throw ImportError("Xbox didn't send your account details.")
        val uhs = claims.optString("uhs")
        val xuid = claims.optString("xid")
        val auth = mapOf(
            "Authorization" to "XBL3.0 x=$uhs;${x.optString("Token")}",
            "x-xbl-contract-version" to "2",
            "Accept-Language" to "en-GB",
        )

        onProgress("Reading your Xbox games…")
        val j = Http.json(
            "https://titlehub.xboxlive.com/users/xuid($xuid)/titles/titlehistory/decoration/detail",
            headers = auth,
        )
        val titles = j.optJSONArray("titles") ?: JSONArray()
        return (0 until titles.length()).mapNotNull { i ->
            val t = titles.optJSONObject(i) ?: return@mapNotNull null
            if (!t.optString("type").equals("Game", true)) return@mapNotNull null
            val name = t.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val devices = t.optJSONArray("devices")?.let { d -> (0 until d.length()).map { d.optString(it) } } ?: emptyList()
            val platform = when {
                "XboxSeries" in devices -> "Xbox Series"
                "XboxOne" in devices -> "Xbox One"
                "Xbox360" in devices -> "Xbox 360"
                "PC" in devices -> "PC"
                else -> "Xbox"
            }
            ImportItem(name, platform, "Xbox", note = "Played")
        }
    }
}
