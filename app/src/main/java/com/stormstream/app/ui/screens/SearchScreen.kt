package com.stormstream.app.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stormstream.app.data.AppViewModel
import com.stormstream.app.data.MediaItem
import com.stormstream.app.ui.components.PosterCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: AppViewModel = viewModel(),
    onItemClick: (MediaItem) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val results by viewModel.searchResults.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; viewModel.search(it) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text("Search movies, shows, channels…") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
            )
            if (query.isBlank()) {
                Spacer(Modifier.height(24.dp))
                Text("Start typing to search across all installed providers.",
                    modifier = Modifier.padding(horizontal = 24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(120.dp),
                    contentPadding = PaddingValues(8.dp),
                ) {
                    items(results, key = { it.id + "@" + it.providerId }) { item ->
                        PosterCard(item = item, onClick = { onItemClick(item) })
                    }
                }
            }
        }
    }
}
