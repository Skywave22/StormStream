package com.stormstream.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.stormstream.app.data.MediaItem
import kotlinx.coroutines.delay

/**
 * Hero / featured card. Displayed at the top of Home when we have items with
 * backdrops. Auto-rotates through up to 5 featured items every 6 seconds.
 */
@Composable
fun HeroCard(
    items: List<MediaItem>,
    onItemClick: (MediaItem) -> Unit,
    onPlayClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val featured = remember(items) {
        items.filter { !it.backdropUrl.isNullOrBlank() || !it.posterUrl.isNullOrBlank() }.take(5)
    }
    if (featured.isEmpty()) return

    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(featured.size) {
        while (true) {
            delay(6_000L)
            index = (index + 1) % featured.size
        }
    }
    val item = featured[index]

    Card(
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(240.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable { onItemClick(item) },
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Box(Modifier.fillMaxSize()) {
            val img = item.backdropUrl ?: item.posterUrl
            SubcomposeAsyncImage(
                model = img,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.35f),
                                Color.Black.copy(alpha = 0.85f)
                            ),
                            startY = 100f
                        )
                    )
            )
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
            ) {
                val tag = listOfNotNull(
                    item.year?.toString(),
                    item.rating?.let { "★ ${"%.1f".format(it)}" },
                    item.type.name.lowercase().replaceFirstChar { c -> c.uppercase() }
                ).joinToString(" · ")
                Text(
                    item.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (tag.isNotBlank()) {
                    Text(
                        tag,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (!item.description.isNullOrBlank()) {
                    Text(
                        item.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.9f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Button(
                    onClick = { onPlayClick(item) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Watch now", fontWeight = FontWeight.Bold)
                }
            }

            // Pager dots
            if (featured.size > 1) {
                Row(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    featured.indices.forEach { i ->
                        Box(
                            Modifier
                                .width(if (i == index) 18.dp else 6.dp)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(
                                    if (i == index) MaterialTheme.colorScheme.primary
                                    else Color.White.copy(alpha = 0.4f)
                                )
                        )
                    }
                }
            }
        }
    }
}
