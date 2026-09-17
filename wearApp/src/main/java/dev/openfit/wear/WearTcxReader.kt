package dev.openfit.wear

import java.io.File

/** Tiny TCX summary reader (home screen card + review screen for older files). */
object WearTcxReader {
    data class Summary(
        val points: List<Pair<Double, Double>>,
        val distanceMeters: Double?,
        val totalSeconds: Double?,
        val avgHr: Int?,
        val maxHr: Int?,
        val calories: Int?,
    )

    private val POSITION =
        Regex("<Position><LatitudeDegrees>(-?[0-9.]+)</LatitudeDegrees><LongitudeDegrees>(-?[0-9.]+)</LongitudeDegrees></Position>")
    private val DISTANCE = Regex("<DistanceMeters>([0-9.]+)</DistanceMeters>")
    private val TIME = Regex("<TotalTimeSeconds>([0-9.]+)</TotalTimeSeconds>")
    private val AVG_HR = Regex("<AverageHeartRateBpm><Value>(\\d+)</Value></AverageHeartRateBpm>")
    private val MAX_HR = Regex("<MaximumHeartRateBpm><Value>(\\d+)</Value></MaximumHeartRateBpm>")
    private val CALORIES = Regex("<Calories>(\\d+)</Calories>")

    fun parse(file: File): Summary {
        val text = runCatching { file.readText() }.getOrDefault("")
        val points = POSITION.findAll(text).map { m ->
            m.groupValues[1].toDouble() to m.groupValues[2].toDouble()
        }.toList()
        return Summary(
            points = points,
            distanceMeters = DISTANCE.find(text)?.groupValues?.get(1)?.toDoubleOrNull(),
            totalSeconds = TIME.find(text)?.groupValues?.get(1)?.toDoubleOrNull(),
            avgHr = AVG_HR.find(text)?.groupValues?.get(1)?.toIntOrNull(),
            maxHr = MAX_HR.find(text)?.groupValues?.get(1)?.toIntOrNull(),
            calories = CALORIES.find(text)?.groupValues?.get(1)?.toIntOrNull(),
        )
    }
}
