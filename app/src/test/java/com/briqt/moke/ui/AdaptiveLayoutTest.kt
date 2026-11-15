package com.briqt.moke.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveLayoutTest {

    @Test
    fun phonePortraitUsesBottomNavAndFullWidth() {
        val spec = windowAdaptiveSpec(widthDp = 393, heightDp = 873)
        assertEquals(HomeNavMode.Bottom, spec.homeNavMode)
        assertFalse(spec.constrainReadableWidth)
        assertFalse(spec.twoPaneAppearance)
        assertFalse(spec.wideTerminalOverlay)
    }

    @Test
    fun justBelow600RemainsCompact() {
        val spec = windowAdaptiveSpec(widthDp = 599, heightDp = 800)
        assertEquals(HomeNavMode.Bottom, spec.homeNavMode)
        assertFalse(spec.constrainReadableWidth)
        assertFalse(spec.twoPaneAppearance)
        assertFalse(spec.wideTerminalOverlay)
    }

    @Test
    fun width600PortraitUsesCollapsedRailAndReadableConstraints() {
        val spec = windowAdaptiveSpec(widthDp = 600, heightDp = 960)
        assertEquals(HomeNavMode.CollapsedRail, spec.homeNavMode)
        assertTrue(spec.constrainReadableWidth)
        assertFalse(spec.twoPaneAppearance)
        assertTrue(spec.wideTerminalOverlay)
    }

    @Test
    fun phoneLandscapeKeepsCollapsedRailWhenHeightIsShort() {
        val spec = windowAdaptiveSpec(widthDp = 873, heightDp = 393)
        assertEquals(HomeNavMode.CollapsedRail, spec.homeNavMode)
        assertTrue(spec.constrainReadableWidth)
        assertTrue(spec.twoPaneAppearance)
        assertTrue(spec.wideTerminalOverlay)
    }

    @Test
    fun shortWidePhoneBelow720StaysSinglePaneAppearance() {
        val spec = windowAdaptiveSpec(widthDp = 719, heightDp = 360)
        assertEquals(HomeNavMode.CollapsedRail, spec.homeNavMode)
        assertTrue(spec.constrainReadableWidth)
        assertFalse(spec.twoPaneAppearance)
        assertTrue(spec.wideTerminalOverlay)
    }

    @Test
    fun tabletPortraitBelow840UsesCollapsedRailSinglePane() {
        val spec = windowAdaptiveSpec(widthDp = 785, heightDp = 1200)
        assertEquals(HomeNavMode.CollapsedRail, spec.homeNavMode)
        assertTrue(spec.constrainReadableWidth)
        assertFalse(spec.twoPaneAppearance)
        assertTrue(spec.wideTerminalOverlay)
    }

    @Test
    fun tabletSquare720By720IsNotLandscapeTwoPane() {
        val spec = windowAdaptiveSpec(widthDp = 720, heightDp = 720)
        assertEquals(HomeNavMode.CollapsedRail, spec.homeNavMode)
        assertFalse(spec.twoPaneAppearance)
    }

    @Test
    fun tabletLandscape840By480BoundaryEnablesExpandedRailAndTwoPane() {
        val spec = windowAdaptiveSpec(widthDp = 840, heightDp = 480)
        assertEquals(HomeNavMode.ExpandedRail, spec.homeNavMode)
        assertTrue(spec.constrainReadableWidth)
        assertTrue(spec.twoPaneAppearance)
        assertTrue(spec.wideTerminalOverlay)
    }

    @Test
    fun largeTabletPortraitAbove840UsesExpandedRailSinglePane() {
        val spec = windowAdaptiveSpec(widthDp = 900, heightDp = 1280)
        assertEquals(HomeNavMode.ExpandedRail, spec.homeNavMode)
        assertTrue(spec.constrainReadableWidth)
        assertFalse(spec.twoPaneAppearance)
        assertTrue(spec.wideTerminalOverlay)
    }

    @Test
    fun largeTabletLandscapeUsesExpandedRailAndTwoPane() {
        val spec = windowAdaptiveSpec(widthDp = 1280, heightDp = 800)
        assertEquals(HomeNavMode.ExpandedRail, spec.homeNavMode)
        assertTrue(spec.constrainReadableWidth)
        assertTrue(spec.twoPaneAppearance)
        assertTrue(spec.wideTerminalOverlay)
    }

    @Test
    fun negativeDimensionsClampSafelyToZero() {
        val spec = windowAdaptiveSpec(widthDp = -10, heightDp = -20)
        assertEquals(0, spec.widthDp)
        assertEquals(0, spec.heightDp)
        assertEquals(HomeNavMode.Bottom, spec.homeNavMode)
        assertFalse(spec.constrainReadableWidth)
        assertFalse(spec.twoPaneAppearance)
        assertFalse(spec.wideTerminalOverlay)
    }
}
