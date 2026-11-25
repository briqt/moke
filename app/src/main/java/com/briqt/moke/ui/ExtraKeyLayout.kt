package com.briqt.moke.ui

import com.briqt.moke.R
import com.briqt.moke.terminal.KeyId
import com.briqt.moke.terminal.KeySeq
import com.briqt.moke.terminal.KeyStep
import com.briqt.moke.terminal.ModKind
import org.json.JSONArray
import org.json.JSONObject

/** 底部快捷键与扩展面板的键帽高度档位。 */
enum class ExtraKeyHeight(val dp: Int) {
    COMPACT(28),
    STANDARD(34),
    COMFORTABLE(40),
}

/** 可选终端实心图标标识（由 UI 层映射到 Material Filled 矢量图标，避免序列化绑定 UI 对象）。 */
enum class ExtraKeyIcon(val id: String) {
    RETURN("return"),
    BACKSPACE("backspace"),
    TAB("tab"),
    SPACE("space"),
    FIRST_PAGE("first_page"),
    LAST_PAGE("last_page"),
    TOP("top"),
    BOTTOM("bottom"),
    SPLIT_VERTICAL("split_v"),
    SPLIT_HORIZONTAL("split_h"),
    ADD("add"),
    CLOSE("close"),
    STOP("stop"),
    SEARCH("search"),
    HISTORY("history"),
    TERMINAL("terminal"),
    SWAP_HORIZ("swap_h"),
    WINDOW("window");

    companion object {
        private val byIdMap = entries.associateBy { it.id }
        fun byId(id: String): ExtraKeyIcon? = byIdMap[id]
    }
}

/** 组合键构造器中的目标键分类。 */
enum class ComboTargetCategory {
    COMMON,
    NAVIGATION,
    LETTERS,
    DIGITS,
    FUNCTION,
}

/**
 * 可视化组合键构造器可选的目标按键。
 *
 * 所有默认短标识只使用 ASCII 或已核验包含在随包 `jetbrains_mono.ttf` 中的符号字形
 * （`↑↓←→↖↘⇞⇟⇥⎋⌫⌦⏎⇧␣`），保证任何设备上都不会渲染成缺字豆腐块。
 */
