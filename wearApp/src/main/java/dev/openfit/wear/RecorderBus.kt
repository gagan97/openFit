package dev.openfit.wear

import androidx.health.services.client.data.ExerciseState
import kotlinx.coroutines.flow.MutableStateFlow

/** UI state shared between the recording service and the activity (same process). */
data class RecorderState(
    val phase: Phase = Phase.IDLE,
    val exerciseName: String = "Walking",
    val exerciseState: ExerciseState? = null,
    val heartRate: Double? = null,
    val heartRateAvg: Double? = null,
    val distanceMeters: Double? = null,
    val calories: Double? = null,
    val activeMillis: Long = 0L,
    val hasLocation: Boolean = false,
    val samples: Int = 0,
    val lastFile: String? = null,
    val lastSendStatus: String? = null,
    val pending: Int = 0,
    val review: Int = 0,
    val laps: Int = 0,
    val strokes: Long = 0,
    val route: List<Pair<Double, Double>> = emptyList(),
    val splits: List<String> = emptyList(),
    val error: String? = null,
) {
    enum class Phase { IDLE, RECORDING, SAVING, DONE }
}

object RecorderBus {
    val state = MutableStateFlow(RecorderState())
}
