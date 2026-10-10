package com.stormstream.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.stormstream.app.data.AppViewModel
import com.stormstream.app.data.CatalogRef
import com.stormstream.app.data.MediaItem
import com.stormstream.app.data.MediaType
import com.stormstream.app.data.THEME_DARK
import com.stormstream.app.data.THEME_LIGHT
import com.stormstream.app.data.THEME_SYSTEM
import com.stormstream.app.ui.navigation.StormScreen
import com.stormstream.app.ui.screens.BrowseScreen
import com.stormstream.app.ui.screens.DetailScreen
import com.stormstream.app.ui.screens.ExtensionsScreen
import com.stormstream.app.ui.screens.HomeScreen
import com.stormstream.app.ui.screens.PlayerScreen
import com.stormstream.app.ui.screens.SearchScreen
import com.stormstream.app.ui.screens.SettingsScreen
import com.stormstream.app.ui.theme.StormTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: AppViewModel = viewModel()
            val theme by viewModel.settings.theme.collectAsState(initial = THEME_DARK)
            val accent by viewModel.settings.accent.collectAsState(initial = "blue")
            val systemDark = isSystemInDarkTheme()
            val dark = when (theme) {
                THEME_LIGHT -> false
                THEME_SYSTEM -> systemDark
                else -> true
            }
            StormTheme(darkTheme = dark, accentKey = accent) {
                StormAppRoot(viewModel = viewModel)
            }
            RequestNotificationPermission()
            HandleDeepLink(intent, viewModel)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        deepLinkHandler?.invoke(uri)
    }

    companion object {
        /** Set from the composable below so onNewIntent can reach the ViewModel. */
        @Volatile
        var deepLinkHandler: ((Uri) -> Unit)? = null
    }
}

@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < 33) return
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result not needed — playback works without the notification */ }
    LaunchedEffect(Unit) {
        // The launcher must be triggered from a composable context; fire once.
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun HandleDeepLink(intent: Intent?, viewModel: AppViewModel) {
    LaunchedEffect(intent) {
        val uri = intent?.data ?: return@LaunchedEffect
        when (uri.scheme?.lowercase()) {
            "stremio" -> viewModel.installStremio(uri.toHttpsUrl())
            "storm" -> viewModel.installJsPlugin(uri.toHttpsUrl(), name = null)
        }
    }
}

/** stremio://host/path?query -> https://host/path?query */
private fun Uri.toHttpsUrl(): String =
    "https://${host.orEmpty()}${encodedPath.orEmpty()}" +
        (encodedQuery?.let { "?$it" } ?: "")

private data class BottomTab(val screen: StormScreen, val icon: ImageVector, val label: String)

@Composable
private fun StormAppRoot(viewModel: AppViewModel) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    // Route deep links from onNewIntent through the ViewModel.
    MainActivity.deepLinkHandler = { uri ->
        when (uri.scheme?.lowercase()) {
            "stremio" -> viewModel.installStremio(uri.toHttpsUrl())
            "storm" -> viewModel.installJsPlugin(uri.toHttpsUrl(), name = null)
        }
    }

    // Show ViewModel messages as snackbars.
    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    val tabs = listOf(
        BottomTab(StormScreen.Home, Icons.Default.Home, "Home"),
        BottomTab(StormScreen.Search, Icons.Default.Search, "Search"),
        BottomTab(StormScreen.Extensions, Icons.Default.Extension, "Extensions"),
        BottomTab(StormScreen.Settings, Icons.Default.Settings, "Settings"),
    )
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val mainTabs = listOf(
        StormScreen.Home.route,
        StormScreen.Search.route,
        StormScreen.Extensions.route,
        StormScreen.Settings.route,
    )
    val showBottomBar = currentRoute in mainTabs

    val onItemClick: (MediaItem) -> Unit = { item ->
        viewModel.openItem(item)
        navController.navigate(StormScreen.Detail.route)
    }
    val onBrowseCatalog: (CatalogRef) -> Unit = { catalog ->
        navController.navigate(
            StormScreen.Browse.create(catalog.providerId, catalog.catalogId, catalog.name)
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        val selected = backStackEntry?.destination?.hierarchy?.any {
                            it.route == tab.screen.route
                        } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = StormScreen.Home.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(StormScreen.Home.route) {
                HomeScreen(
                    viewModel = viewModel,
                    onItemClick = onItemClick,
                    onBrowseCatalog = onBrowseCatalog,
                    onOpenExtensions = { navController.navigate(StormScreen.Extensions.route) },
                )
            }
            composable(StormScreen.Search.route) {
                SearchScreen(
                    viewModel = viewModel,
                    onItemClick = onItemClick,
                )
            }
            composable(StormScreen.Extensions.route) {
                ExtensionsScreen(viewModel = viewModel)
            }
            composable(StormScreen.Settings.route) {
                SettingsScreen(
                    viewModel = viewModel,
                    onOpenLogs = { navController.navigate(StormScreen.Logs.route) },
                )
            }
            composable(StormScreen.Detail.route) {
                DetailScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onPlayStart = { navController.navigate(StormScreen.Player.route) },
                )
            }
            composable(StormScreen.Player.route) {
                PlayerScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(StormScreen.Logs.route) {
                com.stormstream.app.ui.screens.LogsScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                StormScreen.Browse.route,
                arguments = listOf(
                    navArgument("providerId") { type = NavType.StringType },
                    navArgument("catalogId") { type = NavType.StringType },
                    navArgument("catalogName") { type = NavType.StringType },
                ),
            ) { entry ->
                val providerId = StormScreen.Browse.decodeProviderId(
                    entry.arguments?.getString("providerId") ?: ""
                )
                val catalogId = StormScreen.Browse.decodeCatalogId(
                    entry.arguments?.getString("catalogId") ?: ""
                )
                val catalogName = StormScreen.Browse.decodeCatalogName(
                    entry.arguments?.getString("catalogName") ?: ""
                )
                val catalog = CatalogRef(
                    providerId = providerId,
                    catalogId = catalogId,
                    name = catalogName,
                    mediaType = MediaType.UNKNOWN,
                )
                LaunchedEffect(catalog) { viewModel.openBrowse(catalog) }
                BrowseScreen(
                    catalog = catalog,
                    viewModel = viewModel,
                    onItemClick = onItemClick,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
