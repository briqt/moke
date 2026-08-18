package com.briqt.moke.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.briqt.moke.terminal.FontCatalog
import com.briqt.moke.terminal.TerminalThemes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "moke_settings")

/** 用户上传的本地字体记录（文件存于 filesDir/fonts/<id>.ttf）。 */
data class UserFont(val id: String, val name: String)

/** 应用设置持久化：配色方案 + 终端主字体/回退字体。 */
class SettingsStore(private val context: Context) {

    private val colorSchemeKey = stringPreferencesKey("color_scheme_id")
    // 配色随明暗联动：开关 + 浅色模式专用方案（深色模式沿用 colorSchemeKey，保持老数据语义不变）。
    private val lightColorSchemeKey = stringPreferencesKey("color_scheme_id_light")
    private val schemeFollowsThemeKey = booleanPreferencesKey("scheme_follows_theme")
    private val primaryFontKey = stringPreferencesKey("primary_font_id")
    private val fallbackFontKey = stringPreferencesKey("fallback_font_id")
    private val fontSizeKeyInt = intPreferencesKey("font_size_sp")     // 旧键（Int），仅用于迁移读取
    private val fontSizeKey = floatPreferencesKey("font_size_sp_f")    // 新键（Float，支持 0.5 步进）
    private val cursorStyleKey = intPreferencesKey("cursor_style")
    private val cursorBlinkKey = booleanPreferencesKey("cursor_blink")
    // 连接页：固定按项目分组（不再有维度选择），仅持久化「分组顺序」与「已折叠的分组」。
    private val hostGroupOrderKey = stringPreferencesKey("host_group_order")
    private val hostCollapsedGroupsKey = stringPreferencesKey("host_collapsed_groups")
    // 会话页：仍是分组 / 排序两个正交维度。
    private val sessionGroupByKey = stringPreferencesKey("session_group_by")
    private val sessionSortByKey = stringPreferencesKey("session_sort_by")
    private val lineSpacingKey = floatPreferencesKey("line_spacing_mul")
    private val letterSpacingKey = floatPreferencesKey("letter_spacing_mul")
    private val userFontsKey = stringPreferencesKey("user_fonts")
    private val extraKeysVisibleKey = booleanPreferencesKey("extra_keys_visible")
    // 外观（应用层，与终端配色相互独立）：明暗模式 + 是否取系统壁纸动态色（Android 12+）。
    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val dynamicColorKey = booleanPreferencesKey("dynamic_color")
    // 终端软键盘模式（厂商安全键盘/中文输入的逃生口，见 KeyboardMode）。
    private val keyboardModeKey = stringPreferencesKey("keyboard_mode")
    // 全屏程序内的滑动语义（见 ScrollMode）。
    private val scrollModeKey = stringPreferencesKey("scroll_mode")
    // 关闭会话前二次确认（误触保护）。
    private val confirmCloseKey = booleanPreferencesKey("confirm_close_session")
    // 终端页是否保持屏幕常亮（看长任务输出方便，但耗电）。
    private val keepScreenOnKey = booleanPreferencesKey("keep_screen_on")
    // 静默检查更新：上次检查时间戳 + 已知的最新版本 tag 与其发布页地址（用于跨启动保留"有更新"小圆点与跳转目标）。
    private val lastUpdateCheckKey = androidx.datastore.preferences.core.longPreferencesKey("last_update_check_at")
    private val latestSeenTagKey = stringPreferencesKey("latest_seen_tag")
    private val latestSeenUrlKey = stringPreferencesKey("latest_seen_url")
    // 检查更新时是否把预发布版（rc）算进来。
    private val includePrereleaseKey = booleanPreferencesKey("include_prerelease")
    // 下载目录（SAF 目录树 URI，已持久化读写授权）；空=还没选过，首次下载时问一次。
    private val downloadTreeKey = stringPreferencesKey("download_tree_uri")
    // 文件页：是否显示隐藏文件 + 排序方式。
    private val filesShowHiddenKey = booleanPreferencesKey("files_show_hidden")
    private val filesSortKey = stringPreferencesKey("files_sort")

