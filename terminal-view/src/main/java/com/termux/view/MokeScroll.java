package com.termux.view;

/** Pure scroll routing. Only explicit terminal protocol requests affect automatic mode. */
public final class MokeScroll {
    private MokeScroll() {}

    public static final int MODE_SMART = 0;
    public static final int MODE_WHEEL = 1;
    public static final int MODE_ARROWS = 2;

    public static final int ACTION_ARROWS = 0;
    public static final int ACTION_WHEEL = 1;
    public static final int ACTION_LOCAL = 2;
    public static final int ACTION_NONE = 3;

    /** Manual modes are an explicit, temporary request to send program input, in either buffer. */
    public static int decide(int mode, boolean alternate, boolean mouseTracking, boolean alternateScroll) {
        if (mode == MODE_WHEEL) return ACTION_WHEEL;
        if (mode == MODE_ARROWS) return ACTION_ARROWS;
        if (mouseTracking) return ACTION_WHEEL;
        if (!alternate) return ACTION_LOCAL;
        return alternateScroll ? ACTION_ARROWS : ACTION_NONE;
    }
}
