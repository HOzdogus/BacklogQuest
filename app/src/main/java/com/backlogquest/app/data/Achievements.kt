package com.backlogquest.app.data

import android.content.Context
import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Finds a game's full achievement / trophy list.
 *
 * - Steam: Steam's public store search and community achievement pages.
 * - RetroAchievements: the official RA web API (needs your username + API key).
 * - PlayStation: PSNProfiles; Xbox: TrueAchievements. These two only work in a
 *   real browser, so they're read with an invisible one ([PageReader]).
 */
object Achievements {
    const val STEAM = "Steam"
    const val RA = "RetroAchievements"
    const val PSN = "PSNProfiles"
    const val XBOX = "TrueAchievements"
    val SOURCES = listOf(STEAM, RA, PSN, XBOX)

    class Found(val title: String, val list: List<Trophy>)
    class AchError(message: String) : Exception(message)

    /** Picks where to look, from the game's platform (or device) and store. */
    fun pickSource(g: Game, deviceName: String): String? {
        val p = g.platform.ifBlank { deviceName }.lowercase()
        return when {
            g.source.equals("Steam", true) || p.contains("steam") -> STEAM
            Regex("ps3|ps4|ps5|vita|playstation ?[345]").containsMatchIn(p) -> PSN
            p.contains("xbox") -> XBOX
            raAlias(p) != null -> RA
            p == "pc" || p.contains("windows") || Regex("\\bpc\\b").containsMatchIn(p) -> STEAM
            else -> null
        }
    }

    suspend fun find(ctx: Context, source: String, title: String, platform: String, s: AppState): Found =
        when (source) {
            STEAM -> steam(title)
            RA -> retro(title, platform, s)
            PSN -> psnProfiles(ctx, title, platform)
            XBOX -> trueAchievements(ctx, title)
            else -> throw AchError("Unknown source.")
        }

    /** Keeps your ticks when a list is refreshed. */
    fun mergeEarned(old: List<Trophy>, new: List<Trophy>): List<Trophy> {
        val earned = old.filter { it.earned }.map { it.name.lowercase() }.toSet()
        return new.map { if (it.earned || it.name.lowercase() in earned) it.copy(earned = true) else it }
    }

    private fun clean(html: String): String =
        Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().replace(Regex("\\s+"), " ").trim()

    // ---------------- Steam ----------------

    private suspend fun steam(title: String): Found = withContext(Dispatchers.IO) {
        val search = Http.json(
            "https://store.steampowered.com/api/storesearch/?term=${Http.enc(title)}&l=english&cc=GB",
        ).optJSONArray("items") ?: JSONArray()
        val items = (0 until search.length()).mapNotNull { search.optJSONObject(it) }
        val q = normTitle(title)
        val app = items.firstOrNull { normTitle(it.optString("name")) == q } ?: items.firstOrNull()
            ?: throw AchError("Couldn't find this game on Steam.")
        val id = app.optInt("id")
        val page = Http.call("https://steamcommunity.com/stats/$id/achievements/?l=english")
        if (page.code !in 200..299) throw AchError("Steam has no achievement page for ${app.optString("name")}.")
        val rows = Regex("""<h3>(.*?)</h3>\s*<h5>(.*?)</h5>""", RegexOption.DOT_MATCHES_ALL).findAll(page.body)
        val percents = Regex("""achievePercent">\s*([\d.]+)%""").findAll(page.body).map { it.groupValues[1] }.toList()
        val list = rows.mapIndexed { i, m ->
            Trophy(
                name = clean(m.groupValues[1]),
                desc = clean(m.groupValues[2]),
                kind = percents.getOrNull(i)?.let { "$it% of players" } ?: "",
            )
        }.filter { it.name.isNotBlank() }.toList()
        if (list.isEmpty()) throw AchError("${app.optString("name")} has no Steam achievements.")
        Found(app.optString("name"), list)
    }

    // ---------------- RetroAchievements ----------------

