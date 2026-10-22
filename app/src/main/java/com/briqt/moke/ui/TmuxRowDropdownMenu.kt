package com.briqt.moke.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/** The row menu uses Material's appearance and effects timing, with no spatial exit animation. */
@Composable
internal fun TmuxRowDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = expanded
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val latestExpanded by rememberUpdatedState(expanded)
    val positionProvider = remember(density) {
        TmuxMenuPositionProvider(with(density) { 48.dp.roundToPx() }) { latestExpanded }
    }
    if (visibility.currentState || visibility.targetState || !visibility.isIdle) {
        Popup(
            popupPositionProvider = positionProvider,
            onDismissRequest = onDismissRequest,
            properties = PopupProperties(focusable = true),
        ) {
            // M3 1.4.0 FastEffects: the standard and expressive schemes both use these spring
            // values. The scheme accessor itself is internal, so use the public animation API.
            val effectsSpec = spring<Float>(dampingRatio = 1f, stiffness = 3800f)
            AnimatedVisibility(
                visibleState = visibility,
                enter = fadeIn(animationSpec = effectsSpec),
                exit = fadeOut(animationSpec = effectsSpec),
            ) {
                Surface(
                    shape = MenuDefaults.shape,
                    color = MenuDefaults.containerColor,
                    tonalElevation = MenuDefaults.TonalElevation,
                    shadowElevation = MenuDefaults.ShadowElevation,
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 8.dp).width(IntrinsicSize.Max).verticalScroll(scrollState),
                        content = content,
                    )
                }
            }
        }
    }
}

/** Anchor in reading order, prefer below/above, then keep within the current window. */
internal class TmuxMenuPositionProvider(
    private val verticalMargin: Int,
    private val isExpanded: () -> Boolean,
) : PopupPositionProvider {
    private var lastPosition: IntOffset? = null

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val bottom = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        val top = verticalMargin.coerceIn(0, bottom / 2)
        val maxY = bottom - top
        // Refreshing or removing a row during exit must not move the fading popup. A changed
        // window can still clamp it into view; opening again resumes normal anchor positioning.
        if (!isExpanded()) {
            lastPosition?.let { return IntOffset(it.x.coerceIn(0, maxX), it.y.coerceIn(top, maxY)) }
        }
        val start = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left else anchorBounds.right - popupContentSize.width
        val end = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.right - popupContentSize.width else anchorBounds.left
        val x = listOf(start, end).firstOrNull { it in 0..maxX } ?: start.coerceIn(0, maxX)
        val y = listOf(anchorBounds.bottom, anchorBounds.top - popupContentSize.height, anchorBounds.top - popupContentSize.height / 2)
            .firstOrNull { it in top..maxY } ?: anchorBounds.bottom.coerceIn(top, maxY)
        return IntOffset(x, y).also { lastPosition = it }
    }
}
