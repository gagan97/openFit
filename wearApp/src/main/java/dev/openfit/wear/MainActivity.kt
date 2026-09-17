package dev.openfit.wear

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.io.File
import java.util.Locale
import kotlinx.coroutines.delay

private val ACCENT = Color(0xFFFF6D3A)
private val CAPSULE = Color(0xFF2B2C30)
private val MUTED = Color(0xFF9AA0A6)
private val OK = Color(0xFF9BE15D)
private val WARN = Color(0xFFFFC24B)
private val DANGER = Color(0xFFFF6E6E)
private val DELETE_BG = Color(0xFF7F1D1D)
private val ROUTE_BLUE = Color(0xFF8AB4F8)

private data class SportSpec(val key: String, val label: String, val icon: Int)

private val SPORTS = listOf(
    SportSpec("Walking", "Walk", R.drawable.ic_walk),
    SportSpec("Running", "Run", R.drawable.ic_run),
    SportSpec("Cycling", "Ride", R.drawable.ic_ride),
    SportSpec("Workout", "Gym", R.drawable.ic_gym),
    SportSpec("Swimming", "Swimming", R.drawable.ic_swim),
)

private fun isGpsSport(exerciseName: String): Boolean =
    exerciseName != "Workout" && exerciseName != "Pool Swim"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                OpenFitScreen()
            }
        }
    }
}

@Composable
private fun OpenFitScreen() {
    val context = LocalContext.current
    val state by RecorderBus.state.collectAsState()
    var chosenSport by remember { mutableStateOf<String?>(null) }
    var reviewFile by remember { mutableStateOf<File?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    val permissionLauncher =
        androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { }

    LaunchedEffect(Unit) {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACTIVITY_RECOGNITION,
        )
        if (Build.VERSION.SDK_INT >= 36) {
            // Wear OS 6 / Android 16: heart rate moved to the granular "health" permission.
            perms.add("android.permission.health.READ_HEART_RATE")
        } else {
            perms.add(Manifest.permission.BODY_SENSORS)
        }
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        permissionLauncher.launch(perms.toTypedArray())

        // Auto-retry only the upload queue (files already approved with Done).
        val pending = runCatching {
            WearPrefs.approvedPending(context, WearPrefs.activitiesDir(context)).size
        }.getOrDefault(0)
        if (pending > 0) send(context, ExerciseService.ACTION_RETRY, null)
    }

    LaunchedEffect(state.phase) {
        if (state.phase != RecorderState.Phase.IDLE) {
            chosenSport = null
            showSettings = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (state.phase) {
            RecorderState.Phase.IDLE -> {
                val rf = reviewFile
                val picked = chosenSport
                when {
                    rf != null -> ReviewFileScreen(rf, context) { reviewFile = null }

                    picked != null -> PreStartScreen(picked, context) { chosenSport = null }

                    showSettings -> SettingsScreen { showSettings = false }

                    else -> HomeScreen(
                        state,
                        context,
                        onPick = { chosenSport = it },
                        onReview = { reviewFile = it },
                        onSettings = { showSettings = true },
                    )
                }
            }

            RecorderState.Phase.RECORDING -> RecordingScreen(state, context)
            RecorderState.Phase.SAVING -> Text("Saving…", textAlign = TextAlign.Center)
            RecorderState.Phase.DONE -> DoneScreen(state, context)
        }
    }
}

// --- home ---------------------------------------------------------------------

@Composable
private fun HomeScreen(
    state: RecorderState,
    context: Context,
    onPick: (String) -> Unit,
    onReview: (File) -> Unit,
    onSettings: () -> Unit,
) {
    var latest by remember { mutableStateOf<Pair<File, WearTcxReader.Summary>?>(null) }
    var unreviewed by remember { mutableStateOf<List<File>>(emptyList()) }
    var pendingUp by remember { mutableStateOf<List<File>>(emptyList()) }

    LaunchedEffect(state.lastFile, state.pending, state.review, state.phase) {
        val dir = WearPrefs.activitiesDir(context)
        unreviewed = runCatching { WearPrefs.unreviewed(context, dir) }.getOrDefault(emptyList())
        pendingUp = runCatching { WearPrefs.approvedPending(context, dir) }.getOrDefault(emptyList())
        val f = dir.listFiles()?.filter { it.isFile }?.maxByOrNull { it.name }
        latest = f?.let { file ->
            file to runCatching { WearTcxReader.parse(file) }
                .getOrDefault(WearTcxReader.Summary(emptyList(), null, null, null, null, null))
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        ) {
            Spacer(Modifier.width(30.dp))
            Text(
                "openFit",
                style = MaterialTheme.typography.h6,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Icon(
                painterResource(R.drawable.ic_settings),
                contentDescription = "Settings",
                tint = MUTED,
                modifier = Modifier
                    .size(26.dp)
                    .clickable { onSettings() },
            )
        }

        SPORTS.forEach { s ->
            SportCapsule(s.label, s.icon) { onPick(s.key) }
        }

        if (unreviewed.isNotEmpty()) {
            Text(
                "${unreviewed.size} waiting for review",
                style = MaterialTheme.typography.caption,
                color = WARN,
                modifier = Modifier.padding(top = 6.dp),
            )
            Button(onClick = { onReview(unreviewed.last()) }) { Text("Review latest") }
        }

        if (pendingUp.isNotEmpty()) {
            Text("${pendingUp.size} waiting to upload", style = MaterialTheme.typography.caption, color = WARN)
            Button(onClick = { SyncManager.syncNow(context) }) { Text("Upload now") }
        }

        latest?.let { (file, sum) ->
            Text("last activity", style = MaterialTheme.typography.caption, color = MUTED, modifier = Modifier.padding(top = 8.dp))
            Text(prettyName(file.name), style = MaterialTheme.typography.caption, color = Color.White, textAlign = TextAlign.Center)
            val line = buildString {
                sum.totalSeconds?.let { t -> append("%d:%02d".format((t / 60).toInt(), (t % 60).toInt())) }
                sum.distanceMeters?.takeIf { it > 0 }?.let { d ->
                    if (isNotEmpty()) append(" · ")
                    append(Units.dist(context, d))
                }
            }
            if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.caption, color = Color.White)
            if (sum.points.size >= 2) RoutePreview(sum.points, 64.dp)
        }

        state.error?.let {
            Text("! $it", style = MaterialTheme.typography.caption, color = DANGER, textAlign = TextAlign.Center)
        }
        val configured = WearPrefs.base(context) != null && WearPrefs.apiKey(context) != null
        if (!configured) {
            Text("Dreeve: NOT configured", style = MaterialTheme.typography.caption, color = WARN, textAlign = TextAlign.Center)
        }

        // --- settings entry -------------------------------------------------------
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(50))
                .background(CAPSULE)
                .clickable { onSettings() }
                .padding(horizontal = 5.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF3A3B40)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_settings),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text("Settings", style = MaterialTheme.typography.body2, color = Color.White)
        }

        // Bottom breathing room so the last item can scroll fully inside the round display.
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun SportCapsule(label: String, icon: Int, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(50))
            .background(CAPSULE)
            .clickable { onClick() }
            .padding(horizontal = 5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(33.dp)
                .clip(CircleShape)
                .background(ACCENT),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.body1, color = Color.White)
    }
}