    private val RA_ALIASES = mapOf(
        "NES/Famicom" to listOf("nes", "famicom", "nintendo entertainment system"),
        "SNES/Super Famicom" to listOf("snes", "super nintendo", "super famicom", "sfc"),
        "Nintendo 64" to listOf("n64", "nintendo 64"),
        "Game Boy" to listOf("gb", "game boy", "gameboy"),
        "Game Boy Color" to listOf("gbc", "game boy color", "gameboy color"),
        "Game Boy Advance" to listOf("gba", "game boy advance", "gameboy advance"),
        "Nintendo DS" to listOf("ds", "nds", "nintendo ds"),
        "Nintendo DSi" to listOf("dsi", "nintendo dsi"),
        "GameCube" to listOf("gc", "gcn", "gamecube", "nintendo gamecube"),
        "Wii" to listOf("wii"),
        "Virtual Boy" to listOf("virtual boy"),
        "PlayStation" to listOf("ps1", "psx", "psone", "playstation", "playstation 1"),
        "PlayStation 2" to listOf("ps2", "playstation 2"),
        "PlayStation Portable" to listOf("psp", "playstation portable"),
        "Genesis/Mega Drive" to listOf("genesis", "mega drive", "megadrive", "sega genesis", "sega mega drive", "md"),
        "Master System" to listOf("sms", "master system", "sega master system"),
        "Game Gear" to listOf("game gear", "gg", "sega game gear"),
        "Sega CD" to listOf("sega cd", "mega cd"),
        "32X" to listOf("32x", "sega 32x"),
        "Saturn" to listOf("saturn", "sega saturn"),
        "Dreamcast" to listOf("dreamcast", "dc", "sega dreamcast"),
        "PC Engine/TurboGrafx-16" to listOf("pc engine", "turbografx", "turbografx-16", "tg16", "pce"),
        "Atari 2600" to listOf("atari 2600", "2600"),
        "Atari 7800" to listOf("atari 7800", "7800"),
        "Atari Jaguar" to listOf("jaguar", "atari jaguar"),
        "Atari Lynx" to listOf("lynx", "atari lynx"),
        "Neo Geo Pocket" to listOf("neo geo pocket", "ngp", "ngpc"),
        "WonderSwan" to listOf("wonderswan", "ws", "wsc"),
        "3DO Interactive Multiplayer" to listOf("3do"),
        "Arcade" to listOf("arcade", "mame"),
        "MSX" to listOf("msx"),
    )

    /** The RetroAchievements console name for a platform like "PS2" or "Game Boy Advance". */
    fun raAlias(platform: String): String? {
        val p = platform.trim().lowercase()
        if (p.isEmpty()) return null
        return RA_ALIASES.entries.firstOrNull { (_, names) -> p in names }?.key
    }

    private var raConsoles: JSONArray? = null
    private val raGameLists = mutableMapOf<Int, JSONArray>()

    private fun raUrl(endpoint: String, s: AppState, params: String) =
        "https://retroachievements.org/API/$endpoint.php?y=${Http.enc(s.raKey)}" +
            (if (s.raUser.isNotBlank()) "&z=${Http.enc(s.raUser)}" else "") + "&$params"

    private fun raTitle(t: String) = normTitle(t.replace(Regex("\\bthe\\b", RegexOption.IGNORE_CASE), ""))

