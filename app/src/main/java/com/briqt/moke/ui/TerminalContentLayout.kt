package com.briqt.moke.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints

/** Measure the complete footer before assigning the remaining height to the first, terminal child. */
@Composable
internal fun TerminalContentLayout(
    modifier: Modifier = Modifier,
    minimumTerminalHeight: () -> Int,
    onBudget: (TerminalInputBudget) -> Unit,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val footerConstraints = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val footers = measurables.drop(1).map { it.measure(footerConstraints) }
        val reserved = footers.sumOf { it.height }
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else reserved + minimumTerminalHeight()
        val budget = TerminalInputBudget(height, reserved, minimumTerminalHeight())
        val terminal = measurables.firstOrNull()?.measure(Constraints.fixed(constraints.maxWidth, budget.terminalHeight))
        layout(constraints.maxWidth, height) {
            terminal?.placeRelative(0, 0)
            var top = budget.terminalHeight
            footers.forEach {
                it.placeRelative(0, top)
                top += it.height
            }
            // Publish geometry only. The screen's effect performs asynchronous IME requests.
            onBudget(budget)
        }
    }
}
