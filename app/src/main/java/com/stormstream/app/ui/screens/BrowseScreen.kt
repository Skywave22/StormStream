package com.stormstream.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stormstream.app.data.AppViewModel
import com.stormstream.app.data.CatalogRef
import com.stormstream.app.data.MediaItem
import com.stormstream.app.ui.components.EmptyState
import com.stormstream.app.ui.components.ErrorCard
import com.stormstream.app.ui.components.MediaGrid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    catalog: CatalogRef,
    viewModel: AppViewModel = viewModel(),
    onItemClick: (MediaItem) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.browse.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(catalog.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state == null || (state.items.isEmpty() && state.isLoading) -> {
                    com.stormstream.app.ui.components.CenteredLoading()
                }
                state.items.isEmpty() && state.error != null -> {
                    ErrorCard(message = state.error, onRetry = { viewModel.loadBrowsePage() })
                }
                state.items.isEmpty() -> {
                    EmptyState(
                        title = "Nothing here",
                        message = "This catalog has no items.",
                    )
                }
                else -> MediaGrid(
                    items = state.items,
                    onItemClick = onItemClick,
                    providerName = { viewModel.providerName(it.providerId) },
                    isLoadingMore = state.isLoading,
                    onLoadMore = { viewModel.loadBrowsePage() },
                )
            }
        }
    }
}
