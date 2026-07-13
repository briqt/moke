package com.briqt.moke.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.briqt.moke.MokeApplication
import com.briqt.moke.data.Host
import com.briqt.moke.terminal.MokeSessionService
import com.briqt.moke.data.HostSort
import com.briqt.moke.data.HostStore
import com.briqt.moke.data.SettingsStore
import com.briqt.moke.data.UserFont
import com.briqt.moke.terminal.FontCatalog
import com.briqt.moke.terminal.FontInstallState
import com.briqt.moke.terminal.FontRepository
import com.briqt.moke.terminal.FontSpec
import com.briqt.moke.terminal.TerminalThemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MokeViewModel(app: Application) : AndroidViewModel(app) {

    private val store = HostStore(app)
    private val settings = SettingsStore(app)
    val fonts = FontRepository(app)

    /** 多会话管理器：Application 作用域单例，跨导航/Activity 存活，配合前台服务后台保活。 */
    val sessions = (app as MokeApplication).sessions

    val hosts: StateFlow<List<Host>> = store.hosts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前配色方案 id；变化时即注入全局终端调色板（含启动首值）。 */
    val colorSchemeId: StateFlow<String> = settings.colorSchemeId
        .onEach { id -> TerminalThemes.byId(id).applyToTerminal() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, TerminalThemes.DEFAULT_ID)

    val primaryFontId: StateFlow<String> = settings.primaryFontId
        .stateIn(viewModelScope, SharingStarted.Eagerly, FontCatalog.DEFAULT_ID)

    val fallbackFontId: StateFlow<String> = settings.fallbackFontId
        .stateIn(viewModelScope, SharingStarted.Eagerly, "noto_sans_sc")

    val fontSizeSp: StateFlow<Float> = settings.fontSizeSp
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsStore.DEFAULT_FONT_SIZE_SP)

    val cursorStyle: StateFlow<Int> = settings.cursorStyle
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val cursorBlink: StateFlow<Boolean> = settings.cursorBlink
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val extraKeysVisible: StateFlow<Boolean> = settings.extraKeysVisible
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val hostSort: StateFlow<HostSort> = settings.hostSort
        .stateIn(viewModelScope, SharingStarted.Eagerly, HostSort.DEFAULT)

    val lineSpacing: StateFlow<Float> = settings.lineSpacing
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsStore.DEFAULT_SPACING)

    val letterSpacing: StateFlow<Float> = settings.letterSpacing
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsStore.DEFAULT_SPACING)

    /** 内置目录 + 用户上传字体的合并列表（供外观/字体页展示与选择）。 */
    val fontCatalog: StateFlow<List<FontSpec>> = settings.userFonts
        .map { users ->
            FontCatalog.all + users.map { uf ->
                FontSpec(
                    id = uf.id, name = uf.name, nameZh = uf.name, license = "本地",
                    cjk = false, bundled = false, url = null, archive = false,
                    entryHint = "", approxBytes = 0, userUploaded = true, note = "本地导入",
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, FontCatalog.all)

    /** 仅承载「下载中 / 失败」这类瞬时状态；已装/未装由字体文件是否存在推导。 */
    private val _downloadStates = MutableStateFlow<Map<String, FontInstallState>>(emptyMap())

    /** 每个字体的安装状态：下载中/失败取瞬时态，否则按文件是否存在给已装/未装。 */
    val fontStates: StateFlow<Map<String, FontInstallState>> =
        combine(fontCatalog, _downloadStates) { catalog, dl ->
            catalog.associate { spec ->
                spec.id to (dl[spec.id] ?: if (fonts.isInstalled(spec)) FontInstallState.Installed else FontInstallState.Absent)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** 字体导入的错误提示（一次性，供 UI 展示后清除）。 */
    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()
    fun clearImportError() { _importError.value = null }

    /** 字体导入进行中（UI 显示 loading、禁用按钮）。 */
    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    /** 导入成功提示（字体名，一次性）。 */
    private val _importSuccess = MutableStateFlow<String?>(null)
    val importSuccess: StateFlow<String?> = _importSuccess.asStateFlow()
    fun clearImportSuccess() { _importSuccess.value = null }

    fun save(host: Host) = viewModelScope.launch { store.upsert(host, hosts.value) }

    fun delete(host: Host) = viewModelScope.launch { store.delete(host, hosts.value) }

    /** 复制主机为新条目（label 加「副本」，新 id，清空最近连接时间）。 */
    fun duplicate(host: Host) = viewModelScope.launch {
        val copy = host.copy(
            id = java.util.UUID.randomUUID().toString(),
            label = (host.label.ifBlank { host.displayName }) + " 副本",
            lastConnectedAt = 0L,
        )
        store.upsert(copy, hosts.value)
    }

    fun setHostSort(sort: HostSort) = viewModelScope.launch { settings.setHostSort(sort) }

    /** 手动拖动重排：持久化新的连接顺序（切到「手动」排序时生效）。 */
    fun reorderHosts(newOrder: List<Host>) = viewModelScope.launch { store.save(newOrder) }

    /** 拖动重排会话（仅内存，无持久化）。 */
    fun reorderSessions(orderedIds: List<String>) = sessions.reorder(orderedIds)

    /** 记录最近连接时间（用于"最近连接"排序）。 */
    fun touchHost(host: Host) = viewModelScope.launch {
        store.upsert(host.copy(lastConnectedAt = System.currentTimeMillis()), hosts.value)
    }

    /** 新建会话并返回其 id（UI 据此导航到终端页），并记录最近连接。解析跳板机（避免自引用）。 */
    fun openSession(host: Host): String {
        touchHost(host)
        val jump = host.jumpHostId
            .takeIf { it.isNotBlank() && it != host.id }
            ?.let { id -> hosts.value.firstOrNull { it.id == id } }
        val id = sessions.open(host, jump).id
        // 拉起前台服务：退后台/关屏时保活会话（服务在会话归零时自行停止）。
        val ctx = getApplication<Application>()
        runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, MokeSessionService::class.java)) }
        return id
    }

    fun closeSession(id: String) = sessions.close(id)

    fun setColorScheme(id: String) = viewModelScope.launch { settings.setColorScheme(id) }

    fun setPrimaryFont(id: String) = viewModelScope.launch { settings.setPrimaryFont(id) }

    fun setFallbackFont(id: String) = viewModelScope.launch { settings.setFallbackFont(id) }

    fun setFontSize(sp: Float) = viewModelScope.launch { settings.setFontSize(sp) }

    fun setCursorStyle(style: Int) = viewModelScope.launch { settings.setCursorStyle(style) }

    fun setCursorBlink(blink: Boolean) = viewModelScope.launch { settings.setCursorBlink(blink) }

    fun setLineSpacing(v: Float) = viewModelScope.launch { settings.setLineSpacing(v) }

    fun setLetterSpacing(v: Float) = viewModelScope.launch { settings.setLetterSpacing(v) }

    fun setExtraKeysVisible(visible: Boolean) = viewModelScope.launch { settings.setExtraKeysVisible(visible) }

    /** 恢复外观默认（配色/字体/字号/行距/字距/光标）。 */
    fun resetAppearanceDefaults() = viewModelScope.launch { settings.resetAppearanceDefaults() }

    fun downloadFont(id: String) {
        val spec = FontCatalog.byId(id)
        if (spec.bundled || spec.url == null) return
        if (_downloadStates.value[id] is FontInstallState.Downloading) return
        viewModelScope.launch(Dispatchers.IO) {
            _downloadStates.update { it + (id to FontInstallState.Downloading(0f)) }
            val result = fonts.download(spec) { p -> _downloadStates.update { it + (id to FontInstallState.Downloading(p)) } }
            _downloadStates.update { m ->
                result.fold(
                    onSuccess = { m - id },   // 装好后移除瞬时态 → 由文件存在推导为「已装」
                    onFailure = { m + (id to FontInstallState.Failed(it.message ?: "下载失败")) },
                )
            }
        }
    }

    /** 从系统文件选择器导入本地 TTF/OTF 字体。 */
    fun importFont(uri: android.net.Uri) = viewModelScope.launch {
        val name = displayNameOf(uri)
        _importing.value = true
        _importError.value = null
        fonts.importFont(uri).fold(
            onSuccess = { id -> settings.addUserFont(UserFont(id, name)); _importSuccess.value = name },
            onFailure = { _importError.value = it.message ?: "导入失败" },
        )
        _importing.value = false
    }

    private fun displayNameOf(uri: android.net.Uri): String {
        val ctx = getApplication<Application>()
        val fromCursor = runCatching {
            ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        val raw = fromCursor ?: uri.lastPathSegment ?: "字体"
        return raw.substringAfterLast('/').substringBeforeLast('.').ifBlank { "字体" }
    }

    fun deleteFont(id: String) = viewModelScope.launch {
        fonts.delete(id)
        settings.removeUserFont(id)              // 若是用户字体则移除记录（对内置无副作用）
        _downloadStates.update { it - id }
        // 若被删字体正被使用，回退到默认/无
        if (primaryFontId.value == id) settings.setPrimaryFont(FontCatalog.DEFAULT_ID)
        if (fallbackFontId.value == id) settings.setFallbackFont("")
    }
}
