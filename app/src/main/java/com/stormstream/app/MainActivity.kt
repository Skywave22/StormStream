package com.stormstream.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.stormstream.app.data.AppViewModel
import com.stormstream.app.data.CatalogRef
import com.stormstream.app.data.Episode
import com.stormstream.app.data.MediaItem
import com.stormstream.app.ui.navigation.StormScreen
import com.stormstream.app.ui.screens.*
import com.stormstream.app.ui.theme.StormTheme
import kotlinx.coroutines.flow.collectLatest

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
        BottomTab(StormScreen.Extensions, Icons.Default.Extension, "Ext"),
        BottomTab(StormScreen.Settings, Icons.Default.Settings, "Settings"),
    )
    val viewModel: AppViewModel = viewModel()
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.errors.collectLatest { msg ->
            snackbarHost.showSnackbar(message = msg, duration = SnackbarDuration.Short)
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isPlayer = currentRoute == StormScreen.Player.route

    val onItemClick: (MediaItem) -> Unit = { item ->
        viewModel.openItem(item)
        navController.navigate(StormScreen.Detail.route)
    }

    val onPlay: (MediaItem, Episode?, Int) -> Unit = { item, ep, streamIdx ->
        viewModel.startPlayback(item, ep, streamIdx)
        navController.navigate(StormScreen.Player.route)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (!isPlayer) {
                GlassBottomBar(navController, tabs, backStackEntry)
            }
        }
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding)) {
            NavHost(
                navController = navController,
                startDestination = StormScreen.Home.route,
            ) {
                composable(StormScreen.Home.route) {
                    HomeScreen(
                        onOpenItem = onItemClick,
                        onSearch = { navController.navigate(StormScreen.Search.route) },
                        onExtensions = { navController.navigate(StormScreen.Extensions.route) },
                        onSettings = { navController.navigate(StormScreen.Settings.route) },
                        onMore = { _ -> },
                        viewModel = viewModel
                    )
                }
                composable(StormScreen.Search.route) {
                    SearchScreen(onItemClick = onItemClick, viewModel = viewModel)
                }
                composable(StormScreen.Extensions.route) {
                    ExtensionsScreen(
                        onBack = { navController.popBackStack() },
                        viewModel = viewModel
                    )
                }
                composable(StormScreen.Settings.route) {
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
                        viewModel = viewModel
                    )
                }
                composable(StormScreen.Detail.route) {
                    DetailScreen(
                        onPlay = onPlay,
                        onBack = { navController.popBackStack() },
                        viewModel = viewModel
                    )
                }
                composable(StormScreen.Player.route) {
                    PlayerScreen(
                        onBack = {
                            viewModel.clearPlayback()
                            navController.popBackStack()
                        },
                        viewModel = viewModel
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassBottomBar(
    navController: NavHostController,
    tabs: List<BottomTab>,
    backStackEntry: androidx.navigation.NavBackStackEntry?,
) {
    val noIndicationInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, MaterialTheme.colorScheme.background)
                )
            )
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 10.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            tonalElevation = 10.dp,
            shadowElevation = 16.dp,
            modifier = Modifier.fillMaxWidth().height(64.dp)
        ) {
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                tabs.forEach { tab ->
                    val selected = backStackEntry?.destination?.hierarchy?.any {
                        it.route == tab.screen.route
                    } == true
                    val color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (selected) color.copy(alpha = 0.16f) else Color.Transparent)
                            .clickableNoIndication(noIndicationInteraction) {
                                navController.navigate(tab.screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        androidx.compose.foundation.layout.Column(
                            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                        ) {
                            androidx.compose.material3.Icon(
                                tab.icon, contentDescription = tab.label,
                                tint = color, modifier = Modifier.size(22.dp)
                            )
                            Text(
                                tab.label,
                                color = color,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold
                                else androidx.compose.ui.text.font.FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.clickableNoIndication(
    interactionSource: androidx.compose.foundation.interaction.MutableInteractionSource,
    onClick: () -> Unit
) = this.then(
    androidx.compose.foundation.clickable(
        interactionSource = interactionSource,
        indication = null,
        onClick = onClick
    )
)
