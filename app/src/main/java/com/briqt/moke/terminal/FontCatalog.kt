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
    /** 用户从本地导入的字体（非内置目录）。 */
    val userUploaded: Boolean = false,
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
            // maple 发行变体内置(res/font/maple_mono.ttf)；standard 变体仍走下载。
            license = "OFL", cjk = true, bundled = com.briqt.moke.BuildConfig.BUNDLE_MAPLE,
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
        FontSpec(
            id = "ibm_plex_mono", name = "IBM Plex Mono", nameZh = "IBM Plex Mono",
            license = "OFL", cjk = false, bundled = false,
            url = "https://raw.githubusercontent.com/google/fonts/main/ofl/ibmplexmono/IBMPlexMono-Regular.ttf",
            archive = false, entryHint = "", approxBytes = 135_580L,
            sha256 = "6a3412f058c7d8dfd9170c41e85ade48e5156ecb89356110ca57a0a27734af46",
            note = "IBM 出品 · 沉稳清晰",
        ),
        FontSpec(
            id = "source_code_pro", name = "Source Code Pro", nameZh = "Source Code Pro",
            license = "OFL", cjk = false, bundled = false,
            url = "https://raw.githubusercontent.com/google/fonts/main/ofl/sourcecodepro/SourceCodePro%5Bwght%5D.ttf",
            archive = false, entryHint = "", approxBytes = 212_340L,
            sha256 = "b400fc584e10aff25d0e775ce181b4fc1c5ea1b5dc37b81aeb2084375b945790",
            note = "Adobe 经典编程等宽",
        ),
        FontSpec(
            id = "roboto_mono", name = "Roboto Mono", nameZh = "Roboto Mono",
            license = "Apache", cjk = false, bundled = false,
            url = "https://raw.githubusercontent.com/google/fonts/main/ofl/robotomono/RobotoMono%5Bwght%5D.ttf",
            archive = false, entryHint = "", approxBytes = 183_700L,
            sha256 = "66a80e79d17e4c7cabd162e2916578a4cc08fd19eef6e2a643305eae9c567b2b",
            note = "Roboto 家族等宽版",
        ),
        FontSpec(
            id = "ubuntu_mono", name = "Ubuntu Mono", nameZh = "Ubuntu Mono",
            license = "UFL", cjk = false, bundled = false,
            url = "https://raw.githubusercontent.com/google/fonts/main/ufl/ubuntumono/UbuntuMono-Regular.ttf",
            archive = false, entryHint = "", approxBytes = 205_748L,
            sha256 = "b35dd9d2131d5d83a9b87fe9ad22c6288fa3d17688d43302c14da29812417d63",
            note = "Ubuntu 系统等宽",
        ),
        FontSpec(
            id = "inconsolata", name = "Inconsolata", nameZh = "Inconsolata",
            license = "OFL", cjk = false, bundled = false,
            url = "https://raw.githubusercontent.com/google/fonts/main/ofl/inconsolata/Inconsolata%5Bwdth,wght%5D.ttf",
            archive = false, entryHint = "", approxBytes = 347_180L,
            sha256 = "23ded25b447074d00659392bf9b1123d89df55cb07b0ad9bfef3366d199b5fcb",
            note = "清晰细长的等宽",
        ),
        FontSpec(
            id = "space_mono", name = "Space Mono", nameZh = "Space Mono",
            license = "OFL", cjk = false, bundled = false,
            url = "https://raw.githubusercontent.com/google/fonts/main/ofl/spacemono/SpaceMono-Regular.ttf",
            archive = false, entryHint = "", approxBytes = 99_356L,
            sha256 = "95837e182baeeada83368f7748db28357f0a1b75c6b84ff7065b5edf933c8e18",
            note = "复古几何风等宽",
        ),
        FontSpec(
            id = "anonymous_pro", name = "Anonymous Pro", nameZh = "Anonymous Pro",
            license = "OFL", cjk = false, bundled = false,
            url = "https://raw.githubusercontent.com/google/fonts/main/ofl/anonymouspro/AnonymousPro-Regular.ttf",
            archive = false, entryHint = "", approxBytes = 158_100L,
            sha256 = "46d8b9a5f4b38fc9d30f3cdd676d4c6f78a9bef949bb1a8304216cc731eb87f8",
            note = "为编程设计的等宽",
        ),
        FontSpec(
            id = "cascadia_code", name = "Cascadia Code", nameZh = "Cascadia Code",
            license = "OFL", cjk = false, bundled = false,
            url = "https://raw.githubusercontent.com/google/fonts/main/ofl/cascadiacode/CascadiaCode%5Bwght%5D.ttf",
            archive = false, entryHint = "", approxBytes = 727_940L,
            sha256 = "30f2e14a5c389b346b1fce110c4ddcf0dc5ce8265faf5eca2ab7323f49dba590",
            ligature = true,
            note = "微软终端字体 · 连字",
        ),
        FontSpec(
            id = "jetbrains_mono_nerd_font", name = "JetBrainsMono Nerd Font", nameZh = "JetBrainsMono Nerd Font",
            license = "OFL", cjk = false, bundled = false,
            url = "https://raw.githubusercontent.com/ryanoasis/nerd-fonts/v3.4.0/patched-fonts/JetBrainsMono/Ligatures/Regular/JetBrainsMonoNerdFont-Regular.ttf",
            archive = false, entryHint = "", approxBytes = 2_469_104L,
            sha256 = "0ec29a68b539ece7078fc714cebff0c0accb2f4948f8f7963d9f5e86633b12d9",
            ligature = true,
            note = "JetBrains Mono + Nerd 图标",
        ),
        FontSpec(
            id = "victor_mono", name = "Victor Mono", nameZh = "Victor Mono",
            license = "OFL", cjk = false, bundled = false,
            url = "https://raw.githubusercontent.com/google/fonts/main/ofl/victormono/VictorMono%5Bwght%5D.ttf",
            archive = false, entryHint = "", approxBytes = 194_312L,
            sha256 = "6fab3abe37b456f56d180987e04a3a0c326bace2cb825bc638b6be1eb03edf8f",
            ligature = true,
            note = "连字 + 草书斜体",
        ),
        FontSpec(
            id = "dejavu_sans_mono", name = "DejaVu Sans Mono", nameZh = "DejaVu Sans Mono",
            license = "Bitstream Vera", cjk = false, bundled = false,
            url = "https://github.com/dejavu-fonts/dejavu-fonts/releases/download/version_2_37/dejavu-fonts-ttf-2.37.zip",
            archive = true, entryHint = "DejaVuSansMono.ttf", approxBytes = 5_522_795L,
            sha256 = "7576310b219e04159d35ff61dd4a4ec4cdba4f35c00e002a136f00e96a908b0a",
            note = "字形覆盖广 · 兼容性好",
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
