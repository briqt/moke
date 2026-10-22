package com.briqt.moke.data

/** 应用明暗主题：跟随系统 / 强制浅色 / 强制深色。 */
enum class ThemeMode {
    SYSTEM, LIGHT, DARK;

    companion object {
        fun fromName(n: String?, def: ThemeMode = SYSTEM): ThemeMode =
            entries.firstOrNull { it.name == n } ?: def
    }
}

/**
 * 终端软键盘模式——决定 `TerminalView.onCreateInputConnection` 上报的 inputType。
 * 部分厂商（如荣耀/华为）见到"密码变体"输入框会强制切到系统安全键盘，导致中文候选不可用，
 * 故把它做成可切换项而非硬编码。
 *
 * - [SECURE]   字符模式：`TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | NO_SUGGESTIONS`，逐字符提交，
 *              不学习不纠错；最省心，但可能触发厂商安全键盘。
 * - [STANDARD] 标准：`TYPE_NULL`（termux 上游默认），多数输入法直通按键。
 * - [IME]      输入法优先：普通文本框（`TYPE_CLASS_TEXT`），完整候选词/联想/纠错，
 *              中文输入最顺；代价是输入法可能自动纠错。
 */
enum class KeyboardMode {
    SECURE, STANDARD, IME;

    companion object {
        fun fromName(n: String?, def: KeyboardMode = SECURE): KeyboardMode =
            entries.firstOrNull { it.name == n } ?: def
    }
}

/**
 * 当前连接的临时滑动方式。自动只依据鼠标/备用屏滚动请求，不猜测前台程序类型。
 * [WHEEL]、[ARROWS] 是用户主动发送的输入，不保证只移动程序视口；实际输入或离开后恢复自动。
 * 枚举名称保留旧配置的读取兼容，但不再作为全局偏好应用到新连接。
 */
enum class ScrollMode {
    SMART, WHEEL, ARROWS;

    companion object {
        fun fromName(n: String?, def: ScrollMode = SMART): ScrollMode =
            entries.firstOrNull { it.name == n } ?: def
    }
}

/** 文件页排序维度。目录始终排在文件前面，本枚举只决定同类之间怎么比。 */
enum class FilesSort { NAME, TIME, SIZE }
