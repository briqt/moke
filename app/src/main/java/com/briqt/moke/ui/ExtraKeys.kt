package com.briqt.moke.ui

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.KeyboardTab
import androidx.compose.material.icons.automirrored.filled.LastPage
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HorizontalSplit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.filled.Window
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.briqt.moke.R
import com.briqt.moke.terminal.KeyId
import com.briqt.moke.terminal.KeySeq
import com.briqt.moke.terminal.ModKind
import com.briqt.moke.terminal.ModState
import com.briqt.moke.terminal.Modifiers
import com.briqt.moke.ui.theme.MokeDimens
import com.briqt.moke.ui.theme.MokeMono
import com.briqt.moke.ui.theme.MokeShapes
import com.briqt.moke.ui.theme.extra

/** 上滑副动作：可发送按键序列或触发界面动作（更多面板 / 文本输入条）。 */
sealed interface ExtraKeySecondary {
    val label: String
    val icon: ExtraKeyIcon?
    val microHint: String

    data class SendKey(
        override val label: String,
        val key: KeyId,
        override val icon: ExtraKeyIcon? = null,
        override val microHint: String = "",
    ) : ExtraKeySecondary

    data class TriggerAction(
        val id: String,
        override val label: String = "",
        override val icon: ExtraKeyIcon? = null,
        override val microHint: String = "",
    ) : ExtraKeySecondary
}

/** 附加键：普通按键（字节由 `KeySeq` 按当前修饰统一编码）/ 修饰键 / 动作键。 */
sealed interface ExtraKey {
    val label: String

    /** 普通按键：可附带自定义图标与上滑副动作。 */
    data class Key(
        override val label: String,
        val key: KeyId,
        val icon: ExtraKeyIcon? = null,
        val secondary: ExtraKeySecondary? = null,
        val microHint: String = "",
    ) : ExtraKey

    /** 修饰键（三态：一次性 / 锁定 / 关），可附带自定义图标与上滑副动作。 */
    data class Mod(
        override val label: String,
        val kind: ModKind,
        val icon: ExtraKeyIcon? = null,
        val secondary: ExtraKeySecondary? = null,
    ) : ExtraKey

    /**
     * 动作键（[id] 交给上层处理，支持自定义标签/图标覆盖与上滑副动作）。
     *
     * 默认 [label] 为空串：未覆盖时按 [id] 取本地化资源与默认图标（见 KeyRow）。
     */
    data class Action(
        val id: String,
        override val label: String = "",
        val icon: ExtraKeyIcon? = null,
        val secondary: ExtraKeySecondary? = null,
    ) : ExtraKey
}

internal fun ExtraKey.secondaryOrNull(): ExtraKeySecondary? = when (this) {
    is ExtraKey.Key -> secondary
    is ExtraKey.Mod -> secondary
    is ExtraKey.Action -> secondary
}

fun ExtraKeyIcon.imageVector(): ImageVector = when (this) {
    ExtraKeyIcon.RETURN -> Icons.AutoMirrored.Filled.KeyboardReturn
    ExtraKeyIcon.BACKSPACE -> Icons.AutoMirrored.Filled.Backspace
    ExtraKeyIcon.TAB -> Icons.AutoMirrored.Filled.KeyboardTab
    ExtraKeyIcon.SPACE -> Icons.Filled.SpaceBar
    ExtraKeyIcon.FIRST_PAGE -> Icons.Filled.FirstPage
    ExtraKeyIcon.LAST_PAGE -> Icons.AutoMirrored.Filled.LastPage
    ExtraKeyIcon.TOP -> Icons.Filled.VerticalAlignTop
    ExtraKeyIcon.BOTTOM -> Icons.Filled.VerticalAlignBottom
    ExtraKeyIcon.SPLIT_VERTICAL -> Icons.Filled.VerticalSplit
    ExtraKeyIcon.SPLIT_HORIZONTAL -> Icons.Filled.HorizontalSplit
    ExtraKeyIcon.ADD -> Icons.Filled.Add
    ExtraKeyIcon.CLOSE -> Icons.Filled.Close
    ExtraKeyIcon.STOP -> Icons.Filled.Stop
    ExtraKeyIcon.SEARCH -> Icons.Filled.Search
    ExtraKeyIcon.HISTORY -> Icons.Filled.History
    ExtraKeyIcon.TERMINAL -> Icons.Filled.Terminal
    ExtraKeyIcon.SWAP_HORIZ -> Icons.Filled.SwapHoriz
    ExtraKeyIcon.WINDOW -> Icons.Filled.Window
}

