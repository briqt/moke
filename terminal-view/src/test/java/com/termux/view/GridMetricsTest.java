package com.termux.view;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 格子几何的约定（见 {@link GridMetrics} 的类注释）。这些数字决定了终端看起来"正不正"，
 * 所以每一条都写死成断言：改坏了必须有人看见。
 */
public class GridMetricsTest {

    private static final float EPS = 0.001f;

    /** 等宽字体：ASCII 样本的 advance 完全一致，格子宽就是它，与取样哪个字形无关。 */
    @Test
    public void cellWidthIsTheSharedAdvanceOfAMonoFont() {
        float[] advances = new float[95];
        for (int i = 0; i < advances.length; i++) advances[i] = 22.796875f;
        assertEquals(22.796875f, GridMetrics.cellWidth(advances), EPS);
    }

    /** 上游按 'X' 单点取样：'X' 一旦是异类字形，整个网格就歪了。众数对这种情形稳健。 */
    @Test
    public void cellWidthIgnoresAnOutlierGlyph() {
        float[] advances = new float[]{22.8f, 22.8f, 22.8f, 26.5f, 22.8f, 12.0f, 22.8f};
        assertEquals(22.8f, GridMetrics.cellWidth(advances), EPS);
    }

    /** 非等宽字体（用户硬选的比例字体）：退化成"最常见宽度"，至少不会因为一个极值字宽崩掉。 */
    @Test
    public void cellWidthFallsBackToTheMostCommonWidthForProportionalFonts() {
        float[] advances = new float[]{18f, 18f, 24f, 18f, 30f, 12f};
        assertEquals(18f, GridMetrics.cellWidth(advances), EPS);
    }

    @Test
    public void cellWidthIgnoresNonPositiveAndEmptySamples() {
        assertEquals(0f, GridMetrics.cellWidth(null), EPS);
        assertEquals(0f, GridMetrics.cellWidth(new float[0]), EPS);
        assertEquals(0f, GridMetrics.cellWidth(new float[]{0f, -3f, Float.NaN}), EPS);
        assertEquals(20f, GridMetrics.cellWidth(new float[]{0f, 20f, 20f}), EPS);
    }

    /** 样本量不出正数时退回估计值：格子宽为 0 会让列数计算除零，比估错严重得多。 */
    @Test
    public void cellWidthFallsBackWhenTheSampleHasNoPositiveAdvance() {
        assertEquals(20f, GridMetrics.cellWidthOr(null, 20f), EPS);
        assertEquals(20f, GridMetrics.cellWidthOr(new float[]{0f, Float.NaN}, 20f), EPS);
        assertEquals(1f, GridMetrics.cellWidthOr(new float[0], 0f), EPS);
        assertEquals(22.5f, GridMetrics.cellWidthOr(new float[]{22.5f, 22.5f}, 20f), EPS);
    }

    @Test
    public void lineHeightIsTheFontSpacingTimesTheMultiplierRoundedUp() {
        assertEquals(48, GridMetrics.lineHeight(48f, 1f));
        assertEquals(49, GridMetrics.lineHeight(48.2f, 1f));
        assertEquals(63, GridMetrics.lineHeight(48f, 1.3f));   // 62.4 → 63，宁可多一像素
        assertEquals(34, GridMetrics.lineHeight(48f, 0.7f));   // 33.6 → 34
    }

    /** 行距倍率多出来的高度一半在基线上、一半在基线下：文字在行内居中，而不是贴住行顶。 */
    @Test
    public void halfLeadingSplitsTheExtraLineHeight() {
        assertEquals(0f, GridMetrics.halfLeading(48, 48f), EPS);
        assertEquals(7.5f, GridMetrics.halfLeading(63, 48f), EPS);
        // 压紧时不向上偏：字形本来就在靠拢，再上移基线只会让降部更难读。
        assertEquals(0f, GridMetrics.halfLeading(34, 48f), EPS);
    }

    /** 溢出上限：每侧 0.15em → 总量 0.3em（默认配置下 1em 图标在 0.6em 格子里画成 0.9em）。 */
    private static final float OVERFLOW = 0.3f;

