package dev.openfit.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.health.services.client.ExerciseClient
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.endExercise
import androidx.health.services.client.getCapabilities
import androidx.health.services.client.markLap
import androidx.health.services.client.pauseExercise
import androidx.health.services.client.resumeExercise
import androidx.health.services.client.startExercise
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.google.android.gms.tasks.Tasks
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hosts the Health Services exercise session (walk / run / ride / gym / swim):
 * records metrics, keeps a live foreground notification (stats + pause/stop actions),
 * and on stop serializes the session to TCX. Upload is strictly user-gated: the
 * finish screen offers Delete or Done — only Done queues the file for Dreeve.
 */
class ExerciseService : LifecycleService() {

    private val exerciseClient: ExerciseClient
        get() = (application as OpenFitApp).exerciseClient

    private val notificationManager: NotificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        }
    }

    private val samples = ArrayList<TrackSample>(600)
    private val routePoints = ArrayList<Pair<Double, Double>>(400)
    private var startWallMillis: Long = 0L
    private var lastSampleSecond: Long = -1L
    private var hrSum = 0.0
    private var hrCount = 0
    private var hrMax = 0.0
    private var finalizing = false
    private var tcxSport = "Walking"
    private var isPoolSwim = false
    private var swimLaps = 0
    private var manualLaps = 0
    private var lastSplitIdx = 0
    private var lastPaused = false
    private var lastCheckpoint: ExerciseUpdate.ActiveDurationCheckpoint? = null
    private var tickJob: Job? = null
    private var lastNotifMs = 0L
    private var collectJob: Job? = null
    private var ongoingActivity: OngoingActivity? = null

    private val updateFlow = callbackFlow<ExerciseUpdate> {
        val callback = object : ExerciseUpdateCallback {
            override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
                trySendBlocking(update)
            }

            override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {
                if (lapSummary.lapCount > swimLaps) swimLaps = lapSummary.lapCount
            }

            override fun onRegistered() {}
            override fun onRegistrationFailed(throwable: Throwable) {}
            override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {}
        }
        exerciseClient.setUpdateCallback(callback)
        awaitClose { exerciseClient.clearUpdateCallbackAsync(callback) }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        refreshCounts()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                promoteToForeground("Recording (starting…)")
                val name = intent.getStringExtra(EXTRA_EXERCISE) ?: "Walking"
                lifecycleScope.launch { startRecording(name) }
            }

            ACTION_PAUSE -> lifecycleScope.launch {
                runCatching { exerciseClient.pauseExercise() }
                updateNotification(force = true)
            }

            ACTION_RESUME -> lifecycleScope.launch {
                runCatching { exerciseClient.resumeExercise() }
                updateNotification(force = true)
            }

            ACTION_LAP -> lifecycleScope.launch {
                runCatching { exerciseClient.markLap() }
                if (!isPoolSwim) {
                    // Manual lap (intervals); pool swims get lap counts from the platform.
                    manualLaps++
                    RecorderBus.state.value = RecorderBus.state.value.copy(laps = manualLaps)
                }
                buzz(HAPTIC_LAP)
                updateNotification(force = true)
            }

            ACTION_STOP -> lifecycleScope.launch { stopAndFinish() }

            ACTION_REVIEW_DONE -> {
                promoteToForeground("Uploading…")
                val name = intent.getStringExtra(EXTRA_FILE)
                lifecycleScope.launch {
                    reviewDone(name)
                    removeForeground()
                    stopSelf()
                }
            }

            ACTION_REVIEW_DELETE -> {
                promoteToForeground("Deleting…")
                val name = intent.getStringExtra(EXTRA_FILE)
                lifecycleScope.launch {
                    reviewDelete(name)
                    removeForeground()
                    stopSelf()
                }
            }

            ACTION_RETRY -> {
                promoteToForeground("Checking pending uploads…")
                lifecycleScope.launch {
                    retryPending()
                    removeForeground()
                    stopSelf()
                }
            }

            ACTION_CONFIG -> {
                promoteToForeground("Applying settings…")
                WearPrefs.setConfig(this, intent.getStringExtra(EXTRA_BASE), intent.getStringExtra(EXTRA_KEY))
                Log.i(TAG, "config applied (base=${intent.getStringExtra(EXTRA_BASE)})")
                lifecycleScope.launch {
                    retryPending()
                    removeForeground()
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private suspend fun startRecording(name: String) {
        try {
            val key = name.lowercase(Locale.US)
            data class SportPlan(
                val type: ExerciseType,
                val tcx: String,
                val display: String,
                val gps: Boolean,
                val autoPause: Boolean,
            )

            val plan = when (key) {
                "running" -> SportPlan(ExerciseType.RUNNING, "Running", "Running", true, true)
                "cycling" -> SportPlan(ExerciseType.BIKING, "Biking", "Cycling", true, true)
                "workout" -> SportPlan(ExerciseType.STRENGTH_TRAINING, "StrengthTraining", "Workout", false, false)
                "swimming" -> SportPlan(ExerciseType.SWIMMING_POOL, "Swimming", "Pool Swim", false, false)
                "openwater" -> SportPlan(ExerciseType.SWIMMING_OPEN_WATER, "OpenWaterSwimming", "Open Water", true, true)
                else -> SportPlan(ExerciseType.WALKING, "Walking", "Walking", true, true)
            }
            tcxSport = plan.tcx
            isPoolSwim = plan.type == ExerciseType.SWIMMING_POOL

            val caps = exerciseClient.getCapabilities()
            val typeCaps = caps.getExerciseTypeCapabilities(plan.type)
            // Auto-pause: needs the user pref AND device+sport support (varies by hardware;
            // documented in docs/design/auto-pause.md).
            val autoPausePref = WearPrefs.autoPauseEnabled(this)
            val autoPauseSupported = caps.autoPauseAndResumeEnabledExercises.contains(plan.type)
            val useAutoPause = autoPausePref && plan.autoPause && autoPauseSupported
            Log.i(
                TAG,
                "autoPause capabilities: supported=${caps.autoPauseAndResumeEnabledExercises} " +
                    "pref=$autoPausePref requested=${plan.autoPause} → enabled=$useAutoPause for ${plan.type}",
            )
            val wanted = setOf(
                DataType.HEART_RATE_BPM,
                DataType.HEART_RATE_BPM_STATS,
                DataType.LOCATION,
                DataType.DISTANCE_TOTAL,
                DataType.CALORIES_TOTAL,
                DataType.SPEED,
                DataType.ELEVATION_GAIN,
                DataType.SWIMMING_LAP_COUNT,
                DataType.SWIMMING_STROKES,
                DataType.SWIMMING_STROKES_TOTAL,
            )
            val dataTypes = wanted.intersect(typeCaps.supportedDataTypes).let {
                // LOCATION requires GPS; never request it when GPS is off (gym / pool swim).
                if (plan.gps) it else it - DataType.LOCATION
            }

            samples.clear()
            routePoints.clear()
            lastSampleSecond = -1L
            hrSum = 0.0
            hrCount = 0
            hrMax = 0.0
            swimLaps = 0
            manualLaps = 0
            lastSplitIdx = 0
            lastPaused = false
            lastCheckpoint = null
            finalizing = false
            startWallMillis = System.currentTimeMillis()

            collectJob?.cancel()
            collectJob = lifecycleScope.launch(Dispatchers.Default) {
                updateFlow.collect { handleUpdate(it) }
            }

            val config = ExerciseConfig(
                exerciseType = plan.type,
                dataTypes = dataTypes,
                isAutoPauseAndResumeEnabled = useAutoPause,
                isGpsEnabled = plan.gps,
                swimmingPoolLengthMeters = if (isPoolSwim) POOL_LENGTH_METERS.toFloat() else 0f,
            )
            exerciseClient.startExercise(config)
            Log.i(TAG, "recording started: ${plan.type} (${plan.display}) dataTypes=$dataTypes")
            buzz(HAPTIC_START)

            RecorderBus.state.value = RecorderState(
                phase = RecorderState.Phase.RECORDING,
                exerciseName = plan.display,
                pending = RecorderBus.state.value.pending,
                review = RecorderBus.state.value.review,
            )
            updateNotification(force = true)
            startTicker()
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            RecorderBus.state.value = RecorderBus.state.value.copy(error = "start failed: ${e.message}")
            removeForeground()
            stopSelf()
        }
    }

    /**
     * Advances the displayed duration once a second from the newest checkpoint, so the on-screen
     * timer keeps ticking smoothly even when Health Services batches updates (screen off/ambient).
     * Cancelled by [tickJob] on stop/finalize; `delay` makes the loop cancellable.
     */
    private fun startTicker() {
        tickJob?.cancel()
        tickJob = lifecycleScope.launch {
            while (true) {
                delay(1000)
                val s = RecorderBus.state.value
                if (s.phase != RecorderState.Phase.RECORDING) continue
                val cp = lastCheckpoint ?: continue
                val paused = s.exerciseState?.isPaused == true
                val ms = cp.activeDuration.toMillis().let { base ->
                    if (paused) base
                    else base + (System.currentTimeMillis() - cp.time.toEpochMilli()).coerceAtLeast(0L)
                }
                if (ms > s.activeMillis) {
                    RecorderBus.state.value = s.copy(activeMillis = ms)
                }
            }
        }
    }

    private fun handleUpdate(update: ExerciseUpdate) {
        val metrics = update.latestMetrics
        val hr = metrics.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value
        val hrAvg = metrics.getData(DataType.HEART_RATE_BPM_STATS)?.average
        val dist = metrics.getData(DataType.DISTANCE_TOTAL)?.total
        val cal = metrics.getData(DataType.CALORIES_TOTAL)?.total
        val loc = metrics.getData(DataType.LOCATION).lastOrNull()?.value
        val strokes = metrics.getData(DataType.SWIMMING_STROKES_TOTAL)?.total ?: 0L
        // The checkpoint is a BASE value: docs say the live duration is
        // `activeDuration + (now - time)` while active (frozen at `activeDuration` while paused).
        // Using the raw base made the on-screen timer jump only when a new checkpoint arrived.
        val cp = update.activeDurationCheckpoint
        lastCheckpoint = cp
        val pausedAtUpdate = update.exerciseStateInfo.state.isPaused
        val activeMsFromCp = cp?.let {
            val base = it.activeDuration.toMillis()
            if (pausedAtUpdate) base
            else base + (System.currentTimeMillis() - it.time.toEpochMilli()).coerceAtLeast(0L)
        } ?: 0L
        // Fallback: some exercise types report no checkpoint early on — use wall-clock since start.
        val activeMs = if (activeMsFromCp > 0) activeMsFromCp else (System.currentTimeMillis() - startWallMillis)

        if (hr != null && hr > 0) {
            hrSum += hr
            hrCount++
            if (hr > hrMax) hrMax = hr
        }

        val now = System.currentTimeMillis()
        val sec = now / 1000L
        if (sec != lastSampleSecond) {
            lastSampleSecond = sec
            samples.add(
                TrackSample(
                    timeMillis = now,
                    lat = loc?.latitude,
                    lon = loc?.longitude,
                    altitude = loc?.altitude?.takeIf { it.isFinite() && it > -1000.0 },
                    heartRate = hr,
                    distanceMeters = dist,
                )
            )
            if (loc != null && sec % 5 == 0L && routePoints.size < 400) {
                routePoints.add(loc.latitude to loc.longitude)
            }
        }

        val prev = RecorderBus.state.value
        val effDist: Double? = when {
            (dist != null && dist > 0.0) -> dist
            (isPoolSwim && swimLaps > 0) -> swimLaps * POOL_LENGTH_METERS
            else -> prev.distanceMeters
        }
        val newState = update.exerciseStateInfo.state
        RecorderBus.state.value = prev.copy(
            exerciseState = newState,
            heartRate = hr ?: prev.heartRate,
            heartRateAvg = hrAvg ?: prev.heartRateAvg,
            distanceMeters = effDist,
            calories = cal ?: prev.calories,
            activeMillis = activeMs,
            hasLocation = loc != null || prev.hasLocation,
            samples = samples.size,
            laps = if (isPoolSwim) swimLaps else manualLaps,
            strokes = strokes,
            route = routePoints.toList(),
        )
        updateNotification()

        // --- haptics: the wrist equivalent of Strava's audio announcements -------------
        val pausedNow = newState.isPaused
        if (pausedNow != lastPaused) {
            lastPaused = pausedNow
            buzz(if (pausedNow) HAPTIC_PAUSE else HAPTIC_RESUME)
        }
        effDist?.let { d ->
            val step = if (WearPrefs.unitsImperial(this)) Units.METERS_PER_MILE else 1000.0
            val idx = (d / step).toInt()
            if (idx > lastSplitIdx) {
                lastSplitIdx = idx
                buzz(HAPTIC_SPLIT)
            }
        }

        if (update.exerciseStateInfo.state.isEnded && !finalizing) {
            lifecycleScope.launch { finalizeAndStop() }
        }
    }

    private suspend fun stopAndFinish() {
        if (RecorderBus.state.value.phase == RecorderState.Phase.DONE) {
            removeForeground()
            stopSelf()
            return
        }
        RecorderBus.state.value = RecorderBus.state.value.copy(phase = RecorderState.Phase.SAVING, lastSendStatus = null)
        runCatching { exerciseClient.endExercise() }
        delay(2000)
        finalizeAndStop()
    }

    private suspend fun finalizeAndStop() {
        if (finalizing) return
        finalizing = true
        try {
            val st = RecorderBus.state.value
            val totalSec = (st.activeMillis / 1000L).coerceAtLeast(1L)
            val avgHr = if (hrCount > 0) hrSum / hrCount else null
            val xml = TcxWriter.build(
                sport = tcxSport,
                samples = samples.toList(),
                totalSeconds = totalSec,
                distanceMeters = st.distanceMeters,
                calories = st.calories,
                avgHr = avgHr,
                maxHr = hrMax.takeIf { it > 0 },
            )
            val bytes = xml.toByteArray(Charsets.UTF_8)
            val fmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val filename = "openfit-${fmt.format(Date(startWallMillis))}-${tcxSport.lowercase(Locale.US)}.tcx"
            val dir = WearPrefs.activitiesDir(this)
            val outFile = File(dir, filename)
            outFile.writeBytes(bytes)
            Log.i(TAG, "finalize: file=$filename bytes=${bytes.size} samples=${samples.size}")

            // Upload is user-gated: this only saves the file and shows the review screen.
            RecorderBus.state.value = st.copy(
                phase = RecorderState.Phase.DONE,
                lastFile = filename,
                lastSendStatus = "saved - tap Done to upload",
                splits = computeSplits(),
            )
            refreshCounts()
        } catch (e: Exception) {
            Log.e(TAG, "finalize failed", e)
            RecorderBus.state.value = RecorderBus.state.value.copy(
                phase = RecorderState.Phase.DONE,
                error = "save failed: ${e.message}",
            )
        } finally {
            collectJob?.cancel()
            tickJob?.cancel()
            removeForeground()
            stopSelf()
        }
    }

    // --- review gate: Done (approve + upload) / Delete -------------------------

    private suspend fun reviewDone(name: String?) {
        if (name.isNullOrBlank()) return
        val file = File(WearPrefs.activitiesDir(this), name)
        if (!file.exists()) {
            RecorderBus.state.value = RecorderBus.state.value.copy(lastSendStatus = "file missing")
            refreshCounts()
            return
        }
        WearPrefs.markApproved(this, name)
        RecorderBus.state.value = RecorderBus.state.value.copy(lastFile = name, lastSendStatus = "uploading…")
        val status = deliver(file)
        RecorderBus.state.value = RecorderBus.state.value.copy(lastFile = name, lastSendStatus = status)
        Log.i(TAG, "review done: $name -> $status")
        refreshCounts()
    }

    private suspend fun reviewDelete(name: String?) {
        if (name.isNullOrBlank()) return
        val file = File(WearPrefs.activitiesDir(this), name)
        WearPrefs.deleteActivity(this, file)
        RecorderBus.state.value = RecorderBus.state.value.copy(lastFile = name, lastSendStatus = "deleted")
        Log.i(TAG, "review delete: $name")
        refreshCounts()
    }

    private fun refreshCounts() {
        val dir = WearPrefs.activitiesDir(this)
        RecorderBus.state.value = RecorderBus.state.value.copy(
            pending = WearPrefs.approvedPending(this, dir).size,
            review = WearPrefs.unreviewed(this, dir).size,
        )
    }

    // --- delivery ---------------------------------------------------------------

    /** Direct upload to Dreeve when configured; otherwise (or on failure) hand to the phone. */
    private suspend fun deliver(file: File): String {
        val base = WearPrefs.base(this)
        val key = WearPrefs.apiKey(this)
        if (base != null && key != null) {
            val err = withContext(Dispatchers.IO) { DreeveUploader.upload(file, base, key) }
            if (err == null) {
                WearPrefs.markUploaded(this, file.name)
                Log.i(TAG, "uploaded directly: ${file.name}")
                return "uploaded to Dreeve"
            }
            Log.w(TAG, "direct upload failed for ${file.name}: $err")
            val viaPhone = sendToPhone(file)
            return if (viaPhone) "upload failed, sent via phone ($err)" else "upload failed ($err) - will retry"
        }
        val viaPhone = sendToPhone(file)
        return if (viaPhone) "sent to phone (Dreeve not configured on watch)" else "not configured - will retry"
    }

    private suspend fun sendToPhone(file: File): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Tasks.await(
                DataLayerSender.sendActivityFile(this@ExerciseService, file.name, file.readBytes()),
                90,
                TimeUnit.SECONDS,
            )
            WearPrefs.markSentToPhone(this@ExerciseService, file.name)
            true
        }.getOrDefault(false)
    }

    /** Re-attempt every approved file that has not reached Dreeve yet. */
    private suspend fun retryPending() {
        val dir = WearPrefs.activitiesDir(this)
        val pending = WearPrefs.approvedPending(this, dir)
        var stillPending = 0
        pending.forEach { f ->
            val status = deliver(f)
            Log.i(TAG, "retry ${f.name}: $status")
            if (status.contains("will retry")) stillPending++
        }
        RecorderBus.state.value = RecorderBus.state.value.copy(
            pending = stillPending,
            lastSendStatus = if (pending.isEmpty()) RecorderBus.state.value.lastSendStatus
            else "checked ${pending.size} file(s), $stillPending still pending",
        )
        refreshCounts()
    }

    // --- live foreground notification -----------------------------------------

    private fun statusLine(): String {
        val s = RecorderBus.state.value
        val parts = mutableListOf<String>()
        parts.add(fmtDur(s.activeMillis))
        if (s.laps > 0) parts.add("${s.laps} laps")
        s.distanceMeters?.takeIf { it > 0 }?.let { parts.add(Units.dist(this, it)) }
        s.heartRate?.takeIf { it > 0 }?.let { parts.add("${it.toInt()} bpm") }
        if (!s.hasLocation && s.phase == RecorderState.Phase.RECORDING && s.laps == 0) parts.add("no GPS yet")
        val es = s.exerciseState
        if (es != null && es.name == "AUTO_PAUSED") parts.add("auto-paused")
        else if (es?.isPaused == true) parts.add("paused")
        return parts.joinToString(" · ")
    }

    private fun fmtDur(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(Locale.US, s / 60, s % 60)
    }

    /** Short haptic patterns — split / lap / pause-resume feedback on the wrist. */
    private fun buzz(pattern: LongArray) {
        runCatching {
            val v = vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            }
        }
    }

    /** Per-km (or per-mile) splits from the recorded samples, e.g. "km 1 · 5:32". */
    private fun computeSplits(): List<String> {
        val out = mutableListOf<String>()
        val step = if (WearPrefs.unitsImperial(this)) Units.METERS_PER_MILE else 1000.0
        var nextSplit = step
        var prevMs = samples.firstOrNull()?.timeMillis ?: return out
        var n = 1
        for (s in samples) {
            val d = s.distanceMeters ?: continue
            if (d >= nextSplit) {
                val secs = ((s.timeMillis - prevMs) / 1000).coerceAtLeast(1)
                out.add(Units.split(this, n, secs))
                prevMs = s.timeMillis
                n++
                nextSplit += step
                if (n > 15) break
            }
        }
        return out
    }

    private fun updateNotification(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotifMs < 2000) return
        lastNotifMs = now
        val text = statusLine()
        val nb = notificationBuilder(text)
        notificationManager.notify(NOTIF_ID, nb.build())
        updateOngoingActivity(nb, text)
    }

    /**
     * Ongoing-activity chip: live stats on the watch face (icon at the bottom) and in the
     * launcher; tapping it reopens the app. It disappears together with the notification.
     */
    private fun updateOngoingActivity(builder: NotificationCompat.Builder, statusText: String) {
        val s = RecorderBus.state.value
        if (s.phase != RecorderState.Phase.RECORDING) return
        val status = Status.forPart(Status.TextPart(statusText))
        val existing = ongoingActivity
        if (existing == null) {
            val touch = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val created = OngoingActivity.Builder(this, NOTIF_ID, builder)
                .setStaticIcon(R.drawable.ic_run)
                .setTouchIntent(touch)
                .setTitle(s.exerciseName)
                .setStatus(status)
                .build()
            created.apply(this)
            ongoingActivity = created
            Log.i(TAG, "ongoing activity registered: $statusText")
        } else {
            existing.update(this, status)
        }
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, ExerciseService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW)
        )
    }

    /** Big notification line: time · distance (· laps). */
    private fun notifTitle(): String {
        val s = RecorderBus.state.value
        val parts = mutableListOf<String>()
        parts.add(fmtDur(s.activeMillis))
        s.distanceMeters?.takeIf { it > 0 }?.let { parts.add(Units.dist(this, it)) }
        if (s.laps > 0) parts.add("${s.laps} laps")
        return parts.joinToString(" · ")
    }

    /** Secondary notification line: heart rate · pace · state. */
    private fun notifDetail(): String {
        val s = RecorderBus.state.value
        val parts = mutableListOf<String>()
        s.heartRate?.takeIf { it > 0 }?.let { parts.add("${it.toInt()} bpm") }
        if (s.activeMillis > 0) parts.add(Units.pace(this, s.activeMillis, s.distanceMeters))
        val es = s.exerciseState
        when {
            es != null && es.name == "AUTO_PAUSED" -> parts.add("auto-paused")
            es?.isPaused == true -> parts.add("paused")
            !s.hasLocation && s.phase == RecorderState.Phase.RECORDING && s.laps == 0 -> parts.add("no GPS")
        }
        return parts.joinToString(" · ")
    }

    private fun notificationBuilder(text: String): NotificationCompat.Builder {
        val s = RecorderBus.state.value
        val recording = s.phase == RecorderState.Phase.RECORDING
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val nb = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (recording) notifTitle() else text)
            .setContentText(if (recording) notifDetail() else s.exerciseName)
            .setSubText("openFit · ${s.exerciseName}")
            .setSmallIcon(R.drawable.ic_run)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (recording) {
            val paused = s.exerciseState?.isPaused == true
            nb.addAction(
                if (paused) R.drawable.ic_play else R.drawable.ic_pause,
                if (paused) "Resume" else "Pause",
                servicePendingIntent(if (paused) ACTION_RESUME else ACTION_PAUSE, 1),
            )
            nb.addAction(R.drawable.ic_stop, "Stop", servicePendingIntent(ACTION_STOP, 2))
        }
        return nb
    }

    private fun buildNotification(text: String): Notification = notificationBuilder(text).build()

    private fun promoteToForeground(text: String) {
        val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(text), types)
    }

    private fun removeForeground() {
        // The ongoing-activity chip lives with the notification; clearing one clears both.
        ongoingActivity = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    companion object {
        const val ACTION_START = "dev.openfit.wear.START"
        const val ACTION_PAUSE = "dev.openfit.wear.PAUSE"
        const val ACTION_RESUME = "dev.openfit.wear.RESUME"
        const val ACTION_LAP = "dev.openfit.wear.LAP"
        const val ACTION_STOP = "dev.openfit.wear.STOP"
        const val ACTION_REVIEW_DONE = "dev.openfit.wear.REVIEW_DONE"
        const val ACTION_REVIEW_DELETE = "dev.openfit.wear.REVIEW_DELETE"
        const val ACTION_RETRY = "dev.openfit.wear.RETRY"
        const val ACTION_CONFIG = "dev.openfit.wear.CONFIG"
        const val EXTRA_EXERCISE = "exercise"
        const val EXTRA_FILE = "file"
        const val EXTRA_BASE = "base"
        const val EXTRA_KEY = "key"

        /** Default pool length for lap-based distance estimation. */
        const val POOL_LENGTH_METERS = 25.0

        // Haptic patterns (off, on, off, on, …; -1 = play once) — see buzz().
        private val HAPTIC_START = longArrayOf(0, 70)
        private val HAPTIC_SPLIT = longArrayOf(0, 60, 90, 60)
        private val HAPTIC_LAP = longArrayOf(0, 40, 60, 40)
        private val HAPTIC_PAUSE = longArrayOf(0, 140, 90, 140)
        private val HAPTIC_RESUME = longArrayOf(0, 60, 60, 60)

        private const val CHANNEL_ID = "openfit_recording"
        private const val NOTIF_ID = 42
        private const val TAG = "openFitWear"
    }
}
