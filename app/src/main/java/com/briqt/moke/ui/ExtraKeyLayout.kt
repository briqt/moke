package com.briqt.moke.ui

import com.briqt.moke.terminal.KeyId
import com.briqt.moke.terminal.ModKind

/**
 * 底部两排快捷键里可以换的那 6+6 格。
 *
 * 每排最后一个位置固定：「更多」打开全键盘，「文本」打开文本段。存下来的是稳定 id，
 * 不认识或格数不对就整表退回默认，避免半截配置把入口弄丢。
 */
object ExtraKeyLayout {
    const val SLOTS = 6

    const val DEFAULT =
        "esc,ctrl,alt,up,home,end|tab,btab,left,down,right,cc"

    /** 选择列表的分组：标题资源 + 这一组的 id。 */
    val groups: List<Pair<Int, List<String>>> = listOf(
        com.briqt.moke.R.string.keys_section_edit to listOf(
            "ctrl", "alt", "shift", "esc", "tab", "btab",
            "up", "down", "left", "right", "home", "end",
            "pgup", "pgdn", "ins", "del", "bs", "enter",
        ),
        com.briqt.moke.R.string.keys_section_fn to (1..12).map { "f$it" },
        com.briqt.moke.R.string.keys_section_ctrl to listOf(
            "ca", "ce", "cu", "ck", "cw", "cy", "cl",
            "cd", "cz", "cr", "cp", "cn", "cb", "cc", "altenter",
        ),
    )

    fun rows(stored: String?): List<List<ExtraKey>> {
        val slots = slots(stored)
        return listOf(
            slots[0].map { key(it) } + ExtraKey.Action(ACTION_PANEL),
            slots[1].map { key(it) } + ExtraKey.Action(ACTION_COMPOSER),
        )
    }

    /** 两排各 [SLOTS] 个 id。非法存储整表退回 [DEFAULT]。 */
    fun slots(stored: String?): List<List<String>> {
        val raw = stored?.takeIf { it.isNotBlank() } ?: DEFAULT
        val rows = raw.split('|')
        if (rows.size != 2) return slots(DEFAULT)
        val parsed = rows.map { row ->
            val ids = row.split(',')
            if (ids.size != SLOTS || ids.any { it !in byId }) return slots(DEFAULT)
            ids
        }
        return parsed
    }

    fun encode(slots: List<List<String>>): String =
        slots.joinToString("|") { it.joinToString(",") }

    fun key(id: String): ExtraKey = byId.getValue(id)

    fun label(id: String): String = key(id).label

    private fun ctrlOf(c: Char) = (c.uppercaseChar().code - 64).toChar().toString()

    private fun macro(label: String, bytes: String) = ExtraKey.Key(label, KeyId.Macro(bytes))

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
        put("altenter", macro("ALT↵", "\u001b\r"))
    }
}
