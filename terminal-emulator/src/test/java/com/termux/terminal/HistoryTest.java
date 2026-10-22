package com.termux.terminal;


public class HistoryTest extends TerminalTestCase {

	public void testHistory() {
		final int rows = 3;
		final int cols = 3;
		withTerminalSized(cols, rows).enterString("111222333444555666777888999");
		assertCursorAt(2, 2);
		assertLinesAre("777", "888", "999");
		assertHistoryStartsWith("666", "555");

		resize(cols, 2);
		assertHistoryStartsWith("777", "666", "555");

		resize(cols, 3);
		assertHistoryStartsWith("666", "555");
	}

	public void testHistoryShiftTracksRingMovementAfterCapacityIsFull() {
        TerminalBuffer buffer = new TerminalBuffer(3, 5, 3);
        long generation = buffer.getHistoryGeneration();
        for (int i = 0; i < 8; i++) {
            buffer.scrollDownOneLine(0, 3, TextStyle.NORMAL);
            assertEquals(i + 1L, buffer.getHistoryShift());
            assertEquals(Math.min(i + 1, 2), buffer.getActiveTranscriptRows());
        }
        assertEquals(generation, buffer.getHistoryGeneration());
        long shift = buffer.getHistoryShift();
        buffer.clearTranscript();
        assertEquals(0, buffer.getActiveTranscriptRows());
        assertEquals(shift, buffer.getHistoryShift());
        assertTrue(buffer.getHistoryGeneration() > generation);
        buffer.scrollDownOneLine(0, 3, TextStyle.NORMAL);
        assertEquals(shift + 1, buffer.getHistoryShift());
    }

    public void testHorizontalMarginScrollDoesNotShiftHistoryOrigin() {
        withTerminalSized(5, 4).enterString("\033[?69h\033[2;4s\033[4;2H");
        TerminalBuffer buffer = mTerminal.getScreen();
        long shift = buffer.getHistoryShift();
        long generation = buffer.getHistoryGeneration();
        int scrollCounter = mTerminal.getScrollCounter();
        enterString("\n\n\n");
        assertEquals(scrollCounter + 3, mTerminal.getScrollCounter());
        assertEquals(shift, buffer.getHistoryShift());
        assertEquals(generation, buffer.getHistoryGeneration());
        assertEquals(0, buffer.getActiveTranscriptRows());
        enterString("\033[?69l\n");
        assertEquals(shift + 1, buffer.getHistoryShift());
    }

    public void testHistoryGenerationInvalidatesClearAndResetAndStaysPerBuffer() {
        withTerminalSized(3, 3).enterString("a\r\nb\r\nc\r\nd");
        TerminalBuffer main = mTerminal.getScreen();
        long generation = main.getHistoryGeneration();
        long shift = main.getHistoryShift();
        enterString("\033[3J");
        assertTrue(main.getHistoryGeneration() > generation);
        assertEquals(shift, main.getHistoryShift());
        generation = main.getHistoryGeneration();
        enterString("\033[!p");
        assertTrue(main.getHistoryGeneration() > generation);
        enterString("\033[?1049h\033[3;1H\n\n");
        assertEquals(shift, main.getHistoryShift());
        assertEquals(2, mTerminal.getScreen().getHistoryShift());
        enterString("\033[?1049l");
        assertSame(main, mTerminal.getScreen());
        assertEquals(shift, main.getHistoryShift());
    }

    public void testHistoryWithScrollRegion() {
		// "CSI P_s ; P_s r" - set Scrolling Region [top;bottom] (default = full size of window) (DECSTBM).
		withTerminalSized(3, 4).enterString("111222333444");
		assertLinesAre("111", "222", "333", "444");
		enterString("\033[2;3r");
		// NOTE: "DECSTBM moves the cursor to column 1, line 1 of the page."
		assertCursorAt(0, 0);
		enterString("\nCDEFGH").assertLinesAre("111", "CDE", "FGH", "444");
		enterString("IJK").assertLinesAre("111", "FGH", "IJK", "444").assertHistoryStartsWith("CDE");
		enterString("LMN").assertLinesAre("111", "IJK", "LMN", "444").assertHistoryStartsWith("FGH", "CDE");
	}

}
