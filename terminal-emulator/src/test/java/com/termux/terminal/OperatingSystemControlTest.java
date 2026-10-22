package com.termux.terminal;

import android.util.Base64;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** "ESC ]" is the Operating System Command. */
public class OperatingSystemControlTest extends TerminalTestCase {

	public void testSetTitle() throws Exception {
		List<ChangedTitle> expectedTitleChanges = new ArrayList<>();

		withTerminalSized(10, 10);
		enterString("\033]0;Hello, world\007");
		assertEquals("Hello, world", mTerminal.getTitle());
		expectedTitleChanges.add(new ChangedTitle(null, "Hello, world"));
		assertEquals(expectedTitleChanges, mOutput.titleChanges);

		enterString("\033]0;Goodbye, world\007");
		assertEquals("Goodbye, world", mTerminal.getTitle());
		expectedTitleChanges.add(new ChangedTitle("Hello, world", "Goodbye, world"));
		assertEquals(expectedTitleChanges, mOutput.titleChanges);

		enterString("\033]0;Goodbye, \u00F1 world\007");
		assertEquals("Goodbye, \uu00F1 world", mTerminal.getTitle());
		expectedTitleChanges.add(new ChangedTitle("Goodbye, world", "Goodbye, \uu00F1 world"));
		assertEquals(expectedTitleChanges, mOutput.titleChanges);

		// 2 should work as well (0 sets both title and icon).
		enterString("\033]2;Updated\007");
		assertEquals("Updated", mTerminal.getTitle());
		expectedTitleChanges.add(new ChangedTitle("Goodbye, \uu00F1 world", "Updated"));
		assertEquals(expectedTitleChanges, mOutput.titleChanges);

		enterString("\033[22;0t");
		enterString("\033]0;FIRST\007");
		expectedTitleChanges.add(new ChangedTitle("Updated", "FIRST"));
		assertEquals("FIRST", mTerminal.getTitle());
		assertEquals(expectedTitleChanges, mOutput.titleChanges);

		enterString("\033[22;0t");
		enterString("\033]0;SECOND\007");
		assertEquals("SECOND", mTerminal.getTitle());

		expectedTitleChanges.add(new ChangedTitle("FIRST", "SECOND"));
		assertEquals(expectedTitleChanges, mOutput.titleChanges);

		enterString("\033[23;0t");
		assertEquals("FIRST", mTerminal.getTitle());

		expectedTitleChanges.add(new ChangedTitle("SECOND", "FIRST"));
		assertEquals(expectedTitleChanges, mOutput.titleChanges);

		enterString("\033[23;0t");
		expectedTitleChanges.add(new ChangedTitle("FIRST", "Updated"));
		assertEquals(expectedTitleChanges, mOutput.titleChanges);

		enterString("\033[22;0t");
		enterString("\033[22;0t");
		enterString("\033[22;0t");
		// Popping to same title should not cause changes.
		enterString("\033[23;0t");
		enterString("\033[23;0t");
		enterString("\033[23;0t");
		assertEquals(expectedTitleChanges, mOutput.titleChanges);
	}

	public void testTitleStack() throws Exception {
		// echo -ne '\e]0;BEFORE\007' # set title
		// echo -ne '\e[22t' # push to stack
		// echo -ne '\e]0;AFTER\007' # set new title
		// echo -ne '\e[23t' # retrieve from stack

		withTerminalSized(10, 10);
		enterString("\033]0;InitialTitle\007");
		assertEquals("InitialTitle", mTerminal.getTitle());
		enterString("\033[22t");
		assertEquals("InitialTitle", mTerminal.getTitle());
		enterString("\033]0;UpdatedTitle\007");
		assertEquals("UpdatedTitle", mTerminal.getTitle());
		enterString("\033[23t");
		assertEquals("InitialTitle", mTerminal.getTitle());
		enterString("\033[23t\033[23t\033[23t");
		assertEquals("InitialTitle", mTerminal.getTitle());
	}

