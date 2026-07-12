package com.briqt.moke.terminal

import com.termux.terminal.TerminalColors
import java.util.Properties

/**
 * 终端配色方案：16 个 ANSI 色 + 前景/背景 + 一个 UI accent（均为 #rrggbb）。
 * 名单精选自知名开源终端配色（多数与 Otty 一致）。注入点为 vendored 内核的静态
 * [TerminalColors.COLOR_SCHEME]，无需改渲染代码；影响随后创建/reset 的会话。
 */
data class TermColorScheme(
    val id: String,
    val name: String,
    val nameZh: String,
    val isDark: Boolean,
    val bg: String,
    val fg: String,
    val accent: String,
    val ansi: List<String>,
) {
    /** 把本方案写入全局终端调色板（cursor 由内核按背景亮度自动选取）。 */
    fun applyToTerminal() {
        val p = Properties()
        p["foreground"] = fg
        p["background"] = bg
        ansi.forEachIndexed { i, c -> p["color$i"] = c }
        TerminalColors.COLOR_SCHEME.updateWith(p)
    }
}

object TerminalThemes {
    const val DEFAULT_ID = "default"

    val all: List<TermColorScheme> = listOf(
        TermColorScheme("default", "Default (xterm)", "默认", true, "#000000", "#FFFFFF", "#7FE3C4",
            listOf("#000000", "#CD0000", "#00CD00", "#CDCD00", "#6495ED", "#CD00CD", "#00CDCD", "#E5E5E5", "#7F7F7F", "#FF0000", "#00FF00", "#FFFF00", "#5C5CFF", "#FF00FF", "#00FFFF", "#FFFFFF")),
    TermColorScheme("tokyo_night", "Tokyo Night", "东京夜", true, "#1A1B26", "#C0CAF5", "#7DCFFF",
        listOf("#15161E", "#F7768E", "#9ECE6A", "#E0AF68", "#7AA2F7", "#BB9AF7", "#7DCFFF", "#A9B1D6", "#414868", "#FF899D", "#9FE044", "#FABA4A", "#8DB0FF", "#C7A9FF", "#A4DAFF", "#C0CAF5")),
    TermColorScheme("dracula", "Dracula", "德古拉", true, "#282A36", "#F8F8F2", "#8BE9FD",
        listOf("#21222C", "#FF5555", "#50FA7B", "#F1FA8C", "#BD93F9", "#FF79C6", "#8BE9FD", "#F8F8F2", "#6272A4", "#FF6E6E", "#69FF94", "#FFFFA5", "#D6ACFF", "#FF92DF", "#A4FFFF", "#FFFFFF")),
    TermColorScheme("nord", "Nord", "北欧", true, "#2E3440", "#f1f6ff", "#88C0D0",
        listOf("#3B4252", "#BF616A", "#A3BE8C", "#EBCB8B", "#81A1C1", "#B48EAD", "#88C0D0", "#E5E9F0", "#4C566A", "#BF616A", "#A3BE8C", "#EBCB8B", "#81A1C1", "#B48EAD", "#8FBCBB", "#ECEFF4")),
    TermColorScheme("one_dark", "One Dark", "One Dark", true, "#282C34", "#ABB2BF", "#56B6C2",
        listOf("#1E2127", "#E06C75", "#98C379", "#D19A66", "#61AFEF", "#C678DD", "#56B6C2", "#ABB2BF", "#5C6370", "#E06C75", "#98C379", "#D19A66", "#61AFEF", "#C678DD", "#56B6C2", "#FFFFFF")),
    TermColorScheme("catppuccin_mocha", "Catppuccin Mocha", "卡布奇诺", true, "#1E1E2E", "#CDD6F4", "#94E2D5",
        listOf("#45475A", "#F38BA8", "#A6E3A1", "#F9E2AF", "#89B4FA", "#F5C2E7", "#94E2D5", "#BAC2DE", "#585B70", "#F38BA8", "#A6E3A1", "#F9E2AF", "#89B4FA", "#F5C2E7", "#94E2D5", "#A6ADC8")),
    TermColorScheme("gruvbox_dark", "Gruvbox Dark", "Gruvbox", true, "#282828", "#EBDBB2", "#689D6A",
        listOf("#282828", "#CC241D", "#98971A", "#D79921", "#458588", "#B16286", "#689D6A", "#A89984", "#928374", "#FB4934", "#B8BB26", "#FABD2F", "#83A598", "#D3869B", "#8EC07C", "#EBDBB2")),
    TermColorScheme("ayu_dark", "Ayu Dark", "Ayu 暗", true, "#0A0E14", "#B3B1AD", "#90E1C6",
        listOf("#01060E", "#EA6C73", "#91B362", "#F9AF4F", "#53BDFA", "#FAE994", "#90E1C6", "#C7C7C7", "#686868", "#F07178", "#C2D94C", "#FFB454", "#59C2FF", "#FFEE99", "#95E6CB", "#FFFFFF")),
    TermColorScheme("solarized_dark", "Solarized Dark", "Solarized 暗", true, "#002B36", "#839496", "#2AA198",
        listOf("#073642", "#DC322F", "#859900", "#B58900", "#268BD2", "#D33682", "#2AA198", "#EEE8D5", "#002B36", "#CB4B16", "#586E75", "#657B83", "#839496", "#6C71C4", "#93A1A1", "#FDF6E3")),
    TermColorScheme("monokai", "Monokai Classic", "Monokai", true, "#272822", "#FDFFF1", "#66D9EF",
        listOf("#272822", "#F92672", "#A6E22E", "#E6DB74", "#FD971F", "#AE81FF", "#66D9EF", "#FDFFF1", "#6E7066", "#F92672", "#A6E22E", "#E6DB74", "#FD971F", "#AE81FF", "#66D9EF", "#FDFFF1")),
    TermColorScheme("april_dark", "April Dark", "四月·暗", true, "#141B18", "#FFFFFF", "#C5E86C",
        listOf("#101513", "#E06C75", "#98C379", "#E5C07B", "#61AFEF", "#B876B8", "#5FB8A0", "#8A9992", "#5C6B64", "#EB8A91", "#C5E86C", "#F0D499", "#82C2F3", "#D098D0", "#85CDB6", "#FFFFFF")),
    TermColorScheme("rose_pine", "Rose Pine", "玫瑰松", true, "#191724", "#E0DEF4", "#EBBCBA",
        listOf("#26233A", "#EB6F92", "#31748F", "#F6C177", "#9CCFD8", "#C4A7E7", "#EBBCBA", "#E0DEF4", "#6E6A86", "#EB6F92", "#31748F", "#F6C177", "#9CCFD8", "#C4A7E7", "#EBBCBA", "#E0DEF4")),
    TermColorScheme("owl", "Owl", "夜枭", true, "#2F2B2C", "#DEDEDE", "#7F7F7F",
        listOf("#302C2C", "#5A5A5A", "#989898", "#CACACA", "#656565", "#B1B1B1", "#7F7F7F", "#DEDEDE", "#5D595B", "#DA5B2C", "#989898", "#CACACA", "#656565", "#B1B1B1", "#7F7F7F", "#FFFFFF")),
    TermColorScheme("seafoam", "Seafoam Pastel", "海沫", true, "#243435", "#D4E7D4", "#729494",
        listOf("#757575", "#825D4D", "#728C62", "#ADA16D", "#4D7B82", "#8A7267", "#729494", "#E0E0E0", "#8A8A8A", "#CF937A", "#98D9AA", "#FAE79D", "#7AC3CF", "#D6B2A1", "#ADE0E0", "#E0E0E0")),
    TermColorScheme("night", "Night", "夜", true, "#363B40", "#ffffff", "#82AAFF",
        listOf("#1F2226", "#F07178", "#C3E88D", "#FFCB6B", "#82AAFF", "#C792EA", "#89DDFF", "#BFC7D5", "#676E95", "#FF8B92", "#D2EE9F", "#FFD68A", "#9CBEFF", "#D5A8F0", "#A5E5FF", "#FFFFFF")),
    TermColorScheme("solarized_light", "Solarized Light", "Solarized 亮", false, "#FDF6E3", "#586E75", "#2AA198",
        listOf("#073642", "#DC322F", "#859900", "#B58900", "#268BD2", "#D33682", "#2AA198", "#EEE8D5", "#002B36", "#CB4B16", "#586E75", "#657B83", "#839496", "#6C71C4", "#93A1A1", "#FDF6E3")),
    TermColorScheme("ayu_light", "Ayu Light", "Ayu 亮", false, "#FCFCFC", "#5C6166", "#4196DF",
        listOf("#010101", "#E7666A", "#80AB24", "#EBA54D", "#4196DF", "#9870C3", "#51B891", "#C1C1C1", "#343434", "#EE9295", "#9FD32F", "#F0BC7B", "#6DAEE6", "#B294D2", "#75C7A8", "#DBDBDB")),
    TermColorScheme("one_light", "One Light", "One 亮", false, "#F8F8F8", "#2A2B33", "#3E953A",
        listOf("#000000", "#DE3D35", "#3E953A", "#D2B67B", "#2F5AF3", "#A00095", "#3E953A", "#BBBBBB", "#000000", "#DE3D35", "#3E953A", "#D2B67B", "#2F5AF3", "#A00095", "#3E953A", "#FFFFFF")),
    TermColorScheme("paper", "Paper", "纸张", false, "#FCFBF9", "#1A1A1A", "#2B5A38",
        listOf("#1A1A1A", "#A33A3A", "#2B5A38", "#A85A20", "#4A7A8A", "#4A3A6A", "#3A7A6A", "#C1BEB5", "#8C8A80", "#C36A6A", "#6B9A78", "#C88A50", "#7A9AAA", "#8A7A9A", "#6ABAAA", "#EBEBE6")),
    )

    fun byId(id: String?): TermColorScheme = all.firstOrNull { it.id == id } ?: all.first()
}