enum class ComboTarget(
    val id: String,
    val display: String,
    val shortGlyph: String,
    val category: ComboTargetCategory,
    val keyId: KeyId,
) {
    // 常用符号与空格（覆盖终端复用分屏、窗格互换、复制模式与行编辑高频键）
    SPACE("space", "\u2423", "\u2423", ComboTargetCategory.COMMON, KeyId.Chars(" ")),
    PERCENT("pct", "%", "%", ComboTargetCategory.COMMON, KeyId.Chars("%")),
    DQUOTE("dquote", "\"", "\"", ComboTargetCategory.COMMON, KeyId.Chars("\"")),
    LBRACKET("lbrack", "[", "[", ComboTargetCategory.COMMON, KeyId.Chars("[")),
    RBRACKET("rbrack", "]", "]", ComboTargetCategory.COMMON, KeyId.Chars("]")),
    LBRACE("lbrace", "{", "{", ComboTargetCategory.COMMON, KeyId.Chars("{")),
    RBRACE("rbrace", "}", "}", ComboTargetCategory.COMMON, KeyId.Chars("}")),
    LPAREN("lparen", "(", "(", ComboTargetCategory.COMMON, KeyId.Chars("(")),
    RPAREN("rparen", ")", ")", ComboTargetCategory.COMMON, KeyId.Chars(")")),
    BANG("bang", "!", "!", ComboTargetCategory.COMMON, KeyId.Chars("!")),
    MINUS("minus", "-", "-", ComboTargetCategory.COMMON, KeyId.Chars("-")),
    UNDERSCORE("under", "_", "_", ComboTargetCategory.COMMON, KeyId.Chars("_")),
    PLUS("plus", "+", "+", ComboTargetCategory.COMMON, KeyId.Chars("+")),
    EQUALS("eq", "=", "=", ComboTargetCategory.COMMON, KeyId.Chars("=")),
    STAR("star", "*", "*", ComboTargetCategory.COMMON, KeyId.Chars("*")),
    PIPE("pipe", "|", "|", ComboTargetCategory.COMMON, KeyId.Chars("|")),
    COLON("colon", ":", ":", ComboTargetCategory.COMMON, KeyId.Chars(":")),
    SEMICOLON("semi", ";", ";", ComboTargetCategory.COMMON, KeyId.Chars(";")),
    QUESTION("qmark", "?", "?", ComboTargetCategory.COMMON, KeyId.Chars("?")),
    SLASH("slash", "/", "/", ComboTargetCategory.COMMON, KeyId.Chars("/")),
    BACKSLASH("bslash", "\\", "\\", ComboTargetCategory.COMMON, KeyId.Chars("\\")),
    CARET("caret", "^", "^", ComboTargetCategory.COMMON, KeyId.Chars("^")),
    AT("at", "@", "@", ComboTargetCategory.COMMON, KeyId.Chars("@")),
    HASH("hash", "#", "#", ComboTargetCategory.COMMON, KeyId.Chars("#")),
    DOLLAR("dollar", "$", "$", ComboTargetCategory.COMMON, KeyId.Chars("$")),
    AMPERSAND("amp", "&", "&", ComboTargetCategory.COMMON, KeyId.Chars("&")),
    COMMA("comma", ",", ",", ComboTargetCategory.COMMON, KeyId.Chars(",")),
    DOT("dot", ".", ".", ComboTargetCategory.COMMON, KeyId.Chars(".")),
    SQUOTE("squote", "'", "'", ComboTargetCategory.COMMON, KeyId.Chars("'")),
    GRAVE("grave", "`", "`", ComboTargetCategory.COMMON, KeyId.Chars("`")),
    TILDE("tilde", "~", "~", ComboTargetCategory.COMMON, KeyId.Chars("~")),

    // 导航与编辑键
    UP("up", "↑", "↑", ComboTargetCategory.NAVIGATION, KeyId.Up),
    DOWN("down", "↓", "↓", ComboTargetCategory.NAVIGATION, KeyId.Down),
    LEFT("left", "←", "←", ComboTargetCategory.NAVIGATION, KeyId.Left),
    RIGHT("right", "→", "→", ComboTargetCategory.NAVIGATION, KeyId.Right),
    HOME("home", "HOME", "↖", ComboTargetCategory.NAVIGATION, KeyId.Home),
    END("end", "END", "↘", ComboTargetCategory.NAVIGATION, KeyId.End),
    PGUP("pgup", "PgUp", "⇞", ComboTargetCategory.NAVIGATION, KeyId.PageUp),
    PGDN("pgdn", "PgDn", "⇟", ComboTargetCategory.NAVIGATION, KeyId.PageDown),
    TAB("tab", "TAB", "⇥", ComboTargetCategory.NAVIGATION, KeyId.Tab),
    ENTER("enter", "Enter", "⏎", ComboTargetCategory.NAVIGATION, KeyId.Enter),
    BACKSPACE("bs", "⌫", "⌫", ComboTargetCategory.NAVIGATION, KeyId.Backspace),
    DELETE("del", "DEL", "⌦", ComboTargetCategory.NAVIGATION, KeyId.Delete),
    INSERT("ins", "INS", "INS", ComboTargetCategory.NAVIGATION, KeyId.Insert),
    ESC("esc", "ESC", "⎋", ComboTargetCategory.NAVIGATION, KeyId.Esc),

    // 字母 A–Z（无 Shift 发小写，勾 Shift 发大写）
    KEY_A("a", "A", "a", ComboTargetCategory.LETTERS, KeyId.Chars("a")),
    KEY_B("b", "B", "b", ComboTargetCategory.LETTERS, KeyId.Chars("b")),
    KEY_C("c", "C", "c", ComboTargetCategory.LETTERS, KeyId.Chars("c")),
    KEY_D("d", "D", "d", ComboTargetCategory.LETTERS, KeyId.Chars("d")),
    KEY_E("e", "E", "e", ComboTargetCategory.LETTERS, KeyId.Chars("e")),
    KEY_F("f", "F", "f", ComboTargetCategory.LETTERS, KeyId.Chars("f")),
    KEY_G("g", "G", "g", ComboTargetCategory.LETTERS, KeyId.Chars("g")),
    KEY_H("h", "H", "h", ComboTargetCategory.LETTERS, KeyId.Chars("h")),
    KEY_I("i", "I", "i", ComboTargetCategory.LETTERS, KeyId.Chars("i")),
    KEY_J("j", "J", "j", ComboTargetCategory.LETTERS, KeyId.Chars("j")),
    KEY_K("k", "K", "k", ComboTargetCategory.LETTERS, KeyId.Chars("k")),
    KEY_L("l", "L", "l", ComboTargetCategory.LETTERS, KeyId.Chars("l")),
    KEY_M("m", "M", "m", ComboTargetCategory.LETTERS, KeyId.Chars("m")),
    KEY_N("n", "N", "n", ComboTargetCategory.LETTERS, KeyId.Chars("n")),
    KEY_O("o", "O", "o", ComboTargetCategory.LETTERS, KeyId.Chars("o")),
    KEY_P("p", "P", "p", ComboTargetCategory.LETTERS, KeyId.Chars("p")),
    KEY_Q("q", "Q", "q", ComboTargetCategory.LETTERS, KeyId.Chars("q")),
    KEY_R("r", "R", "r", ComboTargetCategory.LETTERS, KeyId.Chars("r")),
    KEY_S("s", "S", "s", ComboTargetCategory.LETTERS, KeyId.Chars("s")),
    KEY_T("t", "T", "t", ComboTargetCategory.LETTERS, KeyId.Chars("t")),
    KEY_U("u", "U", "u", ComboTargetCategory.LETTERS, KeyId.Chars("u")),
    KEY_V("v", "V", "v", ComboTargetCategory.LETTERS, KeyId.Chars("v")),
    KEY_W("w", "W", "w", ComboTargetCategory.LETTERS, KeyId.Chars("w")),
    KEY_X("x", "X", "x", ComboTargetCategory.LETTERS, KeyId.Chars("x")),
    KEY_Y("y", "Y", "y", ComboTargetCategory.LETTERS, KeyId.Chars("y")),
    KEY_Z("z", "Z", "z", ComboTargetCategory.LETTERS, KeyId.Chars("z")),

    // 数字 0–9
    DIGIT_0("0", "0", "0", ComboTargetCategory.DIGITS, KeyId.Chars("0")),
    DIGIT_1("1", "1", "1", ComboTargetCategory.DIGITS, KeyId.Chars("1")),
    DIGIT_2("2", "2", "2", ComboTargetCategory.DIGITS, KeyId.Chars("2")),
    DIGIT_3("3", "3", "3", ComboTargetCategory.DIGITS, KeyId.Chars("3")),
    DIGIT_4("4", "4", "4", ComboTargetCategory.DIGITS, KeyId.Chars("4")),
    DIGIT_5("5", "5", "5", ComboTargetCategory.DIGITS, KeyId.Chars("5")),
    DIGIT_6("6", "6", "6", ComboTargetCategory.DIGITS, KeyId.Chars("6")),
    DIGIT_7("7", "7", "7", ComboTargetCategory.DIGITS, KeyId.Chars("7")),
    DIGIT_8("8", "8", "8", ComboTargetCategory.DIGITS, KeyId.Chars("8")),
    DIGIT_9("9", "9", "9", ComboTargetCategory.DIGITS, KeyId.Chars("9")),

    // 功能键 F1–F12
    F1("f1", "F1", "F1", ComboTargetCategory.FUNCTION, KeyId.Fn(1)),
    F2("f2", "F2", "F2", ComboTargetCategory.FUNCTION, KeyId.Fn(2)),
    F3("f3", "F3", "F3", ComboTargetCategory.FUNCTION, KeyId.Fn(3)),
    F4("f4", "F4", "F4", ComboTargetCategory.FUNCTION, KeyId.Fn(4)),
    F5("f5", "F5", "F5", ComboTargetCategory.FUNCTION, KeyId.Fn(5)),
    F6("f6", "F6", "F6", ComboTargetCategory.FUNCTION, KeyId.Fn(6)),
    F7("f7", "F7", "F7", ComboTargetCategory.FUNCTION, KeyId.Fn(7)),
    F8("f8", "F8", "F8", ComboTargetCategory.FUNCTION, KeyId.Fn(8)),
    F9("f9", "F9", "F9", ComboTargetCategory.FUNCTION, KeyId.Fn(9)),
    F10("f10", "F10", "F10", ComboTargetCategory.FUNCTION, KeyId.Fn(10)),
    F11("f11", "F11", "F11", ComboTargetCategory.FUNCTION, KeyId.Fn(11)),
    F12("f12", "F12", "F12", ComboTargetCategory.FUNCTION, KeyId.Fn(12));

    companion object {
        private val byIdMap = entries.associateBy { it.id }
        fun byId(id: String): ComboTarget? = byIdMap[id]
        fun forCategory(cat: ComboTargetCategory): List<ComboTarget> = entries.filter { it.category == cat }
        val letterTargets: List<ComboTarget> = entries.filter { it.category == ComboTargetCategory.LETTERS }
    }
}