	public void testSetColor() throws Exception {
		// "OSC 4; $INDEX; $COLORSPEC BEL" => Change color $INDEX to the color specified by $COLORSPEC.
		withTerminalSized(4, 4).enterString("\033]4;5;#00FF00\007");
		assertEquals(Integer.toHexString(0xFF00FF00), Integer.toHexString(mTerminal.mColors.mCurrentColors[5]));
		enterString("\033]4;5;#00FFAB\007");
		assertEquals(mTerminal.mColors.mCurrentColors[5], 0xFF00FFAB);
		enterString("\033]4;255;#ABFFAB\007");
		assertEquals(mTerminal.mColors.mCurrentColors[255], 0xFFABFFAB);
		// Two indexed colors at once:
		enterString("\033]4;7;#00FF00;8;#0000FF\007");
		assertEquals(mTerminal.mColors.mCurrentColors[7], 0xFF00FF00);
		assertEquals(mTerminal.mColors.mCurrentColors[8], 0xFF0000FF);
	}

	void assertIndexColorsMatch(int[] expected) {
		for (int i = 0; i < 255; i++)
			assertEquals("index=" + i, expected[i], mTerminal.mColors.mCurrentColors[i]);
	}

	public void testResetColor() throws Exception {
		withTerminalSized(4, 4);
		int[] initialColors = new int[TextStyle.NUM_INDEXED_COLORS];
		System.arraycopy(mTerminal.mColors.mCurrentColors, 0, initialColors, 0, initialColors.length);
		int[] expectedColors = new int[initialColors.length];
		System.arraycopy(mTerminal.mColors.mCurrentColors, 0, expectedColors, 0, expectedColors.length);
		Random rand = new Random();
		for (int endType = 0; endType < 3; endType++) {
			// Both BEL (7) and ST (ESC \) can end an OSC sequence.
			String ender = (endType == 0) ? "\007" : "\033\\";
			for (int i = 0; i < 255; i++) {
				expectedColors[i] = 0xFF000000 + (rand.nextInt() & 0xFFFFFF);
				int r = (expectedColors[i] >> 16) & 0xFF;
				int g = (expectedColors[i] >> 8) & 0xFF;
				int b = expectedColors[i] & 0xFF;
				String rgbHex = String.format("%02x", r) + String.format("%02x", g) + String.format("%02x", b);
				enterString("\033]4;" + i + ";#" + rgbHex + ender);
				assertEquals(expectedColors[i], mTerminal.mColors.mCurrentColors[i]);
			}
		}

		enterString("\033]104;0\007");
		expectedColors[0] = TerminalColors.COLOR_SCHEME.mDefaultColors[0];
		assertIndexColorsMatch(expectedColors);
		enterString("\033]104;1;2\007");
		expectedColors[1] = TerminalColors.COLOR_SCHEME.mDefaultColors[1];
		expectedColors[2] = TerminalColors.COLOR_SCHEME.mDefaultColors[2];
		assertIndexColorsMatch(expectedColors);
		enterString("\033]104\007"); // Reset all colors.
		assertIndexColorsMatch(TerminalColors.COLOR_SCHEME.mDefaultColors);
	}

	public void disabledTestSetClipboard() {
		// Cannot run this as a unit test since Base64 is a android.util class.
		enterString("\033]52;c;" + Base64.encodeToString("Hello, world".getBytes(), 0) + "\007");
	}

	public void testResettingTerminalResetsColor() throws Exception {
		// "OSC 4; $INDEX; $COLORSPEC BEL" => Change color $INDEX to the color specified by $COLORSPEC.
		withTerminalSized(4, 4).enterString("\033]4;5;#00FF00\007");
		enterString("\033]4;5;#00FFAB\007").assertColor(5, 0xFF00FFAB);
		enterString("\033]4;255;#ABFFAB\007").assertColor(255, 0xFFABFFAB);
		mTerminal.reset();
		assertIndexColorsMatch(TerminalColors.COLOR_SCHEME.mDefaultColors);
	}

