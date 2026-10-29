package com.backlogquest.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Small helpers so JSON nulls come back as empty values instead of the text "null". */
private fun JSONObject.str(key: String): String =
    if (!has(key) || isNull(key)) "" else optString(key, "")

private fun JSONObject.num(key: String): Double =
    if (!has(key) || isNull(key)) 0.0 else optDouble(key, 0.0).let { if (it.isNaN()) 0.0 else it }

private fun JSONArray.strAt(i: Int): String =
    if (i >= length() || isNull(i)) "" else optString(i, "")

private fun JSONArray.numAt(i: Int): Double =
    if (i >= length() || isNull(i)) 0.0 else optDouble(i, 0.0).let { if (it.isNaN()) 0.0 else it }

object Net {
    /** Downloads a web address as text. Throws if the server says anything other than OK. */
    fun get(address: String): String {
        val conn = URL(address).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "BacklogQuest-Android")
        try {
            val code = conn.responseCode
            if (code == 404) throw java.io.FileNotFoundException(address)
            if (code !in 200..299) throw java.io.IOException("The server answered $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

object Store {
    private const val STATE_FILE = "state.json"
    private const val REF_FILE = "data-file.json"

    // ---------- Your own state ----------

    fun saveState(dir: File, s: AppState) {
        val o = JSONObject()
        o.put("baseline", s.baseline)
        o.put("devices", JSONArray().apply { s.devices.forEach { put(deviceToJson(it)) } })
        o.put("games", JSONArray().apply { s.games.forEach { put(gameToJson(it)) } })
        o.put("hiddenWish", JSONArray().apply { s.hiddenWish.forEach { put(it) } })
        o.put("dataUrl", s.dataUrl)
        o.put("rawgKey", s.rawgKey)
        o.put("lastSync", s.lastSync)
        // Write to a temporary file first so a crash mid-save can't wipe your list.
        val tmp = File(dir, "$STATE_FILE.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(File(dir, STATE_FILE))
    }

    fun loadState(dir: File): AppState? {
        val f = File(dir, STATE_FILE)
        if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText())
            AppState(
                baseline = o.optInt("baseline", 0),
                devices = parseDevices(o.optJSONArray("devices")).ifEmpty { DEFAULT_DEVICES },
                games = parseGames(o.optJSONArray("games")),
                hiddenWish = o.optJSONArray("hiddenWish")?.let { a ->
                    (0 until a.length()).map { a.strAt(it) }.toSet()
                } ?: emptySet(),
                dataUrl = o.str("dataUrl").ifBlank { DEFAULT_DATA_URL },
                rawgKey = o.str("rawgKey"),
                lastSync = o.str("lastSync"),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun deviceToJson(d: Device) = JSONObject().apply {
        put("id", d.id); put("name", d.name); put("exempt", d.exempt)
    }

    private fun gameToJson(g: Game) = JSONObject().apply {
        put("id", g.id); put("title", g.title); put("device", g.device); put("status", g.status)
        put("genre", g.genre); put("series", g.series); put("desc", g.desc)
        put("est", g.est); put("estSource", g.estSource); put("hours", g.hours)
        put("beatenAt", g.beatenAt); put("addedAt", g.addedAt); put("order", g.order)
        put("cover", g.cover); put("platform", g.platform); put("source", g.source)
    }

    private fun parseDevices(a: JSONArray?): List<Device> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.str("id")
            if (id.isBlank()) null else Device(id, o.str("name").ifBlank { id }, o.optBoolean("exempt", false))
        }
    }

    private fun parseGames(a: JSONArray?): List<Game> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.str("id")
            if (id.isBlank()) return@mapNotNull null
            Game(
                id = id,
                title = o.str("title"),
                device = o.str("device"),
                status = o.str("status").ifBlank { "backlog" },
                genre = o.str("genre"),
                series = o.str("series"),
                desc = o.str("desc"),
                est = o.num("est"),
                estSource = o.str("estSource"),
                hours = o.num("hours"),
                beatenAt = o.str("beatenAt"),
                addedAt = o.str("addedAt"),
                order = if (o.has("order") && !o.isNull("order")) o.optLong("order", 0L) else 0L,
                cover = o.str("cover"),
                platform = o.str("platform"),
                source = o.str("source"),
            )
        }
    }

    // ---------- The data file ----------

    fun saveDataFile(dir: File, text: String) {
        val tmp = File(dir, "$REF_FILE.tmp")
        tmp.writeText(text)
        tmp.renameTo(File(dir, REF_FILE))
    }

    fun loadDataFile(dir: File): ParsedExport? {
        val f = File(dir, REF_FILE)
        if (!f.exists()) return null
        return try {
            parseExport(f.readText())
        } catch (e: Exception) {
            null
        }
    }

    /** Reads the backlog-data.json format made from the web version of Backlog Quest. */
    fun parseExport(text: String): ParsedExport {
        val root = JSONObject(text)

        val history = ArrayList<Completion>()
        root.optJSONArray("history")?.let { a ->
            for (i in 0 until a.length()) {
                val x = a.optJSONArray(i) ?: continue
                history.add(Completion(x.strAt(0), x.strAt(1), x.strAt(2)))
            }
        }

        val onDrive = HashMap<String, String>()
        root.optJSONObject("onDrive")?.let { o ->
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                onDrive[k] = o.str(k)
            }
        }

        val hidden = HashSet<String>()
        root.optJSONArray("hidden")?.let { a -> for (i in 0 until a.length()) hidden.add(a.strAt(i)) }

        fun platforms(s: String) = s.split("|").filter { it.isNotBlank() }

        val wish = ArrayList<WishItem>()
        root.optJSONArray("wishlist")?.let { a ->
            for (i in 0 until a.length()) {
                val x = a.optJSONArray(i) ?: continue
                val id = x.strAt(0)
                val tags = ArrayList<String>()
                x.optJSONArray(5)?.let { t -> for (j in 0 until t.length()) tags.add(t.strAt(j)) }
                val drive = onDrive[id].orEmpty()
                if (drive.isNotEmpty()) tags.add("drive")
                wish.add(WishItem(id, x.strAt(1), platforms(x.strAt(2)), x.numAt(3), x.numAt(4), tags, false, drive))
            }
        }
        root.optJSONArray("catalog")?.let { a ->
            for (i in 0 until a.length()) {
                val x = a.optJSONArray(i) ?: continue
                val id = x.strAt(0)
                if (id in hidden) continue
                val drive = onDrive[id].orEmpty()
                val tags = if (drive.isNotEmpty()) listOf("drive") else emptyList()
                wish.add(WishItem(id, x.strAt(1), platforms(x.strAt(2)), x.numAt(3), x.numAt(4), tags, true, drive))
            }
        }

        val state = root.optJSONObject("state")?.let { s ->
            ImportedState(
                baseline = s.optInt("baseline", 0),
                devices = parseDevices(s.optJSONArray("devices")),
                games = parseGames(s.optJSONArray("games")),
            )
        }

        return ParsedExport(RefData(history, wish, root.str("exportedAt")), state)
    }
}
