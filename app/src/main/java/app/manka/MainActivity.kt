package app.manka

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.manka.core.Engine
import app.manka.ui.MainViewModel
import app.manka.ui.screens.AppsScreen
import app.manka.ui.screens.AutoSelectScreen
import app.manka.ui.screens.HistoryRunScreen
import app.manka.ui.screens.HistoryScreen
import app.manka.ui.screens.HomeScreen
import app.manka.ui.screens.LogsScreen
import app.manka.ui.screens.PresetsScreen
import app.manka.ui.screens.SettingsScreen
import app.manka.ui.screens.StoreScreen
import app.manka.ui.screens.TelegramScreen
import app.manka.ui.theme.MankaTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MankaTheme {
                MankaNav(vm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refresh()
    }
}

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", R.string.tab_home, Icons.Filled.Home),
    Tab("auto", R.string.tab_auto, Icons.Filled.AutoFixHigh),
    Tab("store", R.string.tab_store, Icons.Filled.Storefront),
    Tab("settings", R.string.tab_settings, Icons.Filled.Settings),
)

@Composable
private fun MankaNav(vm: MainViewModel) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val topLevel = tabs.any { it.route == route }

    Scaffold(
        contentWindowInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (topLevel) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { nav.switchTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(padding)) {
            composable("home") { HomeScreen(vm, nav) }
            composable("auto") { AutoSelectScreen(vm, nav) }
            composable("store") { StoreScreen(vm) }
            composable("settings") { SettingsScreen(vm, nav) }
            composable("telegram") { TelegramScreen(vm) { nav.popBackStack() } }
            composable("apps") { AppsScreen(vm) { nav.popBackStack() } }
            composable("logs") { LogsScreen(vm) { nav.popBackStack() } }
            composable(
                "presets/{engine}/{net}",
                arguments = listOf(
                    navArgument("engine") { type = NavType.StringType },
                    navArgument("net") { type = NavType.StringType },
                ),
            ) { e ->
                val engine = Engine.of(e.arguments?.getString("engine")) ?: Engine.BYEDPI
                val profile = e.arguments?.getString("net") ?: "wifi"
                PresetsScreen(vm, engine, profile) { nav.popBackStack() }
            }
            composable("history") { HistoryScreen(vm, nav) { nav.popBackStack() } }
            composable(
                "history/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { e ->
                HistoryRunScreen(vm, e.arguments?.getString("id").orEmpty()) { nav.popBackStack() }
            }
        }
    }
}

fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
