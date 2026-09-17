package dev.openfit.wear

import android.content.Context
import java.io.File

/**
 * SharedPreferences helpers: Dreeve config + activity file bookkeeping.
 *
 * File lifecycle states (stored as name-sets):
 *  - in neither [approved] nor [uploaded] -> NEW, awaiting review on the watch
 *  - in    [approved]                     -> user tapped Done; queued for upload (auto-retried)
 *  - in    [uploaded]                     -> delivered to Dreeve
 */
object WearPrefs {
    const val ACTIVITIES_DIR = "activities"

    private const val FILE = "openfit_wear"
    private const val KEY_BASE = "dreeve_base"
    private const val KEY_API = "dreeve_key"
    private const val SET_UPLOADED = "uploaded"
    private const val SET_APPROVED = "approved"
    private const val SET_SENT = "sent" // legacy phone hand-off bookkeeping

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun base(ctx: Context): String? =
        prefs(ctx).getString(KEY_BASE, null)?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }

    fun apiKey(ctx: Context): String? =
        prefs(ctx).getString(KEY_API, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setConfig(ctx: Context, base: String?, apiKey: String?) {
        prefs(ctx).edit().apply {
            if (!base.isNullOrBlank()) putString(KEY_BASE, base.trim().trimEnd('/'))
            if (!apiKey.isNullOrBlank()) putString(KEY_API, apiKey.trim())
        }.apply()
    }

    fun uploaded(ctx: Context): MutableSet<String> =
        prefs(ctx).getStringSet(SET_UPLOADED, emptySet())!!.toMutableSet()

    fun approved(ctx: Context): MutableSet<String> =
        prefs(ctx).getStringSet(SET_APPROVED, emptySet())!!.toMutableSet()

    fun sentToPhone(ctx: Context): MutableSet<String> =
        prefs(ctx).getStringSet(SET_SENT, emptySet())!!.toMutableSet()

    // --- settings (units, auto-pause) -----------------------------------------

    private const val KEY_UNITS_IMPERIAL = "units_imperial"
    private const val KEY_AUTO_PAUSE = "auto_pause_enabled"

    fun unitsImperial(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_UNITS_IMPERIAL, false)

    fun setUnitsImperial(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_UNITS_IMPERIAL, v).apply()
    }

    fun autoPauseEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_AUTO_PAUSE, true)

    fun setAutoPauseEnabled(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTO_PAUSE, v).apply()
    }

    fun markApproved(ctx: Context, name: String) {
        val s = approved(ctx).apply { add(name) }
        prefs(ctx).edit().putStringSet(SET_APPROVED, s).apply()
    }

    fun markUploaded(ctx: Context, name: String) {
        val s = uploaded(ctx).apply { add(name) }
        prefs(ctx).edit().putStringSet(SET_UPLOADED, s).apply()
    }

    fun markSentToPhone(ctx: Context, name: String) {
        val s = sentToPhone(ctx).apply { add(name) }
        prefs(ctx).edit().putStringSet(SET_SENT, s).apply()
    }

    /** Remove a file's name from every bookkeeping set (used when deleting). */
    fun forget(ctx: Context, name: String) {
        prefs(ctx).edit()
            .putStringSet(SET_APPROVED, approved(ctx).apply { remove(name) })
            .putStringSet(SET_UPLOADED, uploaded(ctx).apply { remove(name) })
            .putStringSet(SET_SENT, sentToPhone(ctx).apply { remove(name) })
            .apply()
    }

    fun activitiesDir(ctx: Context): File = File(ctx.filesDir, ACTIVITIES_DIR).apply { mkdirs() }

    private fun filesByName(dir: File): Map<String, File> =
        dir.listFiles()?.filter { it.isFile }?.associateBy { it.name } ?: emptyMap()

    /** Files recorded but not yet reviewed (no Done/Delete tap yet). */
    fun unreviewed(ctx: Context, dir: File): List<File> {
        val decided = approved(ctx) + uploaded(ctx)
        return filesByName(dir).filterKeys { it !in decided }.values.sortedBy { it.name }
    }

    /** Files the user approved (Done) but which are not on Dreeve yet. */
    fun approvedPending(ctx: Context, dir: File): List<File> {
        val up = uploaded(ctx)
        val ap = approved(ctx)
        return filesByName(dir).filterKeys { it in ap && it !in up }.values.sortedBy { it.name }
    }

    /** Delete an activity file locally and forget it. */
    fun deleteActivity(ctx: Context, file: File) {
        runCatching { file.delete() }
        forget(ctx, file.name)
    }
}
