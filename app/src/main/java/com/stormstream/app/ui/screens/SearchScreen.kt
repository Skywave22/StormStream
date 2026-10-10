package com.stormstream.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stormstream.app.data.AppViewModel
import com.stormstream.app.data.MediaItem
import com.stormstream.app.ui.components.CenteredLoading
import com.stormstream.app.ui.components.EmptyState
import com.stormstream.app.ui.components.MediaGrid
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: AppViewModel = viewModel(),
    onItemClick: (MediaItem) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val results by viewModel.searchResults.collectAsState()
    val searching by viewModel.searching.collectAsState()

    // Debounce the search-as-you-type.
    LaunchedEffect(query) {
        delay(400)
        viewModel.search(query)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text("Movies, series, channels…") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            when {
                query.isBlank() -> EmptyState(
                    title = "Search everything",
                    message = "Results from all your installed sources appear here.",
                    modifier = Modifier.padding(top = 80.dp),
                )
                searching && results.isEmpty() -> CenteredLoading(modifier = Modifier.padding(top = 80.dp))
                results.isEmpty() -> EmptyState(
                    title = "No results",
                    message = "Nothing found for \"$query\". Try another source or wording.",
                    modifier = Modifier.padding(top = 80.dp),
                )
                else -> MediaGrid(
                    items = results,
                    onItemClick = onItemClick,
                    providerName = { viewModel.providerName(it.providerId) },
                    contentPadding = PaddingValues(8.dp),
                )
            }
        }
    }
}