	public void testSettingDynamicColors() {
		// "${OSC}${DYNAMIC};${COLORSPEC}${BEL_OR_STRINGTERMINATOR}" => Change ${DYNAMIC} color to the color specified by $COLORSPEC where:
		// DYNAMIC=10: Text foreground color.
		// DYNAMIC=11: Text background color.
		// DYNAMIC=12: Text cursor color.
		withTerminalSized(3, 3).enterString("\033]10;#ABCD00\007").assertColor(TextStyle.COLOR_INDEX_FOREGROUND, 0xFFABCD00);
		enterString("\033]11;#0ABCD0\007").assertColor(TextStyle.COLOR_INDEX_BACKGROUND, 0xFF0ABCD0);
		enterString("\033]12;#00ABCD\007").assertColor(TextStyle.COLOR_INDEX_CURSOR, 0xFF00ABCD);
		// Two special colors at once
		// ("Each successive parameter changes the next color in the list. The value of P s tells the starting point in the list"):
		enterString("\033]10;#FF0000;#00FF00\007").assertColor(TextStyle.COLOR_INDEX_FOREGROUND, 0xFFFF0000);
		assertColor(TextStyle.COLOR_INDEX_BACKGROUND, 0xFF00FF00);
		// Three at once:
		enterString("\033]10;#0000FF;#00FF00;#FF0000\007").assertColor(TextStyle.COLOR_INDEX_FOREGROUND, 0xFF0000FF);
		assertColor(TextStyle.COLOR_INDEX_BACKGROUND, 0xFF00FF00).assertColor(TextStyle.COLOR_INDEX_CURSOR, 0xFFFF0000);

		// Without ending semicolon:
		enterString("\033]10;#FF0000\007").assertColor(TextStyle.COLOR_INDEX_FOREGROUND, 0xFFFF0000);
		// For background and cursor:
		enterString("\033]11;#FFFF00;\007").assertColor(TextStyle.COLOR_INDEX_BACKGROUND, 0xFFFFFF00);
		enterString("\033]12;#00FFFF;\007").assertColor(TextStyle.COLOR_INDEX_CURSOR, 0xFF00FFFF);

		// Using string terminator:
		String stringTerminator = "\033\\";
		enterString("\033]10;#FF0000" + stringTerminator).assertColor(TextStyle.COLOR_INDEX_FOREGROUND, 0xFFFF0000);
		// For background and cursor:
		enterString("\033]11;#FFFF00;" + stringTerminator).assertColor(TextStyle.COLOR_INDEX_BACKGROUND, 0xFFFFFF00);
		enterString("\033]12;#00FFFF;" + stringTerminator).assertColor(TextStyle.COLOR_INDEX_CURSOR, 0xFF00FFFF);
	}

	public void testReportSpecialColors() {
		// "${OSC}${DYNAMIC};?${BEL}" => Terminal responds with the control sequence which would set the current color.
		// Both xterm and libvte (gnome-terminal and others) use the longest color representation, which means that
		// the response is "${OSC}rgb:RRRR/GGGG/BBBB"
		withTerminalSized(3, 3).enterString("\033]10;#ABCD00\007").assertColor(TextStyle.COLOR_INDEX_FOREGROUND, 0xFFABCD00);
		assertEnteringStringGivesResponse("\033]10;?\007", "\033]10;rgb:abab/cdcd/0000\007");
		// Same as above but with string terminator. xterm uses the same string terminator in the response, which
		// e.g. script posted at http://superuser.com/questions/157563/programmatic-access-to-current-xterm-background-color
		// relies on:
		assertEnteringStringGivesResponse("\033]10;?\033\\", "\033]10;rgb:abab/cdcd/0000\033\\");
	}

	public void testOsc7ReportsDirectoryWithBelAndSt() {
		withTerminalSized(10, 3);
		for (String ender : new String[]{"\007", "\033\\"}) {
			enterString("\033]7;file://host/work" + ender);
			assertEquals("file://host/work", mOutput.directories.get(mOutput.directories.size() - 1));
		}
		assertEquals(2, mOutput.directories.size());
		assertEquals(0, mOutput.bellsRung);
	}

	public void testOsc7WaitsForCompleteFrameAcrossChunks() {
		withTerminalSized(10, 3);
		for (String ender : new String[]{"\007", "\033\\"}) {
			mOutput.directories.clear();
			enterString("\033");
			enterString("]7");
			enterString(";file://host");
			enterString("/work");
			assertEquals(0, mOutput.directories.size());
			if (ender.length() == 2) {
				enterString("\033");
				assertEquals(0, mOutput.directories.size());
				enterString("\\");
			} else {
				enterString(ender);
			}
			assertEquals(1, mOutput.directories.size());
			assertEquals("file://host/work", mOutput.directories.get(0));
		}
	}