// --- pre-start ----------------------------------------------------------------

@Composable
private fun PreStartScreen(sportKey: String, context: Context, onBack: () -> Unit) {
    val spec = SPORTS.firstOrNull { it.key == sportKey } ?: SPORTS.first()
    val gpsSport = sportKey != "Workout" && sportKey != "Swimming"
    var gpsReady by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(sportKey) {
        if (!gpsSport) return@LaunchedEffect
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        while (true) {
            val loc = runCatching { lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
            gpsReady = loc != null && System.currentTimeMillis() - loc.time < 120_000
            delay(2000)
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(ACCENT),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(spec.icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
        }
        Text(spec.label, style = MaterialTheme.typography.h6, color = Color.White)
        if (gpsSport) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(
                    painterResource(if (gpsReady == true) R.drawable.ic_gps_on else R.drawable.ic_gps_off),
                    contentDescription = if (gpsReady == true) "GPS ready" else "Getting GPS fix",
                    tint = if (gpsReady == true) OK else WARN,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    if (gpsReady == true) "GPS ready" else "Getting fix…",
                    style = MaterialTheme.typography.caption,
                    color = if (gpsReady == true) OK else WARN,
                )
            }
        }
        Button(
            onClick = { send(context, ExerciseService.ACTION_START, sportKey) },
            modifier = Modifier
                .height(44.dp)
                .fillMaxWidth(0.72f),
            colors = ButtonDefaults.buttonColors(backgroundColor = ACCENT, contentColor = Color.White),
        ) { Text("Start") }
        TextButton(onClick = onBack) { Text("Cancel", color = MUTED) }
    }
}

// --- recording (3 swipeable pages) ---------------------------------------------

