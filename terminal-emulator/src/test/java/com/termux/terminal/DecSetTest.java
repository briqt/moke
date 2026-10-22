package com.termux.terminal;

/**
 * <pre>
 * "CSI ? Pm h", DEC Private Mode Set (DECSET)
 * </pre>
 * <p/>
 * and
 * <p/>
 * <pre>
 * "CSI ? Pm l", DEC Private Mode Reset (DECRST)
 * </pre>
 * <p/>
 * controls various aspects of the terminal
 */
public class DecSetTest extends TerminalTestCase {

	/** DECSET 25, DECTCEM, controls visibility of the cursor. */
	public void testEnableDisableCursor() {
		withTerminalSized(3, 3);
		assertTrue("Initially the cursor should be enabled", mTerminal.isCursorEnabled());
		enterString("\033[?25l"); // Disable Cursor (DECTCEM).
		assertFalse(mTerminal.isCursorEnabled());
		enterString("\033[?25h"); // Enable Cursor (DECTCEM).
		assertTrue(mTerminal.isCursorEnabled());

		enterString("\033[?25l"); // Disable Cursor (DECTCEM), again.
		assertFalse(mTerminal.isCursorEnabled());
		mTerminal.reset();
		assertTrue("Resetting the terminal should enable the cursor", mTerminal.isCursorEnabled());

		enterString("\033[?25l");
		assertFalse(mTerminal.isCursorEnabled());
		enterString("\033c"); // RIS resetting should enabled cursor.
		assertTrue(mTerminal.isCursorEnabled());
	}

	/** DECSET 2004, controls bracketed paste mode. */
	public void testBracketedPasteMode() {
		withTerminalSized(3, 3);

		mTerminal.paste("a");
		assertEquals("Pasting 'a' should output 'a' when bracketed paste mode is disabled", "a", mOutput.getOutputAndClear());

		enterString("\033[?2004h"); // Enable bracketed paste mode.
		mTerminal.paste("a");
		assertEquals("Pasting when in bracketed paste mode should be bracketed", "\033[200~a\033[201~", mOutput.getOutputAndClear());

		enterString("\033[?2004l"); // Disable bracketed paste mode.
		mTerminal.paste("a");
		assertEquals("Pasting 'a' should output 'a' when bracketed paste mode is disabled", "a", mOutput.getOutputAndClear());

		enterString("\033[?2004h"); // Enable bracketed paste mode, again.
		mTerminal.paste("a");
		assertEquals("Pasting when in bracketed paste mode again should be bracketed", "\033[200~a\033[201~", mOutput.getOutputAndClear());

		mTerminal.paste("\033ab\033cd\033");
		assertEquals("Pasting an escape character should not input it", "\033[200~abcd\033[201~", mOutput.getOutputAndClear());
		mTerminal.paste("\u0081ab\u0081cd\u009F");
		assertEquals("Pasting C1 control codes should not input it", "\033[200~abcd\033[201~", mOutput.getOutputAndClear());

		mTerminal.reset();
		mTerminal.paste("a");
		assertEquals("Terminal reset() should disable bracketed paste mode", "a", mOutput.getOutputAndClear());
	}

	/** 1007 is an explicit request and never inferred from other terminal modes. */
    public void testAlternateScrollIsOnlyAnExplicitProtocolRequest() {
        withTerminalSized(5, 3);
        assertFalse(mTerminal.isAlternateScrollEnabled());
        enterString("\033[?1049h\033[?2004h");
        assertFalse(mTerminal.isAlternateScrollEnabled());
        enterString("\033[?1007h");
        assertTrue(mTerminal.isAlternateScrollEnabled());
        assertFalse(mTerminal.isMouseTrackingActive());
        assertEquals("", mOutput.getOutputAndClear());
        enterString("\033[?1049l");
        assertTrue("Leaving alternate screen must not clear the protocol bit", mTerminal.isAlternateScrollEnabled());
        enterString("\033[?1007l\033[?1049h");
        assertFalse("Alternate screen alone must not request cursor keys", mTerminal.isAlternateScrollEnabled());
        assertEnteringStringGivesResponse("\033[?1007$p", "\033[?1007;2$y");
        enterString("\033[?1049l\033[?1007h");
        assertTrue("1007 is also stored when received on the main screen", mTerminal.isAlternateScrollEnabled());
        assertEnteringStringGivesResponse("\033[?1007$p", "\033[?1007;1$y");
    }