fun ExtraKeyIcon.labelRes(): Int = when (this) {
    ExtraKeyIcon.RETURN -> R.string.extra_keys_icon_return
    ExtraKeyIcon.BACKSPACE -> R.string.extra_keys_icon_backspace
    ExtraKeyIcon.TAB -> R.string.extra_keys_icon_tab
    ExtraKeyIcon.SPACE -> R.string.extra_keys_icon_space
    ExtraKeyIcon.FIRST_PAGE -> R.string.extra_keys_icon_first_page
    ExtraKeyIcon.LAST_PAGE -> R.string.extra_keys_icon_last_page
    ExtraKeyIcon.TOP -> R.string.extra_keys_icon_top
    ExtraKeyIcon.BOTTOM -> R.string.extra_keys_icon_bottom
    ExtraKeyIcon.SPLIT_VERTICAL -> R.string.extra_keys_icon_split_v
    ExtraKeyIcon.SPLIT_HORIZONTAL -> R.string.extra_keys_icon_split_h
    ExtraKeyIcon.ADD -> R.string.extra_keys_icon_add
    ExtraKeyIcon.CLOSE -> R.string.extra_keys_icon_close
    ExtraKeyIcon.STOP -> R.string.extra_keys_icon_stop
    ExtraKeyIcon.SEARCH -> R.string.extra_keys_icon_search
    ExtraKeyIcon.HISTORY -> R.string.extra_keys_icon_history
    ExtraKeyIcon.TERMINAL -> R.string.extra_keys_icon_terminal
    ExtraKeyIcon.SWAP_HORIZ -> R.string.extra_keys_icon_swap_h
    ExtraKeyIcon.WINDOW -> R.string.extra_keys_icon_window
}

/** 动作键 id：文本段入口、展开全键盘面板。 */
const val ACTION_COMPOSER = "composer"
const val ACTION_PANEL = "panel"

/** 单字符符号字形集合（在键帽中略放大字号以保持清晰易辨）。 */
internal const val SINGLE_GLYPH_CHARS = "↑↓←→↖↘⇞⇟⇥⎋⌫⌦⏎␣"

/** 按住连发的初始延迟与连发间隔（毫秒）。 */
internal const val KEY_REPEAT_INITIAL_MS = 400L
internal const val KEY_REPEAT_INTERVAL_MS = 60L

/** 常驻双排附加键默认表：均匀铺满宽度、不横向滚动，中间三列保持倒 T 方向键。 */
val DEFAULT_EXTRA_KEYS: List<List<ExtraKey>> = ExtraKeyLayout.rows(null as String?)

/** 全键盘面板的一个分组（面板是单页竖排，分组只作视觉分区，不再有分段切换）。 */
data class KeySection(val titleRes: Int, val rows: List<List<ExtraKey>>, val slots: Int = 0)

private fun macro(label: String, bytes: String) = ExtraKey.Key(label, KeyId.Macro(bytes))

/** Ctrl+字母的字节（宏用；标签沿用终端惯例的 `^X` 写法）。 */
private fun ctrlOf(c: Char) = ((c.uppercaseChar().code - 64)).toChar().toString()

/**
 * 面板的三个分组：编辑 / 功能键 / 控制键。**一屏全在，不用切**。
 */
val KEY_SECTIONS: List<KeySection> = listOf(
    KeySection(
        R.string.keys_section_edit,
        listOf(
            listOf(
                ExtraKey.Mod("SHIFT", ModKind.Shift),
                ExtraKey.Key("INS", KeyId.Insert),
                ExtraKey.Key("DEL", KeyId.Delete),
                ExtraKey.Key("PgUp", KeyId.PageUp),
                ExtraKey.Key("PgDn", KeyId.PageDown),
                ExtraKey.Key("⌫", KeyId.Backspace),
                ExtraKey.Key("Enter", KeyId.Enter),
            ),
        ),
    ),
    KeySection(
        R.string.keys_section_fn,
        listOf(
            (1..6).map { ExtraKey.Key("F$it", KeyId.Fn(it)) },
            (7..12).map { ExtraKey.Key("F$it", KeyId.Fn(it)) },
        ),
    ),
    KeySection(
        R.string.keys_section_ctrl,
        listOf(
            listOf(
                macro("^A", ctrlOf('a')),
                macro("^E", ctrlOf('e')),
                macro("^U", ctrlOf('u')),
                macro("^K", ctrlOf('k')),
                macro("^W", ctrlOf('w')),
                macro("^Y", ctrlOf('y')),
                macro("^L", ctrlOf('l')),
            ),
            listOf(
                macro("^D", ctrlOf('d')),
                macro("^Z", ctrlOf('z')),
                macro("^R", ctrlOf('r')),
                macro("^P", ctrlOf('p')),
                macro("^N", ctrlOf('n')),
                macro("^B", ctrlOf('b')),
                // 标签不用 ⌥：等宽字体里没有该字形，真机上会渲染成豆腐块；⏎ 包含于随包字库。
                macro("ALT⏎", "\u001b\r"),
            ),
        ),
    ),
)