    private suspend fun retro(title: String, platform: String, s: AppState): Found = withContext(Dispatchers.IO) {
        if (s.raKey.isBlank()) throw AchError("Add your RetroAchievements username and API key in Setup first.")
        val consoleName = raAlias(platform) ?: throw AchError("Set the game's platform (e.g. PS2 or SNES) so RetroAchievements knows where to look.")

        val consoles = raConsoles ?: run {
            val r = Http.call(raUrl("API_GetConsoleIDs", s, "a=1&g=1"))
            if (r.code == 401 || r.code == 403) throw AchError("RetroAchievements didn't accept your API key.")
            JSONArray(r.body).also { raConsoles = it }
        }
        val console = (0 until consoles.length()).mapNotNull { consoles.optJSONObject(it) }
            .firstOrNull { it.optString("Name").equals(consoleName, true) }
            ?: (0 until consoles.length()).mapNotNull { consoles.optJSONObject(it) }
                .firstOrNull { normTitle(it.optString("Name")).contains(normTitle(consoleName)) }
            ?: throw AchError("RetroAchievements doesn't support $consoleName.")
        val consoleId = console.optInt("ID")

        val games = raGameLists[consoleId] ?: JSONArray(Http.call(raUrl("API_GetGameList", s, "i=$consoleId&f=1")).body)
            .also { raGameLists[consoleId] = it }
        val q = raTitle(title)
        val candidates = (0 until games.length()).mapNotNull { games.optJSONObject(it) }
            .filter { !it.optString("Title").startsWith("~") } // skip hacks, homebrew and subsets
        val game = candidates.firstOrNull { raTitle(it.optString("Title")) == q }
            ?: candidates.firstOrNull { raTitle(it.optString("Title")).let { t -> t.startsWith(q) || q.startsWith(t) } }
            ?: throw AchError("Couldn't find this game on RetroAchievements for ${console.optString("Name")}.")
        val gameId = game.optInt("ID")

        // With your username we also get which ones you've already earned.
        val info = JSONObject(
            Http.call(
                if (s.raUser.isNotBlank()) raUrl("API_GetGameInfoAndUserProgress", s, "g=$gameId&u=${Http.enc(s.raUser)}")
                else raUrl("API_GetGameExtended", s, "i=$gameId"),
            ).body,
        )
        val achs = info.optJSONObject("Achievements") ?: JSONObject()
        val list = achs.keys().asSequence().mapNotNull { achs.optJSONObject(it) }
            .sortedBy { it.optInt("DisplayOrder", 0) }
            .map { a ->
                val earned = listOf("DateEarned", "DateEarnedHardcore").any { k -> !a.isNull(k) && a.optString(k).isNotBlank() }
                Trophy(a.optString("Title"), a.optString("Description"), "${a.optInt("Points")} pts", earned)
            }.toList()
        if (list.isEmpty()) throw AchError("This game has no RetroAchievements yet.")
        Found(game.optString("Title"), list)
    }

    // ---------------- PSNProfiles / TrueAchievements ----------------

    /** Waits for a real page (not a "checking your browser" one) before reading. */
    private const val READY =
        "if (document.readyState !== 'complete' || /just a moment|attention required|checking your browser/i.test(document.title)) return '';"

    private suspend fun psnProfiles(ctx: Context, title: String, platform: String): Found {
        val search = PageReader.read(
            ctx, "https://psnprofiles.com/search/games?q=${Http.enc(title)}",
            """
            (function(){ $READY
              var out = [], seen = {};
              document.querySelectorAll('a[href^="/trophies/"]').forEach(function(a){
                var t = (a.innerText || '').trim(), h = a.getAttribute('href');
                if (!t || seen[h]) return; seen[h] = 1;
                var row = a.closest('tr'); out.push({t: t, h: h, r: row ? row.innerText : ''});
              });
              return JSON.stringify(out);
            })()
            """.trimIndent(),
        ) ?: throw AchError("Couldn't reach PSNProfiles. Try again in a moment.")
        val results = JSONArray(search).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
        val q = normTitle(title)
        val plat = Regex("ps3|ps4|ps5|vita", RegexOption.IGNORE_CASE).find(platform)?.value?.uppercase()
        val exact = results.filter { normTitle(it.optString("t")) == q }
        val pick = exact.firstOrNull { plat != null && it.optString("r").uppercase().contains(plat) }
            ?: exact.firstOrNull() ?: results.firstOrNull()
            ?: throw AchError("Couldn't find this game on PSNProfiles.")

        val page = PageReader.read(
            ctx, "https://psnprofiles.com" + pick.optString("h"),
            """
            (function(){ $READY
              var out = [], seen = {};
              document.querySelectorAll('a[href*="/trophy/"]').forEach(function(a){
                var n = (a.innerText || '').trim();
                if (!n || seen[n]) return; seen[n] = 1;
                var row = a.closest('tr') || a.parentElement;
                var cell = a.closest('td') || a.parentElement;
                var d = (cell.innerText || '').replace(n, '').trim().split('\n')[0] || '';
                var k = '', html = row.innerHTML.toLowerCase();
                ['Platinum','Gold','Silver','Bronze'].forEach(function(x){
                  if (html.indexOf('title="' + x.toLowerCase() + '"') >= 0 || html.indexOf(x.toLowerCase() + '.png') >= 0) k = x;
                });
                var img = row.querySelector('img[title]');
                if (!k && img && /platinum|gold|silver|bronze/i.test(img.title)) k = img.title;
                out.push({n: n, d: d, k: k});
              });
              return JSON.stringify(out);
            })()
            """.trimIndent(),
            timeoutMs = 30_000,
        ) ?: throw AchError("Couldn't load the trophy list from PSNProfiles.")
        val arr = JSONArray(page)
        val list = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .map { Trophy(it.optString("n"), it.optString("d"), it.optString("k")) }
        if (list.isEmpty()) throw AchError("PSNProfiles didn't list any trophies for this game.")
        return Found(pick.optString("t"), list)
    }

