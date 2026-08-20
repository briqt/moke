package com.termux.terminal;

/**
 * [moke] 忽略备用屏切换（mosh 会话专用）。
 *
 * <p>mosh-client 一连上就发 {@code ESC[?1049h} 把客户端钉在备用屏里，而它并不转发远端程序自己的
 * 1049——这个信号在 mosh 下恒为真、毫无信息量，还让本地 scrollback 彻底不可达。开关打开后
 * 47/1047/1049 全部忽略，屏幕留在主屏、历史照常沉进 transcript。
 */
public class MokeIgnoreAltScreenTest extends TerminalTestCase {

	/** 默认（SSH 路径）行为不变：1049h 切备用屏、1049l 切回。 */
	public void testDefaultStillSwitchesBuffers() {
		withTerminalSized(3, 2);
		assertFalse(mTerminal.isAlternateBufferActive());
		enterString("\033[?1049h");
		assertTrue("1049h should switch to the alternate buffer", mTerminal.isAlternateBufferActive());
		enterString("\033[?1049l");
		assertFalse(mTerminal.isAlternateBufferActive());
	}

	/** 打开开关后 47/1047/1049 都不再切屏。 */
	public void testIgnoredWhenEnabled() {
		withTerminalSized(3, 2);
		mTerminal.setMokeIgnoreAltScreen(true);
		for (String mode : new String[] { "47", "1047", "1049" }) {
			enterString("\033[?" + mode + "h");
			assertFalse("DECSET " + mode + " should be ignored", mTerminal.isAlternateBufferActive());
			enterString("\033[?" + mode + "l");
			assertFalse("DECRST " + mode + " should be ignored", mTerminal.isAlternateBufferActive());
		}
	}

	/**
	 * 关键收益：备用屏被忽略后，滚出屏幕的行会沉进 transcript（这正是滑动能滚起来的前提）。
	 * 对照组——同样的输入在默认行为下进了备用屏，备用屏没有 scrollback。
	 */
	public void testScrolledOffLinesReachTranscriptWhenIgnored() {
		withTerminalSized(3, 2);
		mTerminal.setMokeIgnoreAltScreen(true);
		enterString("\033[?1049h"); // mosh-client 开场那一发。
		enterString("a\r\nb\r\nc\r\nd");
		assertTrue("scrolled-off lines should be kept as transcript",
			mTerminal.getScreen().getActiveTranscriptRows() > 0);

		withTerminalSized(3, 2);
		enterString("\033[?1049h");
		enterString("a\r\nb\r\nc\r\nd");
		assertEquals("alternate buffer has no scrollback", 0, mTerminal.getScreen().getActiveTranscriptRows());
	}
}