	public void testOsc7PreservesRawUtf8UriAcrossByteChunks() {
		withTerminalSized(10, 3);
		String uri = "file://host/目录/é/📁/%2Fkeep%20encoded;a?b#c";
		for (String ender : new String[]{"\007", "\033\\"}) {
			mOutput.directories.clear();
			byte[] bytes = ("\033]7;" + uri + ender).getBytes(StandardCharsets.UTF_8);
			for (int i = 0; i < bytes.length; i++) {
				assertEquals(0, mOutput.directories.size());
				mTerminal.append(new byte[]{bytes[i]}, 1);
			}
			assertEquals(1, mOutput.directories.size());
			assertEquals(uri, mOutput.directories.get(0));
			assertInvariants();
		}
	}

	public void testOsc7LeavesUriValidationToClient() {
		withTerminalSized(10, 3);
		for (String raw : new String[]{"", "relative/path", "https://host/work", "file://host/%zz"}) {
			enterString("\033]7;" + raw + "\007");
			assertEquals(raw, mOutput.directories.get(mOutput.directories.size() - 1));
		}
		assertEquals(4, mOutput.directories.size());
	}

	public void testOsc7PreservesControlsForClientValidation() {
		withTerminalSized(10, 3);
		String raw = "file://host/a\000\010\t\n\013\014\r\016\017\033x\033\033y";
		for (String ender : new String[]{"\007", "\033\\"}) {
			mOutput.directories.clear();
			enterString("\033]7;" + raw + ender);
			assertEquals(1, mOutput.directories.size());
			assertEquals(raw, mOutput.directories.get(0));
		}
		assertEquals(0, mOutput.bellsRung);
		assertLinesAre("          ", "          ", "          ");
	}

	public void testOsc7EscImmediatelyBeforeTerminatorRemainsInPayload() {
		withTerminalSized(10, 3);
		for (String ender : new String[]{"\007", "\033\\"}) {
			mOutput.directories.clear();
			enterString("\033]7;file://host/work\033" + ender);
			assertEquals(1, mOutput.directories.size());
			assertEquals("file://host/work\033", mOutput.directories.get(0));
		}
	}

	public void testOsc7DoesNotChangeTitleNotifyOrPrint() {
		withTerminalSized(10, 3).enterString("\033]2;title\007");
		enterString("\033]7;file://host/work\007x\033]7;file://host/other\033\\");
		assertEquals("title", mTerminal.getTitle());
		assertEquals(1, mOutput.titleChanges.size());
		assertEquals(0, mOutput.notifications.size());
		assertEquals(0, mOutput.bellsRung);
		assertEquals(2, mOutput.directories.size());
		assertLinesAre("x         ", "          ", "          ");
	}

	public void testOsc7RequiresCommandSeparatorAndNoNumericOverflow() {
		withTerminalSized(10, 3).enterString("\033]7\007\033]4294967303;file://host/wrapped\033\\");
		assertEquals(0, mOutput.directories.size());
		enterString("\033]007;file://host/work\007");
		assertEquals(1, mOutput.directories.size());
		assertEquals("file://host/work", mOutput.directories.get(0));
	}

	public void testOsc7AcceptsExactBufferLimit() {
		withTerminalSized(10, 3);
		// The shared OSC buffer includes the "7;" command as well as its payload.
		String uri = directoryUriOfLength(8192 - 2);
		for (String ender : new String[]{"\007", "\033\\"}) {
			mOutput.directories.clear();
			enterString("\033]7;" + uri + ender);
			assertEquals(1, mOutput.directories.size());
			assertEquals(uri, mOutput.directories.get(0));
		}
	}

	public void testOsc7RejectsOversizedFramesAndRecovers() {
		withTerminalSized(10, 3);
		for (String ender : new String[]{"\007", "\033\\"}) {
			mOutput.directories.clear();
			enterString("\033]7;" + directoryUriOfLength(8192 - 1));
			assertEquals(0, mOutput.directories.size());
			enterString(ender);
			assertEquals(1, mOutput.directories.size());
			assertEquals("", mOutput.directories.get(0));
			enterString("\033]7;file://host/recovered" + ender);
			assertEquals(2, mOutput.directories.size());
			assertEquals("file://host/recovered", mOutput.directories.get(1));
		}
	}

