package com.termux.view;

import org.junit.Test;
import static org.junit.Assert.*;

public class ScrollViewportTest {
    private ScrollViewport viewport(int history) {
        ScrollViewport viewport = new ScrollViewport();
        viewport.observe(0, 0, history);
        return viewport;
    }

    @Test public void outputDoesNotStealReadingPosition() {
        ScrollViewport viewport = viewport(100);
        viewport.setPosition(-30.25);
        viewport.observe(0, 0, 100); // characters/colours only, not a history scroll
        assertEquals(-30.25, viewport.position(), 0);
        viewport.observe(2, 0, 102);
        assertEquals(-32.25, viewport.position(), 0);
        assertFalse(viewport.isFollowing());
    }

    @Test public void fullRingStillPreservesContentOrigin() {
        ScrollViewport viewport = viewport(100);
        viewport.setPosition(-30.25);
        viewport.observe(7, 0, 100); // history size is unchanged at capacity
        assertEquals(-37.25, viewport.position(), 0);
        viewport.observe(1000, 0, 100);
        assertEquals(-100, viewport.position(), 0);
        assertFalse(viewport.isFollowing());
    }

    @Test public void clearInvalidatesCoordinatesWithoutInventingContent() {
        ScrollViewport viewport = viewport(100);
        viewport.setPosition(-30.25);
        assertEquals(Long.MIN_VALUE, viewport.observe(0, 1, 0));
        assertEquals(0, viewport.position(), 0);
        viewport.observe(1, 1, 1);
        assertEquals(-1, viewport.position(), 0);
    }

    @Test public void followingIsRestoredOnlyAtBottomOrExplicitly() {
        ScrollViewport viewport = viewport(100);
        viewport.observe(8, 0, 100);
        assertEquals(0, viewport.position(), 0);
        viewport.movePixels(-5, 20);
        assertEquals(-1, viewport.topRow());
        assertEquals(.75, viewport.fraction(), 0);
        assertFalse(viewport.isFollowing());
        viewport.movePixels(10000, 20);
        assertTrue(viewport.isFollowing());
        assertEquals(0, viewport.fraction(), 0);
        viewport.hold(); // selecting the current screen also anchors output
        viewport.observe(9, 0, 100);
        assertEquals(-1, viewport.position(), 0);
        viewport.follow();
        viewport.observe(10, 0, 100);
        assertEquals(0, viewport.position(), 0);
    }

    @Test public void renderAndAllHitTestsUseSameFractionalTransform() {
        ScrollViewport viewport = viewport(100);
        viewport.setPosition(-20.75);
        assertEquals(-21, viewport.topRow());
        assertEquals(-5, viewport.translationY(20), 0);
        for (int row = -21; row < 0; row++) {
            float top = viewport.rowTop(row, 20, 4);
            assertEquals(row, viewport.rowAt(top + 1, 20, 4));
            assertEquals(row, viewport.rowAt(top + 19, 20, 4));
            assertEquals(row + 1, viewport.rowAt(top + 20, 20, 4));
        }
    }

    @Test public void reflowMarkerWinsOverChangedGenerationAndShift() {
        ScrollViewport viewport = viewport(100);
        viewport.setPosition(-30.25);
        viewport.restore(-55.25, false, 18, 2, 200);
        viewport.observe(18, 2, 200);
        assertEquals(-55.25, viewport.position(), 0);
        viewport.observe(19, 2, 201);
        assertEquals(-56.25, viewport.position(), 0);
    }

    @Test public void independentBuffersDoNotShareReadingOffsets() {
        ScrollViewport main = viewport(100);
        ScrollViewport alternate = viewport(0);
        main.setPosition(-50.5);
        alternate.movePixels(-1000, 20);
        assertEquals(0, alternate.position(), 0);
        assertEquals(-50.5, main.position(), 0);
    }
}
