package com.backlogquest.app.data

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Starts games in emulators or as Android apps, the way frontends like ES-DE do.
 * The emulator details below come from ES-DE's Android launch rules.
 */

/** How a game is started. mode: "app" (an Android app/game) or "emulator". */
data class Launch(
    val mode: String,
    /** The Android app (for "app"), or the emulator's package once found. */
    val pkg: String = "",
    /** Emulator key from [Emulators]. */
    val emu: String = "",
    /** The game file you picked (content:// address). */
    val uri: String = "",
    /** For emulators that start games by ID (PS3/PS4/Vita title IDs). */
    val gameId: String = "",
    /** RetroArch core name, e.g. "snes9x". */
    val core: String = "",
)

/** How an emulator wants to be given the game. */
enum class EmuInput { FILE, ID, NONE }

class Emulator(
    val key: String,
    val label: String,
    /** "package/activity" pairs to try, in order (activity may start with "."). */
    val components: List<String>,
    val input: EmuInput,
    /** Platforms it's usually used for (to suggest it). */
    val platforms: List<String>,
    /** Hint shown for ID-based emulators. */
    val idHint: String = "",
    /** Builds the launch intent for a component, game file and/or ID. */
    val build: (Intent, Launch, Uri?) -> Unit,
)

object Emulators {
    private fun Intent.view(uri: Uri?) {
        action = Intent.ACTION_VIEW
        if (uri != null) data = uri
    }