/** 面板里最宽的一排（编辑、控制键各 7 个）。 */
private const val PANEL_ROW_SLOTS = 7

/** 面板内容：底部被换掉的默认键放最前面，其后三组自动剔除已在底部常驻的主键与修饰键。 */
internal fun panelSections(storedLayout: String?): List<KeySection> {
    val config = ExtraKeyLayout.parseConfig(storedLayout)
    val displaced = ExtraKeyLayout.displacedDefaults(config)
    val activeBuiltins = config.rows.flatten()
        .mapNotNull { (it.primary as? ExtraKeySpec.Builtin)?.id }
        .map { ExtraKeyLayout.key(it) }
    val activeMods = activeBuiltins.filterIsInstance<ExtraKey.Mod>().map { it.kind }.toSet()
    val activeKeyIds = activeBuiltins.filterIsInstance<ExtraKey.Key>().map { it.key }.toSet()

    val baseSections = KEY_SECTIONS.mapNotNull { section ->
        val allKeys = section.rows.flatten()
        val remaining = allKeys.filter { key ->
            when (key) {
                is ExtraKey.Mod -> key.kind !in activeMods
                is ExtraKey.Key -> key.key !in activeKeyIds
                is ExtraKey.Action -> true
            }
        }
        when {
            remaining.isEmpty() -> null
            remaining.size == allKeys.size -> {
                if (config.compactSymbols) {
                    section.copy(rows = section.rows.map { row -> row.map { ExtraKeyLayout.compactExtraKey(it, true) } })
                } else {
                    section
                }
            }
            else -> {
                val mapped = remaining.map { ExtraKeyLayout.compactExtraKey(it, config.compactSymbols) }
                KeySection(section.titleRes, mapped.chunked(PANEL_ROW_SLOTS), PANEL_ROW_SLOTS)
            }
        }
    }
    if (displaced.isEmpty()) return baseSections
    val displacedKeys = displaced.map { ExtraKeyLayout.key(it, config.compactSymbols) }
    return listOf(
        KeySection(R.string.keys_section_displaced, displacedKeys.chunked(PANEL_ROW_SLOTS), PANEL_ROW_SLOTS),
    ) + baseSections
}

