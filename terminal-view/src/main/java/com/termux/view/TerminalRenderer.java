package com.termux.view;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Typeface;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.TextStyle;
import com.termux.terminal.WcWidth;

/**
 * Renderer of a {@link TerminalEmulator} into a {@link Canvas}.
 * <p/>
 * Saves font metrics, so needs to be recreated each time the typeface or font size changes.
 * <p/>
 * moke: 格子几何全部交给 {@link GridMetrics} 计算，本类只负责把结果画出去。与上游 termux 的关键差别：
 * <ul>
 *   <li>列宽取 ASCII 样本 advance 的众数（不再单点 {@code measureText("X")}）。</li>
 *   <li>字距倍数缩放的是<b>格子宽</b>，不再用 {@code Paint.setLetterSpacing}——后者在固定网格里
 *       只会让一段文字的 advance 与列数脱钩，最终被横向归一化抵消掉。</li>
 *   <li>不再对宽度不符的 run 做 {@code canvas.scale(k, 1)}：压缩模式等比缩小、宽字形（2 列）窄于
 *       跨度时等比放大填满、比格子宽的外来字形（Nerd 图标）按设计尺寸对称溢出、其余居中，
 *       字形永不被横向拉伸（详见 {@link GridMetrics#placementScale}）。</li>
 *   <li>行距余量的一半给基线之上，文字在行内视觉居中。</li>
 *   <li>连字（liga/calt）保持开启：一段文字的宽度按 shaping 后的结果量，等宽连字字形的 advance
 *       等于它替换掉的列宽之和，因此连字与网格可以共存。</li>
 * </ul>
 */
public final class TerminalRenderer {

    final int mTextSize;
    final Typeface mTypeface;
    private final Paint mTextPaint = new Paint();

    /** 一个格子的宽度：ASCII 样本 advance 的众数 × 字距倍数。 */
    final float mFontWidth;
    /** 一个格子的高度：字体自然行距 × 行距倍数。 */
    final int mFontLineSpacing;
    /** The {@link Paint#ascent()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    private final int mFontAscent;
    /** 行距余量的一半，文字基线相对行顶下移这么多。 */
    private final float mHalfLeading;
    /** The {@link #mFontLineSpacing} + {@link #mFontAscent}. */
    final int mFontLineSpacingAndAscent;
    /** 字距倍数为 1 时的格子宽（字体自己的 advance），用来判断是否处于压缩模式。 */
    private final float mNaturalFontWidth;
    /** 压缩模式：用户把字距调到 < 1，格子比字形自己的 advance 还窄，只能等比缩小。 */
    private final boolean mCondensed;
    /** 外来字形允许向两侧溢出的总量（px），见 {@link #ICON_OVERFLOW_EM_PER_SIDE}。 */
    private final float mOverflowAllowance;

    /**
     * 外来字形（Nerd 图标这类按 1em 设计、比格子宽的字形）允许向两侧各溢出的 em 数。
     *
     * <p>0.15em：1em 的图标在 0.6em 的格子里画成 0.9em——保住九成设计尺寸，横向只挤掉邻居
     * 0.15em（约四分之一格，而图标后面通常本来就跟着空格）。再大就会压到相邻字符，再小就回到
     * "图标偏小"。取值理由见 docs/terminal-experience.md 的 ADR-2。
     */
    private static final float ICON_OVERFLOW_EM_PER_SIDE = 0.15f;

    /** 可打印 ASCII 的 advance（每帧都要用，预先量好）。 */
    private final float[] asciiMeasures = new float[0x7f];
    /** 非 ASCII 码点的 advance 缓存：满屏 CJK 时每帧要量上千次，而 measureText 会走一遍 shaping。 */
    private final java.util.HashMap<Integer, Float> codePointAdvances = new java.util.HashMap<>();
    private static final int ADVANCE_CACHE_LIMIT = 8192;

    public TerminalRenderer(int textSize, Typeface typeface) {
        this(textSize, typeface, 1f, 1f);
    }

