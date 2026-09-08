package com.termux.view;

/**
 * 滑动/滚轮该怎么处理的**纯决策**（moke 扩展，无 Android 依赖，可被 JVM 单测覆盖）。
 *
 * <p>抽出来的原因：这段判断踩过两次坑（远端一开鼠标就短路掉用户选的模式；用户选的模式又漏到
 * 主屏幕去），而它恰恰是最难在真机上穷举验证的部分。做成纯函数就能把全部组合钉死在单测里。
 */
public final class MokeScroll {

    private MokeScroll() {}

    /** 「全屏程序内滑动」设置项的三档，与 {@code com.briqt.moke.data.ScrollMode} 的 ordinal 对齐。 */
    public static final int MODE_SMART = 0;
    public static final int MODE_WHEEL = 1;
    public static final int MODE_ARROWS = 2;

    /** 发方向键（上/下）。 */
    public static final int ACTION_ARROWS = 0;
    /** 发滚轮鼠标事件。 */
    public static final int ACTION_WHEEL = 1;
    /** 滚本地 scrollback（不发任何字节到远端）。 */
    public static final int ACTION_LOCAL = 2;
    /** 什么都不发，并告诉用户为什么无处可滚。 */
    public static final int ACTION_NONE = 3;

    /**
     * @param mode            见 {@code MODE_*}；用户在「设置 → 终端与输入 → 全屏程序内滑动」里选的档
     * @param fullScreen      远端当前在备用屏（真正的全屏程序）
     * @param mouseTracking   远端开启了鼠标跟踪
     * @param moshSession     本会话走 mosh
     * @param bracketedPaste  远端开启了括号粘贴模式（行编辑型 TUI 的标志）
     */
    public static int decide(
        int mode,
        boolean fullScreen,
        boolean mouseTracking,
        boolean moshSession,
        boolean bracketedPaste
    ) {
        // 不在全屏程序里 = 主屏幕。这里有**真正可滚的本地 scrollback**，而发方向键会去翻命令历史、
        // 发滚轮会把 \033[M… 原样打进命令行（TerminalEmulator.sendMouseEvent 不校验跟踪是否开启）。
        // 所以「全屏程序内滑动」这个设置项**只在全屏程序内**生效——名字即边界。
        // 唯一例外是远端确实开了鼠标跟踪：那是远端主动要接管滚轮，本地历史也就没有意义。
        if (!fullScreen) {
            return mouseTracking ? ACTION_WHEEL : ACTION_LOCAL;
        }
        // 用户显式选定的档位优先于自动判定——包括压过鼠标跟踪，否则远端一开鼠标，
        // 「始终发方向键」就形同虚设（0.1.19 修过一次的老账）。
        if (mode == MODE_ARROWS) return ACTION_ARROWS;
        if (mode == MODE_WHEEL) return ACTION_WHEEL;
        if (mouseTracking) return ACTION_WHEEL;
        // 以下都是智能档。备用屏里没有 scrollback，只能在「发方向键」与「什么都不发」之间选。
        // 这两种情况判不出方向键是否安全，一律不发：
        //  - mosh：备用屏是 mosh-client 自己的，远端是不是翻页器无从得知；
        //  - 括号粘贴：行编辑型 TUI（claude code、codex、readline），方向键会翻命令历史。
        if (moshSession || bracketedPaste) return ACTION_NONE;
        // 翻页器（less / man / vim）：方向键就是它们的滚动方式。
        return ACTION_ARROWS;
    }
}
