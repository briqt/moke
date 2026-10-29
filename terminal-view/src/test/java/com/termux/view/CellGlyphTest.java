package com.termux.view;

import org.junit.Test;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class CellGlyphTest {
    private static final float WIDTH = 32, HEIGHT = 64;
    private static final float EPSILON = .00001f;

    @Test public void whitelistIsExactAndUnsupportedCharactersEmitNothing() {
        CellGlyph glyph = new CellGlyph(WIDTH, HEIGHT);
        Recording sink = new Recording(WIDTH, HEIGHT);
        for (int codePoint = -1; codePoint <= 0x110000; codePoint++) {
            boolean expected = (codePoint >= 0x2500 && codePoint <= 0x259F)
                || (codePoint >= 0xE0B0 && codePoint <= 0xE0B3);
            assertEquals(expected, CellGlyph.supports(codePoint));
        }
        for (int codePoint : new int[]{-1, 0, 'A', 0x24FF, 0x25A0, 0xE0AF, 0xE0B4, 0x10FFFF}) {
            assertFalse(glyph.draw(codePoint, sink));
        }
        assertEquals(0, sink.commands);
    }

    @Test public void everySupportedGlyphEmitsFiniteBoundedGeometryAtFractionalAndSmallSizes() {
        float[][] dimensions = {{.35f, 1}, {1, 1}, {7.25f, 13}, {10.5f, 23},
            {25.8f, 51}, {2, 200}, {200, 2}};
        for (float[] size : dimensions) {
            CellGlyph glyph = new CellGlyph(size[0], size[1]);
            for (int codePoint : supportedCodePoints()) {
                Recording sink = new Recording(size[0], size[1]);
                assertTrue(label(codePoint), glyph.draw(codePoint, sink));
                assertFalse(label(codePoint), sink.open);
                assertTrue(label(codePoint), sink.commands > 0);
                assertTrue("bounded work " + label(codePoint), sink.commands <= 32);
            }
        }
    }

    @Test public void invalidCellMetricsAreRejected() {
        for (float invalid : new float[]{0, -1, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new CellGlyph(invalid, HEIGHT));
            assertThrows(IllegalArgumentException.class, () -> new CellGlyph(WIDTH, invalid));
        }
    }

    @Test public void allBoxArmDirectionsAndWeightsMatchUnicodeCharacterNames() {
        float[] offsets = {-2, -1.25f, -.75f, -.25f, 0, .25f, .75f, 1.25f, 2};
        for (float[] size : new float[][]{{WIDTH, HEIGHT}, {7.25f, 13}, {.35f, 1}}) {
            CellGlyph glyph = new CellGlyph(size[0], size[1]);
            for (int codePoint = 0x2500; codePoint <= 0x257F; codePoint++) {
                String name = Character.getName(codePoint);
                if (name.contains("DASH") || name.contains("DIAGONAL")) continue;
                Recording sink = render(glyph, codePoint);
                int[] expected = namedArms(name);
                for (int direction = 0; direction < 4; direction++) {
                    for (float offset : offsets) {
                        float x = direction == 0 ? glyph.width * .0001f : direction == 1 ? glyph.width * .9999f
                            : glyph.width / 2 + offset * glyph.lightStroke;
                        float y = direction == 2 ? glyph.height * .0001f : direction == 3 ? glyph.height * .9999f
                            : glyph.height / 2 + offset * glyph.lightStroke;
                        boolean ink = expected[direction] == 1 ? Math.abs(offset) < .5f
                            : expected[direction] == 2 ? Math.abs(offset) < 1
                            : expected[direction] == 3 ? Math.abs(offset) > .5f && Math.abs(offset) < 1.5f
                            : false;
                        assertEquals(name + " arm=" + direction + " offset=" + offset, ink, sink.contains(x, y));
                    }
                }
            }
        }
    }

    @Test public void allBoxCharactersHaveDistinctGeometryNotPlaceholderBlocksOrCrosses() {
        CellGlyph glyph = new CellGlyph(WIDTH, HEIGHT);
        Set<BitSet> signatures = new HashSet<>();
        for (int codePoint = 0x2500; codePoint <= 0x257F; codePoint++) {
            Recording sink = render(glyph, codePoint);
            BitSet signature = new BitSet(128 * 128);
            for (int row = 0; row < 128; row++) {
                for (int column = 0; column < 128; column++) {
                    if (sink.contains(WIDTH * (column + .5f) / 128, HEIGHT * (row + .5f) / 128)) {
                        signature.set(row * 128 + column);
                    }
                }
            }
            assertTrue("distinct " + label(codePoint), signatures.add(signature));
        }
    }

    @Test public void doubleCrossAndDoubleTJunctionsKeepTheirOpenLanes() {
        CellGlyph glyph = new CellGlyph(WIDTH, HEIGHT);
        float s = glyph.lightStroke, cx = WIDTH / 2, cy = HEIGHT / 2;
        Recording cross = render(glyph, 0x256C);
        assertFalse(cross.contains(cx, cy));
        assertFalse(cross.contains(cx, cy - s));
        assertFalse(cross.contains(cx - s, cy));
        for (int xSign : new int[]{-1, 1}) {
            for (int ySign : new int[]{-1, 1}) {
                assertTrue(cross.contains(cx + xSign * s, cy + ySign * s));
            }
        }
        Recording downT = render(glyph, 0x2566);
        assertTrue(downT.contains(cx, cy - s)); // the upper rail continues straight
        assertFalse(downT.contains(cx, cy + s)); // the lower rails turn down separately
        assertFalse(downT.contains(cx, cy));
        Recording rightT = render(glyph, 0x2560);
        assertTrue(rightT.contains(cx - s, cy));
        assertFalse(rightT.contains(cx + s, cy));
        assertFalse(rightT.contains(cx, cy));
    }

    @Test public void singleDoubleCornersReachBothRailsButBranchesStopAtTheNearRail() {
        CellGlyph glyph = new CellGlyph(WIDTH, HEIGHT);
        float s = glyph.lightStroke, cx = WIDTH / 2, cy = HEIGHT / 2;
        // U+2552 down single/right double: the vertical connects both horizontal rails.
        Recording corner = render(glyph, 0x2552);
        assertTrue(corner.contains(cx, cy - s));
        assertTrue(corner.contains(cx, cy));
        assertTrue(corner.contains(cx, cy + s));
        // U+2553 down double/right single: the horizontal connects both vertical rails.
        Recording rotatedCorner = render(glyph, 0x2553);
        assertTrue(rotatedCorner.contains(cx - s, cy));
        assertTrue(rotatedCorner.contains(cx, cy));
        assertTrue(rotatedCorner.contains(cx + s, cy));
        // U+2564 down single/horizontal double: do not draw the branch through the gap.
        Recording branch = render(glyph, 0x2564);
        assertTrue(branch.contains(cx, cy - s));
        assertFalse(branch.contains(cx, cy));
        assertTrue(branch.contains(cx, cy + s));
        // U+255F vertical double/right single: connect only the right vertical rail.
        Recording rightBranch = render(glyph, 0x255F);
        assertTrue(rightBranch.contains(cx - s, cy));
        assertFalse(rightBranch.contains(cx, cy));
        assertTrue(rightBranch.contains(cx + s, cy));
        assertTrue(rightBranch.contains(cx + 2 * s, cy));
        for (int codePoint : new int[]{0x256A, 0x256B}) {
            assertTrue("single line crosses the double gap " + label(codePoint),
                render(glyph, codePoint).contains(cx, cy));
        }
    }

    @Test public void doubleCornersHaveNestedTurnsRatherThanFourCrossingRails() {
        CellGlyph glyph = new CellGlyph(WIDTH, HEIGHT);
        float s = glyph.lightStroke, cx = WIDTH / 2, cy = HEIGHT / 2;
        for (int codePoint : new int[]{0x2554, 0x2557, 0x255A, 0x255D}) {
            int[] arms = namedArms(Character.getName(codePoint));
            int horizontal = arms[1] != 0 ? 1 : -1;
            int vertical = arms[3] != 0 ? 1 : -1;
            Recording corner = render(glyph, codePoint);
            assertFalse(corner.contains(cx, cy));
            assertTrue(corner.contains(cx - horizontal * s, cy - vertical * s));
            assertTrue(corner.contains(cx + horizontal * s, cy + vertical * s));
            assertTrue(corner.contains(cx, cy - vertical * s));
            assertTrue(corner.contains(cx - horizontal * s, cy));
            assertFalse(corner.contains(cx, cy + vertical * s));
            assertFalse(corner.contains(cx + horizontal * s, cy));
        }
    }

    @Test public void solidCornerStrokesMeetAndHalfLineCapsEndAtTheCellCentre() {
        CellGlyph glyph = new CellGlyph(WIDTH, HEIGHT);
        for (int codePoint = 0x250C; codePoint <= 0x254B; codePoint++) {
            Recording sink = render(glyph, codePoint);
            assertTrue(label(codePoint), sink.contains(WIDTH / 2, HEIGHT / 2));
        }
        for (int codePoint = 0x2574; codePoint <= 0x257B; codePoint++) {
            int[] arms = namedArms(Character.getName(codePoint));
            Recording sink = render(glyph, codePoint);
            float distance = glyph.lightStroke / 4;
            if (arms[0] != 0 || arms[1] != 0) {
                assertEquals(arms[0] != 0, sink.contains(WIDTH / 2 - distance, HEIGHT / 2));
                assertEquals(arms[1] != 0, sink.contains(WIDTH / 2 + distance, HEIGHT / 2));
            } else {
                assertEquals(arms[2] != 0, sink.contains(WIDTH / 2, HEIGHT / 2 - distance));
                assertEquals(arms[3] != 0, sink.contains(WIDTH / 2, HEIGHT / 2 + distance));
            }
        }
    }

    @Test public void everySingleDoubleJunctionMatchesHandDrawnInteriorFixtures() {
        CellGlyph glyph = new CellGlyph(WIDTH, HEIGHT);
        String[] horizontal = {".....", "#####", ".....", "#####", "....."};
        String[] doubleSingleCorner = {".....", "..###", "..#..", "..###", "..#.."};
        String[] singleDoubleCorner = {".....", ".....", ".####", ".#.#.", ".#.#."};
        String[] doubleCorner = {".....", ".####", ".#...", ".#.##", ".#.#."};
        String[] doubleSingleT = {"..#..", "..###", "..#..", "..###", "..#.."};
        String[] singleDoubleT = {".#.#.", ".#.#.", ".#.##", ".#.#.", ".#.#."};
        String[] doubleT = {".#.#.", ".#.##", ".#...", ".#.##", ".#.#."};
        String[] horizontalDoubleCross = {"..#..", "#####", "..#..", "#####", "..#.."};
        String[] verticalDoubleCross = {".#.#.", ".#.#.", "#####", ".#.#.", ".#.#."};
        String[] doubleCross = {".#.#.", "##.##", ".....", "##.##", ".#.#."};
        for (int codePoint = 0x2550; codePoint <= 0x256C; codePoint++) {
            Recording sink = render(glyph, codePoint);
            int[] arms = namedArms(Character.getName(codePoint));
            int count = 0;
            for (int arm : arms) if (arm != 0) count++;
            String[] fixture;
            int xSign = 1, ySign = 1;
            boolean swap = false;
            if (codePoint <= 0x2551) {
                fixture = horizontal;
                swap = codePoint == 0x2551;
            } else if (count == 2) {
                int h = arms[1] != 0 ? 1 : 0, v = arms[3] != 0 ? 3 : 2;
                xSign = h == 1 ? 1 : -1;
                ySign = v == 3 ? 1 : -1;
                fixture = arms[h] == 3 ? arms[v] == 3 ? doubleCorner : doubleSingleCorner
                    : singleDoubleCorner;
            } else if (count == 3) {
                int branch = arms[0] == 0 ? 1 : arms[1] == 0 ? 0 : arms[2] == 0 ? 3 : 2;
                int through = branch < 2 ? arms[2] : arms[0];
                fixture = arms[branch] == 3 ? through == 3 ? doubleT : doubleSingleT : singleDoubleT;
                swap = branch >= 2;
                if (swap) ySign = branch == 3 ? 1 : -1;
                else xSign = branch == 1 ? 1 : -1;
            } else {
                fixture = arms[0] == 3 ? arms[2] == 3 ? doubleCross : horizontalDoubleCross
                    : verticalDoubleCross;
            }
            for (int row = 0; row < 5; row++) {
                for (int column = 0; column < 5; column++) {
                    float x = WIDTH / 2 + xSign * ((swap ? row : column) - 2) * glyph.lightStroke;
                    float y = HEIGHT / 2 + ySign * ((swap ? column : row) - 2) * glyph.lightStroke;
                    assertEquals(label(codePoint) + " fixture row=" + row + " column=" + column,
                        fixture[row].charAt(column) == '#', sink.contains(x, y));
                }
            }
        }
    }

    @Test public void dashedLinesPreserveDirectionCountWeightAndIntentionalGaps() {
        CellGlyph glyph = new CellGlyph(WIDTH, HEIGHT);
        for (int codePoint : new int[]{0x2504, 0x2505, 0x2506, 0x2507, 0x2508, 0x2509,
                0x250A, 0x250B, 0x254C, 0x254D, 0x254E, 0x254F}) {
            String name = Character.getName(codePoint);
            boolean horizontal = name.contains("HORIZONTAL"), heavy = name.contains("HEAVY");
            int count = name.contains("TRIPLE") ? 3 : name.contains("QUADRUPLE") ? 4 : 2;
            Recording sink = render(glyph, codePoint);
            assertEquals(count, sink.rectangles.size());
            assertTrue(sink.contours.isEmpty());
            float length = horizontal ? WIDTH : HEIGHT;
            for (int i = 0; i < count; i++) {
                float position = length * (i + .5f) / count;
                float offCentre = glyph.lightStroke * .75f;
                assertTrue(sink.contains(horizontal ? position : WIDTH / 2,
                    horizontal ? HEIGHT / 2 : position));
                assertEquals(heavy, sink.contains(horizontal ? position : WIDTH / 2 + offCentre,
                    horizontal ? HEIGHT / 2 + offCentre : position));
                if (i != 0) {
                    position = length * i / count;
                    assertFalse(sink.contains(horizontal ? position : WIDTH / 2,
                        horizontal ? HEIGHT / 2 : position));
                }
            }
        }
    }

    @Test public void roundedCornersHaveActualCurvesAndDoNotFillTheSquareCornerCentre() {
        CellGlyph glyph = new CellGlyph(WIDTH, HEIGHT);
        for (int codePoint = 0x256D; codePoint <= 0x2570; codePoint++) {
            Recording sink = render(glyph, codePoint);
            assertEquals(2, sink.quadratics);
            assertEquals(2, sink.rectangles.size());
            assertFalse(sink.contains(WIDTH / 2, HEIGHT / 2));
        }
    }

    @Test public void diagonalsFollowBothSlopesAndTheCrossDoesNotHaveAWindingHole() {
        CellGlyph glyph = new CellGlyph(WIDTH, HEIGHT);
        Recording rising = render(glyph, 0x2571), falling = render(glyph, 0x2572);
        Recording cross = render(glyph, 0x2573);
        for (float fraction : new float[]{.05f, .25f, .5f, .75f, .95f}) {
            assertTrue(rising.contains(WIDTH * fraction, HEIGHT * (1 - fraction)));
            assertTrue(falling.contains(WIDTH * fraction, HEIGHT * fraction));
            assertTrue(cross.contains(WIDTH * fraction, HEIGHT * fraction));
            assertTrue(cross.contains(WIDTH * fraction, HEIGHT * (1 - fraction)));
        }
        assertFalse(rising.contains(WIDTH * .1f, HEIGHT * .1f));
        assertFalse(falling.contains(WIDTH * .1f, HEIGHT * .9f));
        assertTrue(cross.contains(WIDTH / 2, HEIGHT / 2));
        assertTrue(cross.rectangles.isEmpty());
    }

    @Test public void fractionBlocksUseExactCellBoundariesWithoutIndependentRounding() {
        CellGlyph glyph = new CellGlyph(7.25f, 13);
        assertRect(glyph, 0x2580, 0, 0, 7.25f, 6.5f);
        for (int eighths = 1; eighths <= 8; eighths++) {
            assertRect(glyph, 0x2580 + eighths, 0, 13 * ((8 - eighths) / 8f), 7.25f, 13);
        }
        for (int eighths = 1; eighths <= 7; eighths++) {
            assertRect(glyph, 0x2590 - eighths, 0, 0, 7.25f * (eighths / 8f), 13);
        }
        assertRect(glyph, 0x2590, 3.625f, 0, 7.25f, 13);
        assertRect(glyph, 0x2594, 0, 0, 7.25f, 1.625f);
        assertRect(glyph, 0x2595, 7.25f * .875f, 0, 7.25f, 13);
        float[] upper = render(glyph, 0x2580).rectangles.get(0);
        float[] lower = render(glyph, 0x2584).rectangles.get(0);
        assertEquals(upper[3], lower[1], 0); // the two halves share one exact boundary
        float[] full = render(glyph, 0x2588).rectangles.get(0);
        for (int column = 0; column < 100; column++) {
            double left = column * (double) glyph.width;
            double nextLeft = (column + 1) * (double) glyph.width;
            assertEquals(nextLeft, left + full[2], 0);
        }
    }

    @Test public void allQuadrantCombinationsOccupyExactlyTheNamedQuarters() {
        CellGlyph glyph = new CellGlyph(10.5f, 23);
        for (int codePoint = 0x2596; codePoint <= 0x259F; codePoint++) {
            String name = Character.getName(codePoint);
            Recording sink = render(glyph, codePoint);
            int filled = 0;
            for (int quadrant = 0; quadrant < 4; quadrant++) {
                String quadrantName = ((quadrant & 2) == 0 ? "UPPER" : "LOWER") + " "
                    + ((quadrant & 1) == 0 ? "LEFT" : "RIGHT");
                boolean expected = name.contains(quadrantName);
                if (expected) filled++;
                assertEquals(name, expected, sink.contains(glyph.width * ((quadrant & 1) == 0 ? .25f : .75f),
                    glyph.height * ((quadrant & 2) == 0 ? .25f : .75f)));
            }
            assertEquals(filled * glyph.width * glyph.height / 4, sink.rectangleArea(), .0001);
        }
    }

    @Test public void shadeCoverageIsQuarterHalfThreeQuartersWithActualStipple() {
        CellGlyph glyph = new CellGlyph(7.25f, 13);
        for (int level = 1; level <= 3; level++) {
            Recording sink = render(glyph, 0x2590 + level);
            assertEquals(8 * level, sink.rectangles.size());
            assertTrue(sink.contours.isEmpty());
            assertEquals(glyph.width * glyph.height * (level / 4f), sink.rectangleArea(), .0001);
            // Every shade has foreground and background, rather than an opaque full block.
            int filled = 0;
            for (int row = 0; row < 8; row++) {
                for (int column = 0; column < 4; column++) {
                    if (sink.contains(glyph.width * (column + .5f) / 4,
                            glyph.height * (row + .5f) / 8)) filled++;
                }
            }
            assertEquals(8 * level, filled);
        }
    }

    @Test public void powerlineTrianglesAndThinChevronsAreMirroredAndNotInterchangeable() {
        CellGlyph glyph = new CellGlyph(10.5f, 23);
        Recording filledRight = render(glyph, 0xE0B0), thinRight = render(glyph, 0xE0B1);
        Recording filledLeft = render(glyph, 0xE0B2), thinLeft = render(glyph, 0xE0B3);
        assertTrue(filledRight.contains(glyph.width / 2, glyph.height / 2));
        assertFalse(thinRight.contains(glyph.width / 2, glyph.height / 2));
        assertTrue(thinRight.contains(glyph.width - .01f, glyph.height / 2));
        assertTrue(thinLeft.contains(.01f, glyph.height / 2));
        for (int row = 0; row < 51; row++) {
            for (int column = 0; column < 51; column++) {
                float x = glyph.width * (column + .37f) / 51;
                float y = glyph.height * (row + .37f) / 51;
                assertEquals(filledRight.contains(x, y), filledLeft.contains(glyph.width - x, y));
                assertEquals(thinRight.contains(x, y), thinLeft.contains(glyph.width - x, y));
            }
        }
    }

    private static int[] supportedCodePoints() {
        int[] result = new int[164];
        for (int i = 0; i < 160; i++) result[i] = 0x2500 + i;
        for (int i = 0; i < 4; i++) result[160 + i] = 0xE0B0 + i;
        return result;
    }

    private static String label(int codePoint) {
        return String.format("U+%04X", codePoint);
    }

    private static Recording render(CellGlyph glyph, int codePoint) {
        Recording sink = new Recording(glyph.width, glyph.height);
        assertTrue(label(codePoint), glyph.draw(codePoint, sink));
        return sink;
    }

    /** Independent oracle: read arm names from the JDK Unicode database, not the product table. */
    private static int[] namedArms(String name) {
        String[] clauses = name.replace("BOX DRAWINGS ", "").replace("ARC ", "").split(" AND ");
        int[] result = new int[4];
        int inherited = namedWeight(clauses[0].split(" ")[0]);
        for (String clause : clauses) {
            int weight = 0;
            for (String word : clause.split(" ")) weight = Math.max(weight, namedWeight(word));
            if (weight == 0) weight = inherited;
            assertTrue(name, weight != 0);
            if (clause.contains("LEFT") || clause.contains("HORIZONTAL")) result[0] = weight;
            if (clause.contains("RIGHT") || clause.contains("HORIZONTAL")) result[1] = weight;
            if (clause.contains("UP") || clause.contains("VERTICAL")) result[2] = weight;
            if (clause.contains("DOWN") || clause.contains("VERTICAL")) result[3] = weight;
        }
        return result;
    }

    private static int namedWeight(String word) {
        return word.equals("LIGHT") || word.equals("SINGLE") ? 1 : word.equals("HEAVY") ? 2
            : word.equals("DOUBLE") ? 3 : 0;
    }

    private static void assertRect(CellGlyph glyph, int codePoint, float left, float top, float right, float bottom) {
        Recording sink = render(glyph, codePoint);
        assertEquals(1, sink.rectangles.size());
        assertTrue(sink.contours.isEmpty());
        assertArrayEquals(label(codePoint), new float[]{left, top, right, bottom}, sink.rectangles.get(0), EPSILON);
    }

    /** Records primitive bounds and models Canvas's nonzero winding fill, without Android or AWT. */
    private static final class Recording implements CellGlyph.Sink {
        final float width, height;
        final List<float[]> rectangles = new ArrayList<>();
        final List<List<float[]>> contours = new ArrayList<>();
        List<float[]> current;
        boolean open;
        int commands, quadratics;

        Recording(float width, float height) {
            this.width = width;
            this.height = height;
        }

        private void point(float x, float y) {
            assertTrue("finite x", Float.isFinite(x));
            assertTrue("finite y", Float.isFinite(y));
            assertTrue("x in cell: " + x + " / " + width, x >= 0 && x <= width);
            assertTrue("y in cell: " + y + " / " + height, y >= 0 && y <= height);
        }

        @Override public void rectangle(float left, float top, float right, float bottom) {
            point(left, top);
            point(right, bottom);
            assertTrue(left < right && top < bottom);
            rectangles.add(new float[]{left, top, right, bottom});
            commands++;
        }

        @Override public void moveTo(float x, float y) {
            assertFalse(open);
            point(x, y);
            current = new ArrayList<>();
            current.add(new float[]{x, y});
            contours.add(current);
            open = true;
            commands++;
        }

        @Override public void lineTo(float x, float y) {
            assertTrue(open);
            point(x, y);
            current.add(new float[]{x, y});
            commands++;
        }

        @Override public void quadTo(float controlX, float controlY, float x, float y) {
            assertTrue(open);
            point(controlX, controlY);
            point(x, y);
            float[] start = current.get(current.size() - 1);
            for (int step = 1; step <= 32; step++) {
                float t = step / 32f, u = 1 - t;
                current.add(new float[]{u * u * start[0] + 2 * u * t * controlX + t * t * x,
                    u * u * start[1] + 2 * u * t * controlY + t * t * y});
            }
            commands++;
            quadratics++;
        }

        @Override public void closePath() {
            assertTrue(open);
            assertTrue(current.size() >= 3);
            open = false;
            commands++;
        }

        double rectangleArea() {
            double area = 0;
            for (float[] rect : rectangles) area += (rect[2] - rect[0]) * (double) (rect[3] - rect[1]);
            return area;
        }

        boolean contains(float x, float y) {
            for (float[] rect : rectangles) {
                if (x >= rect[0] && x < rect[2] && y >= rect[1] && y < rect[3]) return true;
            }
            int winding = 0;
            for (List<float[]> contour : contours) {
                for (int i = 0; i < contour.size(); i++) {
                    float[] a = contour.get(i), b = contour.get((i + 1) % contour.size());
                    float cross = (b[0] - a[0]) * (y - a[1]) - (x - a[0]) * (b[1] - a[1]);
                    if (a[1] <= y && b[1] > y && cross > 0) winding++;
                    if (a[1] > y && b[1] <= y && cross < 0) winding--;
                }
            }
            return winding != 0;
        }
    }
}
