package com.briqt.moke.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.briqt.moke.ui.theme.MokeDimens
import kotlin.math.roundToInt

enum class HomeNavMode {
    Bottom,
    CollapsedRail,
    ExpandedRail,
}

@Immutable
data class WindowAdaptiveSpec(
    val widthDp: Int,
    val heightDp: Int,
    val homeNavMode: HomeNavMode,
    val constrainReadableWidth: Boolean,
    val twoPaneAppearance: Boolean,
    val wideTerminalOverlay: Boolean,
)

internal fun windowAdaptiveSpec(widthDp: Int, heightDp: Int): WindowAdaptiveSpec {
    val w = widthDp.coerceAtLeast(0)
    val h = heightDp.coerceAtLeast(0)
    val navMode = when {
        w < 600 -> HomeNavMode.Bottom
        w >= 840 && h >= 480 -> HomeNavMode.ExpandedRail
        else -> HomeNavMode.CollapsedRail
    }
    return WindowAdaptiveSpec(
        widthDp = w,
        heightDp = h,
        homeNavMode = navMode,
        constrainReadableWidth = w >= 600,
        twoPaneAppearance = w >= 720 && w > h,
        wideTerminalOverlay = w >= 600,
    )
}

@Composable
fun rememberWindowAdaptiveSpec(): WindowAdaptiveSpec {
    val containerSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val widthDp = with(density) { containerSize.width.toDp().value.roundToInt() }
    val heightDp = with(density) { containerSize.height.toDp().value.roundToInt() }
    return remember(widthDp, heightDp) { windowAdaptiveSpec(widthDp, heightDp) }
}

/**
 * Full-width wrapper keeping list item touch alignment anchored across the viewport while
 * constraining visible content to [maxWidth] centered horizontally.
 */
@Composable
fun CenteredItem(
    modifier: Modifier = Modifier,
    maxWidth: Dp = MokeDimens.contentMaxWidth,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .fillMaxWidth(),
            content = content,
        )
    }
}

/**
 * Full-bleed vertical scroll container (so edge scrollbars and side-margin swipe work across the
 * whole width) with an inner column constrained to [maxWidth] and centered horizontally.
 */
@Composable
fun CenteredScrollColumn(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    extraContainerModifier: Modifier = Modifier,
    innerPadding: PaddingValues = PaddingValues(12.dp),
    maxWidth: Dp = MokeDimens.contentMaxWidth,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(12.dp),
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .then(extraContainerModifier)
            .verticalScroll(scrollState),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = maxWidth)
                .fillMaxWidth()
                .padding(innerPadding),
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}