	public void testOsc7OversizedFrameTailCannotBecomeAnotherReport() {
		withTerminalSized(10, 3);
		for (String ender : new String[]{"\007", "\033\\"}) {
			mOutput.directories.clear();
			enterString("\033]7;" + directoryUriOfLength(8192));
			enterString("tail\033\033]7;file://host/injected");
			assertEquals(0, mOutput.directories.size());
			enterString(ender);
			assertEquals(1, mOutput.directories.size());
			assertEquals("", mOutput.directories.get(0));
			assertLinesAre("          ", "          ", "          ");
			enterString("\033]7;file://host/recovered" + ender);
			assertEquals(2, mOutput.directories.size());
			assertEquals("file://host/recovered", mOutput.directories.get(1));
		}
	}

	public void testOsc7DoesNotPublishTruncatedPrefixAfterEscFallback() {
		withTerminalSized(10, 3);
		for (String ender : new String[]{"\007", "\033\\"}) {
			mOutput.directories.clear();
			enterString("\033]7;" + directoryUriOfLength(8192 - 2));
			enterString("\033");
			enterString("x"); // Non-ST ESC fallback tries to append into an already full buffer.
			assertEquals(0, mOutput.directories.size());
			enterString(ender);
			assertEquals(1, mOutput.directories.size());
			assertEquals("", mOutput.directories.get(0));
			enterString("\033]7;file://host/recovered" + ender);
			assertEquals(2, mOutput.directories.size());
			assertEquals("file://host/recovered", mOutput.directories.get(1));
		}
	}

	public void testOsc7SupplementaryCharacterAtBufferBoundary() {
		withTerminalSized(10, 3);
		for (String ender : new String[]{"\007", "\033\\"}) {
			mOutput.directories.clear();
			String exact = directoryUriOfLength(8192 - 4) + "📁";
			enterString("\033]7;" + exact + ender);
			assertEquals(1, mOutput.directories.size());
			assertEquals(exact, mOutput.directories.get(0));
			mOutput.directories.clear();
			String oversized = directoryUriOfLength(8192 - 3) + "📁";
			enterString("\033]7;" + oversized + ender);
			assertEquals(1, mOutput.directories.size());
			assertEquals("", mOutput.directories.get(0));
		}
	}

	public void testOsc7UnterminatedOrCancelledFrameDoesNotReport() {
		withTerminalSized(10, 3).enterString("\033]7;file://host/partial");
		assertEquals(0, mOutput.directories.size());
		enterString("\030\007"); // CAN abandons the frame; a later BEL must not publish it.
		assertEquals(0, mOutput.directories.size());
		enterString("\033]7;file://host/recovered\033\\");
		assertEquals(1, mOutput.directories.size());
		assertEquals("file://host/recovered", mOutput.directories.get(0));
	}

	public void testOtherOscTitleAndNotificationCommandsStayIndependent() {
		withTerminalSized(10, 3);
		for (int command : new int[]{0, 1, 2}) {
			enterString("\033]" + command + ";title" + command + "\007");
			assertEquals("title" + command, mTerminal.getTitle());
		}
		enterString("\033]9;done\007\033]777;notify;Title;Body;tail\033\\");
		assertEquals(3, mOutput.titleChanges.size());
		assertEquals(2, mOutput.notifications.size());
		assertNull(mOutput.notifications.get(0)[0]);
		assertEquals("done", mOutput.notifications.get(0)[1]);
		assertEquals("Title", mOutput.notifications.get(1)[0]);
		assertEquals("Body;tail", mOutput.notifications.get(1)[1]);
		assertEquals(0, mOutput.directories.size());
	}

	public void testOverflowTrackingPreservesLegacyOscEscFallback() {
		withTerminalSized(10, 3);
		for (int command : new int[]{0, 1, 2, 9, 777}) {
			String prefix = command == 777 ? "777;notify;Title;" : command + ";";
			String body = directoryUriOfLength(8192 - prefix.length());
			enterString("\033]" + prefix + body + "\033x\007");
			if (command == 9 || command == 777) {
				String[] notification = mOutput.notifications.get(mOutput.notifications.size() - 1);
				assertEquals(command == 777 ? "Title" : null, notification[0]);
				assertEquals(body, notification[1]);
			} else {
				assertEquals(body, mTerminal.getTitle());
			}
		}
		assertEquals(0, mOutput.directories.size());
	}

