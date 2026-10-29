package com.backlogquest.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Wishlist prices from IsThereAnyDeal: the cheapest current UK price across
 * the stores it tracks (Steam, GOG, Epic, Humble, Fanatical...), plus the
 * all-time low. Needs a free API key from isthereanydeal.com (Apps → Register app).
 */
object Prices {
    private const val API = "https://api.isthereanydeal.com"

    class PriceError(message: String) : Exception(message)

    /**
     * Looks up prices for [titles]. Returns normTitle → Price for every game it
     * could price. Titles it can't find are just left out.
     */
    suspend fun lookup(key: String, titles: List<String>, onProgress: (String) -> Unit = {}): Map<String, Price> =
        withContext(Dispatchers.IO) {
            if (key.isBlank()) throw PriceError("Add your IsThereAnyDeal API key in Setup first.")
            val k = Http.enc(key.trim())

            // 1. Find IsThereAnyDeal's ID for each title.
            val ids = linkedMapOf<String, String>() // ITAD id → normTitle
            titles.distinctBy { normTitle(it) }.forEachIndexed { i, t ->
                if (i % 5 == 0) onProgress("Finding games on IsThereAnyDeal (${i + 1} of ${titles.size})…")
                val r = Http.call("$API/games/lookup/v1?key=$k&title=${Http.enc(t)}")
                if (r.code == 401 || r.code == 403) throw PriceError("IsThereAnyDeal didn't accept your API key.")
                if (r.code !in 200..299) return@forEachIndexed
                val game = try { org.json.JSONObject(r.body).optJSONObject("game") } catch (e: Exception) { null }
                val id = game?.optString("id")?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
                ids[id] = normTitle(t)
            }
            if (ids.isEmpty()) return@withContext emptyMap()

            val today = today()
            val out = mutableMapOf<String, Price>()
            for (chunk in ids.keys.chunked(50)) {
                onProgress("Getting prices (${out.size} of ${ids.size} done)…")
                val body = JSONArray(chunk).toString()
                val headers = mapOf("Content-Type" to "application/json")

                // 2. All-time lows.
                val lows = mutableMapOf<String, Double>()
                try {
                    val arr = JSONArray(Http.call("$API/games/historylow/v1?key=$k&country=GB", "POST", headers, body).body)
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val amount = o.optJSONObject("low")?.optJSONObject("price")?.optDouble("amount", Double.NaN)
                        if (amount != null && !amount.isNaN()) lows[o.optString("id")] = amount
                    }
                } catch (e: Exception) {
                    // Lows are a bonus; carry on without them.
                }

                // 3. Current deals: keep the cheapest.
                val arr = try {
                    JSONArray(Http.call("$API/games/prices/v3?key=$k&country=GB", "POST", headers, body).body)
                } catch (e: Exception) {
                    continue
                }
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    val deals = o.optJSONArray("deals") ?: continue
                    val best = (0 until deals.length()).mapNotNull { deals.optJSONObject(it) }
                        .minByOrNull { it.optJSONObject("price")?.optDouble("amount", Double.MAX_VALUE) ?: Double.MAX_VALUE }
                        ?: continue
                    val price = best.optJSONObject("price") ?: continue
                    val norm = ids[id] ?: continue
                    out[norm] = Price(
                        amount = price.optDouble("amount", 0.0),
                        regular = best.optJSONObject("regular")?.optDouble("amount", 0.0) ?: 0.0,
                        cut = best.optInt("cut", 0),
                        shop = best.optJSONObject("shop")?.optString("name").orEmpty(),
                        low = lows[id],
                        currency = price.optString("currency", "GBP"),
                        url = best.optString("url"),
                        updated = today,
                    )
                }
            }
            out
        }

    private fun money(amount: Double, currency: String): String {
        val symbol = when (currency) {
            "GBP" -> "£"
            "USD" -> "$"
            "EUR" -> "€"
            else -> "$currency "
        }
        return symbol + String.format(java.util.Locale.UK, "%.2f", amount)
    }

    /** "£9.99", or "Free". */
    fun format(p: Price): String = if (p.amount == 0.0) "Free" else money(p.amount, p.currency)

    /** "£9.99 (−50%) at Steam · lowest ever £4.99" */
    fun describe(p: Price): String {
        val parts = mutableListOf(
            format(p) + (if (p.cut > 0) " (−${p.cut}%)" else "") + (if (p.shop.isNotBlank()) " at ${p.shop}" else ""),
        )
        p.low?.let { parts += "lowest ever " + money(it, p.currency) }
        return parts.joinToString(" · ")
    }
}
