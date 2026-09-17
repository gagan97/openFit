package dev.openfit.wear

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private val S_ACCENT = Color(0xFFFF6D3A)
private val S_CAPSULE = Color(0xFF2B2C30)
private val S_MUTED = Color(0xFF9AA0A6)
private val S_OK = Color(0xFF9BE15D)
private val S_WARN = Color(0xFFFFC24B)

/**
 * Settings: upload queue (Sync now), display units, auto-pause.
 * Files are never dropped silently — anything approved but not yet on Dreeve shows here and in
 * the queue count until the upload confirms.
 */
@Composable
fun SettingsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val sync by SyncManager.state.collectAsState()
    val bus by RecorderBus.state.collectAsState()
    var imperial by remember { mutableStateOf(WearPrefs.unitsImperial(context)) }
    var autoPause by remember { mutableStateOf(WearPrefs.autoPauseEnabled(context)) }
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }
    val configured = WearPrefs.base(context) != null && WearPrefs.apiKey(context) != null

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.h6, color = Color.White, modifier = Modifier.padding(top = 6.dp))

        // --- upload queue -------------------------------------------------------
        Text(
            when {
                sync.running -> "syncing ${sync.progress}/${sync.total}…"
                bus.pending > 0 -> "${bus.pending} waiting to upload"
                else -> "all activities uploaded"
            },
            style = MaterialTheme.typography.caption,
            color = when {
                sync.running -> Color.White
                bus.pending > 0 -> S_WARN
                else -> S_OK
            },
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = { SyncManager.syncNow(context) },
            enabled = !sync.running,
            modifier = Modifier.height(44.dp).fillMaxWidth(0.72f),
            colors = ButtonDefaults.buttonColors(backgroundColor = S_ACCENT, contentColor = Color.White),
        ) { Text(if (sync.running) "Syncing…" else "Sync now") }
        sync.message?.let {
            Text(it, style = MaterialTheme.typography.caption, color = S_MUTED, textAlign = TextAlign.Center)
        }
        if (!configured) {
            Text("Dreeve not configured", style = MaterialTheme.typography.caption, color = S_WARN)
        }

        // --- display units ------------------------------------------------------
        SettingsRow(
            label = "Units",
            value = if (imperial) "Imperial" else "Metric",
        ) {
            imperial = !imperial
            WearPrefs.setUnitsImperial(context, imperial)
        }

        // --- auto-pause ---------------------------------------------------------
        SettingsRow(
            label = "Auto-pause",
            value = if (autoPause) "On" else "Off",
        ) {
            autoPause = !autoPause
            WearPrefs.setAutoPauseEnabled(context, autoPause)
        }
        Text(
            "walk · run · ride — pauses when you stop, resumes when you move",
            style = MaterialTheme.typography.caption,
            color = S_MUTED,
            textAlign = TextAlign.Center,
        )

        if (bus.review > 0) {
            Text("${bus.review} waiting for review", style = MaterialTheme.typography.caption, color = S_WARN)
        }
        Text(
            "recordings stay on the watch until uploaded — only Delete removes them",
            style = MaterialTheme.typography.caption,
            color = S_MUTED,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(4.dp))
        Text("openFit $version · ${Build.MODEL}", style = MaterialTheme.typography.caption, color = S_MUTED)
        TextButton(onClick = onClose) { Text("Back", color = S_MUTED) }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SettingsRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(50))
            .background(S_CAPSULE)
            .clickable { onClick() }
            .padding(horizontal = 14.dp),
    ) {
        Text(label, style = MaterialTheme.typography.body2, color = Color.White)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.body2, color = S_ACCENT)
    }
}