    val all: List<Emulator> = listOf(
        Emulator(
            "retroarch", "RetroArch",
            listOf(
                "com.retroarch.aarch64/com.retroarch.browser.retroactivity.RetroActivityFuture",
                "com.retroarch/com.retroarch.browser.retroactivity.RetroActivityFuture",
                "com.retroarch.ra32/com.retroarch.browser.retroactivity.RetroActivityFuture",
            ),
            EmuInput.FILE, listOf("nes", "snes", "gb", "gbc", "gba", "genesis", "mega drive", "master system", "ps1", "arcade", "pc engine"),
        ) { i, l, uri ->
            val pkg = i.component?.packageName.orEmpty()
            i.putExtra("ROM", uri?.let { filePath(it) } ?: l.uri)
            i.putExtra("LIBRETRO", "/data/data/$pkg/cores/${l.core.ifBlank { "snes9x" }}_libretro_android.so")
            i.putExtra("CONFIGFILE", "/storage/emulated/0/Android/data/$pkg/files/retroarch.cfg")
        },
        Emulator(
            "eden", "Eden",
            listOf(
                "dev.eden.eden_emulator/org.yuzu.yuzu_emu.activities.EmulationActivity",
                "dev.legacy.eden_emulator/org.yuzu.yuzu_emu.activities.EmulationActivity",
                "dev.eden.eden_emulator.nightly/org.yuzu.yuzu_emu.activities.EmulationActivity",
            ),
            EmuInput.FILE, listOf("switch"),
        ) { i, _, uri -> i.action = "android.nfc.action.TECH_DISCOVERED"; i.data = uri },
        Emulator(
            "yuzu", "yuzu",
            listOf("org.yuzu.yuzu_emu/.activities.EmulationActivity", "org.yuzu.yuzu_emu.ea/org.yuzu.yuzu_emu.activities.EmulationActivity"),
            EmuInput.FILE, listOf("switch"),
        ) { i, _, uri -> i.action = "android.nfc.action.TECH_DISCOVERED"; i.data = uri },
        Emulator(
            "kenji", "Kenji-NX", listOf("org.kenjinx.android/.MainActivity"), EmuInput.FILE, listOf("switch"),
        ) { i, _, uri -> i.action = "org.kenjinx.android.LAUNCH_GAME"; i.putExtra("bootPath", uri.toString()) },
        Emulator(
            "ppsspp", "PPSSPP",
            listOf("org.ppsspp.ppssppgold/org.ppsspp.ppsspp.PpssppActivity", "org.ppsspp.ppsspp/.PpssppActivity"),
            EmuInput.FILE, listOf("psp"),
        ) { i, _, uri -> i.view(uri); i.addCategory(Intent.CATEGORY_DEFAULT) },
        Emulator(
            "dolphin", "Dolphin",
            listOf("org.dolphinemu.dolphinemu/.ui.main.TvMainActivity"),
            EmuInput.FILE, listOf("gamecube", "wii"),
        ) { i, _, uri ->
            i.action = Intent.ACTION_MAIN
            i.addCategory("android.intent.category.LEANBACK_LAUNCHER")
            i.putExtra("AutoStartFile", uri.toString())
        },
        Emulator(
            "duckstation", "DuckStation", listOf("com.github.stenzek.duckstation/.EmulationActivity"),
            EmuInput.FILE, listOf("ps1", "psx", "playstation"),
        ) { i, _, uri -> i.putExtra("resumeState", false); i.putExtra("bootPath", uri.toString()) },
        Emulator(
            "nethersx2", "NetherSX2",
            listOf(
                "xyz.aethersx2.android/.EmulationActivity",
                "xyz.aethersx2.tturnip/xyz.aethersx2.android.EmulationActivity",
                "xyz.aethersx2.cturnip/xyz.aethersx2.android.EmulationActivity",
            ),
            EmuInput.FILE, listOf("ps2"),
        ) { i, _, uri -> i.action = Intent.ACTION_MAIN; i.putExtra("bootPath", uri.toString()) },
        Emulator(
            "m64", "M64Plus FZ",
            listOf(
                "org.mupen64plusae.v3.fzurita.pro/paulscode.android.mupen64plusae.SplashActivity",
                "org.mupen64plusae.v3.fzurita/paulscode.android.mupen64plusae.SplashActivity",
                "org.mupen64plusae.v3.fzurita.amazon/paulscode.android.mupen64plusae.SplashActivity",
            ),
            EmuInput.FILE, listOf("n64", "nintendo 64"),
        ) { i, _, uri -> i.view(uri) },
        Emulator(
            "azahar", "Azahar",
            listOf(
                "org.azahar_emu.azahar/org.citra.citra_emu.activities.EmulationActivity",
                "io.github.azaharplus.android/org.citra.citra_emu.activities.EmulationActivity",
                "io.github.lime3ds.android/org.citra.citra_emu.activities.EmulationActivity",
            ),
            EmuInput.FILE, listOf("3ds"),
        ) { i, _, uri -> i.data = uri },
        Emulator(
            "melonds", "melonDS",
            listOf("me.magnum.melonds/.ui.emulator.EmulatorActivity", "me.magnum.melonds.nightly/me.magnum.melonds.ui.emulator.EmulatorActivity"),
            EmuInput.FILE, listOf("ds", "nds"),
        ) { i, _, uri ->
            i.action = if (i.component?.packageName?.endsWith("nightly") == true) "me.magnum.melonds.nightly.LAUNCH_ROM"
            else "me.magnum.melonds.LAUNCH_ROM"
            i.putExtra("uri", uri.toString())
        },
        Emulator(
            "flycast", "Flycast",
            listOf("com.flycast.emulator/com.flycast.emulator.MainActivity", "com.flycast.emulator/com.reicast.emulator.MainActivity"),
            EmuInput.FILE, listOf("dreamcast", "naomi"),
        ) { i, _, uri -> i.view(uri) },
        Emulator(
            "cemu", "Cemu",
            listOf("info.cemu.cemu/info.cemu.cemu.emulation.EmulationActivity", "info.cemu.Cemu/info.cemu.Cemu.emulation.EmulationActivity"),
            EmuInput.FILE, listOf("wii u"),
        ) { i, _, uri -> i.data = uri },
        Emulator(
            "eka2l1", "EKA2L1", listOf("com.github.eka2l1/.emu.EmulatorActivity"), EmuInput.FILE, listOf("symbian", "n-gage"),
        ) { i, _, uri -> i.view(uri) },
        Emulator(
            "xendroid", "XenDroid", listOf("xendroid.compose/.EmulatorHostActivity"), EmuInput.FILE, listOf("xbox 360"),
        ) { i, _, uri -> i.data = uri },
        Emulator(
            "j2me", "J2ME Loader", listOf("ru.playsoftware.j2meloader/.MainActivity"), EmuInput.FILE, listOf("j2me", "java"),
        ) { i, _, uri -> i.data = uri },
        Emulator(
            "droidarcadia", "DroidArcadia", listOf("com.amigan.droidarcadia/.MainActivity"), EmuInput.FILE, listOf("arcadia"),
        ) { i, _, uri -> i.setDataAndType(uri, "application/zip") },
        Emulator(
            "vita3k", "Vita3K",
            listOf("org.vita3k.emulator/.Emulator", "org.vita3k.emulator.ikhoeyZX/org.vita3k.emulator.Emulator"),
            EmuInput.ID, listOf("vita", "ps vita"), idHint = "Vita title ID, e.g. PCSE00491",
        ) { i, l, _ -> i.putExtra("AppStartParameters", arrayOf("-r", l.gameId.trim())) },
        Emulator(
            "armsx3", "ARMSX3", listOf("com.armsx3/com.armsx2.Main"),
            EmuInput.ID, listOf("ps3"), idHint = "PS3 title ID, e.g. BLUS30443",
        ) { i, l, _ -> i.action = Intent.ACTION_VIEW; i.putExtra("title_id", l.gameId.trim()) },
        Emulator(
            "bachatas4", "BachataS4",
            listOf("com.bachatas4.android/.DirectLaunchActivity", "com.bachatas4.android.github/com.bachatas4.android.DirectLaunchActivity"),
            EmuInput.ID, listOf("ps4"), idHint = "PS4 title ID, e.g. CUSA00001",
        ) { i, l, _ -> i.putExtra("game_id", l.gameId.trim()) },
    )

