package com.briqt.moke.ui

internal data class TerminalInputBudget(
    val availableHeight: Int,
    val reservedHeight: Int,
    val minimumTerminalHeight: Int,
) {
    val terminalHeight: Int get() = (availableHeight - reservedHeight).coerceAtLeast(0)
    val sufficient: Boolean get() = minimumTerminalHeight > 0 && terminalHeight >= minimumTerminalHeight
    fun sufficientWithoutIme(imeInset: Int): Boolean = minimumTerminalHeight > 0 &&
        availableHeight.toLong() + imeInset.coerceAtLeast(0) >= reservedHeight.toLong() + minimumTerminalHeight
}

internal enum class TerminalImeAction { NONE, HIDE, NOTICE }

/** One hide per explicit input attempt; layout recovery never requests another show. */
internal class TerminalImeFallback {
    private enum class Phase { IDLE, HIDING, RECOVERING, BLOCKED }
    private var phase = Phase.IDLE
    private var ownsInput = false
    var attempt: Long = 0
        private set

    fun requestKeyboard(): Boolean {
        if (phase == Phase.HIDING) return false
        phase = Phase.IDLE
        ownsInput = true
        attempt++
        return true
    }

    fun abandonPendingHide(expectedAttempt: Long) {
        if (attempt == expectedAttempt && (phase == Phase.HIDING || phase == Phase.RECOVERING)) {
            phase = Phase.BLOCKED
            ownsInput = false
        }
    }

    fun update(imeInset: Int, active: Boolean, budget: TerminalInputBudget,
               imeVisible: Boolean = imeInset > 0, windowFocused: Boolean = true): TerminalImeAction {
        if (!active) {
            phase = Phase.IDLE
            ownsInput = false
            return TerminalImeAction.NONE
        }
        // A dismissing popup briefly owns focus; it must not erase an explicit input request.
        if (!windowFocused) return TerminalImeAction.NONE
        if (phase == Phase.IDLE && !ownsInput && imeVisible) {
            ownsInput = true
            attempt++
        }
        if (phase == Phase.HIDING) {
            if (!imeVisible) phase = Phase.RECOVERING
            else return TerminalImeAction.NONE
        }
        if (phase == Phase.RECOVERING) {
            if (!imeVisible && budget.sufficient) {
                phase = Phase.BLOCKED
                ownsInput = false
                return TerminalImeAction.NOTICE
            }
            return TerminalImeAction.NONE
        }
        if (phase == Phase.IDLE && ownsInput && imeInset > 0 && !budget.sufficient && budget.sufficientWithoutIme(imeInset)) {
            phase = Phase.HIDING
            return TerminalImeAction.HIDE
        }
        return TerminalImeAction.NONE
    }
}
