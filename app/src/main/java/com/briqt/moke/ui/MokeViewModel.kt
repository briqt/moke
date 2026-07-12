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
import com.briqt.moke.terminal.FontCatalog
import com.briqt.moke.terminal.FontInstallState
import com.briqt.moke.terminal.FontRepository
import com.briqt.moke.terminal.TerminalThemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
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

    val fontSizeSp: StateFlow<Int> = settings.fontSizeSp
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsStore.DEFAULT_FONT_SIZE_SP)

    val cursorStyle: StateFlow<Int> = settings.cursorStyle
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val cursorBlink: StateFlow<Boolean> = settings.cursorBlink
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val hostSort: StateFlow<HostSort> = settings.hostSort
        .stateIn(viewModelScope, SharingStarted.Eagerly, HostSort.DEFAULT)

    val lineSpacing: StateFlow<Float> = settings.lineSpacing
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsStore.DEFAULT_SPACING)

    val letterSpacing: StateFlow<Float> = settings.letterSpacing
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsStore.DEFAULT_SPACING)

    /** 每个字体的安装状态（下载进度/已装/失败）。 */
    private val _fontStates = MutableStateFlow(initialFontStates())
    val fontStates: StateFlow<Map<String, FontInstallState>> = _fontStates.asStateFlow()

    private fun initialFontStates(): Map<String, FontInstallState> =
        FontCatalog.all.associate { spec ->
            spec.id to if (fonts.isInstalled(spec)) FontInstallState.Installed else FontInstallState.Absent
        }

    private fun setFontState(id: String, state: FontInstallState) =
        _fontStates.update { it + (id to state) }

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

    fun setFontSize(sp: Int) = viewModelScope.launch { settings.setFontSize(sp) }

    fun setCursorStyle(style: Int) = viewModelScope.launch { settings.setCursorStyle(style) }

    fun setCursorBlink(blink: Boolean) = viewModelScope.launch { settings.setCursorBlink(blink) }

    fun setLineSpacing(v: Float) = viewModelScope.launch { settings.setLineSpacing(v) }

    fun setLetterSpacing(v: Float) = viewModelScope.launch { settings.setLetterSpacing(v) }

    fun downloadFont(id: String) {
        val spec = FontCatalog.byId(id)
        if (spec.bundled || spec.url == null) return
        if (_fontStates.value[id] is FontInstallState.Downloading) return
        viewModelScope.launch(Dispatchers.IO) {
            setFontState(id, FontInstallState.Downloading(0f))
            val result = fonts.download(spec) { p -> setFontState(id, FontInstallState.Downloading(p)) }
            setFontState(
                id,
                result.fold(
                    onSuccess = { FontInstallState.Installed },
                    onFailure = { FontInstallState.Failed(it.message ?: "下载失败") },
                ),
            )
        }
    }

    fun deleteFont(id: String) = viewModelScope.launch {
        fonts.delete(id)
        setFontState(id, FontInstallState.Absent)
        // 若被删字体正被使用，回退到默认/无
        if (primaryFontId.value == id) settings.setPrimaryFont(FontCatalog.DEFAULT_ID)
        if (fallbackFontId.value == id) settings.setFallbackFont("")
    }
}
