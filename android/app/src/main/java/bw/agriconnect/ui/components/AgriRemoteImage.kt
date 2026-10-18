package bw.agriconnect.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import bw.agriconnect.core.util.ImageUrls
import bw.agriconnect.ui.theme.AgriColors
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest

/**
 * Renders an image uploaded by the signed-in user or by another user.
 *
 * [url] may be an origin-relative backend path (`/uploads/<uuid>.jpg`), an absolute URL,
 * or a device-local `content://` URI picked but not yet uploaded; [ImageUrls.resolve]
 * normalises it. When there is nothing to show — or loading fails — a neutral
 * placeholder is drawn instead, so a missing photo never leaves a hole in a card.
 *
 * The caller controls the size and clipping via [modifier] (e.g.
 * `Modifier.size(72.dp).clip(RoundedCornerShape(AgriRadius.md))`).
 */
@Composable
fun AgriRemoteImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val model = ImageUrls.resolve(url)
    if (model == null) {
        ImagePlaceholder(modifier)
        return
    }
    SubcomposeAsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(model)
            .crossfade(true)
            .build(),
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier,
        loading = { ImagePlaceholder(Modifier.fillMaxSize()) },
        error = { ImagePlaceholder(Modifier.fillMaxSize()) },
    )
}

@Composable
private fun ImagePlaceholder(modifier: Modifier) {
    Box(
        modifier = modifier.background(AgriColors.DisabledBg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Image,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
    }
}
