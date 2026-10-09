package com.stormstream.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.SubcomposeAsyncImage
import com.stormstream.app.data.*
import com.stormstream.app.ui.components.MediaCard
import com.stormstream.app.ui.components.PosterCard
import com.stormstream.app.data.CatalogRef
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenItem: (MediaItem) -> Unit,
    onSearch: () -> Unit,
    onExtensions: () -> Unit,
    onSettings: () -> Unit,
    onMore: (CatalogRef) -> Unit,
    viewModel: AppViewModel = viewModel(),
) {
    val homeState by viewModel.homeState.collectAsState()
    val homeLoading by viewModel.homeLoading.collectAsState()
    val continueWatching by viewModel.continueWatching.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val providers by viewModel.providers.collectAsState()

    val catalogs = homeState.keys.toList()
    val activeCat = catalogs.firstOrNull()

    // Pick a hero from the first non-empty catalog
    val heroPool = remember(homeState) {
        homeState.values.flatten().filter { it.posterUrl != null || it.backdropUrl != null }.distinctBy { it.id }
    }
    val heroIndex = remember { mutableStateOf(0) }
    LaunchedEffect(heroPool.size) {
        while (heroPool.isNotEmpty()) {
            delay(7_000L)
            heroIndex.value = (heroIndex.value + 1) % heroPool.size
        }
    }
    val hero = heroPool.getOrNull(heroIndex.value)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("⛈", fontSize = 20.sp)
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("StormStream", fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
                    }
                },
                actions = {
                    IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "Search") }
                    IconButton(onClick = onExtensions) { Icon(Icons.Default.Extension, "Extensions") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { innerPadding ->
        if (homeLoading && homeState.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(bottom = 100.dp)
        ) {
            // Hero banner
            item {
                Hero(
                    item = hero,
                    onPlay = { hero?.let { h ->
                        viewModel.fetchDetail(h)
                        viewModel.fetchStreams(h)
                        onOpenItem(h)
                    }},
                    onOpen = { hero?.let(onOpenItem) }
                )
            }

            // Continue watching
            if (continueWatching.isNotEmpty()) {
                item {
                    SectionHeader(title = "Continue Watching", icon = Icons.Default.PlayCircle)
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        continueWatching.forEach { entry ->
                            ContinueCard(entry, onClick = {
                                onOpenItem(entry.item)
                            }, onRemove = { viewModel.removeHistory(entry.key) })
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }

            // Bookmarks
            if (bookmarks.isNotEmpty()) {
                item {
                    SectionHeader(title = "My List", icon = Icons.Default.Bookmark)
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        bookmarks.forEach { entry ->
                            PosterCard(entry.item, onClick = { onOpenItem(entry.item) })
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }

            // Catalogs
            if (activeCat != null) {
                val rows = homeState.entries.toList()
                itemsIndexed(rows) { idx, (ref, items) ->
                    if (items.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        ) {
                            Text(
                                ref.label,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            TextButton(onClick = { onMore(ref) }) {
                                Text("See all")
                            }
                        }
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items.take(18).forEach { m ->
                                MediaCard(m, onClick = { onOpenItem(m) })
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                    }
                }
            }

            if (homeState.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Extension, contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "No sources installed",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "Add Stremio addons, scrapers, or plugin repos in Extensions.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 32.dp)
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = onExtensions) { Text("Open Extensions") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hero(
    item: MediaItem?,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
) {
    if (item == null) {
        Box(Modifier.fillMaxWidth().height(280.dp).background(MaterialTheme.colorScheme.surfaceVariant))
        return
    }

    val backdrop = item.backdropUrl ?: item.posterUrl
    Box(
        Modifier
            .fillMaxWidth()
            .height(380.dp)
            .clickable { onOpen() }
    ) {
        SubcomposeAsyncImage(
            model = backdrop,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            loading = { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) }
        )
        // Gradient overlay
        Box(Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(
                listOf(Color.Transparent, MaterialTheme.colorScheme.background.copy(alpha = 0.5f), MaterialTheme.colorScheme.background),
                startY = 200f
            ))
        )

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    item.type.name.replaceFirstChar { it.uppercase() },
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                item.title,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (item.year != null || item.rating != null) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    item.rating?.let {
                        Icon(Icons.Default.Star, null, tint = Color(0xFFFFD700), modifier = Modifier.size(16.dp))
                        Text(
                            String.format("%.1f", it),
                            color = Color.White,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(start = 4.dp, end = 12.dp)
                        )
                    }
                    item.year?.let { Text("$it", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp) }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row {
                Button(
                    onClick = onPlay,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Play", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(
                    onClick = onOpen,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Icon(Icons.Default.Info, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Details")
                }
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ContinueCard(entry: HistoryEntry, onClick: () -> Unit, onRemove: () -> Unit) {
    Box(
        Modifier
            .width(180.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .clickable { onClick() }
    ) {
        Column {
            SubcomposeAsyncImage(
                model = entry.item.backdropUrl ?: entry.item.posterUrl,
                contentDescription = entry.item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
            )
            LinearProgressIndicator(
                progress = {
                    val d = entry.durationMs.toFloat()
                    if (d <= 0f) 0f else (entry.positionMs.toFloat() / d).coerceIn(0f, 1f)
                },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
            )
            Text(
                entry.item.title,
                modifier = Modifier.padding(8.dp),
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier.align(Alignment.TopEnd).size(28.dp).padding(4.dp)
        ) {
            Icon(Icons.Default.Close, "Remove", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
        }
    }
}