    companion object {
        // 字号（sp）：默认 11，0.5 步进；范围 8–24（上限收窄，24sp 在手机上已很大，避免滑轨大半落在不可用大字号）。
        const val DEFAULT_FONT_SIZE_SP = 11.5f
        const val MIN_FONT_SIZE_SP = 8f
        const val MAX_FONT_SIZE_SP = 24f
        const val FONT_SIZE_STEP = 0.5f
        // 行距/字间距：1.0=默认；范围绕 1.0 对称（0.7–1.3），默认落在滑轨正中；以 0.1 步进微调。
        const val DEFAULT_SPACING = 1.0f
        const val MIN_SPACING = 0.7f
        const val MAX_SPACING = 1.3f
        // 默认字体（"恢复默认"目标；与 FontCatalog 保持一致）。
        // maple 发行变体自带 Maple Mono NF CN 并作为默认中文回退；standard 用内置思源黑体子集。
        val DEFAULT_FALLBACK_FONT_ID: String =
            if (com.briqt.moke.BuildConfig.BUNDLE_MAPLE) "maple_mono" else "noto_sans_sc"

        /** 把任意字号规整到 0.5 网格并夹到范围内（避免浮点漂移）。 */
        fun snapFontSize(v: Float): Float =
            (Math.round(v / FONT_SIZE_STEP) * FONT_SIZE_STEP).coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
    }