    private suspend fun trueAchievements(ctx: Context, title: String): Found {
        val search = PageReader.read(
            ctx, "https://www.trueachievements.com/searchresults.aspx?search=${Http.enc(title)}",
            """
            (function(){ $READY
              if (/^\/game\/[^\/]+\/?$/.test(location.pathname)) {
                var h1 = document.querySelector('h1');
                return JSON.stringify([{t: h1 ? h1.innerText.trim() : '', h: location.pathname.replace(/\/$/, '')}]);
              }
              var out = [], seen = {};
              document.querySelectorAll('a[href^="/game/"]').forEach(function(a){
                var h = a.getAttribute('href').split('?')[0].replace(/\/$/, '');
                var t = (a.innerText || '').trim();
                if (!/^\/game\/[^\/]+$/.test(h) || !t || seen[h]) return; seen[h] = 1;
                out.push({t: t, h: h});
              });
              return JSON.stringify(out);
            })()
            """.trimIndent(),
        ) ?: throw AchError("Couldn't reach TrueAchievements. Try again in a moment.")
        val results = JSONArray(search).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
        val q = normTitle(title)
        val pick = results.firstOrNull { normTitle(it.optString("t")) == q } ?: results.firstOrNull()
            ?: throw AchError("Couldn't find this game on TrueAchievements.")

        val page = PageReader.read(
            ctx, "https://www.trueachievements.com" + pick.optString("h") + "/achievements",
            """
            (function(){ $READY
              var out = [], seen = {};
              document.querySelectorAll('a[href]').forEach(function(a){
                var p = a.getAttribute('href').split('?')[0];
                if (!/^\/a\d+\//.test(p)) return;
                var n = (a.innerText || '').trim();
                if (!n || seen[n]) return; seen[n] = 1;
                var box = a.closest('li') || a.closest('div') || a.parentElement;
                var dp = box ? box.querySelector('p') : null;
                var d = dp ? dp.innerText.trim() : '';
                var g = '', m = (box ? box.innerText : '').match(/(\d+)\s*G\b/);
                if (m) g = m[1] + 'G';
                out.push({n: n, d: d, k: g});
              });
              return JSON.stringify(out);
            })()
            """.trimIndent(),
            timeoutMs = 30_000,
        ) ?: throw AchError("Couldn't load the achievement list from TrueAchievements.")
        val arr = JSONArray(page)
        val list = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .map { Trophy(it.optString("n"), it.optString("d"), it.optString("k")) }
        if (list.isEmpty()) throw AchError("TrueAchievements didn't list any achievements for this game.")
        return Found(pick.optString("t").ifBlank { title }, list)
    }
}
