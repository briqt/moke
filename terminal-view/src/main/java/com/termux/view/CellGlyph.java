package com.termux.view;

/** Cell-local geometry for the explicitly supported terminal graphics, independent of Android. */
final class CellGlyph {
    interface Sink {
        /** Axis-aligned fills; these can be batched and rasterized without antialiasing. */
        void rectangle(float left, float top, float right, float bottom);
        /** Closed, antialiased contours for diagonal edges and rounded corners. */
        void moveTo(float x, float y);
        void lineTo(float x, float y);
        void quadTo(float controlX, float controlY, float x, float y);
        void closePath();
    }

    private static final int NONE = 0, LIGHT = 1, HEAVY = 2, DOUBLE = 3;
    private static final int LEFT = 0, RIGHT = 1, UP = 2, DOWN = 3;

    // Two bits per arm, ordered LEFT, RIGHT, UP, DOWN: absent/light/heavy/double.
    // The connection data follows the Unicode Box Drawings character names. Dashes and
    // arcs keep their arm weights here; diagonals have no axis-aligned arms.
    private static final int[] CONNECTIONS = {
        0x05, 0x0A, 0x50, 0xA0, 0x05, 0x0A, 0x50, 0xA0, // U+2500–U+2507
        0x05, 0x0A, 0x50, 0xA0, 0x44, 0x48, 0x84, 0x88, // U+2508–U+250F
        0x41, 0x42, 0x81, 0x82, 0x14, 0x18, 0x24, 0x28, // U+2510–U+2517
        0x11, 0x12, 0x21, 0x22, 0x54, 0x58, 0x64, 0x94, // U+2518–U+251F
        0xA4, 0x68, 0x98, 0xA8, 0x51, 0x52, 0x61, 0x91, // U+2520–U+2527
        0xA1, 0x62, 0x92, 0xA2, 0x45, 0x46, 0x49, 0x4A, // U+2528–U+252F
        0x85, 0x86, 0x89, 0x8A, 0x15, 0x16, 0x19, 0x1A, // U+2530–U+2537
        0x25, 0x26, 0x29, 0x2A, 0x55, 0x56, 0x59, 0x5A, // U+2538–U+253F
        0x65, 0x95, 0xA5, 0x66, 0x69, 0x96, 0x99, 0x6A, // U+2540–U+2547
        0x9A, 0xA6, 0xA9, 0xAA, 0x05, 0x0A, 0x50, 0xA0, // U+2548–U+254F
        0x0F, 0xF0, 0x4C, 0xC4, 0xCC, 0x43, 0xC1, 0xC3, // U+2550–U+2557
        0x1C, 0x34, 0x3C, 0x13, 0x31, 0x33, 0x5C, 0xF4, // U+2558–U+255F
        0xFC, 0x53, 0xF1, 0xF3, 0x4F, 0xC5, 0xCF, 0x1F, // U+2560–U+2567
        0x35, 0x3F, 0x5F, 0xF5, 0xFF, 0x44, 0x41, 0x11, // U+2568–U+256F
        0x14, 0x00, 0x00, 0x00, 0x01, 0x10, 0x04, 0x40, // U+2570–U+2577
        0x02, 0x20, 0x08, 0x80, 0x09, 0x90, 0x06, 0x60  // U+2578–U+257F
    };
    // Upper left, upper right, lower left, lower right occupy bits 0 through 3.
    private static final int[] QUADRANTS = {4, 8, 1, 13, 9, 7, 11, 2, 6, 14};

    final float width;
    final float height;
    final float lightStroke;

    CellGlyph(float width, float height) {
        if (!Float.isFinite(width) || !Float.isFinite(height) || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Cell dimensions must be finite and positive");
        }
        this.width = width;
        this.height = height;
        float smallerSide = Math.min(width, height);
        // Use one metric for both axes. Keep the two double-line rails and their gap
        // distinct even in very small cells; do not round each cell's edges separately.
        lightStroke = Math.min(Math.max(1f, Math.round(smallerSide / 8f)), smallerSide / 5f);
    }

    static boolean supports(int codePoint) {
        return (codePoint >= 0x2500 && codePoint <= 0x259F)
            || (codePoint >= 0xE0B0 && codePoint <= 0xE0B3);
    }

    /** Emits bounded geometry, without allocating per glyph. Unsupported code points emit nothing. */
    boolean draw(int codePoint, Sink sink) {
        if (!supports(codePoint)) return false;
        if (codePoint >= 0xE0B0) {
            powerline(codePoint, sink);
        } else if (codePoint >= 0x2580) {
            block(codePoint, sink);
        } else {
            box(codePoint, sink);
        }
        return true;
    }

