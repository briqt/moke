package com.termux.view;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.util.SparseArray;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.TextStyle;
import com.termux.terminal.WcWidth;

/** Renders a terminal using one grid for backgrounds, graphics, text and interaction. */
public final class TerminalRenderer {
    final int mTextSize;
    final Typeface mTypeface;
    private final Paint mTextPaint = new Paint();
    private final Paint mBackgroundPaint = new Paint(0);
    final float mFontWidth;
    final int mFontLineSpacing;
    private final int mFontAscent;
    final int mFontLineSpacingAndAscent;
    private final float mBaselineShift;
    private final TerminalGraphics mGraphics;
    private final Rect mTextBounds = new Rect();
    private final Rect mClipBounds = new Rect();
    private final float[] asciiMeasures = new float[127];
    private final int[] asciiLeft = new int[127];
    private final int[] asciiRight = new int[127];
    private final int[] asciiTop = new int[127];
    private final int[] asciiBottom = new int[127];
    private static final int METRIC_CACHE_LIMIT = 1024;
    private final SparseArray<GlyphMetrics> mGlyphMetrics = new SparseArray<>();
    private int mFontEffect = -1;

    private static final class GlyphMetrics {
        final float advance;
        final int left, top, right, bottom;
        GlyphMetrics(float advance, Rect bounds) {
            this.advance = advance;
            left = bounds.left;
            top = bounds.top;
            right = bounds.right;
            bottom = bounds.bottom;
        }
    }
    private int mForeColor;
    private int mBackColor;
    private int mEffect;

    public TerminalRenderer(int textSize, Typeface typeface) {
        this(textSize, typeface, 1f, 0f);
    }

    /** Spacing keeps the existing grid semantics; glyphs themselves are not stretched. */
    public TerminalRenderer(int textSize, Typeface typeface, float lineSpacingMul, float letterSpacingEm) {
        mTextSize = textSize;
        mTypeface = typeface;
        mTextPaint.setTypeface(typeface);
        mTextPaint.setAntiAlias(true);
        mTextPaint.setTextSize(textSize);
        mTextPaint.setLetterSpacing(letterSpacingEm);
        float naturalSpacing = mTextPaint.getFontSpacing();
        mFontLineSpacing = (int) Math.ceil(naturalSpacing * lineSpacingMul);
        mFontAscent = (int) Math.ceil(mTextPaint.ascent());
        mFontLineSpacingAndAscent = mFontLineSpacing + mFontAscent;
        mFontWidth = mTextPaint.measureText("X");
        mBaselineShift = GlyphLayout.baselineShift((int) Math.ceil(naturalSpacing), mFontLineSpacing);
        mGraphics = new TerminalGraphics(mFontWidth, mFontLineSpacing);

        // Letter spacing belongs to the grid; each glyph keeps its natural metrics.
        mTextPaint.setLetterSpacing(0f);
        char[] sample = new char[1];
        for (int i = 0; i < asciiMeasures.length; i++) {
            sample[0] = (char) i;
            asciiMeasures[i] = mTextPaint.measureText(sample, 0, 1);
            mTextPaint.getTextBounds(sample, 0, 1, mTextBounds);
            asciiLeft[i] = mTextBounds.left;
            asciiRight[i] = mTextBounds.right;
            asciiTop[i] = mTextBounds.top;
            asciiBottom[i] = mTextBounds.bottom;
        }
    }

    public final void render(TerminalEmulator emulator, Canvas canvas, int topRow,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2) {
        render(emulator, canvas, topRow, 0, selectionY1, selectionY2, selectionX1, selectionX2);
    }

