package bw.agriconnect.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bw.agriconnect.R
import bw.agriconnect.ui.theme.AgriColors
import bw.agriconnect.ui.theme.AgriRadius
import bw.agriconnect.ui.theme.AgriSpacing

/**
 * The AgriConnect brand lockup. Uses the supplied logo asset (a leaf/growth mark
 * with a connection motif) — the same mark used for the launcher icon and splash.
 */
@Composable
fun BrandLogo(
    modifier: Modifier = Modifier,
    markSize: Dp = 40.dp,
    showWordmark: Boolean = true,
    darkContent: Boolean = false,
) {
    val titleColor = if (darkContent) Color.White else MaterialTheme.colorScheme.onBackground
    val subtitleColor =
        if (darkContent) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier.semantics { contentDescription = "AgriConnect" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AgriSpacing.md),
    ) {
        Box(
            modifier = Modifier
                .size(markSize)
                .clip(RoundedCornerShape(AgriRadius.md))
                .background(AgriColors.WarmWhite),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(id = R.drawable.agri_logo_mark),
                contentDescription = null,
                modifier = Modifier.size(markSize).padding(4.dp),
            )
        }
        if (showWordmark) {
            Column {
                Text(
                    text = "AgriConnect",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = titleColor,
                    letterSpacing = (-0.3).sp,
                )
                Text(
                    text = "Farm · Market · Satellite",
                    style = MaterialTheme.typography.labelSmall,
                    color = subtitleColor,
                )
            }
        }
    }
}

/** Compact mark without the wordmark — for app bars and list avatars. */
@Composable
fun BrandMark(size: Dp = 28.dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(id = R.drawable.agri_logo_mark),
        contentDescription = "AgriConnect",
        modifier = modifier.size(size),
    )
}
