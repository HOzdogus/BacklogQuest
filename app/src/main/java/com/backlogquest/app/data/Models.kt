package com.backlogquest.app.data

/** Where the app downloads your data file from (your GitHub repo). */
const val DEFAULT_DATA_URL =
    "https://raw.githubusercontent.com/HOzdogus/BacklogQuest/main/backlog-data.json"

data class Device(
    val id: String,
    val name: String,
    val exempt: Boolean,
)

val DEFAULT_DEVICES = listOf(
    Device("pc", "PC", false),
    Device("switch2", "Switch 2", false),
    Device("phone", "Phone", true),
)

/** A game in your own list. status is one of: backlog, playing, wishlist, beaten, dropped. */
data class Game(
    val id: String,
    val title: String,
    val device: String,
    val status: String,
    val genre: String = "",
    val series: String = "",
    val desc: String = "",
    /** Main story time to beat, in hours. 0 means unknown. */
    val est: Double = 0.0,
    /** Where est came from: "hltb", "ai", "rawg", "manual" or "". */
    val estSource: String = "",
    /** Hours you actually took, once beaten. */
    val hours: Double = 0.0,
    val beatenAt: String = "",
    val addedAt: String = "",
    val order: Long = 0,
    /** A web address or a file on the phone. Empty means use the cartridge label. */
    val cover: String = "",
    /** The system the game is for (e.g. PS2), when that's not the device you play it on. Empty = same. */
    val platform: String = "",
    /** Store it was imported from, e.g. "Steam". Empty if added by hand. */
    val source: String = "",
    /** The game's full achievement/trophy list, found by Auto-fill. */
    val achievements: List<Trophy> = emptyList(),
    /** Where that list came from: "Steam", "RetroAchievements", "PSNProfiles" or "TrueAchievements". */
    val achSource: String = "",
    /**
     * Set when this is your editable copy of a game from your imported history
     * (see [historyKey]). It then replaces that history entry everywhere, keeps
     * its number and date, and isn't counted a second time.
     */
    val histKey: String = "",
    /** Your screenshots: image files saved inside the app. */
    val screenshots: List<String> = emptyList(),
    /** How to start the game from the app (emulator or Android app), if set up. */
    val launch: Launch? = null,
)

/** One achievement or trophy. kind is e.g. "Gold", "10 pts" or "". */
data class Trophy(
    val name: String,
    val desc: String = "",
    val kind: String = "",
    val earned: Boolean = false,
)

/** Best current price for a wishlist game, from IsThereAnyDeal. Amounts in [currency]. */
data class Price(
    val amount: Double,
    val regular: Double,
    val cut: Int,
    val shop: String,
    val low: Double?,
    val currency: String,
    val url: String,
    val updated: String,
)

/** Everything you can change in the app. Saved on the phone. */
data class AppState(
    val baseline: Int = 0,
    val devices: List<Device> = DEFAULT_DEVICES,
    val games: List<Game> = emptyList(),
    /** Wishlist games you removed (got it, or not interested). */
    val hiddenWish: Set<String> = emptySet(),
    val dataUrl: String = DEFAULT_DATA_URL,
    val rawgKey: String = "",
    val lastSync: String = "",
    val raUser: String = "",
    val raKey: String = "",
    val itadKey: String = "",
    val steamKey: String = "",
    /** Your SteamID64, profile link or custom URL name, for ticking off unlocks. */
    val steamProfile: String = "",
    /** Wishlist prices, keyed by normTitle(title). */
    val prices: Map<String, Price> = emptyMap(),
)

/** One completion from your imported HowLongToBeat history. */
data class Completion(
    val title: String,
    val platform: String,
    val date: String,
)

/** A game from your wishlist or the unmarked catalog. */
data class WishItem(
    val id: String,
    val title: String,
    val platforms: List<String>,
    val hours: Double,
    val coopHours: Double,
    val tags: List<String>,
    /** True for catalog games you haven't tagged yet. */
    val unmarked: Boolean,
    /** "PlayStation 3|4TB;Nintendo 64|4TB" style, or empty. */
    val onDrive: String,
)

/**
 * The read-only data that comes from your data file: history, wishlist and catalog.
 * Deliberately not a data class, so comparing two of them is instant.
 */
class RefData(
    val history: List<Completion>,
    val wishlist: List<WishItem>,
    val exportedAt: String,
) {
    companion object {
        val EMPTY = RefData(emptyList(), emptyList(), "")
    }
}

/** Your games, devices and extra count as they were in the data file. */
data class ImportedState(
    val baseline: Int,
    val devices: List<Device>,
    val games: List<Game>,
)

class ParsedExport(
    val ref: RefData,
    val state: ImportedState?,
)