	public void testOsc7UsesOwningSessionAndCurrentClient() {
		List<TerminalSession> sessions = new ArrayList<>();
		List<String> uris = new ArrayList<>();
		TerminalSessionClient client = directoryClient(sessions, uris);
		TerminalSession first = new TerminalSession(null, 6, client);
		TerminalSession second = new TerminalSession(null, 6, client);
		TerminalEmulator firstEmulator = new TerminalEmulator(first, 10, 3, 13, 15, 6, client);
		TerminalEmulator secondEmulator = new TerminalEmulator(second, 10, 3, 13, 15, 6, client);
		first.mEmulator = firstEmulator;
		second.mEmulator = secondEmulator;
		byte[] frame = "\033]7;file://host/work\007".getBytes(StandardCharsets.UTF_8);
		firstEmulator.append(frame, frame.length);
		secondEmulator.append(frame, frame.length);
		assertEquals(2, sessions.size());
		assertSame(first, sessions.get(0));
		assertSame(second, sessions.get(1));
		assertEquals("file://host/work", uris.get(0));
		assertEquals("file://host/work", uris.get(1));

		List<TerminalSession> replacementSessions = new ArrayList<>();
		List<String> replacementUris = new ArrayList<>();
		first.updateTerminalSessionClient(directoryClient(replacementSessions, replacementUris));
		firstEmulator.append(frame, frame.length);
		assertEquals(2, sessions.size());
		assertEquals(1, replacementSessions.size());
		assertSame(first, replacementSessions.get(0));
		assertEquals("file://host/work", replacementUris.get(0));
		first.updateTerminalSessionClient(null);
		firstEmulator.append(frame, frame.length);
		assertEquals(1, replacementSessions.size());
	}

	public void testOsc7CompleteOversizedFrameInvalidatesPriorDirectory() {
		withTerminalSized(10, 3);
		for (String ender : new String[]{"\007", "\033\\"}) {
			mOutput.directories.clear();
			enterString("\033]7;file://host/previous" + ender);
			enterString("\033]7;" + directoryUriOfLength(8192));
			enterString("\033\033]7;file://host/not-a-new-frame");
			assertEquals(1, mOutput.directories.size());
			enterString(ender);
			assertEquals(2, mOutput.directories.size());
			assertEquals("file://host/previous", mOutput.directories.get(0));
			assertEquals("", mOutput.directories.get(1));
			assertLinesAre("          ", "          ", "          ");
		}
	}

	public void testOsc7BadUtf8InvalidatesPriorDirectoryAtFrameEnd() {
		withTerminalSized(10, 3);
		int[][] invalidSequences = {
			{0xFF}, {0x80}, {0xC0, 0xAF}, {0xE0, 0x80, 0xAF}, {0xE0, 0x9F, 0xBF},
			{0xED, 0xA0, 0x80}, {0xF0, 0x8F, 0xBF, 0xBF}, {0xF4, 0x90, 0x80, 0x80},
			{0xF5, 0x80, 0x80, 0x80}, {0xE2, 0x82, 0x41}, {0xF0, 0x9F, 0x41}
		};
		for (String ender : new String[]{"\007", "\033\\"}) {
			for (int[] invalid : invalidSequences) {
				mOutput.directories.clear();
				enterString("\033]7;file://host/previous" + ender);
				enterString("\033]7;file://host/");
				appendBytesOneAtATime(invalid);
				assertEquals(1, mOutput.directories.size());
				enterString(ender);
				assertEquals(2, mOutput.directories.size());
				assertEquals("file://host/previous", mOutput.directories.get(0));
				assertEquals("", mOutput.directories.get(1));
				assertLinesAre("          ", "          ", "          ");
			}
		}
	}

