package com.termux.view;

/**
 * Pure scroll routing, matching what desktop terminals do with a mouse wheel:
 * a program that asked for the mouse gets wheel events; a full-screen program that did not
 * (less, man, nano) gets Up/Down keys; the ordinary screen scrolls local history.
 */
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
    public static int decide(int mode, boolean alternate, boolean mouseTracking) {
        if (mode == MODE_WHEEL) return ACTION_WHEEL;
        if (mode == MODE_ARROWS) return ACTION_ARROWS;
        if (mouseTracking) return ACTION_WHEEL;
        return alternate ? ACTION_ARROWS : ACTION_LOCAL;
    }
}
