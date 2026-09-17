package dev.openfit.wear

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

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
        if (distanceMeters != null && distanceMeters > 0) {
            sb.append("<DistanceMeters>").append(f1(distanceMeters)).append("</DistanceMeters>\n")
        }
        if (calories != null && calories > 0) {
            sb.append("<Calories>").append(calories.toInt()).append("</Calories>\n")
        }
        if (avgHr != null && avgHr > 0) {
            sb.append("<AverageHeartRateBpm><Value>").append(avgHr.toInt()).append("</Value></AverageHeartRateBpm>\n")
        }
        if (maxHr != null && maxHr > 0) {
            sb.append("<MaximumHeartRateBpm><Value>").append(maxHr.toInt()).append("</Value></MaximumHeartRateBpm>\n")
        }
        sb.append("<Intensity>Active</Intensity>\n")
        sb.append("<TriggerMethod>Manual</TriggerMethod>\n")
        sb.append("<Track>\n")
        for (s in samples) {
            sb.append("<Trackpoint>\n")
            sb.append("<Time>").append(ts(s.timeMillis)).append("</Time>\n")
            if (s.lat != null && s.lon != null) {
                sb.append("<Position><LatitudeDegrees>").append(coord(s.lat))
                    .append("</LatitudeDegrees><LongitudeDegrees>").append(coord(s.lon))
                    .append("</LongitudeDegrees></Position>\n")
            }
            if (s.altitude != null) {
                sb.append("<AltitudeMeters>").append(f1(s.altitude)).append("</AltitudeMeters>\n")
            }
            if (s.distanceMeters != null && s.distanceMeters > 0) {
                sb.append("<DistanceMeters>").append(f1(s.distanceMeters)).append("</DistanceMeters>\n")
            }
            if (s.heartRate != null && s.heartRate > 0) {
                sb.append("<HeartRateBpm><Value>").append(s.heartRate.toInt()).append("</Value></HeartRateBpm>\n")
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
