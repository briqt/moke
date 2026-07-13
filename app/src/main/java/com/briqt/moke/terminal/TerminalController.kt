package com.briqt.moke.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * 同时实现 {@link TerminalSessionClient}（会话回调）与 {@link TerminalViewClient}（视图/输入回调）。
 * 附加键的粘滞修饰状态（Ctrl/Alt）由 UI 层设置，经 read*Key() 反馈给 TerminalView。
 */
class TerminalController(
    context: Context,
    private val onFinished: () -> Unit = {},
    private val onTitle: (String?) -> Unit = {},
) : TerminalViewClient, TerminalSessionClient {

    private val appContext = context.applicationContext
    private val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    var view: TerminalView? = null

    @Volatile var ctrlActive = false
    @Volatile var altActive = false

    /** 当前字号（sp），由 UI 初始化；捏合缩放时按 sp 步进，回调 [onFontSizeSp] 让上层持久化。 */
    var fontSizeSp: Float = DEFAULT_FONT_SIZE_SP
    /** 捏合缩放后回报新字号（sp），上层据此持久化 + 显示缩放提示。 */
    var onFontSizeSp: ((Float) -> Unit)? = null

    /** 光标样式（0=方块 1=下划线 2=竖线）与是否闪烁，由 UI 设置。 */
    @Volatile var cursorStyle: Int = 0
    @Volatile var cursorBlink: Boolean = true

    // ---------- TerminalSessionClient ----------
    override fun onTextChanged(changedSession: TerminalSession) { view?.onScreenUpdated() }
    override fun onTitleChanged(changedSession: TerminalSession) { onTitle(changedSession.title) }
    override fun onSessionFinished(finishedSession: TerminalSession) { onFinished() }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        if (!text.isNullOrEmpty()) {
            clipboard.setPrimaryClip(ClipData.newPlainText("moke", text))
        }
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount > 0) {
            val text = clip.getItemAt(0).coerceToText(appContext)?.toString()
            if (!text.isNullOrEmpty()) session?.write(text)
        }
    }

    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) { view?.onScreenUpdated() }
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
    override fun getTerminalCursorStyle(): Int = cursorStyle

    // ---------- TerminalViewClient ----------
    override fun onScale(scale: Float): Float {
        // TerminalView 传入的是累积缩放因子；超过阈值就调一档字号（±0.5sp，与设置页一致）并把因子复位为 1。
        if (scale < 0.9f || scale > 1.1f) {
            val v = view ?: return 1.0f
            val next = (if (scale > 1f) fontSizeSp + FONT_SIZE_STEP else fontSizeSp - FONT_SIZE_STEP)
                .coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
            if (next != fontSizeSp) {
                fontSizeSp = next
                v.setTextSize(Math.round(next * appContext.resources.displayMetrics.density))
                onFontSizeSp?.invoke(next)  // 让 UI 持久化 + 弹缩放提示
            }
            return 1.0f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent?) { showKeyboard() }

    /** 聚焦终端并弹出软键盘（点击终端 / 工具栏键盘键调用）。用自身 [view]，故会话跨页重建 View 也不失效。 */
    fun showKeyboard() {
        val v = view ?: return
        v.requestFocus()
        val imm = v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
    }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
    override fun onLongPress(event: MotionEvent?): Boolean = false
    override fun readControlKey(): Boolean = ctrlActive
    override fun readAltKey(): Boolean = altActive
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
    override fun onEmulatorSet() { view?.setTerminalCursorBlinkerState(cursorBlink, true) }

    // ---------- 日志（两个接口共用同签名，单实现即可）----------
    override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message ?: "") }
    override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message ?: "") }
    override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: TAG, message ?: "") }
    override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: TAG, message ?: "") }
    override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: TAG, message ?: "") }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag ?: TAG, message ?: "", e) }
    override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag ?: TAG, "", e) }

    companion object {
        private const val TAG = "moke"
        // 与 SettingsStore 的字号范围/默认保持一致（避免耦合，此处复述常量）。
        const val DEFAULT_FONT_SIZE_SP = 11f
        const val FONT_SIZE_STEP = 0.5f
        const val MIN_FONT_SIZE_SP = 8f
        const val MAX_FONT_SIZE_SP = 24f
    }
}