    /**
     * 缩放策略：压缩模式等比缩小；宽字形（2 列一字）窄于跨度时等比放大填满；
     * 比格子宽的外来字形按设计尺寸溢出，但两侧各不超过 allowance/2；窄字形不放大。
     * 任何情形都保持长宽比。
     */
    @Test
    public void placementScaleNeverStretchesAndFillsWideSpans() {
        // 等宽字体：字形 advance 与格子相等，什么都不做。
        assertEquals(1f, GridMetrics.placementScale(0.6f, 0.6f, false, false, OVERFLOW), EPS);
        assertEquals(1f, GridMetrics.placementScale(0.6f, 0.6f, true, false, OVERFLOW), EPS);
        // 1em 的 Nerd 图标落进 0.6em 的格子：0.6 + 0.3 = 0.9em，即两侧各溢出 0.15em。
        assertEquals(0.9f, GridMetrics.placementScale(1.0f, 0.6f, false, false, OVERFLOW), EPS);
        // 溢出额度给足时保持设计尺寸，一点都不缩。
        assertEquals(1f, GridMetrics.placementScale(1.0f, 0.6f, false, false, 0.5f), EPS);
        // 不给溢出额度就退化成"塞进格子"。
        assertEquals(0.6f, GridMetrics.placementScale(1.0f, 0.6f, false, false, 0f), EPS);
        // 压缩模式（字距 < 1）一律等比缩小到格子宽，与溢出额度无关。
        assertEquals(0.6f, GridMetrics.placementScale(1.0f, 0.6f, false, true, OVERFLOW), EPS);
        // 1em 的 CJK 回退字形落进 2 格 = 1.2em：放大 1.2 倍填满，而不是两侧留白。
        assertEquals(1.2f, GridMetrics.placementScale(1.0f, 1.2f, true, false, OVERFLOW), EPS);
        // 宽字形的放大有上限：0.6em advance 却声明 2 列的 emoji 不该被吹成 2 倍。
        assertEquals(1.5f, GridMetrics.placementScale(0.6f, 1.2f, true, false, OVERFLOW), EPS);
        // 窄字形窄于格子：不放大（否则会挤到邻居）。
        assertEquals(1f, GridMetrics.placementScale(0.4f, 0.6f, false, false, OVERFLOW), EPS);
        assertEquals(1f, GridMetrics.placementScale(1.0f, 0f, false, false, OVERFLOW), EPS);
        assertEquals(1f, GridMetrics.placementScale(0f, 0.6f, true, false, OVERFLOW), EPS);
    }

    /** 居中偏移：窄于格子时两侧留白，宽于格子时两侧对称溢出。 */
    @Test
    public void centerOffsetSplitsTheSlackEvenly() {
        assertEquals(0f, GridMetrics.centerOffset(0.6f, 0.6f), EPS);
        assertEquals(0.1f, GridMetrics.centerOffset(1.0f, 1.2f), EPS);
        // 1em 图标落进 0.6em 格子：两侧各溢出 0.2em，而不是全部挤向右边。
        assertEquals(-0.2f, GridMetrics.centerOffset(1.0f, 0.6f), EPS);
        assertEquals(0f, GridMetrics.centerOffset(0.5f, 0f), EPS);
    }

    /** 组合性质：缩放后的段绝不超出"跨度 + 溢出额度"，且溢出两侧对称；长宽比永远不变。 */
    @Test
    public void placementIsContainedOrBoundedAndSymmetric() {
        float[] inks = {0.3f, 0.6f, 0.75f, 1.0f, 1.2f};
        float[] spans = {0.42f, 0.6f, 0.78f, 1.2f};
        for (float ink : inks) {
            for (float span : spans) {
                for (boolean wide : new boolean[]{false, true}) {
                    for (boolean condensed : new boolean[]{false, true}) {
                        final float scale = GridMetrics.placementScale(ink, span, wide, condensed, OVERFLOW);
                        final float drawn = ink * scale;
                        final String label = ink + " in " + span + " wide=" + wide + " condensed=" + condensed;
                        assertTrue(label + " -> " + drawn, drawn <= span + OVERFLOW + EPS);
                        if (!wide) assertTrue(label + " narrow glyphs must not grow", scale <= 1f);
                        // 居中：无论缩放与否，落位后必须相对格子中心对称。
                        final float offset = GridMetrics.centerOffset(ink, span);
                        assertEquals(label + " must be centred", (span - ink) / 2f, offset, EPS);
                        assertEquals(label + " must be symmetric", 2 * offset + ink, span, EPS);
                    }
                }
            }
        }
    }
}