    private void box(int codePoint, Sink sink) {
        int connection = CONNECTIONS[codePoint - 0x2500];
        int dashCount = codePoint >= 0x2504 && codePoint <= 0x2507 ? 3
            : codePoint >= 0x2508 && codePoint <= 0x250B ? 4
            : codePoint >= 0x254C && codePoint <= 0x254F ? 2 : 0;
        if (dashCount != 0) {
            boolean horizontal = arm(connection, LEFT) != NONE;
            dashed(sink, horizontal, stroke(arm(connection, horizontal ? LEFT : UP)), dashCount);
        } else if (codePoint >= 0x256D && codePoint <= 0x2570) {
            rounded(sink, arm(connection, RIGHT) != NONE ? 1 : -1,
                arm(connection, DOWN) != NONE ? 1 : -1);
        } else if (codePoint >= 0x2571 && codePoint <= 0x2573) {
            if (codePoint != 0x2572) diagonal(sink, true);
            if (codePoint != 0x2571) diagonal(sink, false);
        } else {
            int left = arm(connection, LEFT), right = arm(connection, RIGHT);
            int up = arm(connection, UP), down = arm(connection, DOWN);
            arm(sink, LEFT, left, right, up, down);
            arm(sink, RIGHT, right, left, up, down);
            arm(sink, UP, up, down, left, right);
            arm(sink, DOWN, down, up, left, right);
        }
    }

    private static int arm(int connection, int direction) {
        return (connection >> (direction * 2)) & 3;
    }

    private float stroke(int style) {
        return style == NONE ? 0 : style == HEAVY ? lightStroke * 2
            : style == DOUBLE ? lightStroke * 3 : lightStroke;
    }

    private void arm(Sink sink, int direction, int style, int opposite, int negative, int positive) {
        if (style == NONE) return;
        int sign = direction == LEFT || direction == UP ? -1 : 1;
        boolean horizontal = direction == LEFT || direction == RIGHT;
        if (style == DOUBLE) {
            for (int lane = -1; lane <= 1; lane += 2) {
                int preferred = lane < 0 ? negative : positive;
                int other = lane < 0 ? positive : negative;
                float end;
                if (preferred == DOUBLE) {
                    // Turn into the near rail of the adjacent double arm. At a cross
                    // this leaves the middle open instead of making a filled junction.
                    end = sign * lightStroke / 2;
                } else if (opposite == DOUBLE) {
                    end = 0; // this rail continues straight through a T junction
                } else if (other == DOUBLE) {
                    // The outer rail of a corner travels beyond the cell centre.
                    end = -sign * lightStroke * 1.5f;
                } else {
                    end = -sign * Math.max(stroke(negative), stroke(positive)) / 2;
                }
                segment(sink, horizontal, sign, lane * lightStroke, lightStroke, end);
            }
        } else {
            float end;
            if (opposite == NONE && (negative == DOUBLE || positive == DOUBLE)) {
                // A single branch meets the near rail of a double crossbar, but a
                // single/double corner must reach the far rail to connect both lines.
                end = negative == DOUBLE && positive == DOUBLE
                    ? sign * lightStroke / 2 : -sign * lightStroke * 1.5f;
            } else {
                end = -sign * Math.max(stroke(negative), stroke(positive)) / 2;
            }
            segment(sink, horizontal, sign, 0, stroke(style), end);
        }
    }

    /** end is the signed boundary, relative to the centre, of this arm's inner end. */
    private void segment(Sink sink, boolean horizontal, int sign, float offset, float thickness, float end) {
        float half = thickness / 2;
        if (horizontal) {
            rect(sink, sign < 0 ? 0 : width / 2 + end, height / 2 + offset - half,
                sign < 0 ? width / 2 + end : width, height / 2 + offset + half);
        } else {
            rect(sink, width / 2 + offset - half, sign < 0 ? 0 : height / 2 + end,
                width / 2 + offset + half, sign < 0 ? height / 2 + end : height);
        }
    }

    private void dashed(Sink sink, boolean horizontal, float thickness, int count) {
        float length = horizontal ? width : height;
        float pitch = length / count;
        float halfGap = pitch / 6;
        for (int i = 0; i < count; i++) {
            float start = i * pitch + halfGap, end = (i + 1) * pitch - halfGap;
            if (horizontal) {
                rect(sink, start, (height - thickness) / 2, end, (height + thickness) / 2);
            } else {
                rect(sink, (width - thickness) / 2, start, (width + thickness) / 2, end);
            }
        }
    }

    private void rounded(Sink sink, int horizontalSign, int verticalSign) {
        float cx = width / 2, cy = height / 2;
        float radius = Math.min(cx, cy) * .75f, half = lightStroke / 2;
        // Keep the straight endpoints in the non-antialiased batch, including at cell edges.
        if (horizontalSign > 0) rect(sink, cx + radius, cy - half, width, cy + half);
        else rect(sink, 0, cy - half, cx - radius, cy + half);
        if (verticalSign > 0) rect(sink, cx - half, cy + radius, cx + half, height);
        else rect(sink, cx - half, 0, cx + half, cy - radius);
        sink.moveTo(cx + horizontalSign * radius, cy - verticalSign * half);
        sink.quadTo(cx - horizontalSign * half, cy - verticalSign * half,
            cx - horizontalSign * half, cy + verticalSign * radius);
        sink.lineTo(cx + horizontalSign * half, cy + verticalSign * radius);
        sink.quadTo(cx + horizontalSign * half, cy + verticalSign * half,
            cx + horizontalSign * radius, cy + verticalSign * half);
        sink.closePath();
    }