@Composable
fun ExtraKeys(
    rows: List<List<ExtraKey>>,
    mods: Modifiers,
    panelOpen: Boolean = false,
    keyHeight: ExtraKeyHeight = ExtraKeyHeight.STANDARD,
    onKey: (KeyId) -> Unit,
    onSecondaryKey: ((KeyId) -> Unit)? = null,
    onToggleMod: (ModKind) -> Unit,
    onHoldMod: (ModKind) -> Unit,
    onAction: (String) -> Unit,
    onSecondaryAction: ((String) -> Unit)? = null,
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        CenteredItem(
            maxWidth = MokeDimens.terminalControlsMaxWidth,
            modifier = Modifier.windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                rows.forEach { row ->
                    KeyRow(
                        row = row,
                        mods = mods,
                        panelOpen = panelOpen,
                        keyHeight = keyHeight,
                        onKey = onKey,
                        onSecondaryKey = onSecondaryKey,
                        onToggleMod = onToggleMod,
                        onHoldMod = onHoldMod,
                        onAction = onAction,
                        onSecondaryAction = onSecondaryAction,
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyRow(
    row: List<ExtraKey>,
    mods: Modifiers,
    panelOpen: Boolean,
    keyHeight: ExtraKeyHeight,
    onKey: (KeyId) -> Unit,
    onSecondaryKey: ((KeyId) -> Unit)? = null,
    onToggleMod: (ModKind) -> Unit,
    onHoldMod: (ModKind) -> Unit,
    onAction: (String) -> Unit,
    onSecondaryAction: ((String) -> Unit)? = null,
    slots: Int = 0,
) {
    val denseColumns = maxOf(row.size, slots) >= 8
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        row.forEach { key ->
            val state = if (key is ExtraKey.Mod) mods.state(key.kind) else ModState.Off
            val isComposer = key is ExtraKey.Action && key.id == ACTION_COMPOSER
            val isPanel = key is ExtraKey.Action && key.id == ACTION_PANEL
            val defaultActionLabel = when {
                isComposer -> stringResource(R.string.key_text)
                isPanel -> stringResource(R.string.key_more)
                else -> ""
            }
            val label = when (key) {
                is ExtraKey.Action -> key.label.ifEmpty { defaultActionLabel }
                else -> key.label
            }
            val primaryIcon = when (key) {
                is ExtraKey.Key -> key.icon?.imageVector()
                is ExtraKey.Mod -> key.icon?.imageVector()
                is ExtraKey.Action -> when {
                    key.icon != null -> key.icon.imageVector()
                    key.label.isNotEmpty() -> null
                    isComposer -> Icons.Filled.EditNote
                    isPanel -> if (panelOpen) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp
                    else -> null
                }
            }
            val secondary = key.secondaryOrNull()
            val secLabel: String?
            val secIcon: ImageVector?
            val secHint: String?
            val secCallback: (() -> Unit)?
            when (secondary) {
                null -> {
                    secLabel = null
                    secIcon = null
                    secHint = null
                    secCallback = null
                }
                is ExtraKeySecondary.SendKey -> {
                    secLabel = secondary.label
                    secIcon = secondary.icon?.imageVector()
                    secHint = secondary.microHint.ifEmpty { ExtraKeyLayout.takeVisualUnits(secondary.label, 3) }
                    secCallback = { (onSecondaryKey ?: onKey)(secondary.key) }
                }
                is ExtraKeySecondary.TriggerAction -> {
                    val secDefaultLabel = when (secondary.id) {
                        ACTION_COMPOSER -> stringResource(R.string.key_text)
                        ACTION_PANEL -> stringResource(R.string.key_more)
                        else -> secondary.id
                    }
                    secLabel = secondary.label.ifEmpty { secDefaultLabel }
                    secIcon = when {
                        secondary.icon != null -> secondary.icon.imageVector()
                        secondary.label.isNotEmpty() -> null
                        secondary.id == ACTION_COMPOSER -> Icons.Filled.EditNote
                        secondary.id == ACTION_PANEL -> if (panelOpen) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp
                        else -> null
                    }
                    secHint = if (secIcon != null) {
                        null
                    } else {
                        secondary.microHint.ifEmpty { ExtraKeyLayout.takeVisualUnits(secLabel, 3) }
                    }
                    secCallback = { (onSecondaryAction ?: onAction)(secondary.id) }
                }
            }
            val repeatable = key is ExtraKey.Key && KeySeq.isRepeatable(key.key)
            KeyCap(
                label = label,
                icon = primaryIcon,
                active = state.active || (isPanel && panelOpen),
                locked = state == ModState.Locked,
                keyHeight = keyHeight,
                denseColumns = denseColumns,
                isRepeatable = repeatable,
                secondaryLabel = secLabel,
                secondaryHint = secHint,
                secondaryIcon = secIcon,
                modifier = Modifier.weight(1f),
                onClick = {
                    when (key) {
                        is ExtraKey.Key -> onKey(key.key)
                        is ExtraKey.Mod -> onToggleMod(key.kind)
                        is ExtraKey.Action -> onAction(key.id)
                    }
                },
                onLongClick = if (key is ExtraKey.Mod) {
                    { onHoldMod(key.kind) }
                } else {
                    null
                },
                onSecondary = secCallback,
            )
        }
        repeat((slots - row.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
    }
}

/**
 * 展开的全键盘面板：**浮在终端之上**（调用方把它放进终端 Box），不挤压终端——
 * 挤压会改行数触发远端 SIGWINCH，全屏 TUI 会跟着重绘抖动。
 */
@Composable
fun KeyboardPanel(
    sections: List<KeySection>,
    mods: Modifiers,
    keyHeight: ExtraKeyHeight = ExtraKeyHeight.STANDARD,
    onKey: (KeyId) -> Unit,
    onToggleMod: (ModKind) -> Unit,
    onHoldMod: (ModKind) -> Unit,
    onAction: (String) -> Unit = {},
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val windowSpec = rememberWindowAdaptiveSpec()
    val maxHeight = if (windowSpec.heightDp > 0) (windowSpec.heightDp * 0.6f).dp else 400.dp
    val perRow = if (windowSpec.widthDp >= 600) 14 else 0
    val collapseLabel = stringResource(R.string.keys_panel_collapse)
    Surface(
        modifier = modifier.widthIn(max = MokeDimens.terminalControlsMaxWidth).fillMaxWidth().heightIn(max = maxHeight),
        color = MaterialTheme.colorScheme.surface,
        shape = MokeShapes.keyPanel,
        border = cardBorder(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 5.dp)
                .padding(bottom = 6.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(18.dp)
                    .clickable(onClick = onDismiss)
                    .semantics { contentDescription = collapseLabel },
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier.width(34.dp).height(4.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                ) {}
            }
            sections.forEachIndexed { index, section ->
                Text(
                    stringResource(section.titleRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 3.dp, top = if (index == 0) 0.dp else 9.dp, bottom = 4.dp),
                )
                val rows = if (perRow > 0) section.rows.flatten().chunked(perRow) else section.rows
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    rows.forEach { row ->
                        KeyRow(
                            row = row,
                            mods = mods,
                            panelOpen = false,
                            keyHeight = keyHeight,
                            onKey = onKey,
                            onToggleMod = onToggleMod,
                            onHoldMod = onHoldMod,
                            onAction = onAction,
                            slots = if (perRow > 0) 0 else section.slots,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 纯手势状态机（无 Android View / Compose 依赖，便于精确单测验证上滑滞回、连发互斥与越界取消）。
 */
internal class ExtraKeyGestureTracker(
    private val widthPx: Float,
    private val heightPx: Float,
    private val boundsSlopPx: Float,
    private val swipeTriggerPx: Float,
    private val swipeArmYMaxPx: Float,
    private val swipeArmXMarginPx: Float,
    private val hasSecondary: Boolean,
    private val hasLongPress: Boolean,
    private val isRepeatable: Boolean,
    private val longPressTimeoutMs: Long,
    private val repeatInitialMs: Long = KEY_REPEAT_INITIAL_MS,
    private val repeatIntervalMs: Long = KEY_REPEAT_INTERVAL_MS,
) {
    sealed interface Effect {
        data object None : Effect
        data object FirePrimary : Effect
        data object FireLongPress : Effect
        data object FireSecondary : Effect
        data class SetSwipeArmed(val armed: Boolean, val enteredSwipe: Boolean = false) : Effect
        data object LeftBounds : Effect
    }

    private var startX = 0f
    private var startY = 0f
    var inBounds = true
        private set
    var swipeMode = false
        private set
    var secondaryArmed = false
        private set
    var holdTriggered = false
        private set
    var nextTickAtMs: Long? = null
        private set

    fun onDown(x: Float, y: Float, nowMs: Long) {
        startX = x
        startY = y
        inBounds = true
        swipeMode = false
        secondaryArmed = false
        holdTriggered = false
        nextTickAtMs = when {
            hasLongPress -> nowMs + longPressTimeoutMs
            isRepeatable -> nowMs + repeatInitialMs
            else -> null
        }
    }

    fun onTimer(nowMs: Long): Effect {
        val due = nextTickAtMs ?: return Effect.None
        if (swipeMode || !inBounds || nowMs < due) return Effect.None
        return if (hasLongPress) {
            holdTriggered = true
            nextTickAtMs = null
            Effect.FireLongPress
        } else if (isRepeatable) {
            holdTriggered = true
            nextTickAtMs = nowMs + repeatIntervalMs
            Effect.FirePrimary
        } else {
            nextTickAtMs = null
            Effect.None
        }
    }

    fun onMove(x: Float, y: Float): Effect {
        val dy = y - startY
        if (swipeMode) {
            val yThreshold = if (secondaryArmed) swipeArmYMaxPx else -swipeTriggerPx
            val nowArmed = dy <= yThreshold &&
                x >= -swipeArmXMarginPx && x <= widthPx + swipeArmXMarginPx
            if (nowArmed != secondaryArmed) {
                secondaryArmed = nowArmed
                return Effect.SetSwipeArmed(armed = nowArmed, enteredSwipe = false)
            }
            return Effect.None
        }
        if (hasSecondary && !holdTriggered && dy <= -swipeTriggerPx &&
            x >= -swipeArmXMarginPx && x <= widthPx + swipeArmXMarginPx
        ) {
            swipeMode = true
            secondaryArmed = true
            nextTickAtMs = null
            return Effect.SetSwipeArmed(armed = true, enteredSwipe = true)
        }
        val inside = x >= -boundsSlopPx && x <= widthPx + boundsSlopPx &&
            y >= -boundsSlopPx && y <= heightPx + boundsSlopPx
        if (!inside && inBounds) {
            inBounds = false
            nextTickAtMs = null
            return Effect.LeftBounds
        }
        return Effect.None
    }

    fun onUp(): Effect = when {
        swipeMode -> {
            val wasArmed = secondaryArmed
            secondaryArmed = false
            if (wasArmed) Effect.FireSecondary else Effect.None
        }
        holdTriggered -> Effect.None
        inBounds -> Effect.FirePrimary
        else -> Effect.None
    }
}

private class AboveKeyPopupPositionProvider(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - 4).coerceAtLeast(4)
        val x = (anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2).coerceIn(4, maxX)
        val y = (anchorBounds.top - popupContentSize.height - gapPx).coerceAtLeast(4)
        return IntOffset(x, y)
    }
}

@Composable
internal fun KeyCap(
    label: String,
    active: Boolean,
    locked: Boolean = false,
    icon: ImageVector? = null,
    keyHeight: ExtraKeyHeight = ExtraKeyHeight.STANDARD,
    denseColumns: Boolean = false,
    isRepeatable: Boolean = false,
    secondaryLabel: String? = null,
    secondaryHint: String? = null,
    secondaryIcon: ImageVector? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onSecondary: (() -> Unit)? = null,
) {
    val hasSecondary = onSecondary != null && (secondaryLabel != null || secondaryIcon != null)
    val hasLongPress = onLongClick != null
    val description = when {
        locked -> stringResource(R.string.key_mod_locked, label)
        active && hasLongPress -> stringResource(R.string.key_mod_once, label)
        hasSecondary && !secondaryLabel.isNullOrEmpty() -> stringResource(R.string.key_with_secondary, label, secondaryLabel)
        else -> label
    }
    val secondaryActionLabel = if (hasSecondary) {
        if (!secondaryLabel.isNullOrEmpty()) {
            stringResource(R.string.extra_keys_preview_swipe, secondaryLabel)
        } else {
            stringResource(R.string.extra_keys_tab_secondary)
        }
    } else {
        ""
    }

    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongClick by rememberUpdatedState(onLongClick)
    val currentOnSecondary by rememberUpdatedState(onSecondary)
    val currentHasSecondary by rememberUpdatedState(hasSecondary)
    val currentHasLongPress by rememberUpdatedState(hasLongPress)
    val currentIsRepeatable by rememberUpdatedState(isRepeatable)
    val currentWindowFocused by rememberUpdatedState(LocalWindowInfo.current.isWindowFocused)

    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val clampedDensity = remember(density) {
        Density(density.density, density.fontScale.coerceAtMost(1.1f))
    }
    val interactionSource = remember { MutableInteractionSource() }
    var secondaryArmed by remember { mutableStateOf(false) }

    LaunchedEffect(currentWindowFocused) {
        if (!currentWindowFocused) {
            secondaryArmed = false
        }
    }

    DisposableEffect(Unit) {
        onDispose { secondaryArmed = false }
    }

    val popupGapPx = with(density) { 6.dp.roundToPx() }
    val popupPositionProvider = remember(popupGapPx) { AboveKeyPopupPositionProvider(popupGapPx) }

    Surface(
        modifier = modifier
            .height(keyHeight.dp.dp)
            .indication(interactionSource, ripple())
            .pointerInput(hasSecondary, hasLongPress, isRepeatable) {
                val slopPx = 12.dp.toPx()
                val swipeTriggerPx = 14.dp.toPx()
                val swipeArmYMaxPx = -8.dp.toPx()
                val swipeArmXMarginPx = 24.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val pointerId = down.id
                    val press = PressInteraction.Press(down.position)
                    interactionSource.tryEmit(press)
                    val tracker = ExtraKeyGestureTracker(
                        widthPx = size.width.toFloat(),
                        heightPx = size.height.toFloat(),
                        boundsSlopPx = slopPx,
                        swipeTriggerPx = swipeTriggerPx,
                        swipeArmYMaxPx = swipeArmYMaxPx,
                        swipeArmXMarginPx = swipeArmXMarginPx,
                        hasSecondary = currentHasSecondary,
                        hasLongPress = currentHasLongPress,
                        isRepeatable = currentIsRepeatable,
                        longPressTimeoutMs = viewConfiguration.longPressTimeoutMillis,
                    )
                    tracker.onDown(down.position.x, down.position.y, SystemClock.uptimeMillis())
                    var pressActive = true
                    fun dismissPress(released: Boolean) {
                        if (pressActive) {
                            pressActive = false
                            interactionSource.tryEmit(
                                if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press),
                            )
                        }
                    }
                    try {
                        while (true) {
                            val now = SystemClock.uptimeMillis()
                            val due = tracker.nextTickAtMs
                            if (due != null && now >= due) {
                                val pointerStillDown = currentWindowFocused &&
                                    currentEvent.changes.any { it.id == pointerId && it.pressed }
                                if (!pointerStillDown) {
                                    secondaryArmed = false
                                    dismissPress(released = false)
                                    break
                                }
                                when (tracker.onTimer(now)) {
                                    ExtraKeyGestureTracker.Effect.FireLongPress -> {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        currentOnLongClick?.invoke()
                                        continue
                                    }
                                    ExtraKeyGestureTracker.Effect.FirePrimary -> {
                                        currentOnClick()
                                        continue
                                    }
                                    else -> Unit
                                }
                            }
                            val nextDue = tracker.nextTickAtMs
                            val event = if (!tracker.swipeMode && tracker.inBounds && nextDue != null) {
                                val waitMs = (nextDue - SystemClock.uptimeMillis()).coerceAtLeast(1L)
                                withTimeoutOrNull(waitMs) { awaitPointerEvent(PointerEventPass.Main) } ?: continue
                            } else {
                                awaitPointerEvent(PointerEventPass.Main)
                            }
                            val change = event.changes.firstOrNull { it.id == pointerId }
                            if (change == null || change.isConsumed) {
                                secondaryArmed = false
                                dismissPress(released = false)
                                break
                            }
                            if (!change.pressed) {
                                val effect = tracker.onUp()
                                if (effect != ExtraKeyGestureTracker.Effect.None || tracker.holdTriggered || tracker.swipeMode) {
                                    change.consume()
                                }
                                secondaryArmed = false
                                dismissPress(released = effect == ExtraKeyGestureTracker.Effect.FirePrimary || tracker.holdTriggered)
                                when (effect) {
                                    ExtraKeyGestureTracker.Effect.FirePrimary -> currentOnClick()
                                    ExtraKeyGestureTracker.Effect.FireSecondary -> currentOnSecondary?.invoke()
                                    else -> Unit
                                }
                                break
                            }
                            val moveEffect = tracker.onMove(change.position.x, change.position.y)
                            if (tracker.swipeMode) {
                                change.consume()
                                if (moveEffect is ExtraKeyGestureTracker.Effect.SetSwipeArmed) {
                                    secondaryArmed = moveEffect.armed
                                    dismissPress(released = false)
                                    if (moveEffect.enteredSwipe || moveEffect.armed) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                }
                                continue
                            }
                            if (moveEffect == ExtraKeyGestureTracker.Effect.LeftBounds) {
                                dismissPress(released = false)
                            }
                            val finalEvent = awaitPointerEvent(PointerEventPass.Final)
                            if (finalEvent.changes.any { it.id == pointerId && it.isConsumed }) {
                                secondaryArmed = false
                                dismissPress(released = false)
                                break
                            }
                        }
                    } finally {
                        secondaryArmed = false
                        dismissPress(released = false)
                    }
                }
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = description
                onClick {
                    currentOnClick()
                    true
                }
                if (hasLongPress) {
                    onLongClick {
                        currentOnLongClick?.invoke()
                        true
                    }
                }
                if (hasSecondary) {
                    customActions = listOf(
                        CustomAccessibilityAction(secondaryActionLabel) {
                            currentOnSecondary?.invoke()
                            true
                        },
                    )
                }
            },
        shape = MokeShapes.keycap,
        color = if (locked) MaterialTheme.colorScheme.inverseSurface else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (locked) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onSurface,
        border = if (active && !locked) BorderStroke(1.5.dp, MaterialTheme.colorScheme.onSurface) else null,
    ) {
        CompositionLocalProvider(LocalDensity provides clampedDensity) {
            Box(modifier = Modifier.fillMaxSize()) {
                val compactHeight = keyHeight == ExtraKeyHeight.COMPACT
                if (icon != null) {
                    val iconModifier = if (hasSecondary) {
                        Modifier.align(Alignment.Center).padding(top = if (compactHeight) 3.dp else 2.dp)
                    } else {
                        Modifier.align(Alignment.Center)
                    }
                    val iconDp = when (keyHeight) {
                        ExtraKeyHeight.COMPACT -> if (hasSecondary) 15.dp else 17.dp
                        ExtraKeyHeight.STANDARD -> if (hasSecondary) 18.dp else 20.dp
                        ExtraKeyHeight.COMFORTABLE -> if (hasSecondary) 20.dp else 21.dp
                    }
                    Icon(icon, contentDescription = null, modifier = iconModifier.size(iconDp))
                } else {
                    val mainModifier = if (hasSecondary && compactHeight) {
                        Modifier.align(Alignment.Center).padding(top = 3.dp)
                    } else {
                        Modifier.align(Alignment.Center)
                    }
                    val isSingleGlyph = label.length == 1 && label[0] in SINGLE_GLYPH_CHARS
                    val units = ExtraKeyLayout.visualUnits(label)
                    val fontSize = when {
                        isSingleGlyph && compactHeight -> 15.sp
                        isSingleGlyph -> 17.sp
                        units >= 6 -> if (compactHeight || denseColumns) 10.sp else 11.sp
                        units >= 4 -> if (compactHeight || denseColumns) 11.sp else 12.sp
                        compactHeight -> 12.sp
                        else -> 13.sp
                    }
                    Text(
                        label,
                        fontFamily = MokeMono,
                        fontSize = fontSize,
                        lineHeight = fontSize,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false,
                        modifier = mainModifier,
                    )
                }

                if (hasSecondary) {
                    val hintColor = if (locked) {
                        MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.68f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f)
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 1.dp, end = 2.5.dp),
                    ) {
                        if (secondaryIcon != null) {
                            Icon(
                                secondaryIcon,
                                contentDescription = null,
                                tint = hintColor,
                                modifier = Modifier.size(9.dp),
                            )
                        } else if (!secondaryHint.isNullOrEmpty()) {
                            Text(
                                secondaryHint,
                                fontFamily = MokeMono,
                                fontSize = 8.sp,
                                lineHeight = 8.sp,
                                fontWeight = FontWeight.Medium,
                                color = hintColor,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }

                if (secondaryArmed && hasSecondary) {
                    Popup(
                        popupPositionProvider = popupPositionProvider,
                        properties = PopupProperties(focusable = false, clippingEnabled = false),
                    ) {
                        CompositionLocalProvider(LocalDensity provides clampedDensity) {
                            Surface(
                                color = MaterialTheme.colorScheme.inverseSurface,
                                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                                shape = MokeShapes.control,
                                shadowElevation = 4.dp,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .heightIn(min = 32.dp)
                                        .widthIn(min = 40.dp)
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (secondaryIcon != null) {
                                        Icon(secondaryIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                                    } else {
                                        Text(
                                            secondaryLabel.orEmpty(),
                                            fontFamily = MokeMono,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            softWrap = false,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 文本段输入（底部**内联**输入条）：在附加键行的位置就地展开，编辑整段文本后一次性发送——适合长命令 / 多行粘贴。
 */
@Composable
fun TextBlockComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSend: (text: String, appendEnter: Boolean) -> Unit,
    onKeyboardRequest: () -> Boolean = { true },
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val requestKeyboard by rememberUpdatedState(onKeyboardRequest)
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().topHairline()) {
        CenteredItem(
            maxWidth = MokeDimens.terminalControlsMaxWidth,
            modifier = Modifier.windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.composer_title),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { onValueChange("") },
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text(stringResource(R.string.composer_clear)) }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val scroll = rememberScrollState()
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp, max = 120.dp)
                        .focusRequester(focusRequester)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                if (!requestKeyboard()) down.consume()
                            }
                        }
                        .border(1.dp, MaterialTheme.colorScheme.outline, MokeShapes.control)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .verticalScroll(scroll),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = MokeMono,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.extra.seal),
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    FilledTonalButton(
                        onClick = { onSend(value, false) },
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) { Text(stringResource(R.string.composer_send)) }
                    Button(
                        onClick = { onSend(value, true) },
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) { Text(stringResource(R.string.composer_send_enter)) }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        if (requestKeyboard()) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
}
