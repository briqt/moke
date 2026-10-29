package com.termux.view;

/** Placement inside an existing terminal grid; never changes the logical column span. */
final class GlyphLayout {
    private GlyphLayout() {}

    static float baselineShift(int naturalLineHeight, int lineHeight) {
        return Math.max(0, lineHeight - naturalLineHeight) / 2f;
    }

    static boolean isSymbol(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.PRIVATE_USE || type == Character.MATH_SYMBOL
            || type == Character.CURRENCY_SYMBOL || type == Character.MODIFIER_SYMBOL
            || type == Character.OTHER_SYMBOL;
    }

    static boolean canBorrow(boolean symbol, int columns, boolean nextBlank,
                             boolean compatibleStyle, boolean cursorConflict, boolean selectionConflict) {
        return symbol && columns == 1 && nextBlank && compatibleStyle && !cursorConflict && !selectionConflict;
    }

    static float scale(float advance, float inkLeft, float inkRight, float availableWidth) {
        float width = Math.max(advance, inkRight) - Math.min(0, inkLeft);
        if (!(availableWidth > 0) || !Float.isFinite(availableWidth) || !(width > 0) || !Float.isFinite(width)) return 1f;
        return Math.min(1f, availableWidth / width);
    }

    static float origin(float advance, float inkLeft, float inkRight,
                        float logicalWidth, float availableWidth, float scale) {
        float centered = (logicalWidth - advance * scale) / 2f;
        float first = -inkLeft * scale;
        float last = availableWidth - inkRight * scale;
        return Math.max(first, Math.min(centered, last));
    }
}
