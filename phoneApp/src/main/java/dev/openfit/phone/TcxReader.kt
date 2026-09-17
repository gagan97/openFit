package dev.openfit.phone

import java.io.File

/** Minimal TCX reader for phone-side previews (GPS track + headline stats). */
object TcxReader {
    data class Parsed(
        val points: List<Pair<Double, Double>>,
        val distanceMeters: Double?,
        val totalSeconds: Double?,
        val avgHr: Int?,
        val maxHr: Int?,
    )

    private val POSITION =
        Regex("<Position><LatitudeDegrees>(-?[0-9.]+)</LatitudeDegrees><LongitudeDegrees>(-?[0-9.]+)</LongitudeDegrees></Position>")
    private val DISTANCE = Regex("<DistanceMeters>([0-9.]+)</DistanceMeters>")
    private val TIME = Regex("<TotalTimeSeconds>([0-9.]+)</TotalTimeSeconds>")
    private val AVG_HR = Regex("<AverageHeartRateBpm><Value>(\\d+)</Value></AverageHeartRateBpm>")
    private val MAX_HR = Regex("<MaximumHeartRateBpm><Value>(\\d+)</Value></MaximumHeartRateBpm>")

    fun parse(file: File): Parsed {
        val text = runCatching { file.readText() }.getOrDefault("")
        val points = POSITION.findAll(text).map { m ->
            m.groupValues[1].toDouble() to m.groupValues[2].toDouble()
        }.toList()
        return Parsed(
            points = points,
            distanceMeters = DISTANCE.find(text)?.groupValues?.get(1)?.toDoubleOrNull(),
            totalSeconds = TIME.find(text)?.groupValues?.get(1)?.toDoubleOrNull(),
            avgHr = AVG_HR.find(text)?.groupValues?.get(1)?.toIntOrNull(),
            maxHr = MAX_HR.find(text)?.groupValues?.get(1)?.toIntOrNull(),
        )
    }
}