	public void testOsc7TruncatedUtf8InvalidatesOnlyWhenBelOrStCompletesFrame() {
		withTerminalSized(10, 3);
		for (String ender : new String[]{"\007", "\033\\"}) {
			for (int[] partial : new int[][]{{0xC2}, {0xE2, 0x82}, {0xF0, 0x9F, 0x93}}) {
				mOutput.directories.clear();
				enterString("\033]7;file://host/previous" + ender);
				enterString("\033]7;file://host/");
				appendBytesOneAtATime(partial);
				assertEquals(1, mOutput.directories.size());
				for (int i = 0; i < ender.length(); i++) {
					assertEquals(1, mOutput.directories.size());
					enterString(ender.substring(i, i + 1));
				}
				assertEquals(2, mOutput.directories.size());
				assertEquals("", mOutput.directories.get(1));
				assertLinesAre("          ", "          ", "          ");
			}
		}
	}

	public void testOsc7ValidReplacementCharacterAndNextFrameRecoverAfterBadUtf8() {
		withTerminalSized(10, 3);
		for (String ender : new String[]{"\007", "\033\\"}) {
			mOutput.directories.clear();
			enterString("\033]7;file://host/previous" + ender);
			enterString("\033]7;file://host/");
			appendBytesOneAtATime(0xFF);
			enterString(ender);
			enterString("\033]7;file://host/");
			appendBytesOneAtATime(0xEF, 0xBF, 0xBD);
			enterString(ender);
			assertEquals(3, mOutput.directories.size());
			assertEquals("", mOutput.directories.get(1));
			assertEquals("file://host/�", mOutput.directories.get(2));
		}
	}

	public void testOsc7CancelledRejectedFramesDoNotInvalidatePriorDirectory() {
		withTerminalSized(10, 3);
		for (String cancel : new String[]{"\030", "\032"}) {
			for (boolean oversized : new boolean[]{false, true}) {
				mOutput.directories.clear();
				enterString("\033]7;file://host/previous\007");
				enterString("\033]7;" + (oversized ? directoryUriOfLength(8192) : "file://host/"));
				appendBytesOneAtATime(0xE2, 0x82);
				assertEquals(1, mOutput.directories.size());
				enterString(cancel + "\007\033\\");
				assertEquals(1, mOutput.directories.size());
				assertEquals("file://host/previous", mOutput.directories.get(0));
				enterString("\033]7;file://host/recovered\007");
				assertEquals(2, mOutput.directories.size());
				assertEquals("file://host/recovered", mOutput.directories.get(1));
			}
		}
	}

	public void testBadUtf8KeepsLegacyOscTitleAndNotificationBehavior() {
		withTerminalSized(10, 3);
		for (String ender : new String[]{"\007", "\033\\"}) {
			for (int command : new int[]{0, 1, 2, 9, 777}) {
				String prefix = command == 777 ? "777;notify;Title;" : command + ";";
				enterString("\033]" + prefix + "body");
				appendBytesOneAtATime(0xFF);
				enterString(ender);
				assertLegacyOscBody(command, "body�");
				enterString("\033]" + prefix + "body");
				appendBytesOneAtATime(0xE2, 0x82);
				enterString(ender);
				assertLegacyOscBody(command, "body");
			}
		}
		assertEquals(0, mOutput.directories.size());
	}

	private void assertLegacyOscBody(int command, String body) {
		if (command == 9 || command == 777) {
			String[] notification = mOutput.notifications.get(mOutput.notifications.size() - 1);
			assertEquals(command == 777 ? "Title" : null, notification[0]);
			assertEquals(body, notification[1]);
		} else {
			assertEquals(body, mTerminal.getTitle());
		}
	}

	private void appendBytesOneAtATime(int... values) {
		for (int value : values) mTerminal.append(new byte[]{(byte) value}, 1);
		assertInvariants();
	}

	private static TerminalSessionClient directoryClient(List<TerminalSession> sessions, List<String> uris) {
		return (TerminalSessionClient) Proxy.newProxyInstance(TerminalSessionClient.class.getClassLoader(),
			new Class<?>[]{TerminalSessionClient.class}, (proxy, method, args) -> {
				if ("onDirectoryChanged".equals(method.getName())) {
					sessions.add((TerminalSession) args[0]);
					uris.add((String) args[1]);
				}
				return null;
			});
	}

	private static String directoryUriOfLength(int length) {
		StringBuilder uri = new StringBuilder("file://host/");
		while (uri.length() < length) uri.append('a');
		return uri.toString();
	}

}
