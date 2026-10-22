package com.termux.view;

import org.junit.Test;
import static org.junit.Assert.*;

public class ScrollMotionTest {
    @Test public void localMotionIntegratesPixelsNotViewportRows() {
        ScrollMotion motion = new ScrollMotion();
        motion.start(-1000, 100, false, 20);
        double first = motion.step(116);
        assertTrue(first < 0 && first > -16);
        double second = motion.step(132);
        assertTrue(second < 0 && Math.abs(second) < Math.abs(first));
        motion.cancel();
        assertFalse(motion.isRunning());
        assertEquals(0, motion.step(148), 0);
    }

    @Test public void remoteInertiaHasShortTimeAndDistanceBudget() {
        ScrollMotion motion = new ScrollMotion();
        motion.start(100000, 0, true, 60);
        double pixels = 0;
        for (int time = 16; time <= 1000; time += 16) pixels += motion.step(time);
        assertTrue(pixels > 0);
        assertTrue(pixels <= ScrollMotion.REMOTE_FLING_UNITS * 60);
        assertFalse(motion.isRunning());
        assertEquals(0, motion.step(1016), 0);
    }

    @Test public void delayedFrameDoesNotReplayAnUnboundedBacklog() {
        ScrollMotion motion = new ScrollMotion();
        motion.start(12000, 0, false, 20);
        assertTrue(motion.step(1000) < 600);
        assertTrue(motion.step(5000) < 600);
        assertFalse(motion.isRunning());
    }

    @Test public void cadenceUsesThreeRowHeightsPerWheelWithoutExtraAcceleration() {
        ScrollMotion.Quantizer quantizer = new ScrollMotion.Quantizer();
        assertEquals(0, quantizer.add(-20, 60, 8));
        assertEquals(0, quantizer.add(-20, 60, 8));
        assertEquals(-1, quantizer.add(-20, 60, 8));
        assertEquals(2, quantizer.add(120, 60, 8));
    }

    @Test public void perFrameAndGestureBoundsDropRatherThanQueueExcess() {
        ScrollMotion.Quantizer quantizer = new ScrollMotion.Quantizer();
        assertEquals(8, quantizer.add(100000, 20, 8));
        assertEquals(0, quantizer.add(0, 20, 8));
        int count = 8;
        for (int i = 0; i < 1000; i++) count += quantizer.add(160, 20, 8);
        assertEquals(ScrollMotion.MAX_UNITS_PER_GESTURE, count);
        assertEquals(0, quantizer.add(-160, 20, 8));
    }

    @Test public void wheelZeroFractionsMagnitudeAndCancellationAreNotDownThreeRows() {
        ScrollMotion.WheelAccumulator wheel = new ScrollMotion.WheelAccumulator();
        assertEquals(0, wheel.add(0, 8));
        assertEquals(0, wheel.add(-.25, 8));
        assertEquals(0, wheel.add(-.25, 8));
        assertEquals(0, wheel.add(-.25, 8));
        assertEquals(-1, wheel.add(-.25, 8));
        assertEquals(3, wheel.add(3.5, 8));
        assertEquals(0, wheel.add(-.5, 8));
        assertEquals(8, wheel.add(100, 8));
        assertEquals(0, wheel.add(0, 8));
        wheel.add(.75, 8);
        wheel.clear();
        assertEquals(0, wheel.add(.25, 8));
        assertEquals(0, wheel.add(Double.NaN, 8));
    }
}
