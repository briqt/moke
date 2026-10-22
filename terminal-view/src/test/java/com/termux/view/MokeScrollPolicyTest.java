package com.termux.view;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class MokeScrollPolicyTest {
    @Test public void automaticCompleteProtocolMatrix() {
        for (boolean alt : new boolean[]{false, true}) {
            for (boolean mouse : new boolean[]{false, true}) {
                for (boolean scroll1007 : new boolean[]{false, true}) {
                    int expected = mouse ? MokeScroll.ACTION_WHEEL : !alt ? MokeScroll.ACTION_LOCAL
                        : scroll1007 ? MokeScroll.ACTION_ARROWS : MokeScroll.ACTION_NONE;
                    assertEquals("alt=" + alt + " mouse=" + mouse + " 1007=" + scroll1007,
                        expected, MokeScroll.decide(MokeScroll.MODE_SMART, alt, mouse, scroll1007));
                }
            }
        }
    }

    @Test public void explicitTemporaryModesAreProgramInputInEitherBuffer() {
        for (boolean alt : new boolean[]{false, true}) {
            for (boolean mouse : new boolean[]{false, true}) {
                for (boolean scroll1007 : new boolean[]{false, true}) {
                    assertEquals(MokeScroll.ACTION_WHEEL, MokeScroll.decide(MokeScroll.MODE_WHEEL, alt, mouse, scroll1007));
                    assertEquals(MokeScroll.ACTION_ARROWS, MokeScroll.decide(MokeScroll.MODE_ARROWS, alt, mouse, scroll1007));
                }
            }
        }
    }

    @Test public void unknownModeIsConservativeAutomaticNotImplicitArrows() {
        assertEquals(MokeScroll.ACTION_NONE, MokeScroll.decide(99, true, false, false));
        assertEquals(MokeScroll.ACTION_LOCAL, MokeScroll.decide(99, false, false, true));
    }
}