/** 组合键单步规格。 */
data class ComboStepSpec(
    val target: ComboTarget,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
) {
    fun toKeyStep(): KeyStep = KeyStep(
        key = target.keyId,
        ctrl = ctrl,
        alt = alt,
        shift = shift,
    )

    fun autoLabel(compactForTwoStep: Boolean = false): String {
        val isLetter = target.category == ComboTargetCategory.LETTERS
        val keyId = target.keyId
        val effCtrl: Boolean
        val effAlt: Boolean
        val effShift: Boolean
        when (keyId) {
            KeyId.Esc -> {
                effCtrl = false
                effAlt = false
                effShift = false
            }
            KeyId.Enter -> {
                effCtrl = false
                effAlt = alt
                effShift = false
            }
            KeyId.Tab -> {
                effCtrl = false
                effShift = shift
                effAlt = alt && !shift
            }
            KeyId.Backspace -> {
                effCtrl = ctrl
                effAlt = alt
                effShift = false
            }
            is KeyId.Chars -> {
                effCtrl = ctrl && keyId.text.length == 1 && KeySeq.ctrlChar(keyId.text[0]) != null
                effAlt = alt && keyId.text.length == 1
                effShift = shift && isLetter && !effCtrl
            }
            else -> {
                effCtrl = ctrl
                effAlt = alt
                effShift = shift
            }
        }
        val keyPart = when {
            isLetter && (effCtrl || effShift) -> target.display // ^A..^Z 与大写字母统一大写
            else -> target.shortGlyph
        }
        if (!effCtrl && !effAlt && !effShift) return keyPart
        if (isLetter && effShift && !effCtrl && !effAlt) return keyPart
        val modPrefix = buildString {
            if (effCtrl) append('^')
            if (effAlt) append(if (effCtrl || compactForTwoStep) "M" else "M-")
            if (effShift && !isLetter) append('⇧')
        }
        return modPrefix + keyPart
    }

    companion object {
        val PREFIX_CTRL_B = ComboStepSpec(ComboTarget.KEY_B, ctrl = true)
        val PREFIX_CTRL_A = ComboStepSpec(ComboTarget.KEY_A, ctrl = true)
        val PREFIX_CTRL_X = ComboStepSpec(ComboTarget.KEY_X, ctrl = true)
        val PREFIX_ESC = ComboStepSpec(ComboTarget.ESC)
    }
}

/** 单个动作配置：内置按键或可视化组合键，附可选标签/图标覆盖。 */
sealed interface ExtraKeySpec {
    val customLabel: String
    val customIcon: ExtraKeyIcon?

    data class Builtin(
        val id: String,
        override val customLabel: String = "",
        override val customIcon: ExtraKeyIcon? = null,
    ) : ExtraKeySpec

    data class CustomCombo(
        val steps: List<ComboStepSpec>,
        override val customLabel: String = "",
        override val customIcon: ExtraKeyIcon? = null,
    ) : ExtraKeySpec {
        init {
            require(steps.size in 1..2) { "CustomCombo requires 1 or 2 steps" }
        }

        fun toKeyId(): KeyId.Combo = KeyId.Combo(steps.map { it.toKeyStep() })
    }

    val isModifier: Boolean
        get() = this is Builtin && ExtraKeyLayout.isModifierId(id)

    fun defaultLabel(compactSymbols: Boolean): String = when (this) {
        is Builtin -> ExtraKeyLayout.label(id, compactSymbols)
        is CustomCombo -> {
            val raw = if (steps.size == 1) {
                steps[0].autoLabel(compactForTwoStep = false)
            } else {
                steps[0].autoLabel(compactForTwoStep = true) + steps[1].autoLabel(compactForTwoStep = true)
            }
            ExtraKeyLayout.takeCodePoints(raw, ExtraKeyLayout.MAX_CUSTOM_LABEL_CHARS)
        }
    }

    fun effectiveLabel(compactSymbols: Boolean): String {
        val clean = ExtraKeyLayout.sanitizeCustomLabel(customLabel)
        return clean.ifEmpty { defaultLabel(compactSymbols) }
    }

    /** 键帽右上角极简副标（按最多 3 个窄字符宽度截断，避免全角中文溢出键帽）。 */
    fun microHintLabel(): String {
        val clean = ExtraKeyLayout.sanitizeCustomLabel(customLabel)
        if (clean.isNotEmpty()) return ExtraKeyLayout.takeVisualUnits(clean, 3)
        return when (this) {
            is Builtin -> ExtraKeyLayout.microLabel(id)
            is CustomCombo -> ExtraKeyLayout.takeVisualUnits(defaultLabel(compactSymbols = true), 3)
        }
    }

    fun withDisplay(label: String, icon: ExtraKeyIcon?): ExtraKeySpec {
        val clean = ExtraKeyLayout.sanitizeCustomLabel(label)
        return when (this) {
            is Builtin -> copy(customLabel = clean, customIcon = icon)
            is CustomCombo -> copy(customLabel = clean, customIcon = icon)
        }
    }

    fun toSecondary(compactSymbols: Boolean): ExtraKeySecondary? {
        if (isModifier) return null
        val cleanLabel = ExtraKeyLayout.sanitizeCustomLabel(customLabel)
        return when (this) {
            is Builtin -> when (val base = ExtraKeyLayout.key(id)) {
                is ExtraKey.Mod -> null
                is ExtraKey.Key -> ExtraKeySecondary.SendKey(
                    label = effectiveLabel(compactSymbols),
                    key = base.key,
                    icon = customIcon,
                    microHint = microHintLabel(),
                )
                is ExtraKey.Action -> ExtraKeySecondary.TriggerAction(
                    id = base.id,
                    label = cleanLabel,
                    icon = customIcon,
                    microHint = if (cleanLabel.isNotEmpty()) ExtraKeyLayout.takeVisualUnits(cleanLabel, 3) else "",
                )
            }
            is CustomCombo -> ExtraKeySecondary.SendKey(
                label = effectiveLabel(compactSymbols),
                key = toKeyId(),
                icon = customIcon,
                microHint = microHintLabel(),
            )
        }
    }

