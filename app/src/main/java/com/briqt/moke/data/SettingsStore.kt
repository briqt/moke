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
    private val primaryFontKey = stringPreferencesKey("primary_font_id")
    private val fallbackFontKey = stringPreferencesKey("fallback_font_id")
    private val fontSizeKeyInt = intPreferencesKey("font_size_sp")     // 旧键（Int），仅用于迁移读取
    private val fontSizeKey = floatPreferencesKey("font_size_sp_f")    // 新键（Float，支持 0.5 步进）
    private val cursorStyleKey = intPreferencesKey("cursor_style")
    private val cursorBlinkKey = booleanPreferencesKey("cursor_blink")
    private val hostSortKey = stringPreferencesKey("host_sort")
    private val lineSpacingKey = floatPreferencesKey("line_spacing_mul")
    private val letterSpacingKey = floatPreferencesKey("letter_spacing_mul")
    private val userFontsKey = stringPreferencesKey("user_fonts")
    private val extraKeysVisibleKey = booleanPreferencesKey("extra_keys_visible")

    companion object {
        // 字号（sp）：默认 11，0.5 步进；范围 8–32。
        const val DEFAULT_FONT_SIZE_SP = 11f
        const val MIN_FONT_SIZE_SP = 8f
        const val MAX_FONT_SIZE_SP = 32f
        const val FONT_SIZE_STEP = 0.5f
        // 行距/字间距：1.0=默认；以 0.1 步进微调。
        const val DEFAULT_SPACING = 1.0f
        const val MIN_SPACING = 0.8f
        const val MAX_SPACING = 1.6f
        // 默认字体（"恢复默认"目标；与 FontCatalog 保持一致）。
        const val DEFAULT_FALLBACK_FONT_ID = "noto_sans_sc"

        /** 把任意字号规整到 0.5 网格并夹到范围内（避免浮点漂移）。 */
        fun snapFontSize(v: Float): Float =
            (Math.round(v / FONT_SIZE_STEP) * FONT_SIZE_STEP).coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
    }

    val colorSchemeId: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[colorSchemeKey] ?: TerminalThemes.DEFAULT_ID
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

    /** 连接列表排序方式。 */
    val hostSort: Flow<HostSort> = context.settingsDataStore.data.map { prefs ->
        HostSort.fromName(prefs[hostSortKey])
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

    suspend fun setHostSort(sort: HostSort) {
        context.settingsDataStore.edit { it[hostSortKey] = sort.name }
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

    /** 恢复外观默认：配色 / 主字体 / 回退字体 / 字号 / 行距 / 字间距 / 光标（单次事务）。 */
    suspend fun resetAppearanceDefaults() {
        context.settingsDataStore.edit { prefs ->
            prefs[colorSchemeKey] = TerminalThemes.DEFAULT_ID
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
}
