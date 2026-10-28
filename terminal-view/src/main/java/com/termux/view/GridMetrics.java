package com.termux.view;

/**
 * 终端格子的几何计算：格子宽、行高、基线偏移、字形落位。
 *
 * <p>纯计算，不引用任何 Android 类型，因此可以在 JVM 单测里完整验证（见
 * {@code terminal-view/src/test/java/com/termux/view/GridMetricsTest.java}）。
 *
 * <p>这里集中了 moke 关于"格子"的三条约定，{@link TerminalRenderer} 只负责把它们喂给 Canvas：
 * <ol>
 *   <li><b>格子是唯一真相</b>：列宽、行高只由字体度量与用户的字距/行距倍数决定，
 *       任何字形都不参与决定格子大小。</li>
 *   <li><b>格子只被缩放，字形不被拉伸</b>：字距/行距改的是格子；字形永远按自己的
 *       advance 出墨，宽于格子时等比缩小（保持长宽比），绝不只缩 X。</li>
 *   <li><b>余量对半分</b>：行距倍数带来的多余高度一半给基线之上、一半给基线之下，
 *       文字在行内保持视觉居中，而不是贴住行顶。</li>
 * </ol>
 */
public final class GridMetrics {

    /** advance 分箱精度：1/64 px。等宽字体的各字形 advance 会落进同一箱。 */
    private static final float BIN = 64f;

    private GridMetrics() {
    }

    /**
     * 从一个等宽字体的样本里定出格子宽度：取各字形 advance 的<b>众数</b>。
     *
     * <p>上游用 {@code measureText("X")} 单点取样，一旦字体不是严格等宽、或 'X' 恰好是异类，
     * 整个网格就会歪掉。众数对"等宽字体 + 个别异类字形"是稳健的：只要多数字形一致，
     * 结果就是那个共同值。
     *
     * @param advances 各字形 advance（px），非正值忽略
     * @return 格子宽度（px）；样本为空时返回 0
     */
    public static float cellWidth(float[] advances) {
        if (advances == null || advances.length == 0) return 0f;
        int bestBin = 0, bestCount = 0;
        for (int i = 0; i < advances.length; i++) {
            final float advance = advances[i];
            if (!isUsable(advance)) continue;
            final int bin = Math.round(advance * BIN);
            int count = 0;
            for (int j = 0; j < advances.length; j++) {
                if (isInBin(advances[j], bin)) count++;
            }
            // 平票时取更宽的箱子：格子宁宽勿窄，宽出来的部分只是行尾留白，窄了会压字。
            if (count > bestCount || (count == bestCount && bin > bestBin)) {
                bestCount = count;
                bestBin = bin;
            }
        }
        if (bestCount == 0) return 0f;
        // 分箱只用来分组，结果必须回到真实测量值：把 22.8 量化成 22.796875 会在 80 列上累积半个像素的漂移。
        for (int i = 0; i < advances.length; i++) {
            if (isInBin(advances[i], bestBin)) return advances[i];
        }
        return bestBin / BIN;
    }

    /**
     * 与 {@link #cellWidth} 相同，但样本量不出正数时退回给定值。
     *
     * <p>字体损坏、或用户把纯符号字体选为主字体时，ASCII 样本可能全为 0。格子宽为 0 会让
     * `TerminalView` 的列数计算（{@code viewWidth / mFontWidth}）除零、把 PTY 尺寸报成天文数字，
     * 比“估错一点”严重得多。
     */
    public static float cellWidthOr(float[] advances, float fallback) {
        final float width = cellWidth(advances);
        if (width > 0f) return width;
        return fallback > 0f ? fallback : 1f;
    }

    private static boolean isUsable(float advance) {
        return advance > 0f && !Float.isInfinite(advance);
    }

    private static boolean isInBin(float advance, int bin) {
        return isUsable(advance) && Math.round(advance * BIN) == bin;
    }

    /** 行高：字体自然行距 × 行距倍数，向上取整（宁可多一行像素，也不让下一行压住上一行的降部）。 */
    public static int lineHeight(float fontSpacing, float lineSpacingMul) {
        return (int) Math.ceil(fontSpacing * lineSpacingMul);
    }