@Composable
private fun RecordingScreen(state: RecorderState, context: Context) {
    val pagerState = rememberPagerState(pageCount = { 3 })

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            // Round-safe insets: keep page content well inside the circular display.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 26.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                when (page) {
                    0 -> MainMetricsPage(state)
                    1 -> StatsPage(state)
                    else -> RoutePage(state)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(3) { i ->
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(if (pagerState.currentPage == i) ACCENT else Color(0xFF5A5D63))
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // Minimal icon controls (Strava-style): pause/resume · lap · finish.
        Row(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val paused = state.exerciseState?.isPaused == true
            CircleAction(
                icon = if (paused) R.drawable.ic_play else R.drawable.ic_pause,
                label = if (paused) "Resume" else "Pause",
                bg = Color(0xFF2B2C30),
            ) {
                send(context, if (paused) ExerciseService.ACTION_RESUME else ExerciseService.ACTION_PAUSE, null)
            }
            CircleAction(
                icon = R.drawable.ic_flag,
                label = "Lap",
                bg = Color(0xFF2B2C30),
            ) { send(context, ExerciseService.ACTION_LAP, null) }
            CircleAction(
                icon = R.drawable.ic_stop,
                label = "Finish",
                bg = DELETE_BG,
            ) { send(context, ExerciseService.ACTION_STOP, null) }
        }
        Spacer(Modifier.height(52.dp))
    }
}

