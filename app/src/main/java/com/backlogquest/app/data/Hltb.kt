package com.backlogquest.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Looks up times to beat on HowLongToBeat.
 *
 * HLTB has no public API, so this does what its own website does: it finds the
 * search address in the site's code, asks for a search token, then searches.
 * If HLTB changes its website this can stop working until the app is updated.
 * Based on the open-source howlongtobeatpy project.
 */
object Hltb {
    data class Result(
        val title: String,
        /** Hours; 0 when HLTB has no time for that style. */
        val main: Double,
        val extras: Double,
        val completionist: Double,
        val cover: String,
    )

    class LookupError(message: String) : Exception(message)

    private const val BASE = "https://howlongtobeat.com/"
    private const val FALLBACK_PATH = "api/s"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/129.0.0.0 Mobile Safari/537.36"

    private class Session(
        val path: String,
        val token: String?,
        val hpKey: String?,
        val hpVal: String?,
        val madeAt: Long,
    )

    private val lock = Mutex()
    private var session: Session? = null

    suspend fun lookup(title: String): Result? = withContext(Dispatchers.IO) {
        val query = title.trim()
        if (query.isEmpty()) return@withContext null

        var s = lock.withLock { currentSession(forceNew = false) }
        var response = search(s, query)
        if (response == null) {
            // The token may have expired or the site changed: start fresh once.
            s = lock.withLock { currentSession(forceNew = true) }
            response = search(s, query)
        }
        if (response == null) {
            throw LookupError("HowLongToBeat didn't answer. It may have changed its site.")
        }

        val data = JSONObject(response).optJSONArray("data") ?: return@withContext null
        val best = pickBest(data, query) ?: return@withContext null

        fun hours(key: String): Double =
            if (best.isNull(key)) 0.0 else best.optDouble(key, 0.0).let { if (it > 0) it / 3600.0 else 0.0 }

        val image = if (best.isNull("game_image")) "" else best.optString("game_image", "")
        Result(
            title = best.optString("game_name", query),
            main = hours("comp_main"),
            extras = hours("comp_plus"),
            completionist = hours("comp_100"),
            cover = if (image.isNotBlank()) "${BASE}games/$image" else "",
        )
    }

    // ---------- session: search address + token ----------

    private fun currentSession(forceNew: Boolean): Session {
        val now = System.currentTimeMillis()
        val existing = session
        // Reuse for up to 20 minutes so a few lookups in a row stay quick.
        if (!forceNew && existing != null && now - existing.madeAt < 20 * 60_000) return existing

        val path = try {
            findSearchPath()
        } catch (e: Exception) {
            null
        } ?: FALLBACK_PATH

        var token: String? = null
        var hpKey: String? = null
        var hpVal: String? = null
        try {
            val init = JSONObject(get("$BASE$path/init?t=$now"))
            token = init.optString("token").ifBlank { null }
            val names = init.keys()
            while (names.hasNext()) {
                val name = names.next()
                val lower = name.lowercase()
                if (lower.contains("key")) hpKey = init.optString(name)
                else if (lower.contains("val")) hpVal = init.optString(name)
            }
        } catch (e: Exception) {
            // Some versions of the site don't need a token; try the search anyway.
        }

        return Session(path, token, hpKey, hpVal, now).also { session = it }
    }

    /** Reads HLTB's page code to find where its search box sends searches. */
    private fun findSearchPath(): String? {
        val home = get(BASE)
        val scripts = Regex("""src="[^"]*?(/_next/static/chunks/[^"]+\.js)"""")
            .findAll(home)
            .map { it.groupValues[1] }
            .distinct()
            .sortedByDescending { it.contains("_app") || it.contains("pages/") }
            .take(40)
            .toList()

        val fetchPost = Regex(
            """fetch\s*\(\s*["'`]/api/([a-zA-Z0-9_/]+)[^"'`]*["'`]\s*,\s*\{[^}]*method:\s*["']POST["'][^}]*\}""",
            RegexOption.IGNORE_CASE,
        )
        for (src in scripts) {
            val code = try {
                get(BASE + src.removePrefix("/"))
            } catch (e: Exception) {
                continue
            }
            val m = fetchPost.find(code) ?: continue
            return "api/" + m.groupValues[1].trimEnd('/')
        }
        return null
    }

    // ---------- search ----------

    /** Returns the response text, or null if HLTB refused the search. */
    private fun search(s: Session, query: String): String? {
        val terms = JSONArray().apply { query.split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { put(it) } }
        val payload = JSONObject().apply {
            put("searchType", "games")
            put("searchTerms", terms)
            put("searchPage", 1)
            put("size", 20)
            put("searchOptions", JSONObject().apply {
                put("games", JSONObject().apply {
                    put("userId", 0)
                    put("platform", "")
                    put("sortCategory", "popular")
                    put("rangeCategory", "main")
                    put("rangeTime", JSONObject().put("min", 0).put("max", 0))
                    put("gameplay", JSONObject().put("perspective", "").put("flow", "")
                        .put("genre", "").put("difficulty", ""))
                    put("rangeYear", JSONObject().put("max", "").put("min", ""))
                    put("modifier", "")
                })
                put("users", JSONObject().put("sortCategory", "postcount"))
                put("lists", JSONObject().put("sortCategory", "follows"))
                put("filter", "")
                put("sort", 0)
                put("randomizer", 0)
            })
            put("useCache", true)
            if (s.hpKey != null && s.hpVal != null) put(s.hpKey, s.hpVal)
        }

        val conn = URL(BASE + s.path).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "*/*")
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Referer", BASE)
        conn.setRequestProperty("Origin", BASE.trimEnd('/'))
        s.token?.let { conn.setRequestProperty("x-auth-token", it) }
        s.hpKey?.let { conn.setRequestProperty("x-hp-key", it) }
        s.hpVal?.let { conn.setRequestProperty("x-hp-val", it) }
        return try {
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun get(address: String): String {
        val conn = URL(address).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Referer", BASE)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HowLongToBeat answered $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ---------- matching ----------

    private fun norm(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    /** Prefers an exact title match, then a close one, then HLTB's most popular result. */
    private fun pickBest(data: JSONArray, query: String): JSONObject? {
        if (data.length() == 0) return null
        val q = norm(query)
        val items = (0 until data.length()).mapNotNull { data.optJSONObject(it) }
        fun names(o: JSONObject) = listOf(o.optString("game_name"), o.optString("game_alias"))
            .map { norm(it) }.filter { it.isNotEmpty() }

        items.firstOrNull { o -> names(o).any { it == q } }?.let { return it }
        items.firstOrNull { o -> names(o).any { it.startsWith(q) || q.startsWith(it) } }?.let { return it }
        items.firstOrNull { o -> names(o).any { it.contains(q) } }?.let { return it }
        return items.firstOrNull()
    }
}
