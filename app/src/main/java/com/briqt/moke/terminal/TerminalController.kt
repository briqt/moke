package com.briqt.moke.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import com.briqt.moke.data.KeyboardMode
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

    /** 有终端输出/屏幕更新时回调（供上层刷新会话"最后活动时间"）。 */
    var onActivity: (() -> Unit)? = null

    private val appContext = context.applicationContext
    private val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    var view: TerminalView? = null

    @Volatile var ctrlActive = false
    @Volatile var altActive = false
    /** 修饰键是否处于锁定态：锁定时不被单次输入消费（连发 Ctrl+C / 连走光标）。 */
    @Volatile var ctrlLocked = false
    @Volatile var altLocked = false
    /** 粘滞修饰键被一次输入消费后回调（让 UI 熄灭 Ctrl/Alt 高亮）——实现 one-shot「用一次即取消」。 */
    var onModifiersConsumed: (() -> Unit)? = null

    /** 当前字号（sp），由 UI 初始化；捏合缩放时按 sp 步进，回调 [onFontSizeSp] 让上层持久化。 */
    var fontSizeSp: Float = DEFAULT_FONT_SIZE_SP
    /** 捏合缩放后回报新字号（sp），上层据此持久化 + 显示缩放提示。 */
    var onFontSizeSp: ((Float) -> Unit)? = null

    /** 光标样式（0=方块 1=下划线 2=竖线）与是否闪烁，由 UI 设置。 */
    @Volatile var cursorStyle: Int = 0
    @Volatile var cursorBlink: Boolean = true

    /** 软键盘模式（见 [KeyboardMode]）；改动后须 [restartInput] 让输入法重新取 EditorInfo。 */
    @Volatile var keyboardMode: KeyboardMode = KeyboardMode.SECURE

    // ---------- TerminalSessionClient ----------
    override fun onTextChanged(changedSession: TerminalSession) { onActivity?.invoke(); view?.onScreenUpdated() }
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
    /**
     * 键盘模式变更后重新协商输入连接：不 restart 的话输入法仍按旧 EditorInfo 工作，
     * 要退出会话再进来才生效。
     */
    fun restartInput() {
        val v = view ?: return
        val imm = v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.restartInput(v)
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    /** 仅「字符模式」上报密码变体 inputType（逐字符、不学习不纠错）。 */
    override fun shouldEnforceCharBasedInput(): Boolean = keyboardMode == KeyboardMode.SECURE
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    /**
     * 「输入法优先」模式下故意上报 false —— 上游据此把 inputType 设为普通文本框
     * （`TYPE_CLASS_TEXT`），中文候选词/联想才可用。该回调在 vendored 层只被
     * `onCreateInputConnection` 读取一处，无其它副作用。
     */
    override fun isTerminalViewSelected(): Boolean = keyboardMode != KeyboardMode.IME
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
    override fun onLongPress(event: MotionEvent?): Boolean = false
    override fun readControlKey(): Boolean = ctrlActive
    override fun readAltKey(): Boolean = altActive
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
        // one-shot 粘滞修饰：本次按键的 ctrl/alt 已在 TerminalView 内读入本地变量并会照常应用，
        // 这里把吸附状态复位（下次按键不再带修饰）并通知 UI 熄灭高亮。返回 false 不拦截本次输入。
        // 锁定态不复位——那正是"按住不放"的语义。
        val consumable = (ctrlActive && !ctrlLocked) || (altActive && !altLocked)
        if (consumable) {
            if (!ctrlLocked) ctrlActive = false
            if (!altLocked) altActive = false
            onModifiersConsumed?.invoke()
        }
        return false
    }
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
        const val DEFAULT_FONT_SIZE_SP = 11.5f
        const val FONT_SIZE_STEP = 0.5f
        const val MIN_FONT_SIZE_SP = 8f
        const val MAX_FONT_SIZE_SP = 24f
    }
}