    fun byKey(key: String) = all.firstOrNull { it.key == key }

    /** The first of an emulator's components that's installed, or null. */
    fun installed(ctx: Context, e: Emulator): ComponentName? {
        val pm = ctx.packageManager
        for (c in e.components) {
            val pkg = c.substringBefore("/")
            val act = c.substringAfter("/").let { if (it.startsWith(".")) pkg + it else it }
            val cn = ComponentName(pkg, act)
            try {
                pm.getActivityInfo(cn, 0)
                return cn
            } catch (e: PackageManager.NameNotFoundException) {
            }
        }
        return null
    }

    fun installedList(ctx: Context): List<Emulator> = all.filter { installed(ctx, it) != null }

    /** Suggests an emulator from a platform like "PS2" or "Game Boy Advance". */
    fun suggest(ctx: Context, platform: String): Emulator? {
        val p = platform.lowercase()
        if (p.isBlank()) return null
        val alias = Achievements.raAlias(p)?.lowercase().orEmpty()
        return installedList(ctx).firstOrNull { e ->
            e.platforms.any { k -> p.contains(k) || alias.contains(k) }
        }
    }

    /** A sensible RetroArch core for a platform. */
    fun coreFor(platform: String): String {
        val p = platform.lowercase()
        val alias = Achievements.raAlias(p)?.lowercase().orEmpty()
        val key = "$p $alias"
        return when {
            "super" in key || "snes" in key -> "snes9x"
            "nes" in key || "famicom" in key -> "mesen"
            "advance" in key || "gba" in key -> "mgba"
            "game boy" in key || "gbc" in key || Regex("\\bgb\\b").containsMatchIn(key) -> "gambatte"
            "genesis" in key || "mega drive" in key || "master system" in key || "game gear" in key || "sega cd" in key -> "genesis_plus_gx"
            "playstation" in key && "2" !in key || "ps1" in key || "psx" in key -> "swanstation"
            "64" in key -> "mupen64plus_next"
            "ds" in key -> "melonds"
            "pc engine" in key || "turbografx" in key -> "mednafen_pce_fast"
            "atari 2600" in key -> "stella"
            "arcade" in key || "mame" in key -> "fbneo"
            "neo geo pocket" in key -> "mednafen_ngp"
            "wonderswan" in key -> "mednafen_wswan"
            "saturn" in key -> "yabasanshiro"
            else -> "snes9x"
        }
    }

    /** Turns a "primary storage" document address into a normal file path (RetroArch needs one). */
    fun filePath(uri: Uri): String? = try {
        if (uri.authority == "com.android.externalstorage.documents") {
            val id = DocumentsContract.getDocumentId(uri)
            val volume = id.substringBefore(":")
            val path = id.substringAfter(":")
            if (volume.equals("primary", true)) "/storage/emulated/0/$path" else "/storage/$volume/$path"
        } else null
    } catch (e: Exception) {
        null
    }
}

object Launcher {
    class LaunchError(message: String) : Exception(message)

    /** Starts the game. Returns the package that was started (for play-time tracking). */
    fun start(ctx: Context, l: Launch): String {
        if (l.mode == "app") {
            val intent = ctx.packageManager.getLaunchIntentForPackage(l.pkg)
                ?: throw LaunchError("That app isn't installed any more.")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            return l.pkg
        }
        val emu = Emulators.byKey(l.emu) ?: throw LaunchError("Choose an emulator first.")
        val cn = Emulators.installed(ctx, emu) ?: throw LaunchError("${emu.label} isn't installed.")
        val uri = l.uri.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        if (emu.input == EmuInput.FILE && uri == null) throw LaunchError("Pick the game file first.")
        if (emu.input == EmuInput.ID && l.gameId.isBlank()) throw LaunchError("Enter the game's title ID first.")

        val intent = Intent().apply { component = cn }
        emu.build(intent, l, uri)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (uri != null) {
            // Let the emulator read the file you picked through Backlog Quest.
            intent.clipData = ClipData.newRawUri("game", uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            try {
                ctx.grantUriPermission(cn.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (e: Exception) {
            }
        }
        try {
            ctx.startActivity(intent)
        } catch (e: Exception) {
            // Fall back to simply opening the emulator with the file.
            val fallback = Intent(Intent.ACTION_VIEW).apply {
                setPackage(cn.packageName)
                if (uri != null) setDataAndType(uri, "*/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                ctx.startActivity(fallback)
            } catch (e2: Exception) {
                ctx.packageManager.getLaunchIntentForPackage(cn.packageName)?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(it)
                } ?: throw LaunchError("${emu.label} wouldn't start.")
            }
        }
        return cn.packageName
    }

    /** Android apps and games you can pick, sorted by name. */
    fun launchableApps(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != ctx.packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }

    fun appLabel(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
    } catch (e: Exception) {
        pkg
    }
}
