package com.termux.view;

/** Local history position and the single display/hit-test transform. No Android dependencies. */
public final class ScrollViewport {
    private double position;
    private boolean following = true;
    private long historyShift, historyGeneration;
    private boolean observed;
    private int historyRows;

    public double position() { return position; }
    public int topRow() { return (int) Math.floor(position); }
    public double fraction() { return position - Math.floor(position); }
    public boolean isFollowing() { return following; }
    public boolean hasObservation() { return observed; }
    public long historyGeneration() { return historyGeneration; }

    public void follow() { position = 0; following = true; }
    public void hold() { following = false; }

    public void setPosition(double rows) {
        position = clamp(rows);
        following = position == 0;
    }

    /** @return whether content moved; callers may stop motion at a boundary. */
    public boolean movePixels(double pixels, double rowHeight) {
        if (!(rowHeight > 0) || !Double.isFinite(pixels)) return false;
        double before = position;
        setPosition(position + pixels / rowHeight);
        return before != position;
    }

    /**
     * Observe content origins, not total history size or the emulator's margin scroll counter.
     * A changed generation means old coordinates no longer identify the same content.
     * @return origin shift, or Long.MIN_VALUE when old content coordinates were invalidated.
     */
    public long observe(long shift, long generation, int rows) {
        historyRows = Math.max(0, rows);
        long delta = observed ? shift - historyShift : 0;
        boolean invalidated = observed && generation != historyGeneration;
        if (invalidated) {
            position = following ? 0 : -historyRows;
        } else if (!following) {
            position -= Math.max(0, delta);
        }
        position = clamp(position);
        historyShift = shift;
        historyGeneration = generation;
        observed = true;
        return invalidated ? Long.MIN_VALUE : Math.max(0, delta);
    }

    /** Reflow has already mapped this anchor; do not apply its counter changes a second time. */
    public void restore(double rows, boolean wasFollowing, long shift, long generation, int history) {
        observed = false;
        observe(shift, generation, history);
        position = wasFollowing ? 0 : clamp(rows);
        following = wasFollowing;
    }

    private double clamp(double rows) {
        return Math.max(-historyRows, Math.min(0, rows));
    }

    public float translationY(float rowHeight) { return (float) (-fraction() * rowHeight); }

    /** Row cells start at the renderer's ascent padding; floor also works above the first row. */
    public int rowAt(float y, float rowHeight, float gridTop) {
        return (int) Math.floor((y - gridTop) / rowHeight + position);
    }

    public float rowTop(int row, float rowHeight, float gridTop) {
        return (float) (gridTop + (row - position) * rowHeight);
    }
}