    /** Fractional history keeps the same viewport and remote/current-screen coordinates. */
    public final void render(TerminalEmulator emulator, Canvas canvas, int topRow, float fraction,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2) {
        boolean reverse = emulator.isReverseVideo();
        int endRow = Math.min(emulator.mRows, topRow + emulator.mRows + (fraction > 0 ? 1 : 0));
        TerminalBuffer screen = emulator.getScreen();
        int[] palette = emulator.mColors.mCurrentColors;
        if (reverse) canvas.drawColor(palette[TextStyle.COLOR_INDEX_FOREGROUND], PorterDuff.Mode.SRC);
        int save = canvas.save();
        canvas.clipRect(0, mFontLineSpacingAndAscent, canvas.getWidth(), canvas.getHeight());
        canvas.translate(0, -fraction * mFontLineSpacing);

        // Complete all backgrounds before any ink, including ink borrowed by a preceding cell.
        for (int pass = 0; pass < 2; pass++) {
            float bottom = mFontLineSpacingAndAscent;
            for (int row = topRow; row < endRow; row++) {
                bottom += mFontLineSpacing;
                int sel1 = -1, sel2 = -1;
                if (row >= selectionY1 && row <= selectionY2) {
                    if (row == selectionY1) sel1 = selectionX1;
                    sel2 = row == selectionY2 ? selectionX2 : emulator.mColumns;
                }
                int cursorX = row == emulator.getCursorRow() && emulator.shouldCursorBeVisible()
                    ? emulator.getCursorCol() : -1;
                TerminalRow line = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
                drawRow(canvas, line, emulator.mColumns, palette, bottom, cursorX,
                    emulator.getCursorStyle(), sel1, sel2, reverse, pass == 0);
            }
        }
        canvas.restoreToCount(save);
    }

    private void drawRow(Canvas canvas, TerminalRow row, int columns, int[] palette, float bottom,
                         int cursorX, int cursorShape, int sel1, int sel2, boolean reverse,
                         boolean backgrounds) {
        char[] text = row.mText;
        int used = row.getSpaceUsed();
        long lastStyle = 0;
        boolean lastCursor = false, lastSelected = false, lastOwnRun = false;
        int lastGraphic = -1;
        int startColumn = 0, startIndex = 0;
        int column = 0, index = 0;
        while (column < columns && index < used) {
            int codePoint = Character.codePointAt(text, index, used);
            int width = Math.max(1, WcWidth.width(codePoint));
            int next = clusterEnd(text, index, used);
            long style = row.getStyle(column);
            boolean cursor = cursorX == column || (width == 2 && cursorX == column + 1);
            boolean selected = selected(column, sel1, sel2);
            boolean ownRun = false;
            int graphic = -1;
            if (!backgrounds) {
                applyFontStyle(style);
                if (next - index == Character.charCount(codePoint) && TerminalGraphics.supports(codePoint)) {
                    graphic = codePoint;
                } else {
                    ownRun = isBorrowableSymbol(codePoint, width)
                        || !gridAligned(measure(text, index, next - index), width * mFontWidth);
                }
            }
            if (column != 0 && (style != lastStyle || cursor != lastCursor || selected != lastSelected
                || ownRun || lastOwnRun || graphic != lastGraphic)) {
                drawRun(canvas, row, palette, bottom, startColumn, column - startColumn,
                    startIndex, index - startIndex, lastStyle, lastCursor, cursorShape,
                    lastSelected, cursorX, sel1, sel2, reverse, backgrounds, columns, lastGraphic);
                startColumn = column;
                startIndex = index;
            }
            lastStyle = style;
            lastCursor = cursor;
            lastSelected = selected;
            lastOwnRun = ownRun;
            lastGraphic = graphic;
            column += width;
            index = next;
        }
        if (column > startColumn) {
            drawRun(canvas, row, palette, bottom, startColumn, column - startColumn,
                startIndex, index - startIndex, lastStyle, lastCursor, cursorShape,
                lastSelected, cursorX, sel1, sel2, reverse, backgrounds, columns, lastGraphic);
        }
    }

