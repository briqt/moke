package com.briqt.moke.ui

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class TmuxRowDropdownMenuTest {
    private val window = IntSize(400, 800)
    private val menu = IntSize(180, 240)

    @Test fun `LTR aligns reading start when below fits and uses reading end at right edge`() {
        val provider = TmuxMenuPositionProvider(48) { true }
        assertEquals(IntOffset(20, 140), provider.calculatePosition(IntRect(20, 100, 68, 140), window, LayoutDirection.Ltr, menu))
        assertEquals(IntOffset(200, 140), provider.calculatePosition(IntRect(332, 100, 380, 140), window, LayoutDirection.Ltr, menu))
    }

    @Test fun `RTL mirrors the horizontal reading order without changing vertical placement`() {
        val provider = TmuxMenuPositionProvider(48) { true }
        assertEquals(IntOffset(20, 140), provider.calculatePosition(IntRect(152, 100, 200, 140), window, LayoutDirection.Rtl, menu))
        assertEquals(IntOffset(20, 140), provider.calculatePosition(IntRect(20, 100, 68, 140), window, LayoutDirection.Rtl, menu))
    }

    @Test fun `bottom anchor opens above when below cannot fit`() {
        val provider = TmuxMenuPositionProvider(48) { true }
        assertEquals(IntOffset(200, 460), provider.calculatePosition(IntRect(332, 700, 380, 748), window, LayoutDirection.Ltr, menu))
    }

    @Test fun `middle anchor can center a tall menu before falling back to the window edge`() {
        val provider = TmuxMenuPositionProvider(48) { true }
        assertEquals(IntOffset(100, 120), provider.calculatePosition(IntRect(100, 300, 148, 348), IntSize(400, 600), LayoutDirection.Ltr, IntSize(180, 360)))
        val clippedAnchor = IntRect(500, 900, 548, 948)
        assertEquals(IntOffset(220, 512), provider.calculatePosition(clippedAnchor, window, LayoutDirection.Ltr, menu))
    }

    @Test fun `constrained popup height reduces margins rather than placing content off screen`() {
        val provider = TmuxMenuPositionProvider(48) { true }
        assertEquals(IntOffset(20, 10), provider.calculatePosition(IntRect(20, 100, 68, 148), IntSize(400, 260), LayoutDirection.Ltr, menu))
        assertEquals(IntOffset.Zero, provider.calculatePosition(IntRect(20, 100, 68, 148), IntSize(100, 100), LayoutDirection.Ltr, IntSize(200, 200)))
    }

    @Test fun `exit keeps the last position even when the underlying anchor moves or changes direction`() {
        var expanded = true
        val provider = TmuxMenuPositionProvider(48) { expanded }
        val position = provider.calculatePosition(IntRect(332, 700, 380, 748), window, LayoutDirection.Ltr, menu)
        expanded = false
        assertEquals(position, provider.calculatePosition(IntRect(20, 100, 68, 148), window, LayoutDirection.Rtl, menu))
        assertEquals(position, provider.calculatePosition(IntRect(150, 300, 198, 348), window, LayoutDirection.Ltr, menu))
        expanded = true
        assertEquals(IntOffset(20, 148), provider.calculatePosition(IntRect(20, 100, 68, 148), window, LayoutDirection.Ltr, menu))
    }

    @Test fun `window shrink during exit still keeps the pinned position visible`() {
        var expanded = true
        val provider = TmuxMenuPositionProvider(48) { expanded }
        provider.calculatePosition(IntRect(332, 700, 380, 748), window, LayoutDirection.Ltr, menu)
        expanded = false
        assertEquals(IntOffset(80, 12), provider.calculatePosition(IntRect(20, 100, 68, 148), IntSize(260, 264), LayoutDirection.Ltr, menu))
    }

    @Test fun `all window corners and layout directions keep a fitting popup inside the window`() {
        for (direction in LayoutDirection.entries) {
            for (anchor in listOf(IntRect(-20, -20, 28, 28), IntRect(372, -20, 420, 28),
                IntRect(-20, 772, 28, 820), IntRect(372, 772, 420, 820))) {
                val result = TmuxMenuPositionProvider(48) { true }.calculatePosition(anchor, window, direction, menu)
                assertTrue(result.x >= 0 && result.x + menu.width <= window.width)
                assertTrue(result.y >= 48 && result.y + menu.height <= window.height - 48)
            }
        }
    }

    @Test fun `row popup keeps fade only transitions and platform dismissal with Material appearance and scrolling`() {
        val source = listOf(File("src/main/java/com/briqt/moke/ui/TmuxRowDropdownMenu.kt"),
            File("app/src/main/java/com/briqt/moke/ui/TmuxRowDropdownMenu.kt")).first { it.isFile }.readText()
        assertTrue(source.contains("MutableTransitionState(false)"))
        assertTrue(source.contains("visibility.currentState || visibility.targetState || !visibility.isIdle"))
        assertTrue(source.contains("enter = fadeIn(animationSpec = effectsSpec)"))
        assertTrue(source.contains("exit = fadeOut(animationSpec = effectsSpec)"))
        assertTrue(source.contains("spring<Float>(dampingRatio = 1f, stiffness = 3800f)"))
        assertFalse(source.contains("MaterialTheme.motionScheme"))
        assertFalse(source.contains("androidx.compose.material3.internal"))
        assertFalse(Regex("\\b(scaleIn|scaleOut|slideIn|slideOut|expandIn|shrinkOut|delay)\\s*\\(").containsMatchIn(source))
        assertFalse(source.contains("scaleX"))
        assertFalse(source.contains("scaleY"))
        assertFalse(source.contains("translationX"))
        assertFalse(source.contains("translationY"))
        assertTrue(source.contains("properties = PopupProperties(focusable = true)"))
        assertTrue(source.contains("onDismissRequest = onDismissRequest"))
        assertTrue(source.contains("shape = MenuDefaults.shape"))
        assertTrue(source.contains("color = MenuDefaults.containerColor"))
        assertTrue(source.contains("tonalElevation = MenuDefaults.TonalElevation"))
        assertTrue(source.contains("shadowElevation = MenuDefaults.ShadowElevation"))
        assertTrue(source.contains(".verticalScroll(scrollState)"))
    }
}
