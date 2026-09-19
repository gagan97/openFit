package dev.openfit.phone.ui

import android.content.Context
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import dev.openfit.phone.ActivityStore
import dev.openfit.phone.TcxReader
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * App-local tab: the activities our watch handed to this phone, their upload state, and a manual
 * sync. Everything Dreeve-side lives in the Dreeve tabs; this is the part a browser can't do.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(baseUrl: String, onOpenSettings: () -> Unit, onMenu: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<File?>(null) }

    val sel = selected
    if (sel != null) {
        ActivityDetail(ctx, sel, refresh, onChanged = { refresh++ }) { selected = null }
        return
    }

    val files = remember(refresh) { ActivityStore.list(ctx) }
    val pending = files.count { !ActivityStore.status(ctx, it.name).startsWith("uploaded") }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Sync") },
            navigationIcon = {
                IconButton(onClick = onMenu) {
                    Icon(Icons.Default.Menu, contentDescription = "Menu")
                }
            },
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Server", style = MaterialTheme.typography.titleSmall)
                        if (baseUrl.isBlank()) {
                            Text(
                                "No Dreeve server configured yet — the watch can still record and upload on its own.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Button(onClick = onOpenSettings) { Text("Open settings") }
                        } else {
                            Text(baseUrl, style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    enabled = !busy,
                                    onClick = {
                                        busy = true
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) { ActivityStore.syncPending(ctx) }
                                            busy = false
                                            message = result
                                            refresh++
                                        }
                                    },
                                ) { Text(if (busy) "Syncing…" else "Sync $pending pending") }
                                TextButton(onClick = onOpenSettings) { Text("Settings") }
                            }
                        }
                        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            item {
                Text(
                    "Received from the watch: ${files.size} (${files.size - pending} uploaded)",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (files.isEmpty()) {
                item {
                    Text(
                        "Nothing here yet. Activities arrive from the watch when it can't upload directly.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            items(files) { f ->
                var rowBusy by remember(f.name) { mutableStateOf(false) }
                val status = remember(refresh, rowBusy) { ActivityStore.status(ctx, f.name) }
                Card(Modifier.fillMaxWidth().clickable { selected = f }) {
                    Column(Modifier.padding(10.dp)) {
                        Text(f.name, style = MaterialTheme.typography.bodyMedium)
                        Text(status, style = MaterialTheme.typography.bodySmall)
                        if (!status.startsWith("uploaded")) {
                            TextButton(
                                enabled = !rowBusy,
                                onClick = {
                                    rowBusy = true
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) { ActivityStore.upload(ctx, f) }
                                        ActivityStore.setStatus(ctx, f.name, result)
                                        rowBusy = false
                                        refresh++
                                    }
                                },
                            ) { Text(if (rowBusy) "…" else "Upload now") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityDetail(
    ctx: Context,
    file: File,
    refreshKey: Int,
    onChanged: () -> Unit,
    onBack: () -> Unit,
) {
    val parsed = remember(file) { TcxReader.parse(file) }
    val status = remember(refreshKey) { ActivityStore.status(ctx, file.name) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(file.name, style = MaterialTheme.typography.titleSmall)
        Text(status, style = MaterialTheme.typography.bodySmall)

        val stats = buildString {
            parsed.totalSeconds?.let { s -> append("time %d:%02d".format((s / 60).toInt(), (s % 60).toInt())) }
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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!status.startsWith("uploaded")) {
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { ActivityStore.upload(ctx, file) }
                            ActivityStore.setStatus(ctx, file.name, result)
                            busy = false
                            onChanged()
                        }
                    },
                ) { Text(if (busy) "…" else "Upload now") }
            }
            TextButton(onClick = onBack) { Text("← Back") }
        }
    }
}

/** OSM-based route map — no Google API key, works offline after tiles are cached. */
@Composable
fun ActivityMap(points: List<Pair<Double, Double>>, modifier: Modifier = Modifier) {
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
                    outlinePaint.color = android.graphics.Color.parseColor("#F26722")
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
