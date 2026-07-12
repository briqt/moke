package com.briqt.moke.terminal

/**
 * 可选终端字体目录。`jetbrains_mono` 打包进 APK（离线兜底），其余按需从各自项目的
 * GitHub Releases 下载（许可 OFL/Apache）。中文等宽字体体积大（含 CJK 字形），故不内置。
 *
 * archive=true 表示下载的是 zip，需从中提取 [entryHint] 对应的 Regular 字重 ttf。
 */
data class FontSpec(
    val id: String,
    val name: String,
    val nameZh: String,
    val license: String,
    val cjk: Boolean,
    val bundled: Boolean,
    val url: String?,
    val archive: Boolean,
    val entryHint: String,   // zip 内要提取的 ttf 名匹配（大小写不敏感、排除 Italic）
    val approxBytes: Long,   // 下载体积（UI 提示）
    val sha256: String? = null,  // 下载物（zip/ttf）的 sha256，下载后校验完整性；null 则跳过
    /** 含连字（ligature）字形。仅作展示标签——是否渲染为连字取决于渲染层，v0.1 暂按普通等宽显示。 */
    val ligature: Boolean = false,
    val note: String = "",
)

object FontCatalog {
    const val DEFAULT_ID = "jetbrains_mono"

    val all: List<FontSpec> = listOf(
        FontSpec(
            id = "jetbrains_mono",
            name = "JetBrains Mono",
            nameZh = "JetBrains Mono",
            license = "OFL", cjk = false, bundled = true,
            url = null, archive = false, entryHint = "", approxBytes = 0,
            ligature = true,
            note = "内置默认 · 精致清晰，中文走回退字体",
        ),
        FontSpec(
            id = "noto_sans_sc",
            name = "Noto Sans SC",
            nameZh = "思源黑体（Noto Sans SC）",
            license = "OFL", cjk = true, bundled = true,
            url = null, archive = false, entryHint = "", approxBytes = 0,
            note = "内置中文回退 · 思源黑体子集（常用汉字），开箱中文好看",
        ),
        FontSpec(
            id = "fira_code",
            name = "Fira Code",
            nameZh = "Fira Code",
            license = "OFL", cjk = false, bundled = false,
            url = "https://github.com/tonsky/FiraCode/releases/download/6.2/Fira_Code_v6.2.zip",
            archive = true, entryHint = "FiraCode-Regular", approxBytes = 2_462_987L,
            sha256 = "0949915ba8eb24d89fd93d10a7ff623f42830d7c5ffc3ecbf960e4ecad3e3e79",
            ligature = true,
            note = "最流行的连字编程字体 · 体积小",
        ),
        FontSpec(
            id = "maple_mono",
            name = "Maple Mono NF CN",
            nameZh = "Maple Mono · 中英等宽",
            license = "OFL", cjk = true, bundled = false,
            // 高分屏用 unhinted 变体（Normal 无花体 + NL 无连字 + NF Nerd 图标 + CN 中文 2:1）
            url = "https://github.com/subframe7536/maple-font/releases/download/v7.9/MapleMonoNormalNL-NF-CN-unhinted.zip",
            archive = true, entryHint = "Regular", approxBytes = 155_000_000L,
            sha256 = "1bd6b4be3062e6ef2b4aaa44e044d05efa4501afd3c42367842154bdb0367d0b",
            note = "圆角等宽 · 含中文(2:1)+Nerd图标 · 高分屏 unhinted · 整包大，建议 WiFi",
        ),
        FontSpec(
            id = "hack",
            name = "Hack",
            nameZh = "Hack",
            license = "MIT", cjk = false, bundled = false,
            url = "https://github.com/source-foundry/Hack/releases/download/v3.003/Hack-v3.003-ttf.zip",
            archive = true, entryHint = "Hack-Regular", approxBytes = 601_000L,
            sha256 = "0c2604631b1f055041c68a0e09ae4801acab6c5072ba2db6a822f53c3f8290ac",
            note = "经典编程等宽 · 英文，无连字，体积小",
        ),
    )

    fun byId(id: String?): FontSpec = all.firstOrNull { it.id == id } ?: all.first()
}

/** 字体安装状态（UI 用）。 */
sealed interface FontInstallState {
    data object Absent : FontInstallState
    data object Installed : FontInstallState
    data class Downloading(val progress: Float) : FontInstallState
    data class Failed(val message: String) : FontInstallState
}
