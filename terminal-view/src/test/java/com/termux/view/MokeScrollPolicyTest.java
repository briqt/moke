package com.termux.view;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class MokeScrollPolicyTest {
    /** Same as a desktop terminal's mouse wheel: mouse → wheel, full screen → arrows, otherwise history. */
    @Test public void automaticModeMatchesADesktopWheel() {
        for (boolean alt : new boolean[]{false, true}) {
            for (boolean mouse : new boolean[]{false, true}) {
                int expected = mouse ? MokeScroll.ACTION_WHEEL : alt ? MokeScroll.ACTION_ARROWS : MokeScroll.ACTION_LOCAL;
                assertEquals("alt=" + alt + " mouse=" + mouse, expected, MokeScroll.decide(MokeScroll.MODE_SMART, alt, mouse));
            }
        }
    }

    @Test public void aFullScreenProgramWithoutMouseAlwaysGetsArrowsSoSwipesAreNeverSilent() {
        assertEquals(MokeScroll.ACTION_ARROWS, MokeScroll.decide(MokeScroll.MODE_SMART, true, false));
    }

    @Test public void explicitTemporaryModesAreProgramInputInEitherBuffer() {
        for (boolean alt : new boolean[]{false, true}) {
            for (boolean mouse : new boolean[]{false, true}) {
                assertEquals(MokeScroll.ACTION_WHEEL, MokeScroll.decide(MokeScroll.MODE_WHEEL, alt, mouse));
                assertEquals(MokeScroll.ACTION_ARROWS, MokeScroll.decide(MokeScroll.MODE_ARROWS, alt, mouse));
            }
        }
    }

    @Test public void unknownModeFallsBackToAutomatic() {
        assertEquals(MokeScroll.ACTION_ARROWS, MokeScroll.decide(99, true, false));
        assertEquals(MokeScroll.ACTION_LOCAL, MokeScroll.decide(99, false, false));
    }
}
