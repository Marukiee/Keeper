package nl.markmaaktmedia.keeper.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import nl.markmaaktmedia.keeper.graph
import nl.markmaaktmedia.keeper.scan.MediaItem

/** Voorbeeld van een foto. Zolang Android er nog geen heeft, een rustig vlak. */
@Composable
fun PhotoThumb(item: MediaItem, edge: Int, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val scanner = LocalContext.current.graph.scanner
    val bitmap: ImageBitmap? by produceState<ImageBitmap?>(scanner.cached(item, edge)?.asImageBitmap(), item.id, edge) {
        value = scanner.thumbnail(item, edge)?.asImageBitmap()
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = contentScale) }
    }
}