    /**
     * @param lineSpacingMul  行距倍数，缩放格子高度（1.0 = 字体自然行距）
     * @param letterSpacingMul 字距倍数，缩放格子宽度（1.0 = 字体自然 advance）
     */
    public TerminalRenderer(int textSize, Typeface typeface, float lineSpacingMul, float letterSpacingMul) {
        mTextSize = textSize;
        mTypeface = typeface;

        mTextPaint.setTypeface(typeface);
        mTextPaint.setAntiAlias(true);
        // 亚像素定位：字形落在它该在的 x 上，而不是被舍入到整像素（网格对齐更准）。
        mTextPaint.setSubpixelText(true);
        mTextPaint.setTextSize(textSize);
        // 连字（liga/calt）保持开启：一段文字的宽度按 shaping 后的结果量（见 render()），
        // 而等宽连字字体的连字字形 advance 本来就等于它替换掉的列宽之和，所以不会破坏网格。

        final float fontSpacing = mTextPaint.getFontSpacing();
        mFontLineSpacing = GridMetrics.lineHeight(fontSpacing, lineSpacingMul);
        mFontAscent = (int) Math.ceil(mTextPaint.ascent());
        mHalfLeading = GridMetrics.halfLeading(mFontLineSpacing, fontSpacing);
        mFontLineSpacingAndAscent = mFontLineSpacing + mFontAscent;
        // 格子宽：ASCII 样本的众数。样本量不出正数（字体坏了/纯符号字体当主字体）时退回 0.6em 估计值，
        // 宁可估错也不能是 0——0 会让 TerminalView 的列数计算除零。
        mNaturalFontWidth = GridMetrics.cellWidthOr(measureAsciiAdvances(), textSize * 0.6f);
        mFontWidth = mNaturalFontWidth * letterSpacingMul;
        mCondensed = mFontWidth < mNaturalFontWidth - 0.01f;
        mOverflowAllowance = 2f * ICON_OVERFLOW_EM_PER_SIDE * textSize;
    }

    /** 量一遍可打印 ASCII 的 advance，作为定格子宽的样本。 */
    private float[] measureAsciiAdvances() {
        final int first = 0x20, last = 0x7e;
        final float[] sample = new float[last - first + 1];
        final char[] buffer = new char[1];
        for (int codePoint = first; codePoint <= last; codePoint++) {
            buffer[0] = (char) codePoint;
            final float advance = mTextPaint.measureText(buffer, 0, 1);
            asciiMeasures[codePoint] = advance;
            sample[codePoint - first] = advance;
        }
        return sample;
    }

    /** 一个码点的 advance：ASCII 直接查表，其它码点查缓存，未命中才真正量一次。 */
    private float advanceOf(char[] line, int charIndex, int charsForCodePoint, int codePoint) {
        if (codePoint < asciiMeasures.length) return asciiMeasures[codePoint];
        final Float cached = codePointAdvances.get(codePoint);
        if (cached != null) return cached;
        final float advance = mTextPaint.measureText(line, charIndex, charsForCodePoint);
        if (codePointAdvances.size() < ADVANCE_CACHE_LIMIT) codePointAdvances.put(codePoint, advance);
        return advance;
    }

    /** Render the terminal to a canvas with at a specified row scroll, and an optional rectangular selection. */
    public final void render(TerminalEmulator mEmulator, Canvas canvas, int topRow,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2) {
        render(mEmulator, canvas, topRow, 0, selectionY1, selectionY2, selectionX1, selectionX2);
    }