    fun toExtraKey(compactSymbols: Boolean, secondary: ExtraKeySecondary? = null): ExtraKey {
        val cleanLabel = ExtraKeyLayout.sanitizeCustomLabel(customLabel)
        val displayLabel = effectiveLabel(compactSymbols)
        return when (this) {
            is Builtin -> when (val base = ExtraKeyLayout.key(id)) {
                is ExtraKey.Mod -> ExtraKey.Mod(
                    label = displayLabel,
                    kind = base.kind,
                    icon = customIcon,
                    secondary = secondary,
                )
                is ExtraKey.Key -> ExtraKey.Key(
                    label = displayLabel,
                    key = base.key,
                    icon = customIcon,
                    secondary = secondary,
                )
                is ExtraKey.Action -> ExtraKey.Action(
                    id = base.id,
                    label = cleanLabel,
                    icon = customIcon,
                    secondary = secondary,
                )
            }
            is CustomCombo -> ExtraKey.Key(
                label = displayLabel,
                key = toKeyId(),
                icon = customIcon,
                secondary = secondary,
            )
        }
    }
}

/** 底部栏单个可编辑槽位：主动作 + 可选上滑副动作。 */
data class ExtraKeySlot(
    val primary: ExtraKeySpec,
    val secondary: ExtraKeySpec? = null,
) {
    fun toExtraKey(compactSymbols: Boolean): ExtraKey {
        val sec = secondary?.toSecondary(compactSymbols)
        return primary.toExtraKey(compactSymbols, secondary = sec)
    }
}

/** 暂存的列槽位条目：[atEnd] 区分从行尾直接裁下（末格为普通键）还是从锚定动作键左侧裁下。 */
data class StashedExtraKeySlot(
    val slot: ExtraKeySlot,
    val atEnd: Boolean = false,
)