/** Small circular icon button — keeps the control row inside the round display. */
@Composable
private fun CircleAction(icon: Int, label: String, bg: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun MainMetricsPage(state: RecorderState) {
    val context = LocalContext.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(formatDuration(state.activeMillis), style = MaterialTheme.typography.h4, color = Color.White)
        Text(Units.dist(context, state.distanceMeters), style = MaterialTheme.typography.body1, color = Color.White)
        Text(Units.pace(context, state.activeMillis, state.distanceMeters), style = MaterialTheme.typography.body2, color = MUTED)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(hrColor(state.heartRate)))
            Text("${fmt(state.heartRate)} bpm", style = MaterialTheme.typography.body2, color = hrColor(state.heartRate))
        }
        val es = state.exerciseState
        if (es?.isPaused == true) {
            Text(
                if (es.name == "AUTO_PAUSED") "auto-paused · resumes when you move" else "paused",
                style = MaterialTheme.typography.body2,
                color = WARN,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun StatsPage(state: RecorderState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("avg HR ${fmt(state.heartRateAvg)} bpm", style = MaterialTheme.typography.body2, color = Color.White)
        Text("${fmt(state.calories)} kcal", style = MaterialTheme.typography.body2, color = Color.White)
        if (state.laps > 0) {
            Text(
                if (state.laps == 1) "1 lap" else "${state.laps} laps",
                style = MaterialTheme.typography.body2,
                color = Color.White,
            )
        }
        if (state.strokes > 0) {
            Text("${state.strokes} strokes", style = MaterialTheme.typography.body2, color = Color.White)
        }
        if (isGpsSport(state.exerciseName)) {
            Icon(
                painterResource(if (state.hasLocation) R.drawable.ic_gps_on else R.drawable.ic_gps_off),
                contentDescription = if (state.hasLocation) "GPS active" else "GPS searching",
                tint = if (state.hasLocation) OK else WARN,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun RoutePage(state: RecorderState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.route.size >= 2) {
            RoutePreview(state.route, 150.dp)
        } else {
            Text(
                if (isGpsSport(state.exerciseName)) "route builds as you move" else "no route for this sport",
                style = MaterialTheme.typography.caption,
                color = MUTED,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// --- review (fresh recording) ---------------------------------------------------

@Composable
private fun DoneScreen(state: RecorderState, context: Context) {
    val file = state.lastFile
    val status = state.lastSendStatus
    var confirmDelete by remember { mutableStateOf(false) }

    // Scrollable content on top, sticky controls at the bottom — Done/Delete stay reachable even
    // with a route sketch and a long splits list (user feedback 2026-09-17).
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("Review", style = MaterialTheme.typography.h6, color = Color.White, modifier = Modifier.padding(top = 4.dp))
            Text(formatDuration(state.activeMillis), style = MaterialTheme.typography.h5, color = Color.White)
            Text(
                "${Units.dist(context, state.distanceMeters)} · ${Units.pace(context, state.activeMillis, state.distanceMeters)}",
                style = MaterialTheme.typography.body2,
                color = Color.White,
            )
            Text("avg HR ${fmt(state.heartRateAvg)} · ${fmt(state.calories)} kcal", style = MaterialTheme.typography.caption, color = MUTED)
            RoutePreview(state.route, 56.dp)
            if (state.splits.isNotEmpty()) {
                Text("splits", style = MaterialTheme.typography.caption, color = MUTED, modifier = Modifier.padding(top = 2.dp))
                state.splits.take(3).forEach { Text(it, style = MaterialTheme.typography.caption, color = Color.White) }
                if (state.splits.size > 3) {
                    Text("… +${state.splits.size - 3} more", style = MaterialTheme.typography.caption, color = MUTED)
                }
            }
            state.error?.let { Text("! $it", color = DANGER, style = MaterialTheme.typography.caption, textAlign = TextAlign.Center) }
            Text(status ?: "", style = MaterialTheme.typography.caption, textAlign = TextAlign.Center, color = statusColor(status))
        }

        Spacer(Modifier.height(4.dp))
        val done = status != null && (status.startsWith("uploaded") || status.startsWith("sent"))
        if (done) {
            Button(onClick = { RecorderBus.state.value = RecorderState() }) { Text("Home") }
        } else {
            Button(
                onClick = { file?.let { sendFile(context, ExerciseService.ACTION_REVIEW_DONE, it) } },
                colors = ButtonDefaults.buttonColors(backgroundColor = ACCENT, contentColor = Color.White),
            ) { Text(if (status?.contains("uploading") == true) "Uploading…" else "Done") }
            if (!confirmDelete) {
                CircleAction(icon = R.drawable.ic_delete, label = "Delete", bg = DELETE_BG) { confirmDelete = true }
            } else {
                Button(
                    onClick = {
                        file?.let { sendFile(context, ExerciseService.ACTION_REVIEW_DELETE, it) }
                        RecorderBus.state.value = RecorderState()
                    },
                    colors = ButtonDefaults.buttonColors(backgroundColor = DELETE_BG, contentColor = Color.White),
                ) { Text("Confirm delete") }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// --- review (older file, parsed from disk) --------------------------------------

@Composable
private fun ReviewFileScreen(file: File, context: Context, onClose: () -> Unit) {
    val sum = remember(file) { runCatching { WearTcxReader.parse(file) }.getOrNull() }
    val state by RecorderBus.state.collectAsState()
    val status = state.lastSendStatus
    var confirmDelete by remember { mutableStateOf(false) }

    // Sticky controls at the bottom (same pattern as the fresh-review screen).
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("Review", style = MaterialTheme.typography.h6, color = Color.White, modifier = Modifier.padding(top = 4.dp))
            Text(prettyName(file.name), style = MaterialTheme.typography.caption, color = MUTED, textAlign = TextAlign.Center)

            sum?.let {
                val line = buildString {
                    it.totalSeconds?.let { t -> append("%d:%02d".format((t / 60).toInt(), (t % 60).toInt())) }
                    it.distanceMeters?.takeIf { d -> d > 0 }?.let { d ->
                        if (isNotEmpty()) append(" · ")
                        append(Units.dist(context, d))
                    }
                }
                if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.body2, color = Color.White)
                val hrLine = buildString {
                    it.avgHr?.let { a -> append("avg HR $a") }
                    it.maxHr?.let { m ->
                        if (isNotEmpty()) append(" · ")
                        append("max $m")
                    }
                    it.calories?.let { c ->
                        if (isNotEmpty()) append(" · ")
                        append("$c kcal")
                    }
                }
                if (hrLine.isNotEmpty()) Text(hrLine, style = MaterialTheme.typography.caption, color = MUTED)
                if (it.points.size >= 2) RoutePreview(it.points, 56.dp)
            }

            Text(status ?: "", style = MaterialTheme.typography.caption, textAlign = TextAlign.Center, color = statusColor(status))
        }

        Spacer(Modifier.height(4.dp))
        val done = status != null && (status.startsWith("uploaded") || status.startsWith("sent") || status == "deleted")
        if (done) {
            Button(onClick = onClose) { Text("Home") }
        } else {
            Button(
                onClick = { sendFile(context, ExerciseService.ACTION_REVIEW_DONE, file.name) },
                colors = ButtonDefaults.buttonColors(backgroundColor = ACCENT, contentColor = Color.White),
            ) { Text(if (status?.contains("uploading") == true) "Uploading…" else "Done") }
            if (!confirmDelete) {
                CircleAction(icon = R.drawable.ic_delete, label = "Delete", bg = DELETE_BG) { confirmDelete = true }
            } else {
                Button(
                    onClick = {
                        sendFile(context, ExerciseService.ACTION_REVIEW_DELETE, file.name)
                        onClose()
                    },
                    colors = ButtonDefaults.buttonColors(backgroundColor = DELETE_BG, contentColor = Color.White),
                ) { Text("Confirm delete") }
            }
            TextButton(onClick = onClose) { Text("Back", color = MUTED) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Tiny route sketch drawn from the recorded positions (no map tiles on the watch). */
@Composable
private fun RoutePreview(route: List<Pair<Double, Double>>, height: Dp = 110.dp) {
    if (route.size < 2) return
    val minLat = route.minOf { it.first }
    val maxLat = route.maxOf { it.first }
    val minLon = route.minOf { it.second }
    val maxLon = route.maxOf { it.second }
    val dLat = (maxLat - minLat).coerceAtLeast(1e-6)
    val dLon = (maxLon - minLon).coerceAtLeast(1e-6)

    Canvas(modifier = Modifier.fillMaxWidth(0.92f).height(height)) {
        val pad = 8f
        val w = size.width - 2 * pad
        val h = size.height - 2 * pad
        val scale = minOf(w / dLon, h / dLat).toFloat()
        val offX = (w - (dLon * scale).toFloat()) / 2f
        val offY = (h - (dLat * scale).toFloat()) / 2f
        fun project(p: Pair<Double, Double>): Offset {
            val x = pad + offX + ((p.second - minLon) * scale).toFloat()
            val y = pad + offY + ((maxLat - p.first) * scale).toFloat()
            return Offset(x, y)
        }

        val path = Path()
        route.forEachIndexed { i, p ->
            val o = project(p)
            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        drawPath(path, color = ROUTE_BLUE, style = Stroke(width = 4f, cap = StrokeCap.Round))
        drawCircle(OK, radius = 5f, center = project(route.first()))
        drawCircle(ACCENT, radius = 6f, center = project(route.last()))
    }
}

// --- helpers ---------------------------------------------------------------------

private fun send(context: Context, action: String, exercise: String?) {
    val intent = Intent(context, ExerciseService::class.java).setAction(action)
    if (exercise != null) intent.putExtra(ExerciseService.EXTRA_EXERCISE, exercise)
    ContextCompat.startForegroundService(context, intent)
}

private fun sendFile(context: Context, action: String, fileName: String) {
    val intent = Intent(context, ExerciseService::class.java).setAction(action)
    intent.putExtra(ExerciseService.EXTRA_FILE, fileName)
    ContextCompat.startForegroundService(context, intent)
}

/** "openfit-20260916-164406-walking.tcx" -> "16-09 16:44 UTC · walking" */
private fun prettyName(fileName: String): String {
    val core = fileName.removePrefix("openfit-").removeSuffix(".tcx")
    val parts = core.split("-")
    return if (parts.size >= 3) {
        val d = parts[0]
        val t = parts[1]
        val sport = parts.drop(2).joinToString("-")
        val date = if (d.length == 8) "${d.substring(6, 8)}-${d.substring(4, 6)}" else d
        val time = if (t.length >= 4) "${t.substring(0, 2)}:${t.substring(2, 4)}" else t
        "$date $time UTC · $sport"
    } else core
}

private fun statusColor(status: String?): Color = when {
    status == null -> MUTED
    status.startsWith("uploaded") -> OK
    status.startsWith("deleted") -> MUTED
    status.startsWith("sent") -> OK
    status.contains("failed") || status.contains("missing") -> DANGER
    else -> Color.White
}

private fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(Locale.US, s / 60, s % 60)
}

private fun fmt(v: Double?): String =
    if (v == null || v.isNaN()) "--" else "%.0f".format(Locale.US, v)

private fun fmtKm(v: Double?): String =
    if (v == null) "0.00" else "%.2f".format(Locale.US, v / 1000.0)

private fun fmtPace(activeMs: Long, distanceMeters: Double?): String {
    val km = (distanceMeters ?: 0.0) / 1000.0
    if (km < 0.05 || activeMs <= 0) return "--:-- /km"
    val secPerKm = (activeMs / 1000.0) / km
    return "%d:%02d /km".format(Locale.US, (secPerKm / 60).toInt(), (secPerKm % 60).toInt())
}

private fun hrColor(bpm: Double?): Color = when {
    bpm == null || bpm <= 0 -> MUTED
    bpm < 120 -> OK
    bpm < 150 -> WARN
    else -> ACCENT
}