    private void drawRun(Canvas canvas, TerminalRow row, int[] palette, float bottom,
                         int startColumn, int columns, int startIndex, int count, long style,
                         boolean cursor, int cursorShape, boolean inSelection, int cursorX,
                         int sel1, int sel2, boolean reverse, boolean backgrounds, int gridColumns, int graphicCodePoint) {
        boolean invertCursor = cursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK;
        resolveStyle(style, palette, reverse || invertCursor || inSelection);
        float left = startColumn * mFontWidth;
        float right = (startColumn + columns) * mFontWidth;
        float top = bottom - mFontLineSpacing;
        if (backgrounds) {
            if (mBackColor != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
                mBackgroundPaint.setColor(mBackColor);
                canvas.drawRect(left, top, right, bottom, mBackgroundPaint);
            }
            if (cursor) {
                int color = palette[TextStyle.COLOR_INDEX_CURSOR];
                if (color != 0) {
                    mBackgroundPaint.setColor(color);
                    float cursorTop = top;
                    float cursorRight = right;
                    if (cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE)
                        cursorTop = bottom - mFontLineSpacing / 4f;
                    else if (cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR)
                        cursorRight = left + (right - left) / 4f;
                    canvas.drawRect(left, cursorTop, cursorRight, bottom, mBackgroundPaint);
                }
            }
            return;
        }
        if ((mEffect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) != 0) return;
        applyFontStyle(style);
        int color = mForeColor;
        if ((mEffect & TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0) {
            int red = ((color >> 16) & 255) * 2 / 3;
            int green = ((color >> 8) & 255) * 2 / 3;
            int blue = (color & 255) * 2 / 3;
            color = 0xff000000 | red << 16 | green << 8 | blue;
        }
        mTextPaint.setColor(color);
        float baseline = bottom - mFontLineSpacingAndAscent + mBaselineShift;
        char[] text = row.mText;
        int codePoint = Character.codePointAt(text, startIndex, startIndex + count);
        if (graphicCodePoint >= 0) {
            mGraphics.drawRepeated(canvas, graphicCodePoint, left, top, color, columns);
        } else {
            float advance = measure(text, startIndex, count);
            if (!isBorrowableSymbol(codePoint, columns) && gridAligned(advance, columns * mFontWidth)) {
                canvas.drawTextRun(text, startIndex, count, startIndex, count, left, baseline, false, mTextPaint);
            } else {
                int index = startIndex, column = startColumn;
                int end = startIndex + count;
                while (index < end) {
                    int cp = Character.codePointAt(text, index, end);
                    int width = Math.max(1, WcWidth.width(cp));
                    int next = clusterEnd(text, index, end);
                    drawGlyph(canvas, row, index, next - index, column, width, baseline,
                        style, cursorX, sel1, sel2, gridColumns, palette, reverse);
                    index = next;
                    column += width;
                }
            }
        }
        drawDecorations(canvas, left, right, baseline);
    }

    private void drawGlyph(Canvas canvas, TerminalRow row, int index, int count, int column,
                           int columns, float baseline, long style, int cursorX, int sel1, int sel2, int gridColumns,
                           int[] palette, boolean reverse) {
        char[] text = row.mText;
        float advance = measure(text, index, count);
        bounds(text, index, count);
        float span = columns * mFontWidth;
        float available = span;
        float inkWidth = Math.max(advance, mTextBounds.right) - Math.min(0, mTextBounds.left);
        int nextIndex = index + count;
        int used = row.getSpaceUsed();
        int codePoint = Character.codePointAt(text, index, nextIndex);
        boolean nextBlank = column + columns < gridColumns && nextIndex < used
            && text[nextIndex] == ' ' && clusterEnd(text, nextIndex, used) == nextIndex + 1;
        boolean cursorConflict = cursorX == column || cursorX == column + columns;
        boolean selectionConflict = selected(column, sel1, sel2) || selected(column + columns, sel1, sel2);
        boolean compatibleStyle = nextBlank && compatibleBlank(style, row.getStyle(column + columns), palette, reverse);
        if (inkWidth > span && GlyphLayout.canBorrow(isBorrowableSymbol(codePoint, columns), columns,
            nextBlank, compatibleStyle, cursorConflict, selectionConflict)) available += mFontWidth;
        float scale = GlyphLayout.scale(advance, mTextBounds.left, mTextBounds.right, available);
        float offset = GlyphLayout.origin(advance, mTextBounds.left, mTextBounds.right, span, available, scale);
        float left = column * mFontWidth;
        int save = canvas.save();
        canvas.getClipBounds(mClipBounds);
        canvas.clipRect(left, mClipBounds.top, left + available, mClipBounds.bottom);
        canvas.translate(left + offset, baseline);
        canvas.scale(scale, scale);
        canvas.drawTextRun(text, index, count, index, count, 0, 0, false, mTextPaint);
        canvas.restoreToCount(save);
    }

    private static int clusterEnd(char[] text, int index, int used) {
        int next = index + Character.charCount(Character.codePointAt(text, index, used));
        while (next < used && WcWidth.width(text, next) <= 0)
            next += Character.charCount(Character.codePointAt(text, next, used));
        return next;
    }

    private static boolean selected(int column, int first, int last) {
        return column >= first && column <= last;
    }

    private static boolean isBorrowableSymbol(int codePoint, int columns) {
        return columns == 1 && codePoint >= 128 && !TerminalGraphics.supports(codePoint) && GlyphLayout.isSymbol(codePoint);
    }

    private boolean gridAligned(float advance, float span) {
        return Math.abs(advance - span) <= mFontWidth * 0.01f;
    }

    private float measure(char[] text, int start, int count) {
        if (count == 1 && text[start] < asciiMeasures.length && mFontEffect == 0)
            return asciiMeasures[text[start]];
        GlyphMetrics cached = metrics(text, start, count);
        return cached == null ? mTextPaint.measureText(text, start, count) : cached.advance;
    }

    private void bounds(char[] text, int start, int count) {
        if (count == 1 && text[start] < asciiMeasures.length && mFontEffect == 0) {
            int cp = text[start];
            mTextBounds.set(asciiLeft[cp], asciiTop[cp], asciiRight[cp], asciiBottom[cp]);
            return;
        }
        GlyphMetrics cached = metrics(text, start, count);
        if (cached == null) mTextPaint.getTextBounds(text, start, count, mTextBounds);
        else mTextBounds.set(cached.left, cached.top, cached.right, cached.bottom);
    }

    /** Per-renderer, style-aware cache; combining/contextual runs are measured as runs. */
    private GlyphMetrics metrics(char[] text, int start, int count) {
        int cp = Character.codePointAt(text, start, start + count);
        if (count != Character.charCount(cp)) return null;
        int fontStyle = ((mFontEffect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0 ? 1 : 0)
            | ((mFontEffect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0 ? 2 : 0);
        int key = (cp << 2) | fontStyle;
        GlyphMetrics cached = mGlyphMetrics.get(key);
        if (cached == null && mGlyphMetrics.size() < METRIC_CACHE_LIMIT) {
            float advance = mTextPaint.measureText(text, start, count);
            mTextPaint.getTextBounds(text, start, count, mTextBounds);
            cached = new GlyphMetrics(advance, mTextBounds);
            mGlyphMetrics.put(key, cached);
        }
        return cached;
    }

    private void applyFontStyle(long style) {
        int effect = TextStyle.decodeEffect(style)
            & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK | TextStyle.CHARACTER_ATTRIBUTE_ITALIC);
        if (effect == mFontEffect) return;
        mFontEffect = effect;
        mTextPaint.setFakeBoldText((effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0);
        mTextPaint.setTextSkewX((effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0 ? -0.35f : 0f);
    }

    private static int color(int index, int[] palette, boolean bright) {
        if ((index & 0xff000000) == 0xff000000) return index;
        if (bright && index >= 0 && index < 8) index += 8;
        return palette[index];
    }

    private void resolveStyle(long style, int[] palette, boolean reverse) {
        mEffect = TextStyle.decodeEffect(style);
        boolean bright = (mEffect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;
        int fore = color(TextStyle.decodeForeColor(style), palette, bright);
        int back = color(TextStyle.decodeBackColor(style), palette, false);
        boolean invert = reverse ^ (mEffect & TextStyle.CHARACTER_ATTRIBUTE_INVERSE) != 0;
        mForeColor = invert ? back : fore;
        mBackColor = invert ? fore : back;
    }

    private boolean compatibleBlank(long style, long nextStyle, int[] palette, boolean reverse) {
        if (style == nextStyle) return true;
        int effect = TextStyle.decodeEffect(nextStyle);
        int decorations = TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE | TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH;
        if (((mEffect | effect) & decorations) != 0) return false;
        boolean invert = reverse ^ (effect & TextStyle.CHARACTER_ATTRIBUTE_INVERSE) != 0;
        boolean bright = (effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;
        int background = invert ? color(TextStyle.decodeForeColor(nextStyle), palette, bright)
            : color(TextStyle.decodeBackColor(nextStyle), palette, false);
        // Undecorated spaces have no foreground ink; equivalent colour encodings are compatible.
        return background == mBackColor;
    }

    private void drawDecorations(Canvas canvas, float left, float right, float baseline) {
        if ((mEffect & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0) {
            float y = baseline + mTextPaint.getUnderlinePosition();
            canvas.drawRect(left, y, right, y + Math.max(1f, mTextPaint.getUnderlineThickness()), mTextPaint);
        }
        if ((mEffect & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0) {
            float y = baseline + mTextPaint.getStrikeThruPosition();
            canvas.drawRect(left, y, right, y + Math.max(1f, mTextPaint.getStrikeThruThickness()), mTextPaint);
        }
    }

    public float getFontWidth() { return mFontWidth; }
    public int getFontLineSpacing() { return mFontLineSpacing; }
}