/** 底部两排快捷键完整配置（每排全部 [columns] 格均可自由编辑与互换）。 */
data class ExtraKeyConfig(
    val columns: Int = ExtraKeyLayout.DEFAULT_COLUMNS,
    val height: ExtraKeyHeight = ExtraKeyHeight.STANDARD,
    val compactSymbols: Boolean = false,
    val rows: List<List<ExtraKeySlot>> = DEFAULT_ROWS,
    val stashedCols: List<List<StashedExtraKeySlot>> = EMPTY_STASH,
) {
    val editableColumns: Int get() = columns
    val isDefault: Boolean get() = this == DEFAULT

    /**
     * 调整每排总列数（6 / 7 / 8），逐列缩减暂存或逐列扩回无损恢复。
     *
     * 若某排末格仍为内置动作键（更多 / 文本），则在末格左侧相邻处暂存或补齐；
     * 若末格已改为普通键（含动作键左移后右侧放普通键），则按 [StashedExtraKeySlot.atEnd] 精确在行尾暂存与恢复。
     */
    fun resizeColumns(newColumns: Int): ExtraKeyConfig {
        val clamped = newColumns.coerceIn(ExtraKeyLayout.MIN_COLUMNS, ExtraKeyLayout.MAX_COLUMNS)
        if (clamped == columns) return this
        var current = this
        while (current.columns > clamped) {
            current = current.shrinkOneColumn()
        }
        while (current.columns < clamped) {
            current = current.expandOneColumn()
        }
        return current
    }

    private fun shrinkOneColumn(): ExtraKeyConfig {
        val nextCols = columns - 1
        val maxStash = ExtraKeyLayout.MAX_COLUMNS - nextCols
        val keptRows = mutableListOf<List<ExtraKeySlot>>()
        val candidateStash = mutableListOf<List<StashedExtraKeySlot>>()
        for (rowIndex in 0..1) {
            val row = rows[rowIndex]
            val prev = stashedCols.getOrElse(rowIndex) { emptyList() }
            if (hasAnchoredTailAction(row)) {
                val tail = row.last()
                val removed = row[row.size - 2]
                keptRows += row.dropLast(2) + tail
                candidateStash += (listOf(StashedExtraKeySlot(removed, atEnd = false)) + prev).take(maxStash)
            } else {
                val removed = row.last()
                keptRows += row.dropLast(1)
                candidateStash += (listOf(StashedExtraKeySlot(removed, atEnd = true)) + prev).take(maxStash)
            }
        }
        return copy(
            columns = nextCols,
            rows = keptRows,
            stashedCols = normalizeStash(keptRows, candidateStash),
        )
    }

    private fun expandOneColumn(): ExtraKeyConfig {
        val nextCols = columns + 1
        val maxStash = ExtraKeyLayout.MAX_COLUMNS - nextCols
        val usedIds = rows.flatten()
            .mapNotNull { (it.primary as? ExtraKeySpec.Builtin)?.id }
            .toMutableSet()
        val remainingStash = mutableListOf<List<StashedExtraKeySlot>>()
        val resizedRows = rows.mapIndexed { rowIndex, row ->
            val queue = ArrayDeque(stashedCols.getOrElse(rowIndex) { emptyList() })
            val candidates = if (rowIndex == 0) ROW0_PAD_CANDIDATES else ROW1_PAD_CANDIDATES
            val restoredEntry = queue.removeFirstOrNull()
            val newRow = if (restoredEntry != null) {
                val primary = restoredEntry.slot.primary
                val resolvedSlot = if (
                    primary is ExtraKeySpec.Builtin &&
                    ExtraKeyLayout.isModifierId(primary.id) &&
                    primary.id in usedIds
                ) {
                    val fallback = ExtraKeyLayout.allNonModifierIds.firstOrNull { it !in usedIds } ?: "esc"
                    usedIds += fallback
                    restoredEntry.slot.copy(primary = ExtraKeySpec.Builtin(fallback))
                } else {
                    if (primary is ExtraKeySpec.Builtin) usedIds += primary.id
                    restoredEntry.slot
                }
                if (!restoredEntry.atEnd && hasAnchoredTailAction(row)) {
                    row.dropLast(1) + resolvedSlot + row.last()
                } else {
                    row + resolvedSlot
                }
            } else {
                val pick = candidates.firstOrNull { it !in usedIds }
                    ?: ExtraKeyLayout.allNonModifierIds.first { it !in usedIds }
                usedIds += pick
                val padSlot = ExtraKeySlot(ExtraKeySpec.Builtin(pick))
                if (hasAnchoredTailAction(row)) {
                    row.dropLast(1) + padSlot + row.last()
                } else {
                    row + padSlot
                }
            }
            remainingStash += queue.take(maxStash)
            newRow
        }
        return copy(
            columns = nextCols,
            rows = resizedRows,
            stashedCols = normalizeStash(resizedRows, remainingStash),
        )
    }

    /**
     * 设置指定槽位的主动作。
     *
     * 修饰键全局唯一：若选中的 `ctrl`/`alt`/`shift` 已存在于另一槽位 `(r2, c2)`，
     * 自动将 `(r2, c2)` 的主动作与当前槽位原主动作互换（两侧已配的副动作均原样保留）。
     */
    fun assignPrimary(row: Int, col: Int, spec: ExtraKeySpec): ExtraKeyConfig {
        if (row !in 0..1 || col !in 0 until columns) return this
        val next = rows.map { it.toMutableList() }
        val previousPrimary = next[row][col].primary
        if (spec is ExtraKeySpec.Builtin && ExtraKeyLayout.isModifierId(spec.id)) {
            for (r in 0..1) {
                for (c in 0 until columns) {
                    if (r == row && c == col) continue
                    val existing = next[r][c].primary
                    if (existing is ExtraKeySpec.Builtin && existing.id == spec.id) {
                        val replacement = if (previousPrimary is ExtraKeySpec.Builtin && previousPrimary.id == spec.id) {
                            ExtraKeySpec.Builtin(fallbackNonModId(next))
                        } else {
                            previousPrimary
                        }
                        next[r][c] = next[r][c].copy(primary = replacement)
                    }
                }
            }
        }
        next[row][col] = next[row][col].copy(primary = spec)
        val builtRows = next.map { it.toList() }
        return copy(rows = builtRows, stashedCols = normalizeStash(builtRows, stashedCols))
    }

    /** 设置指定槽位的上滑副动作（修饰键不允许作为副动作，其余内置键、组合键与面板/文本动作均可）。 */
    fun assignSecondary(row: Int, col: Int, spec: ExtraKeySpec?): ExtraKeyConfig {
        if (row !in 0..1 || col !in 0 until columns) return this
        if (spec != null && spec.isModifier) return this
        val next = rows.map { it.toMutableList() }
        next[row][col] = next[row][col].copy(secondary = spec)
        val builtRows = next.map { it.toList() }
        return copy(rows = builtRows, stashedCols = normalizeStash(builtRows, stashedCols))
    }

    /** 拖拽互换两个槽位的整套主/副动作配置。 */
    fun swapSlots(from: Pair<Int, Int>, to: Pair<Int, Int>): ExtraKeyConfig {
        if (from == to) return this
        val (r1, c1) = from
        val (r2, c2) = to
        if (r1 !in 0..1 || r2 !in 0..1 || c1 !in 0 until columns || c2 !in 0 until columns) {
            return this
        }
        val next = rows.map { it.toMutableList() }
        val tmp = next[r1][c1]
        next[r1][c1] = next[r2][c2]
        next[r2][c2] = tmp
        val builtRows = next.map { it.toList() }
        return copy(rows = builtRows, stashedCols = normalizeStash(builtRows, stashedCols))
    }

    private fun fallbackNonModId(grid: List<List<ExtraKeySlot>>): String {
        val used = grid.flatten().mapNotNull { (it.primary as? ExtraKeySpec.Builtin)?.id }.toSet()
        return ExtraKeyLayout.allNonModifierIds.firstOrNull { it !in used } ?: "esc"
    }

    companion object {
        private val ROW0_PAD_CANDIDATES = listOf("end", "pgup", "home", "del", "ins", "esc", "f1", "f2", "f3", "f4")
        private val ROW1_PAD_CANDIDATES = listOf("cc", "pgdn", "btab", "bs", "enter", "tab", "cd", "cz", "cb", "cr")
        val EMPTY_STASH: List<List<StashedExtraKeySlot>> = listOf(emptyList(), emptyList())

        val DEFAULT_ROWS: List<List<ExtraKeySlot>> = listOf(
            listOf("esc", "ctrl", "alt", "up", "home", "end", ACTION_PANEL).map { ExtraKeySlot(ExtraKeySpec.Builtin(it)) },
            listOf("tab", "btab", "left", "down", "right", "cc", ACTION_COMPOSER).map { ExtraKeySlot(ExtraKeySpec.Builtin(it)) },
        )

        val DEFAULT = ExtraKeyConfig()

        private fun hasAnchoredTailAction(row: List<ExtraKeySlot>): Boolean {
            val tailPrimary = row.lastOrNull()?.primary as? ExtraKeySpec.Builtin ?: return false
            return ExtraKeyLayout.isActionId(tailPrimary.id)
        }

        /** 若暂存区与空暂存时逐列自动补齐生成的槽位及插入位置完全一致，省去冗余暂存以保持纯净默认态。 */
        private fun normalizeStash(
            activeRows: List<List<ExtraKeySlot>>,
            stash: List<List<StashedExtraKeySlot>>,
        ): List<List<StashedExtraKeySlot>> {
            if (stash.all { it.isEmpty() }) return EMPTY_STASH
            val usedIds = activeRows.flatten()
                .mapNotNull { (it.primary as? ExtraKeySpec.Builtin)?.id }
                .toMutableSet()
            val synthetic = (0..1).map { rowIndex ->
                val row = activeRows[rowIndex]
                val expectedAtEnd = !hasAnchoredTailAction(row)
                val count = stash.getOrElse(rowIndex) { emptyList() }.size
                val candidates = if (rowIndex == 0) ROW0_PAD_CANDIDATES else ROW1_PAD_CANDIDATES
                (0 until count).map {
                    val pick = candidates.firstOrNull { it !in usedIds }
                        ?: ExtraKeyLayout.allNonModifierIds.first { it !in usedIds }
                    usedIds += pick
                    StashedExtraKeySlot(ExtraKeySlot(ExtraKeySpec.Builtin(pick)), atEnd = expectedAtEnd)
                }
            }
            return if (stash == synthetic) EMPTY_STASH else stash.map { it.toList() }
        }
    }
}

/**
 * 底部两排快捷键布局与编解码。
 *
 * 全部列位均可自定义（默认行尾为「更多」与「文本」）。
 * 同时兼容旧版 `id1,id2,...|id7,...` 紧凑串、JSON v1（隐式行尾）与 JSON v2（全列存储），任何损坏或超限配置安全回退默认。
 */
object ExtraKeyLayout {
    const val SLOTS = 6
    const val MIN_COLUMNS = 6
    const val DEFAULT_COLUMNS = 7
    const val MAX_COLUMNS = 8
    const val MAX_CUSTOM_LABEL_CHARS = 4
    const val MAX_STORED_BYTES = 65_536
    private const val JSON_VERSION_V1 = 1
    private const val JSON_VERSION = 2

    const val DEFAULT =
        "esc,ctrl,alt,up,home,end|tab,btab,left,down,right,cc"

    /** 面板与输入辅助动作 id（文案与默认图标由 UI 层本地化渲染）。 */
    val actionIds: List<String> = listOf(ACTION_PANEL, ACTION_COMPOSER)

