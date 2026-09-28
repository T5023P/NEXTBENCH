package com.nextbench.app.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nextbench.core.designsystem.NbDimens
import com.nextbench.core.designsystem.NbIcons
import com.nextbench.core.designsystem.NbLogo
import com.nextbench.core.designsystem.NbTheme
import com.nextbench.core.designsystem.pressScale
import kotlin.math.absoluteValue
import kotlinx.coroutines.launch

private data class OnboardingPage(
    val title: String,
    val body: String,
    val icon: ImageVector,
    val accent: OnboardingAccent,
)

private enum class OnboardingAccent { Teal, Pink, Mint }

private val Pages = listOf(
    OnboardingPage(
        title = "A community you can trust",
        body = "Campus verification keeps profiles accountable, conversations useful, and every exchange grounded in a real community.",
        icon = NbIcons.Shield,
        accent = OnboardingAccent.Teal,
    ),
    OnboardingPage(
        title = "Stay close to campus life",
        body = "Follow stories, thoughtful posts, and clubs from the people shaping what happens around you.",
        icon = NbIcons.Messages,
        accent = OnboardingAccent.Pink,
    ),
    OnboardingPage(
        title = "Pass good books forward",
        body = "Discover recommendations and exchange books directly with verified students nearby.",
        icon = NbIcons.Marketplace,
        accent = OnboardingAccent.Mint,
    ),
)

@Composable
fun OnboardingScreen(
    isCompleting: Boolean,
    error: String?,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(pageCount = Pages::size)
    val scope = rememberCoroutineScope()
    val isLastPage = pagerState.currentPage == Pages.lastIndex

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NbTheme.colors.surfaceBase)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = NbDimens.space20),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NbLogo(size = 34.dp)
            Text(
                text = "NextBench",
                style = MaterialTheme.typography.titleMedium,
                color = NbTheme.colors.ink,
                modifier = Modifier.padding(start = NbDimens.space12),
            )
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onComplete, enabled = !isCompleting) {
                Text("Skip", color = NbTheme.colors.inkMuted)
            }
        }

        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            modifier = Modifier.weight(1f),
        ) { pageIndex ->
            val offset = (
                (pagerState.currentPage - pageIndex) + pagerState.currentPageOffsetFraction
                ).absoluteValue.coerceIn(0f, 1f)
            OnboardingPageContent(
                page = Pages[pageIndex],
                modifier = Modifier.graphicsLayer {
                    alpha = 1f - (offset * 0.28f)
                    scaleX = 1f - (offset * 0.04f)
                    scaleY = 1f - (offset * 0.04f)
                },
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NbDimens.space24)
                .padding(bottom = NbDimens.space20),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PageIndicator(
                selectedPage = pagerState.currentPage,
                pageCount = Pages.size,
            )
            if (error != null) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = NbTheme.colors.brandPink,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = NbDimens.space12),
                )
            }
            Spacer(modifier = Modifier.height(NbDimens.space20))
            Button(
                onClick = {
                    if (isLastPage) {
                        onComplete()
                    } else {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                },
                enabled = !isCompleting,
                shape = RoundedCornerShape(NbDimens.radiusSm),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NbTheme.colors.brandTeal,
                    contentColor = Color.White,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .pressScale(targetScale = 0.98f),
            ) {
                Text(
                    text = if (isCompleting) "Getting things ready..." else if (isLastPage) "Get started" else "Continue",
                    style = MaterialTheme.typography.labelLarge,
                )
                if (!isCompleting) {
                    Spacer(modifier = Modifier.width(NbDimens.space8))
                    Icon(
                        imageVector = NbIcons.ArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingPageContent(
    page: OnboardingPage,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = NbDimens.space24),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = NbDimens.space16),
            contentAlignment = Alignment.Center,
        ) {
            OnboardingScene(page)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = page.title,
                style = MaterialTheme.typography.headlineLarge,
                color = NbTheme.colors.ink,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(NbDimens.space16))
            Text(
                text = page.body,
                style = MaterialTheme.typography.bodyLarge,
                color = NbTheme.colors.inkMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun OnboardingScene(page: OnboardingPage) {
    val accent = when (page.accent) {
        OnboardingAccent.Teal -> NbTheme.colors.brandTeal
        OnboardingAccent.Pink -> NbTheme.colors.brandPink
        OnboardingAccent.Mint -> NbTheme.colors.brandMint
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.82f)
            .clip(RoundedCornerShape(NbDimens.radiusSm))
            .background(accent),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(112.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = page.icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(56.dp),
            )
        }
        SceneDetails(page.accent)
    }
}

@Composable
private fun BoxScope.SceneDetails(accent: OnboardingAccent) {
    when (accent) {
        OnboardingAccent.Teal -> {
            Icon(
                imageVector = NbIcons.Check,
                contentDescription = null,
                tint = NbTheme.colors.brandTeal,
                modifier = Modifier
                    .padding(start = 84.dp, top = 84.dp)
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .padding(NbDimens.space8),
            )
        }
        OnboardingAccent.Pink -> {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = NbDimens.space24),
                horizontalArrangement = Arrangement.spacedBy(NbDimens.space8),
            ) {
                repeat(4) { index ->
                    Box(
                        modifier = Modifier
                            .size(if (index == 1) 38.dp else 30.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = if (index == 1) 0.94f else 0.38f)),
                    )
                }
            }
        }
        OnboardingAccent.Mint -> {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = NbDimens.space24),
                horizontalArrangement = Arrangement.spacedBy(NbDimens.space8),
                verticalAlignment = Alignment.Bottom,
            ) {
                repeat(3) { index ->
                    Box(
                        modifier = Modifier
                            .width(28.dp)
                            .height((44 + index * 10).dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.White.copy(alpha = 0.82f - index * 0.12f)),
                    )
                }
            }
        }
    }
}

@Composable
private fun PageIndicator(
    selectedPage: Int,
    pageCount: Int,
) {
    Row(
        modifier = Modifier.semantics {
            contentDescription = "Page ${selectedPage + 1} of $pageCount"
        },
        horizontalArrangement = Arrangement.spacedBy(NbDimens.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            val selected = index == selectedPage
            Box(
                modifier = Modifier
                    .width(if (selected) 24.dp else 6.dp)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(if (selected) NbTheme.colors.brandTeal else NbTheme.colors.borderStrong),
            )
        }
    }
}