    /**
     * 半行距：行距倍数多出来的高度的一半。基线整体下移这么多，文字在行内视觉居中。
     * 倍率 ≤ 1（压紧）时返回 0——压紧时字形本来就会互相靠拢，再上移基线只会让降部更难读。
     */
    public static float halfLeading(int lineHeight, float fontSpacing) {
        float extra = lineHeight - fontSpacing;
        return extra > 0f ? extra / 2f : 0f;
    }

    /**
     * 一段文字在格子里的横向缩放系数（1 = 保持设计尺寸）。
     *
     * <ul>
     *   <li>宽于格子且处于<b>压缩模式</b>（字距 &lt; 1，格子比字形还窄）→ 等比缩小到刚好填满；</li>
     *   <li>宽于格子且不是压缩模式 → 按设计尺寸溢出，但<b>两侧各不超过 allowance/2</b>：超出才等比缩小
     *       到刚好这个溢出量。图标因此保持原始比例与绝大部分大小（1em 图标在 0.6em 格子里、allowance
     *       0.3em 时画成 0.9em），而不是被压成格子的 0.6em，也不会挤掉邻居的半格；</li>
     *   <li>窄于格子且是宽字形（2 列一字，如 CJK/emoji）→ 等比放大填满它的跨度；</li>
     *   <li>其余 → 不缩放，只居中。</li>
     * </ul>
     *
     * <p>为什么宽字形要填满：占 2 列就意味着「我需要两格」——填满才符合网格语义。回退 CJK 字体
     * 常常是 1em advance 而格子 0.6em，2 格 = 1.2em，若按原尺寸画，每个汉字两侧会各多出 0.1em
     * 的空隙，整段中文看起来像被拉开了字距。窄字形则相反：它本来就只需要一格，放大只会挤到邻居。
     *
     * <p>为什么外来字形要按设计尺寸而不是塞进格子：Nerd 图标的设计尺寸是 1em（方形），而终端格子是
     * 0.6 × 1.32em。把它压进 0.6em 会让图标只剩 60% 大小（这正是"yazi 里图标偏小"的成因）；而完全
     * 不限制又会吃掉邻居半格。所以给溢出设一个上限：默认两侧各 0.15em（约四分之一格），
     * 1em 的图标画成 0.9em——保住九成大小，横向只挤掉邻居 0.15em，图标后通常本来就跟着空格。
     *
     * <p>任何一条路径都只做<b>等比</b>缩放——上游对宽度不符的 run 做 {@code canvas.scale(k, 1)}，
     * 把 1em 的 Nerd 图标压窄 40%、把 1em 的 CJK 拉宽 20%，那才是真正的"横轴比例失衡"。
     */
    public static float placementScale(float runWidth, float spanWidth, boolean wide, boolean condensed,
                                       float overflowAllowance) {
        if (!(spanWidth > 0f) || !(runWidth > 0f)) return 1f;
        if (runWidth > spanWidth) {
            if (condensed) return spanWidth / runWidth;
            final float maxWidth = spanWidth + Math.max(0f, overflowAllowance);
            return runWidth > maxWidth ? maxWidth / runWidth : 1f;
        }
        if (!wide) return 1f;
        return Math.min(spanWidth / runWidth, MAX_WIDE_UPSCALE);
    }

    /**
     * 宽字形放大到填满跨度的上限。
     *
     * <p>正常的字体/格子不匹配是 1.0em 字形对 1.2em 跨度（+20%）；超过 1.5 倍说明那个“宽字形”的
     * advance 远小于它的列跨度（某些字体的 emoji 只有 0.6em advance 却声明 2 列），此时把它吹到
     * 2.4 倍只会得到一屏巨大的表情符号。宁可保持原尺寸居中，也不炸开。
     */
    private static final float MAX_WIDE_UPSCALE = 1.5f;

    /**
     * 居中偏移：把一段文字放在它跨度的正中间。窄于格子时偏移为正（两侧留白），
     * 宽于格子时为负（两侧对称溢出）。只在 {@link #placementScale} 返回 1 时使用。
     */
    public static float centerOffset(float runWidth, float spanWidth) {
        if (!(spanWidth > 0f) || !(runWidth > 0f) || runWidth == spanWidth) return 0f;
        return (spanWidth - runWidth) / 2f;
    }
}