    /** 选择列表的终端按键分组：标题资源 + 这一组的 id。 */
    val groups: List<Pair<Int, List<String>>> = listOf(
        R.string.keys_section_edit to listOf(
            "ctrl", "alt", "shift", "esc", "tab", "btab",
            "up", "down", "left", "right", "home", "end",
            "pgup", "pgdn", "ins", "del", "bs", "enter",
        ),
        R.string.keys_section_fn to (1..12).map { "f$it" },
        R.string.keys_section_ctrl to listOf(
            "ca", "ce", "cu", "ck", "cw", "cy", "cl",
            "cd", "cz", "cr", "cp", "cn", "cb", "cc", "altenter",
        ),
    )

    internal val allNonModifierIds: List<String> by lazy {
        groups.flatMap { it.second }.filterNot(::isModifierId)
    }

    fun isModifierId(id: String): Boolean = id == "ctrl" || id == "alt" || id == "shift"

    fun isActionId(id: String): Boolean = id == ACTION_PANEL || id == ACTION_COMPOSER

    fun rows(stored: String?): List<List<ExtraKey>> = rows(parseConfig(stored))

    fun rows(config: ExtraKeyConfig): List<List<ExtraKey>> = listOf(
        config.rows[0].map { it.toExtraKey(config.compactSymbols) },
        config.rows[1].map { it.toExtraKey(config.compactSymbols) },
    )

    /** 两排主槽位 id（兼容旧接口；JSON 配置中自定义组合槽位记为 `"custom"`）。非法存储整表退回 [DEFAULT]。 */
    fun slots(stored: String?): List<List<String>> {
        if (stored != null && exceedsMaxUtf8Bytes(stored)) {
            return parseLegacySlotsOrNull(DEFAULT)!!
        }
        if (stored != null && stored.trimStart().startsWith("{")) {
            val config = parseJsonConfigOrNull(stored) ?: return parseLegacySlotsOrNull(DEFAULT)!!
            return config.rows.map { row ->
                row.map { slot ->
                    when (val p = slot.primary) {
                        is ExtraKeySpec.Builtin -> p.id
                        is ExtraKeySpec.CustomCombo -> "custom"
                    }
                }
            }
        }
        return parseLegacySlotsOrNull(stored) ?: parseLegacySlotsOrNull(DEFAULT)!!
    }

    /** 解析完整配置（支持空串、旧版竖线串、JSON v1 与 JSON v2；任何损坏或超体积输入安全退回 [ExtraKeyConfig.DEFAULT]）。 */
    fun parseConfig(stored: String?): ExtraKeyConfig {
        if (stored.isNullOrBlank() || exceedsMaxUtf8Bytes(stored)) return ExtraKeyConfig.DEFAULT
        if (stored.trimStart().startsWith("{")) {
            return parseJsonConfigOrNull(stored) ?: ExtraKeyConfig.DEFAULT
        }
        val legacy = parseLegacySlotsOrNull(stored) ?: return ExtraKeyConfig.DEFAULT
        return ExtraKeyConfig(
            columns = DEFAULT_COLUMNS,
            height = ExtraKeyHeight.STANDARD,
            compactSymbols = false,
            rows = listOf(
                legacy[0].map { ExtraKeySlot(ExtraKeySpec.Builtin(it)) } + ExtraKeySlot(ExtraKeySpec.Builtin(ACTION_PANEL)),
                legacy[1].map { ExtraKeySlot(ExtraKeySpec.Builtin(it)) } + ExtraKeySlot(ExtraKeySpec.Builtin(ACTION_COMPOSER)),
            ),
        )
    }

    /** 编码完整配置；完全等于默认配置时存为空串 `""`，其余存为紧凑 JSON v2。 */
    fun encodeConfig(config: ExtraKeyConfig): String {
        if (config.isDefault) return ""
        return JSONObject().apply {
            put("v", JSON_VERSION)
            put("cols", config.columns)
            put("height", config.height.name)
            put("compact", config.compactSymbols)
            put("rows", JSONArray().apply {
                config.rows.forEach { row ->
                    put(JSONArray().apply {
                        row.forEach { slot -> put(encodeSlot(slot)) }
                    })
                }
            })
            if (config.stashedCols.any { it.isNotEmpty() }) {
                put("stash", JSONArray().apply {
                    for (r in 0..1) {
                        put(JSONArray().apply {
                            config.stashedCols.getOrElse(r) { emptyList() }.forEach { entry ->
                                val obj = encodeSlot(entry.slot)
                                if (entry.atEnd) obj.put("end", true)
                                put(obj)
                            }
                        })
                    }
                })
            }
        }.toString()
    }

    /**
     * 默认底部键里当前未作为主槽位内置键出现的 id（保序）。
     * 它们不在「更多」面板固定分组里，换掉或缩减列数移出后由面板顶部自动补回。
     */
    fun displacedDefaults(stored: String?): List<String> = displacedDefaults(parseConfig(stored))

    fun displacedDefaults(config: ExtraKeyConfig): List<String> {
        val currentPrimaryBuiltins = config.rows.flatten()
            .mapNotNull { (it.primary as? ExtraKeySpec.Builtin)?.id }
            .toSet()
        return DEFAULT_SLOT_IDS.filter { it !in currentPrimaryBuiltins }
    }

    fun encode(slots: List<List<String>>): String =
        slots.joinToString("|") { it.joinToString(",") }

    fun key(id: String): ExtraKey = byId.getValue(id)

    fun key(id: String, compactSymbols: Boolean): ExtraKey {
        val base = byId.getValue(id)
        if (!compactSymbols) return base
        val compact = compactLabels[id] ?: return base
        return when (base) {
            is ExtraKey.Key -> base.copy(label = compact)
            is ExtraKey.Mod -> base.copy(label = compact)
            is ExtraKey.Action -> base
        }
    }

    fun compactExtraKey(extraKey: ExtraKey, compactSymbols: Boolean): ExtraKey {
        if (!compactSymbols || extraKey !is ExtraKey.Key || extraKey.icon != null) return extraKey
        val compact = compactLabelsByStandardLabel[extraKey.label] ?: return extraKey
        return extraKey.copy(label = compact)
    }

    fun label(id: String): String = key(id).label

    fun label(id: String, compactSymbols: Boolean): String =
        if (compactSymbols) compactLabels[id] ?: key(id).label else key(id).label

    internal fun microLabel(id: String): String =
        microLabels[id] ?: takeVisualUnits(key(id).label, 3)

