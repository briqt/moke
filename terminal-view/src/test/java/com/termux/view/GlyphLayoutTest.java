package com.termux.view;

import org.junit.Test;

import static org.junit.Assert.*;

public class GlyphLayoutTest {
    private static final float EPS = 0.001f;

    @Test public void leadingOnlySplitsTheIncreaseFromTheRoundedNeutralHeight() {
        assertEquals(0f, GlyphLayout.baselineShift(56, 56), EPS);
        assertEquals(8.5f, GlyphLayout.baselineShift(56, 73), EPS);
        assertEquals(0f, GlyphLayout.baselineShift(56, 39), EPS);
    }

    @Test public void aWideGlyphIsNotEnlargedToFillTwoColumns() {
        float scale = GlyphLayout.scale(42, 0, 42, 50);
        assertEquals(1f, scale, EPS);
        assertEquals(4f, GlyphLayout.origin(42, 0, 42, 50, 50, scale), EPS);
    }

    @Test public void strictPlacementAccountsForInkOverhangAsWellAsAdvance() {
        float scale = GlyphLayout.scale(42, -1, 42, 25);
        float origin = GlyphLayout.origin(42, -1, 42, 25, 25, scale);
        assertEquals(25f / 43f, scale, EPS);
        assertTrue(origin - scale >= -EPS);
        assertTrue(origin + 42 * scale <= 25 + EPS);
    }

    @Test public void borrowingUsesOnlyTheRightSpaceAndDoesNotCenterTheWholeRun() {
        float scale = GlyphLayout.scale(42, -1, 42, 50);
        float origin = GlyphLayout.origin(42, -1, 42, 25, 50, scale);
        assertEquals(1f, scale, EPS);
        assertEquals(1f, origin, EPS);
        assertTrue(origin - scale >= 0);
        assertTrue(origin + 42 * scale <= 50);
    }

    @Test public void everyBorrowPreconditionIsRequired() {
        assertTrue(GlyphLayout.canBorrow(true, 1, true, true, false, false));
        assertFalse(GlyphLayout.canBorrow(false, 1, true, true, false, false));
        assertFalse(GlyphLayout.canBorrow(true, 2, true, true, false, false));
        assertFalse(GlyphLayout.canBorrow(true, 1, false, true, false, false));
        assertFalse(GlyphLayout.canBorrow(true, 1, true, false, false, false));
        assertFalse(GlyphLayout.canBorrow(true, 1, true, true, true, false));
        assertFalse(GlyphLayout.canBorrow(true, 1, true, true, false, true));
    }

    @Test public void symbolsDoNotIncludeOrdinaryLettersOrCombiningMarks() {
        assertTrue(GlyphLayout.isSymbol(0xf015));
        assertTrue(GlyphLayout.isSymbol(0x2192));
        assertFalse(GlyphLayout.isSymbol('W'));
        assertFalse(GlyphLayout.isSymbol(0x4e2d));
        assertFalse(GlyphLayout.isSymbol(0x0301));
    }

    @Test public void containmentHoldsAcrossSpacingBoundsAndInkBearings() {
        for (float cell : new float[]{17.5f, 25f, 32.5f}) {
            for (int columns = 1; columns <= 2; columns++) {
                for (int borrow = 0; borrow <= 1; borrow++) {
                    for (float advance : new float[]{10, 25, 42, 70}) {
                        for (float bearing : new float[]{-3, 0, 2}) {
                            float logical = columns * cell;
                            float available = logical + borrow * cell;
                            float right = advance + 2;
                            float scale = GlyphLayout.scale(advance, bearing, right, available);
                            float origin = GlyphLayout.origin(advance, bearing, right, logical, available, scale);
                            assertTrue(scale > 0 && scale <= 1);
                            assertTrue(origin + bearing * scale >= -EPS);
                            assertTrue(origin + right * scale <= available + EPS);
                        }
                    }
                }
            }
        }
    }
}
