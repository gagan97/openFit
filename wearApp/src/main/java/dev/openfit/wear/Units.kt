package dev.openfit.wear

import android.content.Context
import java.util.Locale

/**
 * Display units. Everything stored (TCX files, state, prefs) stays SI/metric — conversion happens
 * at render time only, so switching units never rewrites history and uploads are unaffected.
 */
object Units {
    const val METERS_PER_MILE = 1609.344

    fun imperial(ctx: Context): Boolean = WearPrefs.unitsImperial(ctx)

    private fun unitLabel(ctx: Context) = if (imperial(ctx)) "mi" else "km"

    /** "1.24 km" / "0.77 mi" */
    fun dist(ctx: Context, meters: Double?): String {
        val m = meters ?: 0.0
        return if (imperial(ctx)) String.format(Locale.US, "%.2f mi", m / METERS_PER_MILE)
        else String.format(Locale.US, "%.2f km", m / 1000.0)
    }

    /** "5:32 /km" / "8:54 /mi" */
    fun pace(ctx: Context, activeMs: Long, meters: Double?): String {
        val per = if (imperial(ctx)) METERS_PER_MILE else 1000.0
        val label = unitLabel(ctx)
        val d = (meters ?: 0.0) / per
        if (d < 0.05 || activeMs <= 0) return "--:-- /$label"
        val secPer = (activeMs / 1000.0) / d
        return String.format(Locale.US, "%d:%02d /%s", (secPer / 60).toInt(), (secPer % 60).toInt(), label)
    }

    /** "km 1 · 5:32" / "mi 1 · 8:54" — one per-split line. */
    fun split(ctx: Context, index: Int, secs: Long): String =
        String.format(Locale.US, "%s %d · %d:%02d", unitLabel(ctx), index, secs / 60, secs % 60)
}