    /** 仅剔除控制字符与替换符（保留空白与长度，供输入法组词过程安全过滤）。 */
    internal fun stripControlChars(raw: String): String = buildString(raw.length) {
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            i += Character.charCount(cp)
            if (!Character.isISOControl(cp) && cp != 0xFFFD) {
                appendCodePoint(cp)
            }
        }
    }

    /** 过滤控制字符、去首尾空白并截断到最多 [MAX_CUSTOM_LABEL_CHARS] 个 Unicode 码点。 */
    fun sanitizeCustomLabel(raw: String): String =
        takeCodePoints(stripControlChars(raw).trim(), MAX_CUSTOM_LABEL_CHARS)

    /** 计算键帽标签的窄字符视觉宽度（中日韩/全角记 2，ASCII 与窄符号记 1）。 */
    fun visualUnits(s: String): Int {
        var units = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            units += if (isWideCodePoint(cp)) 2 else 1
        }
        return units
    }

    /** 按最大窄字符视觉宽度截断字符串，避免全角中文撑破紧凑键帽。 */
    fun takeVisualUnits(s: String, maxUnits: Int): String {
        if (s.isEmpty() || maxUnits <= 0) return ""
        var units = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val w = if (isWideCodePoint(cp)) 2 else 1
            if (units + w > maxUnits) break
            units += w
            i += Character.charCount(cp)
        }
        return s.substring(0, i)
    }

    internal fun takeCodePoints(s: String, maxCodePoints: Int): String {
        if (s.isEmpty() || maxCodePoints <= 0) return ""
        val total = s.codePointCount(0, s.length)
        if (total <= maxCodePoints) return s
        val endIndex = s.offsetByCodePoints(0, maxCodePoints)
        return s.substring(0, endIndex)
    }

    private fun isWideCodePoint(cp: Int): Boolean =
        cp in 0x1100..0x115F ||
            cp in 0x2E80..0x303E ||
            cp in 0x3040..0x33FF ||
            cp in 0x3400..0x4DBF ||
            cp in 0x4E00..0x9FFF ||
            cp in 0xA960..0xA97F ||
            cp in 0xAC00..0xD7FF ||
            cp in 0xF900..0xFAFF ||
            cp in 0xFE10..0xFE6F ||
            cp in 0xFF01..0xFF60 ||
            cp in 0xFFE0..0xFFE6 ||
            cp in 0x1F000..0x1FAFF ||
            cp in 0x20000..0x3FFFF

    private fun exceedsMaxUtf8Bytes(s: String): Boolean {
        if (s.length > MAX_STORED_BYTES) return true
        if (s.length * 3 <= MAX_STORED_BYTES) return false
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            bytes += when {
                cp <= 0x7F -> 1
                cp <= 0x7FF -> 2
                cp <= 0xFFFF -> 3
                else -> 4
            }
            if (bytes > MAX_STORED_BYTES) return true
        }
        return false
    }

    private fun parseLegacySlotsOrNull(stored: String?): List<List<String>>? {
        if (stored != null && exceedsMaxUtf8Bytes(stored)) return null
        val raw = stored?.takeIf { it.isNotBlank() } ?: DEFAULT
        val rows = raw.split('|')
        if (rows.size != 2) return null
        val seenMods = mutableSetOf<String>()
        return rows.map { row ->
            val ids = row.split(',')
            if (ids.size != SLOTS) return null
            for (id in ids) {
                if (id !in byId || isActionId(id)) return null
                if (isModifierId(id) && !seenMods.add(id)) return null
            }
            ids
        }
    }

    private fun parseSlotOrNull(slotObj: JSONObject): ExtraKeySlot? {
        val pObj = slotObj.optJSONObject("p") ?: return null
        val primary = parseSpecOrNull(pObj, allowMod = true) ?: return null
        val secondary = if (slotObj.has("s") && !slotObj.isNull("s")) {
            val sObj = slotObj.optJSONObject("s") ?: return null
            parseSpecOrNull(sObj, allowMod = false) ?: return null
        } else {
            null
        }
        return ExtraKeySlot(primary = primary, secondary = secondary)
    }

    private fun parseJsonConfigOrNull(stored: String): ExtraKeyConfig? = runCatching {
        if (exceedsMaxUtf8Bytes(stored)) return null
        val o = JSONObject(stored)
        val version = o.optInt("v", -1)
        if (version != JSON_VERSION_V1 && version != JSON_VERSION) return null
        val cols = o.optInt("cols", DEFAULT_COLUMNS)
        if (cols !in MIN_COLUMNS..MAX_COLUMNS) return null
        val storedRowLen = if (version == JSON_VERSION_V1) cols - 1 else cols
        val maxStashPerRow = MAX_COLUMNS - cols
        val heightName = o.optString("height", ExtraKeyHeight.STANDARD.name)
        val height = ExtraKeyHeight.entries.firstOrNull { it.name == heightName } ?: return null
        if (o.has("compact") && o.opt("compact") !is Boolean) return null
        val compact = o.optBoolean("compact", false)
        val rowsArr = o.optJSONArray("rows") ?: return null
        if (rowsArr.length() != 2) return null

        val seenModifiers = mutableSetOf<String>()
        val parsedRows = (0..1).map { r ->
            val rowArr = rowsArr.optJSONArray(r) ?: return null
            if (rowArr.length() != storedRowLen) return null
            val rowSlots = (0 until storedRowLen).map { c ->
                val slotObj = rowArr.optJSONObject(c) ?: return null
                val slot = parseSlotOrNull(slotObj) ?: return null
                val primary = slot.primary
                if (primary is ExtraKeySpec.Builtin && isModifierId(primary.id)) {
                    if (!seenModifiers.add(primary.id)) return null
                }
                slot
            }
            if (version == JSON_VERSION_V1) {
                val tailId = if (r == 0) ACTION_PANEL else ACTION_COMPOSER
                rowSlots + ExtraKeySlot(ExtraKeySpec.Builtin(tailId))
            } else {
                rowSlots
            }
        }

        val parsedStash = if (o.has("stash") && !o.isNull("stash")) {
            val stashArr = o.optJSONArray("stash") ?: return null
            if (stashArr.length() != 2) return null
            (0..1).map { r ->
                val rowStashArr = stashArr.optJSONArray(r) ?: return null
                if (rowStashArr.length() > maxStashPerRow) return null
                (0 until rowStashArr.length()).map { i ->
                    val slotObj = rowStashArr.optJSONObject(i) ?: return null
                    if (slotObj.has("end") && slotObj.opt("end") !is Boolean) return null
                    val slot = parseSlotOrNull(slotObj) ?: return null
                    StashedExtraKeySlot(
                        slot = slot,
                        atEnd = version != JSON_VERSION_V1 && slotObj.optBoolean("end", false),
                    )
                }
            }
        } else {
            ExtraKeyConfig.EMPTY_STASH
        }

        ExtraKeyConfig(
            columns = cols,
            height = height,
            compactSymbols = compact,
            rows = parsedRows,
            stashedCols = parsedStash,
        )
    }.getOrNull()

    private fun encodeSlot(slot: ExtraKeySlot): JSONObject = JSONObject().apply {
        put("p", encodeSpec(slot.primary))
        slot.secondary?.let { put("s", encodeSpec(it)) }
    }

    private fun encodeSpec(spec: ExtraKeySpec): JSONObject = JSONObject().apply {
        when (spec) {
            is ExtraKeySpec.Builtin -> {
                put("t", "id")
                put("id", spec.id)
            }
            is ExtraKeySpec.CustomCombo -> {
                put("t", "combo")
                put("steps", JSONArray().apply {
                    spec.steps.forEach { step ->
                        put(JSONObject().apply {
                            put("k", step.target.id)
                            if (step.ctrl) put("c", true)
                            if (step.alt) put("a", true)
                            if (step.shift) put("s", true)
                        })
                    }
                })
            }
        }
        val cleanLabel = sanitizeCustomLabel(spec.customLabel)
        if (cleanLabel.isNotEmpty()) put("label", cleanLabel)
        spec.customIcon?.let { put("icon", it.id) }
    }

    private fun parseSpecOrNull(o: JSONObject, allowMod: Boolean): ExtraKeySpec? {
        val customLabel = if (o.has("label") && !o.isNull("label")) {
            val raw = o.opt("label") as? String ?: return null
            sanitizeCustomLabel(raw)
        } else {
            ""
        }
        val customIcon = if (o.has("icon") && !o.isNull("icon")) {
            val iconId = o.opt("icon") as? String ?: return null
            if (iconId.isEmpty()) null else (ExtraKeyIcon.byId(iconId) ?: return null)
        } else {
            null
        }
        return when (o.optString("t", "")) {
            "id" -> {
                val id = o.optString("id", "")
                if (id !in byId) return null
                if (!allowMod && isModifierId(id)) return null
                ExtraKeySpec.Builtin(id = id, customLabel = customLabel, customIcon = customIcon)
            }
            "combo" -> {
                val stepsArr = o.optJSONArray("steps") ?: return null
                if (stepsArr.length() !in 1..2) return null
                val steps = (0 until stepsArr.length()).map { i ->
                    val stepObj = stepsArr.optJSONObject(i) ?: return null
                    val target = ComboTarget.byId(stepObj.optString("k", "")) ?: return null
                    if (stepObj.has("c") && stepObj.opt("c") !is Boolean) return null
                    if (stepObj.has("a") && stepObj.opt("a") !is Boolean) return null
                    if (stepObj.has("s") && stepObj.opt("s") !is Boolean) return null
                    ComboStepSpec(
                        target = target,
                        ctrl = stepObj.optBoolean("c", false),
                        alt = stepObj.optBoolean("a", false),
                        shift = stepObj.optBoolean("s", false),
                    )
                }
                ExtraKeySpec.CustomCombo(steps = steps, customLabel = customLabel, customIcon = customIcon)
            }
            else -> null
        }
    }

    private fun ctrlOf(c: Char) = (c.uppercaseChar().code - 64).toChar().toString()

    private fun macro(label: String, bytes: String) = ExtraKey.Key(label, KeyId.Macro(bytes))

    private val DEFAULT_SLOT_IDS: List<String> = listOf(
        "esc", "ctrl", "alt", "up", "home", "end",
        "tab", "btab", "left", "down", "right", "cc",
        ACTION_COMPOSER,
    )

    /** 精简符号映射：全部字符均经 `jetbrains_mono.ttf` 实轮廓核验，零缺字风险。 */
    private val compactLabels: Map<String, String> = mapOf(
        "esc" to "⎋",
        "tab" to "⇥",
        "btab" to "⇧⇥",
        "home" to "↖",
        "end" to "↘",
        "pgup" to "⇞",
        "pgdn" to "⇟",
        "del" to "⌦",
        "enter" to "⏎",
        "altenter" to "M-⏎",
    )

    private val compactLabelsByStandardLabel: Map<String, String> = mapOf(
        "ESC" to "⎋",
        "TAB" to "⇥",
        "⇧TAB" to "⇧⇥",
        "HOME" to "↖",
        "END" to "↘",
        "PgUp" to "⇞",
        "PgDn" to "⇟",
        "DEL" to "⌦",
        "Enter" to "⏎",
        "ALT⏎" to "M-⏎",
    )

    private val microLabels: Map<String, String> = compactLabels + mapOf(
        "ins" to "INS",
        "bs" to "⌫",
        "up" to "↑",
        "down" to "↓",
        "left" to "←",
        "right" to "→",
        ACTION_PANEL to "↑",
        ACTION_COMPOSER to "TXT",
    )

    private val byId: Map<String, ExtraKey> = buildMap {
        fun k(id: String, label: String, key: KeyId) { put(id, ExtraKey.Key(label, key)) }
        k("esc", "ESC", KeyId.Esc)
        k("tab", "TAB", KeyId.Tab)
        k("btab", "⇧TAB", KeyId.Macro("\u001b[Z"))
        k("up", "↑", KeyId.Up)
        k("down", "↓", KeyId.Down)
        k("left", "←", KeyId.Left)
        k("right", "→", KeyId.Right)
        k("home", "HOME", KeyId.Home)
        k("end", "END", KeyId.End)
        k("pgup", "PgUp", KeyId.PageUp)
        k("pgdn", "PgDn", KeyId.PageDown)
        k("ins", "INS", KeyId.Insert)
        k("del", "DEL", KeyId.Delete)
        k("bs", "⌫", KeyId.Backspace)
        k("enter", "Enter", KeyId.Enter)
        put("ctrl", ExtraKey.Mod("CTRL", ModKind.Ctrl))
        put("alt", ExtraKey.Mod("ALT", ModKind.Alt))
        put("shift", ExtraKey.Mod("SHIFT", ModKind.Shift))
        for (n in 1..12) k("f$n", "F$n", KeyId.Fn(n))
        listOf('a', 'e', 'u', 'k', 'w', 'y', 'l', 'd', 'z', 'r', 'p', 'n', 'b', 'c').forEach { c ->
            k("c$c", "^${c.uppercaseChar()}", KeyId.Macro(ctrlOf(c)))
        }
        put("altenter", macro("ALT⏎", "\u001b\r"))
        put(ACTION_PANEL, ExtraKey.Action(ACTION_PANEL))
        put(ACTION_COMPOSER, ExtraKey.Action(ACTION_COMPOSER))
    }
}
