package com.backlogquest.app.data

import java.time.LocalDate
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

val RANKS = listOf(
    "Rookie", "Button Masher", "Side Questor", "Speedrunner", "Completionist",
    "Boss Slayer", "Legend", "Mythic", "Eternal",
)

/** Time-to-beat filter buckets: key to label. */
val BUCKETS = listOf(
    "any" to "Any length", "0-2" to "Under 2h", "2-5" to "2–5h", "5-10" to "5–10h",
    "10-20" to "10–20h", "20-40" to "20–40h", "40+" to "40h+",
)

/** Wishlist filter chips: key to label. */
val WISH_TAGS = listOf(
    "all" to "All", "must" to "Must play", "sub" to "Game Pass / PS Plus", "own" to "Owned",
    "drive" to "On my drives", "unreleased" to "Unreleased", "unmarked" to "Unmarked",
)

/** How each wishlist tag is shown: label and colour style (must, sub, own or plain). */
val TAG_INFO = mapOf(
    "must" to ("MUST PLAY" to "must"),
    "gamepass" to ("GAME PASS" to "sub"),
    "psplus" to ("PS PLUS" to "sub"),
    "steam" to ("STEAM" to "own"),
    "epic" to ("EPIC" to "own"),
    "gog" to ("GOG" to "own"),
    "downloaded" to ("DOWNLOADED" to "own"),
    "drive" to ("ON MY DRIVES" to "own"),
    "unreleased" to ("UNRELEASED" to ""),
)

val STATUSES = listOf(
    "backlog" to "Backlog", "playing" to "Playing", "wishlist" to "Wishlist",
    "beaten" to "Beaten", "dropped" to "Dropped",
)

fun today(): String = LocalDate.now().toString()

fun newId(): String = java.util.UUID.randomUUID().toString().take(8)

fun inBucket(hours: Double, bucket: String): Boolean {
    if (bucket == "any") return true
    if (!(hours > 0)) return false
    if (bucket == "40+") return hours >= 40
    val parts = bucket.split("-")
    val lo = parts[0].toDouble()
    val hi = parts[1].toDouble()
    return hours >= lo && hours < hi
}

fun wishTagOk(w: WishItem, tag: String): Boolean = when (tag) {
    "all" -> true
    "must" -> "must" in w.tags
    "sub" -> "gamepass" in w.tags || "psplus" in w.tags
    "own" -> listOf("steam", "epic", "gog", "downloaded").any { it in w.tags }
    "unreleased" -> "unreleased" in w.tags
    "unmarked" -> w.unmarked
    "drive" -> w.onDrive.isNotEmpty()
    else -> true
}

/** "8h", "8½h", "45m" or "?". */
fun fmtHours(v: Double): String {
    if (!(v > 0)) return "?"
    if (v < 1) return "${(v * 60).roundToInt()}m"
    val whole = floor(v).toInt()
    return if (v % 1.0 != 0.0) "${whole}½h" else "${whole}h"
}

/** "12" for whole numbers, "12.5" otherwise. */
fun fmtNum(v: Double): String = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()

fun fmtDate(d: String): String {
    val p = d.split("-")
    if (p.size != 3) return d
    val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val m = p[1].toIntOrNull() ?: return d
    val day = p[2].toIntOrNull() ?: return d
    return "$day ${months.getOrElse(m - 1) { "" }} ${p[0]}"
}

fun deviceName(s: AppState, id: String): String =
    s.devices.firstOrNull { it.id == id }?.name ?: "Unknown"

/** "PS2 on PC" when the platform differs from the device, otherwise just the device. */
fun playedOn(s: AppState, g: Game): String {
    val d = deviceName(s, g.device)
    return if (g.platform.isBlank() || g.platform.equals(d, ignoreCase = true)) d else "${g.platform} on $d"
}

/** Platforms you've used before, most used first. */
fun knownPlatforms(s: AppState): List<String> =
    s.games.map { it.platform.trim() }.filter { it.isNotEmpty() }
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }

fun isExempt(s: AppState, id: String): Boolean =
    s.devices.firstOrNull { it.id == id }?.exempt ?: false

/** Your beaten games in the order you beat them. */
fun beaten(s: AppState): List<Game> =
    s.games.filter { it.status == "beaten" }
        .sortedWith(compareBy<Game>({ it.beatenAt }, { it.order }))

fun gameXp(g: Game): Int = 100 + min(200, (g.hours * 4).roundToInt())

data class Totals(
    val beaten: Int,
    val logged: Int,
    val year: Int,
    val xp: Int,
    val level: Int,
    val pct: Float,
    val toNext: Int,
    val rank: String,
)

/** Every completion: imported history first, then games beaten in the app. */
fun allDone(s: AppState, ref: RefData): List<Completion> =
    ref.history + beaten(s).map { Completion(it.title, it.platform.ifBlank { deviceName(s, it.device) }, it.beatenAt) }

fun totals(s: AppState, ref: RefData): Totals {
    val b = beaten(s)
    val xp = (s.baseline + ref.history.size) * 100 + b.sumOf { gameXp(it) }
    val level = floor(sqrt(xp / 150.0)).toInt() + 1
    val cur = 150 * (level - 1) * (level - 1)
    val next = 150 * level * level
    val yr = today().take(4)
    return Totals(
        beaten = s.baseline + ref.history.size + b.size,
        logged = b.size,
        year = allDone(s, ref).count { it.date.startsWith(yr) },
        xp = xp,
        level = level,
        pct = (xp - cur).toFloat() / (next - cur),
        toNext = next - xp,
        rank = RANKS[min(RANKS.size - 1, (level - 1) / 5)],
    )
}