    public void testPrivateModeSaveRestoresBothEnabledAndDisabledStates() {
        withTerminalSized(5, 3);
        enterString("\033[?1007s\033[?1007h\033[?1007r");
        assertFalse(mTerminal.isAlternateScrollEnabled());
        enterString("\033[?1007h\033[?1007s\033[?1007l\033[?1007r");
        assertTrue(mTerminal.isAlternateScrollEnabled());
        enterString("\033[?1007l\033[?1007s\033[?1007h\033[?1007r");
        assertFalse("Saving off must replace a previously saved on state", mTerminal.isAlternateScrollEnabled());
        enterString("\033[?1;1006s\033[?1;1006h\033[?1;1006r");
        assertFalse(mTerminal.isCursorKeysApplicationMode());
        assertEquals(6, mTerminal.encodeMouseEvent(64, 1, 1, true).length);
    }

    public void testResetClearsAlternateScrollAndCursorEncodingWithoutClearingSoftResetContents() {
        withTerminalSized(5, 3).enterString("a\r\nb\r\nc\r\nd\033[?1007;1h");
        int history = mTerminal.getScreen().getActiveTranscriptRows();
        enterString("\033[!p");
        assertFalse(mTerminal.isAlternateScrollEnabled());
        assertFalse(mTerminal.isCursorKeysApplicationMode());
        assertEquals(history, mTerminal.getScreen().getActiveTranscriptRows());
        assertLinesAre("b    ", "c    ", "d    ");
        enterString("\033[?1007;1h\033c");
        assertFalse(mTerminal.isAlternateScrollEnabled());
        assertFalse(mTerminal.isCursorKeysApplicationMode());
        assertEquals(0, mTerminal.getScreen().getActiveTranscriptRows());
        assertLinesAre("     ", "     ", "     ");
    }

    public void testInputGenerationObservesModeRoundTripsWithinOneAppend() {
        withTerminalSized(5, 3);
        long generation = mTerminal.getInputModeGeneration();
        enterString("\033[?1049h\033[?1049l");
        assertFalse(mTerminal.isAlternateBufferActive());
        assertEquals(generation + 2, mTerminal.getInputModeGeneration());
        generation = mTerminal.getInputModeGeneration();
        enterString("\033[?1h\033[?1l\033[?1007h\033[?1007l\033[?1006h\033[?1006l");
        assertEquals(generation + 6, mTerminal.getInputModeGeneration());
        generation = mTerminal.getInputModeGeneration();
        mTerminal.reset();
        assertTrue(mTerminal.getInputModeGeneration() > generation);
    }

    public void testInputGenerationIgnoresIdempotentModesAndOrdinaryOutput() {
        withTerminalSized(5, 3);
        long generation = mTerminal.getInputModeGeneration();
        enterString("text\033[31m\033[?25l\033[?1l\033[?1007l\033[?1049l");
        assertEquals(generation, mTerminal.getInputModeGeneration());
        enterString("\033[?1;1007;1000;1006h");
        generation = mTerminal.getInputModeGeneration();
        enterString("\033[?1;1007;1000;1006h\033[?1007s\033[?1007r");
        assertEquals(generation, mTerminal.getInputModeGeneration());
        enterString("\033[?1002h");
        assertTrue(mTerminal.getInputModeGeneration() > generation);
        generation = mTerminal.getInputModeGeneration();
        enterString("\033[?1002h\033[?1003h");
        assertEquals(generation, mTerminal.getInputModeGeneration());
        enterString("\033=\033>");
        assertEquals(generation + 2, mTerminal.getInputModeGeneration());
    }

