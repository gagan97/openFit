package dev.openfit.wear

import android.app.Application
import androidx.health.services.client.ExerciseClient
import androidx.health.services.client.HealthServices

class OpenFitApp : Application() {
    val exerciseClient: ExerciseClient by lazy { HealthServices.getClient(this).exerciseClient }
}
