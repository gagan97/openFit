package dev.openfit.wear

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/** One row of the recorded track. */
data class TrackSample(
    val timeMillis: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    val altitude: Double? = null,
    val heartRate: Double? = null,
    val distanceMeters: Double? = null,
)

/** Serializes a recording to TCX v2 — the richest format Dreeve's file import accepts from us. */
object TcxWriter {
    private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

    private fun ts(ms: Long): String =
        ISO.format(Instant.ofEpochMilli(ms).truncatedTo(ChronoUnit.SECONDS))

    private fun f1(v: Double): String = String.format(Locale.US, "%.1f", v)

    /** GPS coordinates need ~7 decimals (≈1 cm); rounding them destroys the route. */
    private fun coord(v: Double): String = String.format(Locale.US, "%.7f", v)

    /**
     * Health Services reports "sensor has no value" as [Double.MAX_VALUE] (or NaN/Inf) instead of
     * null. Writing those out produces absurd numbers that break server-side import: Dreeve's
     * combined-stream step overflows them to INF and aborts the activity's metrics pipeline for
     * every subsequent run. Filter such values out of the file entirely.
     */
    private fun sane(v: Double?): Double? =
        v?.takeIf { it.isFinite() && abs(it) < 1e30 }

    fun build(
        sport: String,
        samples: List<TrackSample>,
        totalSeconds: Long,
        distanceMeters: Double?,
        calories: Double?,
        avgHr: Double?,
        maxHr: Double?,
    ): String {
        val sb = StringBuilder(4096)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append(
            "<TrainingCenterDatabase xmlns=\"http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2\" " +
                "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n"
        )
        sb.append("<Activities><Activity Sport=\"").append(sport).append("\">\n")
        val startMs = samples.firstOrNull()?.timeMillis ?: System.currentTimeMillis()
        sb.append("<Id>").append(ts(startMs)).append("</Id>\n")
        sb.append("<Lap StartTime=\"").append(ts(startMs)).append("\">\n")
        sb.append("<TotalTimeSeconds>").append(totalSeconds).append("</TotalTimeSeconds>\n")
        sane(distanceMeters)?.takeIf { it > 0 }?.let {
            sb.append("<DistanceMeters>").append(f1(it)).append("</DistanceMeters>\n")
        }
        sane(calories)?.takeIf { it > 0 }?.let {
            sb.append("<Calories>").append(it.toInt()).append("</Calories>\n")
        }
        sane(avgHr)?.takeIf { it > 0 }?.let {
            sb.append("<AverageHeartRateBpm><Value>").append(it.toInt()).append("</Value></AverageHeartRateBpm>\n")
        }
        sane(maxHr)?.takeIf { it > 0 }?.let {
            sb.append("<MaximumHeartRateBpm><Value>").append(it.toInt()).append("</Value></MaximumHeartRateBpm>\n")
        }
        sb.append("<Intensity>Active</Intensity>\n")
        sb.append("<TriggerMethod>Manual</TriggerMethod>\n")
        sb.append("<Track>\n")
        for (s in samples) {
            sb.append("<Trackpoint>\n")
            sb.append("<Time>").append(ts(s.timeMillis)).append("</Time>\n")
            val lat = sane(s.lat)
            val lon = sane(s.lon)
            if (lat != null && lon != null) {
                sb.append("<Position><LatitudeDegrees>").append(coord(lat))
                    .append("</LatitudeDegrees><LongitudeDegrees>").append(coord(lon))
                    .append("</LongitudeDegrees></Position>\n")
            }
            sane(s.altitude)?.let {
                sb.append("<AltitudeMeters>").append(f1(it)).append("</AltitudeMeters>\n")
            }
            sane(s.distanceMeters)?.takeIf { it > 0 }?.let {
                sb.append("<DistanceMeters>").append(f1(it)).append("</DistanceMeters>\n")
            }
            sane(s.heartRate)?.takeIf { it > 0 }?.let {
                sb.append("<HeartRateBpm><Value>").append(it.toInt()).append("</Value></HeartRateBpm>\n")
            }
            sb.append("</Trackpoint>\n")
        }
        sb.append("</Track>\n")
        sb.append("</Lap>\n")
        sb.append("<Notes>openFit</Notes>\n")
        sb.append("</Activity></Activities>\n")
        sb.append("</TrainingCenterDatabase>\n")
        return sb.toString()
    }
}