    /** Fractional local history draws one extra row; remote/current-screen coordinates stay unchanged. */
    public final void render(TerminalEmulator mEmulator, Canvas canvas, int topRow, float fraction,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2) {
        final boolean reverseVideo = mEmulator.isReverseVideo();
        final int endRow = Math.min(mEmulator.mRows, topRow + mEmulator.mRows + (fraction > 0 ? 1 : 0));
        final int columns = mEmulator.mColumns;
        final int cursorCol = mEmulator.getCursorCol();
        final int cursorRow = mEmulator.getCursorRow();
        final boolean cursorVisible = mEmulator.shouldCursorBeVisible();
        final TerminalBuffer screen = mEmulator.getScreen();
        final int[] palette = mEmulator.mColors.mCurrentColors;
        final int cursorShape = mEmulator.getCursorStyle();

        if (reverseVideo)
            canvas.drawColor(palette[TextStyle.COLOR_INDEX_FOREGROUND], PorterDuff.Mode.SRC);

        int viewportSave = canvas.save();
        canvas.clipRect(0, mFontLineSpacingAndAscent, canvas.getWidth(), canvas.getHeight());
        canvas.translate(0, -fraction * mFontLineSpacing);
        float heightOffset = mFontLineSpacingAndAscent;
        for (int row = topRow; row < endRow; row++) {
            heightOffset += mFontLineSpacing;

            final int cursorX = (row == cursorRow && cursorVisible) ? cursorCol : -1;
            int selx1 = -1, selx2 = -1;
            if (row >= selectionY1 && row <= selectionY2) {
                if (row == selectionY1) selx1 = selectionX1;
                selx2 = (row == selectionY2) ? selectionX2 : mEmulator.mColumns;
            }

            TerminalRow lineObject = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
            final char[] line = lineObject.mText;
            final int charsUsedInLine = lineObject.getSpaceUsed();

            long lastRunStyle = 0;
            boolean lastRunInsideCursor = false;
            boolean lastRunInsideSelection = false;
            boolean lastRunWide = false;
            boolean lastRunOwnRun = false;
            int lastRunStartColumn = -1;
            int lastRunStartIndex = 0;
            int currentCharIndex = 0;

            for (int column = 0; column < columns; ) {
                final char charAtIndex = line[currentCharIndex];
                final boolean charIsHighsurrogate = Character.isHighSurrogate(charAtIndex);
                final int charsForCodePoint = charIsHighsurrogate ? 2 : 1;
                final int codePoint = charIsHighsurrogate ? Character.toCodePoint(charAtIndex, line[currentCharIndex + 1]) : charAtIndex;
                final int codePointWcWidth = WcWidth.width(codePoint);
                final boolean insideCursor = (cursorX == column || (codePointWcWidth == 2 && cursorX == column + 1));
                final boolean insideSelection = column >= selx1 && column <= selx2;
                final long style = lineObject.getStyle(column);

                // 窄字（1 列）与宽字（2 列）分开成段：宽字要填满它的列跨度，窄字保持原尺寸，
                // 混在一段里就没法分别处理。
                final boolean wide = codePointWcWidth >= 2;
                // 比"自己占的列跨度"还宽的字形（Nerd 图标这类按 1em 设计的外来字形）单独成段：
                // 它按设计尺寸溢出，若与邻格并成一段，drawTextRun 会按它自己的 advance 推进，
                // 相邻图标就会互相错位。单独成段才能各自居中在自己格子里。
                // 用列跨度而不是单格宽来比较：1em 的汉字落在 2 格（1.2em）里并不溢出，
                // 不该被拆成一字一段（整屏中文会多出上千次 drawTextRun）。
                final boolean ownRun = !mCondensed
                    && advanceOf(line, currentCharIndex, charsForCodePoint, codePoint) > codePointWcWidth * mFontWidth + 0.01f;

                if (style != lastRunStyle || insideCursor != lastRunInsideCursor || insideSelection != lastRunInsideSelection
                    || wide != lastRunWide || ownRun || lastRunOwnRun) {
                    if (column == 0) {
                        // Skip first column as there is nothing to draw, just record the current style.
                    } else {
                        final int columnWidthSinceLastRun = column - lastRunStartColumn;
                        final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
                        int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
                        boolean invertCursorTextColor = false;
                        if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                            invertCursorTextColor = true;
                        }
                        drawTextRun(canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun,
                            lastRunStartIndex, charsSinceLastRun, measureRun(line, lastRunStartIndex, charsSinceLastRun),
                            lastRunWide, cursorColor, cursorShape, lastRunStyle, reverseVideo || invertCursorTextColor || lastRunInsideSelection);
                    }
                    lastRunStyle = style;
                    lastRunInsideCursor = insideCursor;
                    lastRunInsideSelection = insideSelection;
                    lastRunWide = wide;
                    lastRunOwnRun = ownRun;
                    lastRunStartColumn = column;
                    lastRunStartIndex = currentCharIndex;
                }
                column += codePointWcWidth;
                currentCharIndex += charsForCodePoint;
                while (currentCharIndex < charsUsedInLine && WcWidth.width(line, currentCharIndex) <= 0) {
                    // Eat combining chars so that they are treated as part of the last non-combining code point,
                    // instead of e.g. being considered inside the cursor in the next run.
                    currentCharIndex += Character.isHighSurrogate(line[currentCharIndex]) ? 2 : 1;
                }
            }

            final int columnWidthSinceLastRun = columns - lastRunStartColumn;
            final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
            int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
            boolean invertCursorTextColor = false;
            if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                invertCursorTextColor = true;
            }
            drawTextRun(canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun, lastRunStartIndex, charsSinceLastRun,
                measureRun(line, lastRunStartIndex, charsSinceLastRun), lastRunWide, cursorColor, cursorShape, lastRunStyle,
                reverseVideo || invertCursorTextColor || lastRunInsideSelection);
        }
        canvas.restoreToCount(viewportSave);
    }

    /**
     * 一段文字在 shaping 之后的实际宽度：连字、组合字符、字距都已经被算进去。
     *
     * <p>这是连字能开启的前提：等宽连字字体（JetBrains Mono、Fira Code…）把 {@code ->} 合成一个字
     * 字形时，那个字形的 advance 正好等于两列宽。逐字累加 advance 会算出 2 × 0.6em = 1.2em，
     * 与 shaping 后的 1.2em 一致；但如果连字真的合并了，逐字累加就错了（它不知道连字的存在）。
     */
    private float measureRun(char[] line, int startIndex, int charCount) {
        return charCount <= 0 ? 0f : mTextPaint.measureText(line, startIndex, charCount);
    }

    private void drawTextRun(Canvas canvas, char[] text, int[] palette, float y, int startColumn, int runWidthColumns,
                             int startCharIndex, int runWidthChars, float runWidthPx, boolean wide, int cursor, int cursorStyle,
                             long textStyle, boolean reverseVideo) {
        int foreColor = TextStyle.decodeForeColor(textStyle);
        final int effect = TextStyle.decodeEffect(textStyle);
        int backColor = TextStyle.decodeBackColor(textStyle);
        final boolean bold = (effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;
        final boolean underline = (effect & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0;
        final boolean italic = (effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0;
        final boolean strikeThrough = (effect & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0;
        final boolean dim = (effect & TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0;

        if ((foreColor & 0xff000000) != 0xff000000) {
            // Let bold have bright colors if applicable (one of the first 8):
            if (bold && foreColor >= 0 && foreColor < 8) foreColor += 8;
            foreColor = palette[foreColor];
        }

        if ((backColor & 0xff000000) != 0xff000000) {
            backColor = palette[backColor];
        }

        // Reverse video here if _one and only one_ of the reverse flags are set:
        final boolean reverseVideoHere = reverseVideo ^ (effect & (TextStyle.CHARACTER_ATTRIBUTE_INVERSE)) != 0;
        if (reverseVideoHere) {
            int tmp = foreColor;
            foreColor = backColor;
            backColor = tmp;
        }

        final float left = startColumn * mFontWidth;
        final float span = runWidthColumns * mFontWidth;
        final float baseline = y - mFontLineSpacingAndAscent + mHalfLeading;
        final float cellTop = y - mFontLineSpacingAndAscent + mFontAscent;

        if (backColor != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
            // Only draw non-default background.
            mTextPaint.setColor(backColor);
            canvas.drawRect(left, cellTop, left + span, y, mTextPaint);
        }

        if (cursor != 0) {
            mTextPaint.setColor(cursor);
            float cursorHeight = mFontLineSpacingAndAscent - mFontAscent;
            float cursorLeft = left, cursorRight = left + span;
            if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) cursorHeight /= 4.;
            else if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) cursorRight -= (cursorRight - cursorLeft) * 3 / 4.;
            canvas.drawRect(cursorLeft, y - cursorHeight, cursorRight, y, mTextPaint);
        }

        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) == 0) {
            if (dim) {
                int red = (0xFF & (foreColor >> 16));
                int green = (0xFF & (foreColor >> 8));
                int blue = (0xFF & foreColor);
                // Dim color handling from libvte which in turn took it from xterm
                // (https://bug735245.bugzilla-attachments.gnome.org/attachment.cgi?id=284267):
                red = red * 2 / 3;
                green = green * 2 / 3;
                blue = blue * 2 / 3;
                foreColor = 0xFF000000 + (red << 16) + (green << 8) + blue;
            }

            mTextPaint.setFakeBoldText(bold);
            mTextPaint.setUnderlineText(underline);
            mTextPaint.setTextSkewX(italic ? -0.35f : 0.f);
            mTextPaint.setStrikeThruText(strikeThrough);
            mTextPaint.setColor(foreColor);

            // 字形落位：压缩模式等比缩小、宽字形填满跨度、外来字形按设计尺寸对称溢出、其余居中；
            // 绝不做 canvas.scale(k, 1) 这种只缩 X 的做法——它会把图标压窄、把 CJK 拉宽。
            final float scale = GridMetrics.placementScale(runWidthPx, span, wide, mCondensed, mOverflowAllowance);
            // 先按设计尺寸居中（窄于格子时两侧留白、宽于格子时两侧对称溢出），再以格子中心为锚点缩放：
            // 缩放前后都保持居中——CJK 放大后正好填满跨度，缩小的外来图标也仍然居中。
            final float x = left + GridMetrics.centerOffset(runWidthPx, span);
            if (scale != 1f) {
                final int save = canvas.save();
                canvas.scale(scale, scale, left + span / 2f, baseline);
                // The text alignment is the default Paint.Align.LEFT.
                canvas.drawTextRun(text, startCharIndex, runWidthChars, startCharIndex, runWidthChars, x, baseline, false, mTextPaint);
                canvas.restoreToCount(save);
            } else {
                canvas.drawTextRun(text, startCharIndex, runWidthChars, startCharIndex, runWidthChars, x, baseline, false, mTextPaint);
            }
        }
    }

    public float getFontWidth() {
        return mFontWidth;
    }

    public int getFontLineSpacing() {
        return mFontLineSpacing;
    }
}