/** The number this game got when you beat it, counting your whole history. */
fun numberOf(s: AppState, ref: RefData, g: Game): Int? {
    val i = beaten(s).indexOfFirst { it.id == g.id }
    return if (i < 0) null else s.baseline + ref.history.size + i + 1
}

class RotationState(var count: Int, var cool: Int)

/**
 * Two-for-two rule: 2 completions on a device puts it on cooldown until
 * 2 completions on other (non-exempt) devices.
 */
fun rotation(s: AppState): Map<String, RotationState> {
    val st = LinkedHashMap<String, RotationState>()
    s.devices.filter { !it.exempt }.forEach { st[it.id] = RotationState(0, 0) }
    for (g in beaten(s)) {
        val dev = st[g.device] ?: continue
        for ((k, v) in st) if (k != g.device && v.cool > 0) v.cool--
        if (dev.cool > 0) dev.cool = 0
        dev.count++
        if (dev.count >= 2) {
            dev.count = 0
            dev.cool = 2
        }
    }
    return st
}

private fun rotationEverCleared(s: AppState): Boolean {
    val st = LinkedHashMap<String, RotationState>()
    s.devices.filter { !it.exempt }.forEach { st[it.id] = RotationState(0, 0) }
    var cleared = false
    for (g in beaten(s)) {
        val dev = st[g.device] ?: continue
        for ((k, v) in st) {
            if (k != g.device && v.cool > 0) {
                v.cool--
                if (v.cool == 0) cleared = true
            }
        }
        dev.count++
        if (dev.count >= 2) {
            dev.count = 0
            dev.cool = 2
        }
    }
    return cleared
}

data class Achievement(
    val name: String,
    val desc: String,
    /** B, S or G for bronze, silver, gold. */
    val tier: Char,
    val check: (Totals, AppState, RefData) -> Boolean,
)

val ACHIEVEMENTS = listOf(
    Achievement("First Entry", "Log your first completion here", 'B') { t, _, _ -> t.logged >= 1 },
    Achievement("Triple Digits", "Beat 100 games", 'B') { t, _, _ -> t.beaten >= 100 },
    Achievement("Quarter Thousand", "Beat 250 games", 'B') { t, _, _ -> t.beaten >= 250 },
    Achievement("Five Hundred Club", "Beat 500 games", 'S') { t, _, _ -> t.beaten >= 500 },
    Achievement("Four Digits", "Beat 1,000 games", 'G') { t, _, _ -> t.beaten >= 1000 },
    Achievement("Fifteen Hundred", "Beat 1,500 games", 'G') { t, _, _ -> t.beaten >= 1500 },
    Achievement("Two Thousand Club", "Beat 2,000 games", 'G') { t, _, _ -> t.beaten >= 2000 },
    Achievement("Platform Hopper", "Beat games on 25 different platforms", 'S') { _, s, r ->
        allDone(s, r).map { it.platform }.toSet().size >= 25
    },
    Achievement("Year of Plenty", "Beat 100+ games in one calendar year", 'G') { _, s, r ->
        allDone(s, r).mapNotNull { it.date.take(4).ifBlank { null } }
            .groupingBy { it }.eachCount().values.any { it >= 100 }
    },
    Achievement("Annual Ton", "Beat 100 games this year", 'S') { t, _, _ -> t.year >= 100 },
    Achievement("Hot Streak", "Log 10 completions here", 'S') { t, _, _ -> t.logged >= 10 },
    Achievement("Quick Win", "Beat a game in under 5 hours", 'B') { _, s, _ ->
        beaten(s).any { it.hours > 0 && it.hours < 5 }
    },
    Achievement("Long Haul", "Beat a game that took 50+ hours", 'S') { _, s, _ ->
        beaten(s).any { it.hours >= 50 }
    },
    Achievement("Marathon", "Beat a game that took 100+ hours", 'G') { _, s, _ ->
        beaten(s).any { it.hours >= 100 }
    },
    Achievement("Multiverse", "Beat a game on every device", 'G') { _, s, _ ->
        val b = beaten(s)
        s.devices.size > 1 && s.devices.all { d -> b.any { it.device == d.id } }
    },
    Achievement("Fair Rotation", "Trigger a device cooldown and clear it", 'S') { _, s, _ ->
        rotationEverCleared(s)
    },
    Achievement("Genre Master", "Beat 5 games of one genre", 'S') { _, s, _ ->
        beaten(s).mapNotNull { g -> g.genre.trim().lowercase().ifBlank { null } }
            .groupingBy { it }.eachCount().values.any { it >= 5 }
    },
    Achievement("Pocket Hero", "Beat 5 games on an exempt device", 'B') { _, s, _ ->
        beaten(s).count { isExempt(s, it.device) } >= 5
    },
    Achievement("Know When to Fold", "Drop a game that wasn't fun", 'B') { _, s, _ ->
        s.games.any { it.status == "dropped" }
    },
    Achievement("Backlog Shrinker", "Beat more games than you add in a month", 'G') { _, s, _ ->
        val m = today().take(7)
        val added = s.games.count { it.addedAt.startsWith(m) }
        val done = beaten(s).count { it.beatenAt.startsWith(m) }
        done >= 2 && done > added
    },
)

fun unlocked(s: AppState, ref: RefData): List<String> {
    val t = totals(s, ref)
    return ACHIEVEMENTS.filter { it.check(t, s, ref) }.map { it.name }
}
