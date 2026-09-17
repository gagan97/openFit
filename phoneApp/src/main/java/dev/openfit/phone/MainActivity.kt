package dev.openfit.phone

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                OpenFitPhoneApp()
            }
        }
    }
}

@Composable
private fun OpenFitPhoneApp() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<File?>(null) }

    var baseUrl by remember {
        mutableStateOf(
            ActivityStore.prefs(ctx).getString(ActivityStore.KEY_BASE_URL, ActivityStore.DEFAULT_BASE_URL) ?: ""
        )
    }
    var apiKey by remember {
        mutableStateOf(ActivityStore.prefs(ctx).getString(ActivityStore.KEY_API_KEY, "") ?: "")
    }

    val sel = selected
    if (sel != null) {
        ActivityDetail(ctx, sel) { selected = null }
        return
    }

    val files = remember(refresh) { ActivityStore.list(ctx) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("openFit", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Activities recorded on your Galaxy Watch arrive here and upload to your Dreeve instance. Tap one to see its map.",
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Dreeve URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API key (drv_…)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                ActivityStore.prefs(ctx).edit()
                    .putString(ActivityStore.KEY_BASE_URL, baseUrl.trim())
                    .putString(ActivityStore.KEY_API_KEY, apiKey.trim())
                    .apply()
                message = "saved"
            }) { Text("Save") }

            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        withContext(Dispatchers.IO) { ActivityStore.syncPending(ctx) }
                        busy = false
                        message = "sync done"
                        refresh++
                    }
                },
            ) { Text(if (busy) "Syncing…" else "Sync pending") }
        }

        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        Text("Received: ${files.size}", style = MaterialTheme.typography.titleSmall)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(files) { f ->
                ActivityRow(ctx, f, refresh, onOpen = { selected = f }, onChanged = { refresh++ })
            }
        }
    }
}

@Composable
private fun ActivityRow(
    ctx: Context,
    f: File,
    refreshKey: Int,
    onOpen: () -> Unit,
    onChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val status = remember(refreshKey, busy) { ActivityStore.status(ctx, f.name) }

    Card(modifier = Modifier.fillMaxWidth().clickable { onOpen() }) {
        Column(Modifier.padding(10.dp)) {
            Text(f.name, style = MaterialTheme.typography.bodyMedium)
            Text(status, style = MaterialTheme.typography.bodySmall)
            if (!status.startsWith("uploaded")) {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { ActivityStore.upload(ctx, f) }
                            ActivityStore.setStatus(ctx, f.name, result)
                            busy = false
                            onChanged()
                        }
                    },
                ) { Text(if (busy) "…" else "Upload now") }
            }
        }
    }
}

@Composable
private fun ActivityDetail(ctx: Context, file: File, onBack: () -> Unit) {
    val parsed = remember(file) { TcxReader.parse(file) }
    val status = ActivityStore.status(ctx, file.name)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(file.name, style = MaterialTheme.typography.titleSmall)
        Text(status, style = MaterialTheme.typography.bodySmall)

        val stats = buildString {
            parsed.totalSeconds?.let { s ->
                append("time %d:%02d".format((s / 60).toInt(), (s % 60).toInt()))
            }
            parsed.distanceMeters?.let { d ->
                if (isNotEmpty()) append(" · ")
                append("%.2f km".format(d / 1000.0))
            }
            parsed.avgHr?.let {
                if (isNotEmpty()) append(" · ")
                append("avg HR $it")
            }
            parsed.maxHr?.let { append(" / max $it") }
        }
        if (stats.isNotEmpty()) Text(stats, style = MaterialTheme.typography.bodyMedium)

        if (parsed.points.size >= 2) {
            ActivityMap(parsed.points, Modifier.fillMaxWidth().height(320.dp))
        } else {
            Text("No GPS track in this file.", style = MaterialTheme.typography.bodySmall)
        }

        TextButton(onClick = onBack) { Text("← Back") }
    }
}

/** OSM-based route map — no Google API key, works offline after tiles are cached. */
@Composable
private fun ActivityMap(points: List<Pair<Double, Double>>, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { c ->
            Configuration.getInstance().userAgentValue = c.packageName
            MapView(c).apply {
                setMultiTouchControls(true)
                minZoomLevel = 3.0
            }
        },
        update = { mv ->
            mv.overlays.clear()
            val geos = points.map { GeoPoint(it.first, it.second) }
            if (geos.size >= 2) {
                val line = Polyline().apply {
                    setPoints(geos)
                    outlinePaint.color = android.graphics.Color.parseColor("#4285F4")
                    outlinePaint.strokeWidth = 8f
                }
                mv.overlays.add(line)
                mv.overlays.add(Marker(mv).apply { position = geos.first(); title = "Start" })
                mv.overlays.add(Marker(mv).apply { position = geos.last(); title = "End" })
                mv.controller.setCenter(geos.first())
                mv.controller.setZoom(17.0)
            }
            mv.invalidate()
        },
    )
}
