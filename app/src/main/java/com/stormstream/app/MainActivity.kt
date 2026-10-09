package com.stormstream.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.stormstream.app.data.AppViewModel
import com.stormstream.app.data.MediaItem
import com.stormstream.app.ui.navigation.StormScreen
import com.stormstream.app.ui.screens.*
import com.stormstream.app.ui.theme.StormTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StormTheme {
                StormAppRoot()
            }
        }
    }
}

private data class BottomTab(val screen: StormScreen, val icon: ImageVector, val label: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StormAppRoot() {
    val navController = rememberNavController()
    val tabs = listOf(
        BottomTab(StormScreen.Home, Icons.Default.Home, "Home"),
        BottomTab(StormScreen.Search, Icons.Default.Search, "Search"),
        BottomTab(StormScreen.Extensions, Icons.Default.Extension, "Extensions"),
        BottomTab(StormScreen.Settings, Icons.Default.Settings, "Settings"),
    )
    val viewModel: AppViewModel = viewModel()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in listOf(
        StormScreen.Home.route,
        StormScreen.Search.route,
        StormScreen.Extensions.route,
        StormScreen.Settings.route,
    )

    // When an item is clicked, stash it in the VM (cross-screen state) and
    // navigate to detail. Because ViewModel is activity-scoped, the detail
    // screen reads the selected item directly.
    val onItemClick: (MediaItem) -> Unit = { item ->
        viewModel.openItem(item)
        navController.navigate(StormScreen.Detail.create("item"))
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
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
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = StormScreen.Home.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(StormScreen.Home.route) {
                HomeScreen(viewModel = viewModel, onItemClick = onItemClick)
            }
            composable(StormScreen.Search.route) {
                SearchScreen(viewModel = viewModel, onItemClick = onItemClick)
            }
            composable(StormScreen.Extensions.route) {
                ExtensionsScreen(viewModel = viewModel)
            }
            composable(StormScreen.Settings.route) {
                SettingsScreen(
                    onRefresh = { viewModel.refreshHome() }
                )
            }
            composable(StormScreen.Detail.route,
                arguments = listOf(navArgument(StormScreen.Detail.ARG_ITEM_ID) {
                    type = NavType.StringType
                })
            ) {
                DetailScreen(
                    viewModel = viewModel,
                    onPlay = { _, _ ->
                        navController.navigate(StormScreen.Player.create("item", "null"))
                    }
                )
            }
            composable(StormScreen.Player.route) {
                PlayerScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
