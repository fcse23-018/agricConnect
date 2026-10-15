package bw.agriconnect.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import bw.agriconnect.ui.components.AgriPrimaryButton
import bw.agriconnect.ui.theme.AgriSpacing
import kotlinx.coroutines.launch

private val pages = listOf(
    "Farm management" to "Track plots, crops, harvests and livestock even when offline.",
    "Satellite insights" to "NDVI crop health and Sentinel-2 observations linked to each plot.",
    "Marketplace" to "List produce and trade with buyers across Botswana.",
    "Financial records" to "Sales, expenses and farm productivity summaries.",
)

@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(AgriSpacing.xl), horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { index ->
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(pages[index].first, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(AgriSpacing.md))
                Text(pages[index].second, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AgriSpacing.sm)) {
            repeat(pages.size) { i ->
                Text(if (i == pagerState.currentPage) "●" else "○")
            }
        }
        Spacer(Modifier.height(AgriSpacing.lg))
        if (pagerState.currentPage < pages.lastIndex) {
            AgriPrimaryButton(text = "Next", onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } })
        } else {
            AgriPrimaryButton(text = "Get started", onClick = onFinished)
        }
        Spacer(Modifier.height(AgriSpacing.sm))
        Button(onClick = onFinished) { Text("Skip") }
    }
}
