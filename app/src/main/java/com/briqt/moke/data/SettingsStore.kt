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

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "moke_settings")

/** 应用设置持久化：配色方案 + 终端主字体/回退字体。 */
class SettingsStore(private val context: Context) {

    private val colorSchemeKey = stringPreferencesKey("color_scheme_id")
    private val primaryFontKey = stringPreferencesKey("primary_font_id")
    private val fallbackFontKey = stringPreferencesKey("fallback_font_id")
    private val fontSizeKey = intPreferencesKey("font_size_sp")
    private val cursorStyleKey = intPreferencesKey("cursor_style")
    private val cursorBlinkKey = booleanPreferencesKey("cursor_blink")
    private val hostSortKey = stringPreferencesKey("host_sort")
    private val lineSpacingKey = floatPreferencesKey("line_spacing_mul")
    private val letterSpacingKey = floatPreferencesKey("letter_spacing_mul")

    companion object {
        const val DEFAULT_FONT_SIZE_SP = 14
        const val MIN_FONT_SIZE_SP = 8
        const val MAX_FONT_SIZE_SP = 32
        // 行距/字间距：1.0=默认；以 0.1 步进微调。
        const val DEFAULT_SPACING = 1.0f
        const val MIN_SPACING = 0.8f
        const val MAX_SPACING = 1.6f
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
        prefs[fallbackFontKey] ?: "noto_sans_sc"
    }

    /** 终端字号（sp）。 */
    val fontSizeSp: Flow<Int> = context.settingsDataStore.data.map { prefs ->
        (prefs[fontSizeKey] ?: DEFAULT_FONT_SIZE_SP).coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
    }

    /** 光标样式：0=方块 1=下划线 2=竖线。 */
    val cursorStyle: Flow<Int> = context.settingsDataStore.data.map { prefs ->
        (prefs[cursorStyleKey] ?: 0).coerceIn(0, 2)
    }

    /** 光标是否闪烁。 */
    val cursorBlink: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[cursorBlinkKey] ?: true
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

    suspend fun setFontSize(sp: Int) {
        context.settingsDataStore.edit { it[fontSizeKey] = sp.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP) }
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
}