    public void testBracketedPasteDoesNotPretendScrollRoutingChanged() {
        withTerminalSized(20, 12).enterString("\033[?1000;1006h");
        long generation = mTerminal.getInputModeGeneration();
        enterString("\033[?2004h");
        assertTrue(mTerminal.isBracketedPasteMode());
        assertEquals(generation, mTerminal.getInputModeGeneration());
        enterString("\033[?2004l");
        assertFalse(mTerminal.isBracketedPasteMode());
        assertEquals(generation, mTerminal.getInputModeGeneration());
        enterString("\033[?1006l");
        assertEquals(generation + 1, mTerminal.getInputModeGeneration());
    }

    public void testGeometryResizeDoesNotPretendTheProgramChangedInputProtocol() {
        withTerminalSized(20, 12).enterString("\033[?1007;2004h");
        long generation = mTerminal.getInputModeGeneration();
        resize(20, 6);
        assertEquals(generation, mTerminal.getInputModeGeneration());
        resize(30, 12);
        assertEquals(generation, mTerminal.getInputModeGeneration());
        assertTrue(mTerminal.isAlternateScrollEnabled());
        enterString("\033[?1007l");
        assertEquals(generation + 1, mTerminal.getInputModeGeneration());
    }

    public void testMouseEncoderPreservesLegacyBytesClippingAndBounds() {
        withTerminalSized(300, 300);
        byte[] expected = {'\033', '[', 'M', 96, 33, 33};
        assertTrue(java.util.Arrays.equals(expected, mTerminal.encodeMouseEvent(64, -5, 0, true)));
        assertEquals("Encoding must not send input", 0, mOutput.baos.size());
        mTerminal.sendMouseEvent(64, -5, 0, true);
        assertTrue(java.util.Arrays.equals(expected, mOutput.baos.toByteArray()));
        mOutput.baos.reset();
        byte[] release = {'\033', '[', 'M', 35, (byte) 255, (byte) 255};
        assertTrue(java.util.Arrays.equals(release, mTerminal.encodeMouseEvent(0, 223, 223, false)));
        assertNull(mTerminal.encodeMouseEvent(64, 224, 10, true));
        assertNull(mTerminal.encodeMouseEvent(64, 10, 224, true));
        assertNull(mTerminal.encodeMouseEvent(32, 1, 1, true));
        enterString("\033[?1002h");
        assertNotNull(mTerminal.encodeMouseEvent(32, 1, 1, true));
        withTerminalSized(5, 3);
        byte[] clipped = {'\033', '[', 'M', 96, 37, 35};
        assertTrue(java.util.Arrays.equals(clipped, mTerminal.encodeMouseEvent(64, 500, 500, true)));
    }

    public void testMouseEncoderPreservesSgrCoordinatesReleasesAndMovement() {
        withTerminalSized(300, 300).enterString("\033[?1000;1006h");
        assertEquals("\033[<64;300;1M", new String(mTerminal.encodeMouseEvent(64, 999, 0, true), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("\033[<0;224;225m", new String(mTerminal.encodeMouseEvent(0, 224, 225, false), java.nio.charset.StandardCharsets.UTF_8));
        assertNull(mTerminal.encodeMouseEvent(32, 1, 1, true));
        enterString("\033[?1002h");
        byte[] expected = mTerminal.encodeMouseEvent(32, 2, 3, true);
        assertEquals("\033[<32;2;3M", new String(expected, java.nio.charset.StandardCharsets.UTF_8));
        mTerminal.sendMouseEvent(32, 2, 3, true);
        assertTrue(java.util.Arrays.equals(expected, mOutput.baos.toByteArray()));
    }

    /** DECSET 7, DECAWM, controls wraparound mode. */
	public void testWrapAroundMode() {
		// Default with wraparound:
		withTerminalSized(3, 3).enterString("abcd").assertLinesAre("abc", "d  ", "   ");
		// With wraparound disabled:
		withTerminalSized(3, 3).enterString("\033[?7labcd").assertLinesAre("abd", "   ", "   ");
		enterString("efg").assertLinesAre("abg", "   ", "   ");
		// Re-enabling wraparound:
		enterString("\033[?7hhij").assertLinesAre("abh", "ij ", "   ");
	}

}