    /** 终端配色（关闭联动时的唯一选择；开启联动后作为「深色模式用」那一套）。 */
    val colorSchemeId: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[colorSchemeKey] ?: TerminalThemes.DEFAULT_ID
    }

    /** 浅色模式专用终端配色（仅在联动开启时生效）。 */
    val lightColorSchemeId: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[lightColorSchemeKey] ?: TerminalThemes.DEFAULT_LIGHT_ID
    }

    /** 终端配色是否随应用明暗自动切换（默认关，老用户行为不变）。 */
    val schemeFollowsTheme: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[schemeFollowsThemeKey] ?: false
    }

    /** 主字体 id（默认内置 JetBrains Mono）。 */
    val primaryFontId: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[primaryFontKey] ?: FontCatalog.DEFAULT_ID
    }

    /** 回退字体 id（默认内置思源黑体子集，中文开箱好看；空串 = 走系统）。 */
    val fallbackFontId: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[fallbackFontKey] ?: DEFAULT_FALLBACK_FONT_ID
    }

    /** 终端字号（sp，Float 支持 0.5 步进）。新键缺失时迁移旧 Int 键。 */
    val fontSizeSp: Flow<Float> = context.settingsDataStore.data.map { prefs ->
        val v = prefs[fontSizeKey] ?: prefs[fontSizeKeyInt]?.toFloat() ?: DEFAULT_FONT_SIZE_SP
        snapFontSize(v)
    }

    /** 光标样式：0=方块 1=下划线 2=竖线。 */
    val cursorStyle: Flow<Int> = context.settingsDataStore.data.map { prefs ->
        (prefs[cursorStyleKey] ?: 0).coerceIn(0, 2)
    }

    /** 光标是否闪烁。 */
    val cursorBlink: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[cursorBlinkKey] ?: true
    }

    /** 终端底部附加键是否显示（默认显示）。 */
    val extraKeysVisible: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[extraKeysVisibleKey] ?: true
    }

    /** 应用明暗主题（默认跟随系统）。 */
    val themeMode: Flow<ThemeMode> = context.settingsDataStore.data.map { prefs ->
        ThemeMode.fromName(prefs[themeModeKey], ThemeMode.SYSTEM)
    }

    /** 是否使用系统壁纸动态取色（Android 12+ 才生效；默认关，保留墨客品牌色）。 */
    val dynamicColor: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[dynamicColorKey] ?: false
    }

    /** 终端软键盘模式（默认字符模式，与历史行为一致）。 */
    val keyboardMode: Flow<KeyboardMode> = context.settingsDataStore.data.map { prefs ->
        KeyboardMode.fromName(prefs[keyboardModeKey], KeyboardMode.SECURE)
    }

    /** 全屏程序内滑动语义（默认智能）。 */
    val scrollMode: Flow<ScrollMode> = context.settingsDataStore.data.map { prefs ->
        ScrollMode.fromName(prefs[scrollModeKey], ScrollMode.SMART)
    }

    /** 关闭会话前是否二次确认（默认开）。 */
    val confirmCloseSession: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[confirmCloseKey] ?: true
    }

    /** 终端页保持屏幕常亮（默认开——与历史行为一致；关掉更省电）。 */
    val keepScreenOn: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[keepScreenOnKey] ?: true
    }

    /** 上次静默检查更新的时间（毫秒；0=从未）。 */
    val lastUpdateCheckAt: Flow<Long> = context.settingsDataStore.data.map { prefs ->
        prefs[lastUpdateCheckKey] ?: 0L
    }

    /** 静默检查发现的最新版本 tag（空=无新版；跨启动保留，用于小圆点）。 */
    val latestSeenTag: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[latestSeenTagKey] ?: ""
    }

    /** 与 latestSeenTag 配对的发布页地址（rc 指向 rc 页，正式版指向正式版页；空=无记录）。 */
    val latestSeenUrl: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[latestSeenUrlKey] ?: ""
    }

    /** 是否把预发布版算进"有更新"（默认关：正式用户不该被 rc 打扰）。 */
    val includePrerelease: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[includePrereleaseKey] ?: false
    }

    /** 下载目录（SAF 目录树 URI 字符串；空=未选）。 */
    val downloadTreeUri: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[downloadTreeKey] ?: ""
    }

    val filesShowHidden: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[filesShowHiddenKey] ?: false
    }

    val filesSort: Flow<FilesSort> = context.settingsDataStore.data.map { prefs ->
        runCatching { FilesSort.valueOf(prefs[filesSortKey] ?: FilesSort.NAME.name) }
            .getOrDefault(FilesSort.NAME)
    }

    suspend fun setDownloadTreeUri(uri: String) {
        context.settingsDataStore.edit { it[downloadTreeKey] = uri }
    }

    suspend fun setFilesShowHidden(on: Boolean) {
        context.settingsDataStore.edit { it[filesShowHiddenKey] = on }
    }

    suspend fun setFilesSort(s: FilesSort) {
        context.settingsDataStore.edit { it[filesSortKey] = s.name }
    }

    /** 连接页分组显示顺序（组名列表；空=按主机首次出现序）。 */
    val hostGroupOrder: Flow<List<String>> = context.settingsDataStore.data.map { prefs ->
        decodeStringList(prefs[hostGroupOrderKey])
    }
    /** 连接页已折叠的分组（组名集合）。 */
    val hostCollapsedGroups: Flow<Set<String>> = context.settingsDataStore.data.map { prefs ->
        decodeStringList(prefs[hostCollapsedGroupsKey]).toSet()
    }
    /** 会话页分组维度（默认按项目）。 */
    val sessionGroupBy: Flow<GroupBy> = context.settingsDataStore.data.map { prefs ->
        GroupBy.fromName(prefs[sessionGroupByKey], GroupBy.PROJECT)
    }
    /** 会话页排序维度（默认按创建时间）。 */
    val sessionSortBy: Flow<SortBy> = context.settingsDataStore.data.map { prefs ->
        SortBy.fromName(prefs[sessionSortByKey], SortBy.CREATED)
    }

    /** 行距倍数（1.0=字体自然行距）。 */
    val lineSpacing: Flow<Float> = context.settingsDataStore.data.map { prefs ->
        (prefs[lineSpacingKey] ?: DEFAULT_SPACING).coerceIn(MIN_SPACING, MAX_SPACING)
    }

    /** 字间距倍数（1.0=正常）。 */
    val letterSpacing: Flow<Float> = context.settingsDataStore.data.map { prefs ->
        (prefs[letterSpacingKey] ?: DEFAULT_SPACING).coerceIn(MIN_SPACING, MAX_SPACING)
    }

    suspend fun setColorScheme(id: String) {
        context.settingsDataStore.edit { it[colorSchemeKey] = id }
    }

    suspend fun setLightColorScheme(id: String) {
        context.settingsDataStore.edit { it[lightColorSchemeKey] = id }
    }

    suspend fun setSchemeFollowsTheme(on: Boolean) {
        context.settingsDataStore.edit { it[schemeFollowsThemeKey] = on }
    }

    suspend fun setPrimaryFont(id: String) {
        context.settingsDataStore.edit { it[primaryFontKey] = id }
    }

    suspend fun setFallbackFont(id: String) {
        context.settingsDataStore.edit { it[fallbackFontKey] = id }
    }

    suspend fun setFontSize(sp: Float) {
        context.settingsDataStore.edit { it[fontSizeKey] = snapFontSize(sp) }
    }

    suspend fun setCursorStyle(style: Int) {
        context.settingsDataStore.edit { it[cursorStyleKey] = style.coerceIn(0, 2) }
    }

    suspend fun setCursorBlink(blink: Boolean) {
        context.settingsDataStore.edit { it[cursorBlinkKey] = blink }
    }

    suspend fun setHostGroupOrder(order: List<String>) {
        context.settingsDataStore.edit { it[hostGroupOrderKey] = encodeStringList(order) }
    }
    suspend fun setHostCollapsedGroups(collapsed: Set<String>) {
        context.settingsDataStore.edit { it[hostCollapsedGroupsKey] = encodeStringList(collapsed.toList()) }
    }
    suspend fun setSessionGroupBy(g: GroupBy) {
        context.settingsDataStore.edit { it[sessionGroupByKey] = g.name }
    }
    suspend fun setSessionSortBy(s: SortBy) {
        context.settingsDataStore.edit { it[sessionSortByKey] = s.name }
    }

    suspend fun setLineSpacing(v: Float) {
        context.settingsDataStore.edit { it[lineSpacingKey] = v.coerceIn(MIN_SPACING, MAX_SPACING) }
    }

    suspend fun setLetterSpacing(v: Float) {
        context.settingsDataStore.edit { it[letterSpacingKey] = v.coerceIn(MIN_SPACING, MAX_SPACING) }
    }

    suspend fun setExtraKeysVisible(visible: Boolean) {
        context.settingsDataStore.edit { it[extraKeysVisibleKey] = visible }
    }

    suspend fun setThemeMode(m: ThemeMode) {
        context.settingsDataStore.edit { it[themeModeKey] = m.name }
    }

    suspend fun setDynamicColor(on: Boolean) {
        context.settingsDataStore.edit { it[dynamicColorKey] = on }
    }

    suspend fun setKeyboardMode(m: KeyboardMode) {
        context.settingsDataStore.edit { it[keyboardModeKey] = m.name }
    }

    suspend fun setScrollMode(m: ScrollMode) {
        context.settingsDataStore.edit { it[scrollModeKey] = m.name }
    }

    suspend fun setConfirmCloseSession(on: Boolean) {
        context.settingsDataStore.edit { it[confirmCloseKey] = on }
    }

    suspend fun setKeepScreenOn(on: Boolean) {
        context.settingsDataStore.edit { it[keepScreenOnKey] = on }
    }

    /** 记录一次静默检查的结果（[tag] 为空表示已是最新）。 */
    /**
     * 切换"包含预发布"时**一并清掉已记住的 tag 与检查节流**：否则关掉开关后，小圆点还会继续
     * 替一个用户已经不想要的 rc 版本亮着，而 6 小时节流内又不会重查来纠正它。
     */
    suspend fun setIncludePrerelease(enabled: Boolean) {
        context.settingsDataStore.edit {
            it[includePrereleaseKey] = enabled
            it[latestSeenTagKey] = ""
            it[latestSeenUrlKey] = ""
            it[lastUpdateCheckKey] = 0L
        }
    }

    suspend fun recordUpdateCheck(at: Long, tag: String, url: String = "") {
        context.settingsDataStore.edit {
            it[lastUpdateCheckKey] = at
            it[latestSeenTagKey] = tag
            it[latestSeenUrlKey] = url
        }
    }

    /** 恢复外观默认：配色 / 主字体 / 回退字体 / 字号 / 行距 / 字间距 / 光标（单次事务）。 */
    suspend fun resetAppearanceDefaults() {
        context.settingsDataStore.edit { prefs ->
            prefs[colorSchemeKey] = TerminalThemes.DEFAULT_ID
            prefs[lightColorSchemeKey] = TerminalThemes.DEFAULT_LIGHT_ID
            prefs[schemeFollowsThemeKey] = false
            prefs[primaryFontKey] = FontCatalog.DEFAULT_ID
            prefs[fallbackFontKey] = DEFAULT_FALLBACK_FONT_ID
            prefs[fontSizeKey] = DEFAULT_FONT_SIZE_SP
            prefs[lineSpacingKey] = DEFAULT_SPACING
            prefs[letterSpacingKey] = DEFAULT_SPACING
            prefs[cursorStyleKey] = 0
            prefs[cursorBlinkKey] = true
        }
    }

    /** 用户上传字体清单。 */
    val userFonts: Flow<List<UserFont>> = context.settingsDataStore.data.map { prefs ->
        parseUserFonts(prefs[userFontsKey])
    }

    suspend fun addUserFont(font: UserFont) {
        context.settingsDataStore.edit { prefs ->
            val list = parseUserFonts(prefs[userFontsKey]).toMutableList()
            if (list.none { it.id == font.id }) list.add(font)
            prefs[userFontsKey] = encodeUserFonts(list)
        }
    }

    suspend fun removeUserFont(id: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[userFontsKey] = encodeUserFonts(parseUserFonts(prefs[userFontsKey]).filterNot { it.id == id })
        }
    }

    private fun parseUserFonts(s: String?): List<UserFont> {
        if (s.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(s)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                UserFont(o.getString("id"), o.getString("name"))
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeUserFonts(list: List<UserFont>): String {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name)) }
        return arr.toString()
    }

    /** 字符串列表 JSON 编解码（分组顺序 / 折叠集合用）。 */
    private fun encodeStringList(list: List<String>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        return arr.toString()
    }

    private fun decodeStringList(s: String?): List<String> {
        if (s.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(s)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
    }
}
