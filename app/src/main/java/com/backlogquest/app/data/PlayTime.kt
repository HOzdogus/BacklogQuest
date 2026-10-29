package com.backlogquest.app.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Process
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.backlogquest.app.MainActivity
import com.backlogquest.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

object PlayTime {
    class PlayTimeError(message: String) : Exception(message)

    /** True once you've allowed Usage access for Backlog Quest. */
    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (android.os.Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun usageAccessIntent() = Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)

    /**
     * How long [pkg] was actually on screen between [from] and [to], using
     * Android's usage records. Null if usage access isn't allowed.
     */
    fun foregroundMs(ctx: Context, pkg: String, from: Long, to: Long): Long? {
        if (pkg.isBlank() || !hasUsageAccess(ctx)) return null
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usm.queryEvents(from, to)
        val e = UsageEvents.Event()
        var total = 0L
        var resumedAt: Long? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.packageName != pkg) continue
            // 1 = moved to front (resumed), 2 = moved to back (paused), 23 = stopped.
            when (e.eventType) {
                1 -> if (resumedAt == null) resumedAt = e.timeStamp
                2, 23 -> {
                    resumedAt?.let { total += e.timeStamp - it }
                    resumedAt = null
                }
            }
        }
        resumedAt?.let { total += to - it }
        return total
    }

    /** Hours played for every game in your Steam library, by normTitle. */
    suspend fun steamHours(s: AppState): Map<String, Double> = withContext(Dispatchers.IO) {
        if (s.steamKey.isBlank() || s.steamProfile.isBlank()) {
            throw PlayTimeError("Add your Steam Web API key and Steam profile in Setup first.")
        }
        val key = Http.enc(s.steamKey.trim())
        val raw = s.steamProfile.trim().trimEnd('/').substringAfterLast("/")
        val id = if (raw.matches(Regex("\\d{17}"))) raw else {
            Http.json("https://api.steampowered.com/ISteamUser/ResolveVanityURL/v1/?key=$key&vanityurl=${Http.enc(raw)}")
                .optJSONObject("response")?.optString("steamid")?.takeIf { it.isNotBlank() }
                ?: throw PlayTimeError("Couldn't find that Steam profile.")
        }
        val games = Http.json(
            "https://api.steampowered.com/IPlayerService/GetOwnedGames/v1/?key=$key&steamid=$id" +
                "&include_appinfo=true&include_played_free_games=true",
        ).optJSONObject("response")?.optJSONArray("games") ?: JSONArray()
        if (games.length() == 0) throw PlayTimeError("Steam didn't list any games. Is your profile's game details setting public?")
        (0 until games.length()).mapNotNull { games.optJSONObject(it) }
            .associate { normTitle(it.optString("name")) to it.optDouble("playtime_forever", 0.0) / 60.0 }
    }
}

object Shortcuts {
    const val EXTRA_GAME = "play_game"

    /** Asks the home screen to add an icon for the game. Returns false if it can't. */
    suspend fun pin(ctx: Context, g: Game): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) return false
        val icon = coverIcon(ctx, g.cover) ?: IconCompat.createWithResource(ctx, R.mipmap.ic_launcher)
        val intent = Intent(ctx, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(EXTRA_GAME, g.id)
        val info = ShortcutInfoCompat.Builder(ctx, "game_${g.id}")
            .setShortLabel(g.title.take(24))
            .setLongLabel(g.title.take(60))
            .setIcon(icon)
            .setIntent(intent)
            .build()
        return ShortcutManagerCompat.requestPinShortcut(ctx, info, null)
    }

    /** The game's cover as a square icon (local file or web address). */
    private suspend fun coverIcon(ctx: Context, cover: String): IconCompat? {
        if (cover.isBlank()) return null
        val bmp: Bitmap? = try {
            if (cover.startsWith("/")) {
                withContext(Dispatchers.IO) { BitmapFactory.decodeFile(File(cover).absolutePath) }
            } else {
                val r = ImageLoader(ctx).execute(
                    ImageRequest.Builder(ctx).data(cover).allowHardware(false).size(256).build(),
                )
                ((r as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
            }
        } catch (e: Exception) {
            null
        }
        return bmp?.let { IconCompat.createWithBitmap(square(it)) }
    }

    private fun square(src: Bitmap): Bitmap {
        val size = 192
        val side = minOf(src.width, src.height)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val sx = (src.width - side) / 2
        val sy = (src.height - side) / 2
        Canvas(out).drawBitmap(
            src, android.graphics.Rect(sx, sy, sx + side, sy + side),
            android.graphics.Rect(0, 0, size, size), null,
        )
        return out
    }
}