    private void diagonal(Sink sink, boolean rising) {
        double length = Math.hypot(width, height);
        float dx = (float) (lightStroke * (length / height) / 2);
        float dy = (float) (lightStroke * (length / width) / 2);
        // The six-vertex outline is the diagonal strip intersected with the cell,
        // rather than a stroked line whose caps would extend into neighbouring cells.
        // Keep both contours clockwise so their overlap in U+2573 cannot cancel.
        sink.moveTo(0, rising ? height : 0);
        if (rising) {
            sink.lineTo(0, height - dy);
            sink.lineTo(width - dx, 0);
            sink.lineTo(width, 0);
            sink.lineTo(width, dy);
            sink.lineTo(dx, height);
        } else {
            sink.lineTo(dx, 0);
            sink.lineTo(width, height - dy);
            sink.lineTo(width, height);
            sink.lineTo(width - dx, height);
            sink.lineTo(0, dy);
        }
        sink.closePath();
    }

    private void block(int codePoint, Sink sink) {
        if (codePoint == 0x2580) {
            rect(sink, 0, 0, width, height / 2);
        } else if (codePoint <= 0x2588) {
            rect(sink, 0, height * ((0x2588 - codePoint) / 8f), width, height);
        } else if (codePoint <= 0x258F) {
            rect(sink, 0, 0, width * ((0x2590 - codePoint) / 8f), height);
        } else if (codePoint == 0x2590) {
            rect(sink, width / 2, 0, width, height);
        } else if (codePoint <= 0x2593) {
            shade(codePoint - 0x2590, sink);
        } else if (codePoint == 0x2594) {
            rect(sink, 0, 0, width, height / 8);
        } else if (codePoint == 0x2595) {
            rect(sink, width * .875f, 0, width, height);
        } else {
            int mask = QUADRANTS[codePoint - 0x2596];
            for (int quadrant = 0; quadrant < 4; quadrant++) {
                if ((mask & (1 << quadrant)) == 0) continue;
                boolean right = (quadrant & 1) != 0, bottom = (quadrant & 2) != 0;
                rect(sink, right ? width / 2 : 0, bottom ? height / 2 : 0,
                    right ? width : width / 2, bottom ? height : height / 2);
            }
        }
    }

    private void shade(int level, Sink sink) {
        // An even 4 by 8 stipple tile has exactly 1/4, 1/2 or 3/4 coverage at any
        // cell size. It repeats across cell boundaries and bounds the geometry work.
        for (int row = 0; row < 8; row++) {
            for (int column = 0; column < 4; column++) {
                boolean light = (row & 1) == 0 && (column & 1) == 0;
                boolean fill = level == 1 ? light : level == 2 ? ((row + column) & 1) == 0 : !light;
                if (fill) rect(sink, width * (column / 4f), height * (row / 8f),
                    width * ((column + 1) / 4f), height * ((row + 1) / 8f));
            }
        }
    }

    private void powerline(int codePoint, Sink sink) {
        boolean left = codePoint >= 0xE0B2;
        float edge = left ? width : 0, tip = left ? 0 : width;
        if ((codePoint & 1) == 0) {
            sink.moveTo(edge, 0);
            sink.lineTo(tip, height / 2);
            sink.lineTo(edge, height);
        } else {
            float halfHeight = height / 2;
            float length = (float) Math.hypot(width, halfHeight);
            float dx = lightStroke * (length / halfHeight) / 2;
            float dy = lightStroke * (length / width) / 2;
            float nearEdge = left ? width - dx : dx;
            float innerTip = left ? dx : width - dx;
            sink.moveTo(edge, 0);
            sink.lineTo(nearEdge, 0);
            sink.lineTo(tip, halfHeight - dy);
            sink.lineTo(tip, halfHeight + dy);
            sink.lineTo(nearEdge, height);
            sink.lineTo(edge, height);
            sink.lineTo(edge, height - dy);
            sink.lineTo(innerTip, halfHeight);
            sink.lineTo(edge, dy);
        }
        sink.closePath();
    }

    private void rect(Sink sink, float left, float top, float right, float bottom) {
        left = Math.max(0, left);
        top = Math.max(0, top);
        right = Math.min(width, right);
        bottom = Math.min(height, bottom);
        if (left < right && top < bottom) sink.rectangle(left, top, right, bottom);
    }
}
