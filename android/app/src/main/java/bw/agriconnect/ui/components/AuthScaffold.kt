package bw.agriconnect.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import bw.agriconnect.R
import bw.agriconnect.ui.theme.AgriColors
import bw.agriconnect.ui.theme.AgriSpacing

/**
 * The shared backdrop for the account *setup* journey (welcome, sign-in, register,
 * password reset).
 *
 * It paints a full-screen agricultural photograph behind a dark-green gradient
 * scrim, then centres its [content] in a readable card. The scrim is not decoration:
 * without it, form labels over a busy photograph fail contrast (docs/01 design
 * system), so the gradient is tuned dark enough for the card and any text to sit on
 * top legibly in both light and dark themes.
 *
 * The photograph fills behind the status/navigation bars (edge-to-edge); the content
 * respects the system insets via [safeDrawingPadding].
 *
 * @param card when true (default) the content is wrapped in a surface card — use it
 *   for forms. Pass false for hero content that should sit directly on the scrim.
 */
@Composable
fun AuthScaffold(
    modifier: Modifier = Modifier,
    card: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.auth_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to AgriColors.DeepGreen.copy(alpha = 0.86f),
                        0.45f to AgriColors.ForestGreen.copy(alpha = 0.58f),
                        1.0f to AgriColors.DeepGreen.copy(alpha = 0.90f),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AgriSpacing.xl, vertical = AgriSpacing.xxl),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (card) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                    shadowElevation = 10.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(AgriSpacing.xl),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        content = content,
                    )
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    content = content,
                )
            }
        }
    }
}
