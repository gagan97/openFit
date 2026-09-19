package dev.openfit.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.openfit.phone.ui.AppDest
import dev.openfit.phone.ui.BOTTOM_ROOTS
import dev.openfit.phone.ui.DRAWER_ITEMS
import dev.openfit.phone.ui.Destination
import dev.openfit.phone.ui.DreeveDest
import dev.openfit.phone.ui.OpenFitTheme
import dev.openfit.phone.ui.SettingsScreen
import dev.openfit.phone.ui.SyncScreen
import dev.openfit.phone.ui.WebScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OpenFitApp() }
    }
}

@Composable
private fun OpenFitApp() {
    val ctx = LocalContext.current
    val prefs = ActivityStore.prefs(ctx)
    var themeMode by remember {
        mutableStateOf(prefs.getString(ActivityStore.KEY_THEME, "system") ?: "system")
    }
    var configVersion by remember { mutableStateOf(0) }
    val baseUrl = remember(configVersion) {
        (prefs.getString(ActivityStore.KEY_BASE_URL, "") ?: "").trim().trimEnd('/')
    }
    val dark = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }

    OpenFitTheme(themeOverride = themeMode) {
        AppShell(
            baseUrl = baseUrl,
            dark = dark,
            themeMode = themeMode,
            onThemeChange = {
                themeMode = it
                prefs.edit().putString(ActivityStore.KEY_THEME, it).apply()
            },
            onConfigChanged = { configVersion++ },
        )
    }
}

/**
 * Navigation shell: a bottom bar for the four everyday destinations and a drawer for the rest of
 * Dreeve's own screens (each opens as its own tab). Screens are pushed on a small back stack.
 */
@Composable
private fun AppShell(
    baseUrl: String,
    dark: Boolean,
    themeMode: String,
    onThemeChange: (String) -> Unit,
    onConfigChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var stack by remember { mutableStateOf(listOf<Destination>(Destination.Dreeve(DreeveDest.Dashboard))) }
    val current = stack.last()
    val openMenu: () -> Unit = { scope.launch { drawerState.open() } }

    BackHandler(enabled = stack.size > 1) { stack = stack.dropLast(1) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("openFit", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "companion for your Dreeve instance",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DRAWER_ITEMS.forEach { d ->
                    NavigationDrawerItem(
                        label = { Text(d.title) },
                        selected = d == current,
                        onClick = {
                            stack = listOf(d)
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    )
                }
            }
        },
    ) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    BOTTOM_ROOTS.forEach { d ->
                        NavigationBarItem(
                            selected = current == d,
                            onClick = { stack = listOf(d) },
                            icon = { Icon(rootIcon(d), contentDescription = d.title) },
                            label = { Text(d.title) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (current) {
                    is Destination.App -> when (current.dest) {
                        AppDest.Sync -> SyncScreen(
                            baseUrl = baseUrl,
                            onOpenSettings = { stack = listOf(Destination.App(AppDest.Settings)) },
                            onMenu = openMenu,
                        )

                        AppDest.Settings -> SettingsScreen(
                            themeMode = themeMode,
                            onThemeChange = onThemeChange,
                            onMenu = openMenu,
                            onSaved = onConfigChanged,
                        )
                    }

                    is Destination.Dreeve -> if (baseUrl.isBlank()) {
                        NotConfigured(onOpenSettings = { stack = listOf(Destination.App(AppDest.Settings)) })
                    } else {
                        // key() gives each Dreeve screen its own WebView (fresh history per tab)
                        key(current.dest.path) {
                            WebScreen(
                                baseUrl = baseUrl,
                                path = current.dest.path,
                                title = current.dest.title,
                                dark = dark,
                                onMenu = openMenu,
                                onExit = { if (stack.size > 1) stack = stack.dropLast(1) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotConfigured(onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Point openFit at your Dreeve instance", style = MaterialTheme.typography.titleMedium)
        Text(
            "Add your server URL and API key once — then the dashboard, activities, segments and the " +
                "rest of your stats appear here. The watch keeps recording either way.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onOpenSettings) { Text("Open settings") }
    }
}

private fun rootIcon(d: Destination): ImageVector = when (d) {
    is Destination.Dreeve -> if (d.dest == DreeveDest.Activities) Icons.Default.List else Icons.Default.Home
    is Destination.App -> if (d.dest == AppDest.Sync) Icons.Default.Refresh else Icons.Default.Settings
}
