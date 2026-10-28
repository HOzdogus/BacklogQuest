package com.backlogquest.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Auto-fill using RAWG (rawg.io), a free game database. Needs a free API key,
 * which you paste into Setup.
 */
object Rawg {
    data class Result(
        val title: String,
        val genre: String,
        val description: String,
        /** RAWG's average playtime in hours, or 0 if it doesn't know. */
        val hours: Double,
        val cover: String,
    )

    class LookupError(message: String) : Exception(message)

    suspend fun lookup(key: String, title: String): Result? = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(title, "UTF-8")
        val k = URLEncoder.encode(key.trim(), "UTF-8")
        val searchText = try {
            Net.get("https://api.rawg.io/api/games?key=$k&search=$q&page_size=1")
        } catch (e: java.io.IOException) {
            val msg = e.message.orEmpty()
            throw LookupError(
                if (msg.contains("401")) "RAWG didn't accept your API key. Check it in Setup."
                else "Lookup failed. Check your connection and try again."
            )
        }
        val results = JSONObject(searchText).optJSONArray("results")
        if (results == null || results.length() == 0) return@withContext null
        val g = results.getJSONObject(0)
        val id = g.optInt("id", -1)

        val genres = g.optJSONArray("genres")
        val genre = if (genres != null && genres.length() > 0) {
            genres.optJSONObject(0)?.optString("name", "").orEmpty()
        } else ""

        // The details call has the description; if it fails we still use what we have.
        val description = if (id > 0) {
            try {
                val d = JSONObject(Net.get("https://api.rawg.io/api/games/$id?key=$k"))
                shorten(if (d.isNull("description_raw")) "" else d.optString("description_raw", ""))
            } catch (e: Exception) {
                ""
            }
        } else ""

        Result(
            title = if (g.isNull("name")) title else g.optString("name", title),
            genre = genre,
            description = description,
            hours = if (g.isNull("playtime")) 0.0 else g.optDouble("playtime", 0.0),
            cover = if (g.isNull("background_image")) "" else g.optString("background_image", ""),
        )
    }

    /** First sentence or so, kept under about 200 characters. */
    private fun shorten(text: String): String {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return ""
        val end = clean.indexOf(". ")
        val first = if (end in 1..220) clean.substring(0, end + 1) else clean
        return if (first.length > 220) first.take(217).trimEnd() + "…" else first
    }
}
