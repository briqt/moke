package com.termux.view;

/** Frame-timed pixel inertia and bounded, non-accelerating remote quantization. */
public final class ScrollMotion {
    public static final int MAX_UNITS_PER_FRAME = 8;
    public static final int MAX_UNITS_PER_GESTURE = 256;
    public static final int REMOTE_FLING_UNITS = 12;
    private double velocity, remainingPixels;
    private long lastTime, endTime;
    private boolean running, remote;

    public void start(double pixelsPerSecond, long nowMillis, boolean isRemote, double unitPixels) {
        remote = isRemote;
        velocity = Math.max(-12000, Math.min(12000, pixelsPerSecond));
        remainingPixels = isRemote ? REMOTE_FLING_UNITS * unitPixels : Double.POSITIVE_INFINITY;
        lastTime = nowMillis;
        endTime = nowMillis + (isRemote ? 220 : 1600);
        running = Math.abs(velocity) >= 20 && unitPixels > 0;
    }

    public boolean isRunning() { return running; }
    public void cancel() { running = false; velocity = 0; }

    /** Delayed frames never catch up by replaying a backlog of old motion. */
    public double step(long nowMillis) {
        if (!running || nowMillis <= lastTime) return 0;
        long until = Math.min(nowMillis, endTime);
        double dt = Math.max(0, Math.min(0.05, (until - lastTime) / 1000.0));
        lastTime = nowMillis;
        double decay = remote ? 12 : 5;
        double next = velocity * Math.exp(-decay * dt);
        double pixels = (velocity - next) / decay;
        velocity = next;
        if (Math.abs(pixels) > remainingPixels) pixels = Math.copySign(remainingPixels, pixels);
        remainingPixels -= Math.abs(pixels);
        if (nowMillis >= endTime || Math.abs(velocity) < 20 || remainingPixels <= 0) running = false;
        return pixels;
    }

    /** Physical wheel fractions accumulate, but zero and capped whole notches never become phantom input. */
    public static final class WheelAccumulator {
        private double remainder;
        public void clear() { remainder = 0; }
        public int add(double notches, int limit) {
            if (!Double.isFinite(notches) || notches == 0) return 0;
            double total = remainder + notches;
            int units = (int) Math.copySign(Math.min(Math.abs(total), Math.max(0, limit)), total);
            remainder = total % 1.0;
            return units;
        }
    }

    /** Remainders belong to one gesture and cannot be carried into a different input context. */
    public static final class Quantizer {
        private double remainder;
        private int units;

        public int add(double pixels, double unitPixels, int limit) {
            if (!(unitPixels > 0) || !Double.isFinite(pixels)) return 0;
            remainder += pixels;
            int available = Math.min(Math.max(0, limit), MAX_UNITS_PER_GESTURE - units);
            double raw = remainder / unitPixels;
            int count = (int) Math.copySign(Math.min(Math.abs(raw), available), raw);
            // Excess whole units are deliberately dropped, not queued for later frames.
            remainder = remainder % unitPixels;
            units += Math.abs(count);
            return count;
        }
    }
}
