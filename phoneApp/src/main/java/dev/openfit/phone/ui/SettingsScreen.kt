package dev.openfit.phone.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import dev.openfit.phone.ActivityStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Server + app preferences. Everything the app needs to talk to the user's own Dreeve instance. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themeMode: String,
    onThemeChange: (String) -> Unit,
    onMenu: () -> Unit,
    onSaved: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = ActivityStore.prefs(ctx)

    var baseUrl by remember { mutableStateOf(prefs.getString(ActivityStore.KEY_BASE_URL, "") ?: "") }
    var apiKey by remember { mutableStateOf(prefs.getString(ActivityStore.KEY_API_KEY, "") ?: "") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = onMenu) {
                    Icon(Icons.Default.Menu, contentDescription = "Menu")
                }
            },
        )
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Dreeve server", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Your own instance. The app reads its pages and uploads activities with your API key.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("Server URL") },
                        placeholder = { Text("https://stats.example.com") },
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
                            prefs.edit()
                                .putString(ActivityStore.KEY_BASE_URL, baseUrl.trim().trimEnd('/'))
                                .putString(ActivityStore.KEY_API_KEY, apiKey.trim())
                                .apply()
                            message = "saved"
                            onSaved()
                        }) { Text("Save") }
                        Button(
                            enabled = !busy,
                            onClick = {
                                prefs.edit()
                                    .putString(ActivityStore.KEY_BASE_URL, baseUrl.trim().trimEnd('/'))
                                    .putString(ActivityStore.KEY_API_KEY, apiKey.trim())
                                    .apply()
                                busy = true
                                scope.launch {
                                    message = withContext(Dispatchers.IO) { ActivityStore.testConnection(ctx) }
                                    busy = false
                                    onSaved()
                                }
                            },
                        ) { Text(if (busy) "Testing…" else "Test connection") }
                    }
                    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = {
                        val url = baseUrl.trim().trimEnd('/')
                        if (url.isNotEmpty()) {
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$url/admin"))) }
                        }
                    }) { Text("Open Dreeve admin in browser") }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Appearance", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (value, label) ->
                            FilterChip(
                                selected = themeMode == value,
                                onClick = { onThemeChange(value) },
                                label = { Text(label) },
                            )
                        }
                    }
                    Text(
                        "The Dreeve pages follow this setting too.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                val files = remember { ActivityStore.list(ctx) }
                val pending = files.count { !ActivityStore.status(ctx, it.name).startsWith("uploaded") }
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Diagnostics", style = MaterialTheme.typography.titleSmall)
                    Text("received activities: ${files.size}", style = MaterialTheme.typography.bodySmall)
                    Text("pending uploads: $pending", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "watch uploads go straight to Dreeve; this queue is the fallback path",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("openFit companion", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Pairs with the openFit Wear OS recorder. MIT licensed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
