package com.briqt.moke.ui

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ShortText
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import com.briqt.moke.R
import com.briqt.moke.terminal.KeyId
import com.briqt.moke.terminal.KeySeq
import com.briqt.moke.terminal.ModKind
import com.briqt.moke.terminal.ModState
import com.briqt.moke.terminal.Modifiers
import com.briqt.moke.ui.theme.MokeDimens
import com.briqt.moke.ui.theme.MokeMono
import com.briqt.moke.ui.theme.MokeShapes
import com.briqt.moke.ui.theme.mokeTextFieldColors
import kotlin.math.hypot

fun extraKeyHeightLabelRes(height: ExtraKeyHeight): Int = when (height) {
    ExtraKeyHeight.COMPACT -> R.string.extra_keys_height_compact
    ExtraKeyHeight.STANDARD -> R.string.extra_keys_height_standard
    ExtraKeyHeight.COMFORTABLE -> R.string.extra_keys_height_comfortable
}

private data class PreviewFeedback(
    val text: String,
    val baseText: String,
    val rawKey: KeyId? = null,
    val isSecondary: Boolean = false,
    val count: Int = 1,
    val atMs: Long = 0L,
)

/**
 * 「设置 → 终端与输入 → 底部快捷键」独立二级即时设置页：
 * 顶部 1:1 实时可按预览 + 双排槽位点选/长按拖拽互换 + 列数/键高/符号精简开关。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtraKeysSettingsScreen(
    stored: String,
    onChange: (String) -> Unit,
    onBack: () -> Unit,
) {
    val config = remember(stored) { ExtraKeyLayout.parseConfig(stored) }
    val currentConfig by rememberUpdatedState(config)
    val currentOnChange by rememberUpdatedState(onChange)
    var editingSlot by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    LaunchedEffect(config.editableColumns) {
        val active = editingSlot
        if (active != null && active.second >= config.editableColumns) {
            editingSlot = null
        }
    }

    fun applyConfig(next: ExtraKeyConfig) {
        currentOnChange(ExtraKeyLayout.encodeConfig(next))
    }

    if (confirmReset) {
        ConfirmDialog(
            title = stringResource(R.string.extra_keys_reset_title),
            message = stringResource(R.string.extra_keys_reset_message),
            confirmLabel = stringResource(R.string.reset_default),
            destructive = true,
            onConfirm = {
                confirmReset = false
                editingSlot = null
                currentOnChange("")
            },
            onDismiss = { confirmReset = false },
        )
    }

    val windowSpec = rememberWindowAdaptiveSpec()
    val pinPreview = windowSpec.heightDp >= 420

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.menu_extra_keys),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    TextButton(
                        onClick = { confirmReset = true },
                        enabled = !config.isDefault,
                    ) {
                        Text(stringResource(R.string.extra_keys_reset))
                    }
                },
                expandedHeight = MokeDimens.topBarHeight,
                colors = mokeTopBarColors(),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (pinPreview) {
                ExtraKeysLivePreview(config = config)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyMedium) {
                CenteredScrollColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(0.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (!pinPreview) {
                        Surface(
                            shape = MokeShapes.card,
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            ExtraKeysLivePreview(config = config)
                        }
                    }

                    ExtraKeysSectionHeader(stringResource(R.string.extra_keys_section_slots))
                    EditableSlotGrid(
                        config = config,
                        onTapSlot = { slot -> editingSlot = slot },
                        onSwapSlots = { from, to -> applyConfig(currentConfig.swapSlots(from, to)) },
                    )
                    Text(
                        stringResource(R.string.extra_keys_slots_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )

                    ExtraKeysSectionHeader(stringResource(R.string.extra_keys_section_layout))
                    val columnOptions = listOf(
                        DropdownOption("6", stringResource(R.string.extra_keys_columns_6)),
                        DropdownOption("7", stringResource(R.string.extra_keys_columns_7)),
                        DropdownOption("8", stringResource(R.string.extra_keys_columns_8)),
                    )
                    val heightOptions = ExtraKeyHeight.entries.map { h ->
                        DropdownOption(h.name, stringResource(extraKeyHeightLabelRes(h)))
                    }
                    SettingsGroup {
                        DropdownSettingRow(
                            icon = Icons.Filled.GridView,
                            title = stringResource(R.string.extra_keys_columns),
                            options = columnOptions,
                            selectedId = config.columns.toString(),
                            onSelect = { applyConfig(currentConfig.resizeColumns(it.toInt())) },
                        )
                        DropdownSettingRow(
                            icon = Icons.Filled.Height,
                            title = stringResource(R.string.extra_keys_height),
                            options = heightOptions,
                            selectedId = config.height.name,
                            onSelect = { name ->
                                ExtraKeyHeight.entries.firstOrNull { it.name == name }?.let { h ->
                                    applyConfig(currentConfig.copy(height = h))
                                }
                            },
                        )
                        SwitchRow(
                            icon = Icons.AutoMirrored.Filled.ShortText,
                            title = stringResource(R.string.extra_keys_compact_symbols),
                            checked = config.compactSymbols,
                            onCheckedChange = { applyConfig(currentConfig.copy(compactSymbols = it)) },
                        )
                    }
                }
            }
        }
    }

    val targetSlot = editingSlot
    if (targetSlot != null && targetSlot.first in 0..1 && targetSlot.second in 0 until config.editableColumns) {
        SlotEditorSheet(
            row = targetSlot.first,
            col = targetSlot.second,
            slot = config.rows[targetSlot.first][targetSlot.second],
            compactSymbols = config.compactSymbols,
            onAssignPrimary = { spec -> applyConfig(currentConfig.assignPrimary(targetSlot.first, targetSlot.second, spec)) },
            onAssignSecondary = { spec -> applyConfig(currentConfig.assignSecondary(targetSlot.first, targetSlot.second, spec)) },
            onDismiss = { editingSlot = null },
        )
    }
}

@Composable
private fun ExtraKeysSectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 4.dp),
    )
}

@Composable
private fun ExtraKeysLivePreview(config: ExtraKeyConfig) {
    val resources = LocalResources.current
    val previewRows = remember(config) { ExtraKeyLayout.rows(config) }
    var previewMods by remember { mutableStateOf(Modifiers()) }
    var previewPanelOpen by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<PreviewFeedback?>(null) }

    LaunchedEffect(previewRows) {
        val presentMods = previewRows.flatten().filterIsInstance<ExtraKey.Mod>().map { it.kind }.toSet()
        val cleaned = Modifiers(
            ctrl = if (ModKind.Ctrl in presentMods) previewMods.ctrl else ModState.Off,
            alt = if (ModKind.Alt in presentMods) previewMods.alt else ModState.Off,
            shift = if (ModKind.Shift in presentMods) previewMods.shift else ModState.Off,
        )
        if (cleaned != previewMods) previewMods = cleaned
    }

    fun recordKey(key: KeyId, secondary: Boolean) {
        val now = SystemClock.uptimeMillis()
        val bytes = previewMods.encode(key)
        previewMods = previewMods.consumeOnce()
        val seq = KeySeq.describeBytes(bytes)
        val base = if (secondary) resources.getString(R.string.extra_keys_preview_swipe, seq) else seq
        val prev = feedback
        feedback = if (
            prev != null &&
            prev.rawKey == key &&
            prev.baseText == base &&
            prev.isSecondary == secondary &&
            now - prev.atMs <= 260L
        ) {
            val nextCount = prev.count + 1
            prev.copy(
                text = "${prev.baseText} ×$nextCount",
                count = nextCount,
                atMs = now,
            )
        } else {
            PreviewFeedback(
                text = base,
                baseText = base,
                rawKey = key,
                isSecondary = secondary,
                count = 1,
                atMs = now,
            )
        }
    }

    fun recordMod(kind: ModKind, hold: Boolean) {
        previewMods = if (hold) previewMods.hold(kind) else previewMods.tap(kind)
        val label = kind.name.uppercase()
        val text = when (previewMods.state(kind)) {
            ModState.Off -> resources.getString(R.string.extra_keys_mod_off, label)
            ModState.Once -> resources.getString(R.string.key_mod_once, label)
            ModState.Locked -> resources.getString(R.string.key_mod_locked, label)
        }
        feedback = PreviewFeedback(text = text, baseText = text, atMs = SystemClock.uptimeMillis())
    }

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            CenteredItem(
                maxWidth = MokeDimens.terminalControlsMaxWidth,
                modifier = Modifier.windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(R.string.extra_keys_preview_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                    val activeFeedback = feedback
                    if (activeFeedback == null) {
                        Text(
                            stringResource(R.string.extra_keys_preview_idle),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Text(
                            activeFeedback.text,
                            fontFamily = MokeMono,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            ExtraKeys(
                rows = previewRows,
                mods = previewMods,
                panelOpen = previewPanelOpen,
                keyHeight = config.height,
                onKey = { key -> recordKey(key, secondary = false) },
                onSecondaryKey = { key -> recordKey(key, secondary = true) },
                onToggleMod = { kind -> recordMod(kind, hold = false) },
                onHoldMod = { kind -> recordMod(kind, hold = true) },
                onAction = { id ->
                    val actionText = when (id) {
                        ACTION_PANEL -> {
                            previewPanelOpen = !previewPanelOpen
                            resources.getString(R.string.key_more)
                        }
                        ACTION_COMPOSER -> resources.getString(R.string.key_text)
                        else -> id
                    }
                    feedback = PreviewFeedback(text = actionText, baseText = actionText, atMs = SystemClock.uptimeMillis())
                },
            )
        }
    }
}

@Composable
private fun EditableSlotGrid(
    config: ExtraKeyConfig,
    onTapSlot: (Pair<Int, Int>) -> Unit,
    onSwapSlots: (Pair<Int, Int>, Pair<Int, Int>) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val currentOnTap by rememberUpdatedState(onTapSlot)
    val currentOnSwap by rememberUpdatedState(onSwapSlots)

    var gridOriginInRoot by remember { mutableStateOf(Offset.Zero) }
    val cellRects = remember { mutableStateMapOf<Pair<Int, Int>, Rect>() }
    var draggingSlot by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var hoverSlot by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dragTravel by remember { mutableFloatStateOf(0f) }
    val boundsMarginPx = with(density) { 20.dp.toPx() }

    LaunchedEffect(config.editableColumns) {
        cellRects.keys.removeAll { it.second >= config.editableColumns }
        if (draggingSlot?.second?.let { it >= config.editableColumns } == true) {
            draggingSlot = null
            hoverSlot = null
            dragOffset = Offset.Zero
        }
    }

    fun nearestTargetSlot(pointInGrid: Offset): Pair<Int, Int>? {
        if (cellRects.isEmpty()) return null
        val minX = cellRects.values.minOf { it.left } - boundsMarginPx
        val maxEditableRight = cellRects.values.maxOf { it.right }
        val minY = cellRects.values.minOf { it.top } - boundsMarginPx
        val maxY = cellRects.values.maxOf { it.bottom } + boundsMarginPx
        if (pointInGrid.x < minX || pointInGrid.x > maxEditableRight ||
            pointInGrid.y < minY || pointInGrid.y > maxY
        ) {
            return null
        }
        cellRects.entries.firstOrNull { pointInGrid in it.value }?.let { return it.key }
        var best: Pair<Int, Int>? = null
        var bestDist = Float.MAX_VALUE
        for ((pos, rect) in cellRects) {
            val dx = pointInGrid.x - rect.center.x
            val dy = pointInGrid.y - rect.center.y
            val dist = hypot(dx, dy)
            val threshold = maxOf(rect.width, rect.height) * 0.85f
            if (dist <= threshold && dist < bestDist) {
                bestDist = dist
                best = pos
            }
        }
        return best
    }

    Surface(
        shape = MokeShapes.card,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
                .onGloballyPositioned { coords -> gridOriginInRoot = coords.positionInRoot() },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (row in 0..1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(if (draggingSlot?.first == row) 2f else 0f),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    for (col in 0 until config.editableColumns) {
                        val pos = row to col
                        val slot = config.rows[row][col]
                        val isDragging = draggingSlot == pos
                        val isDropTarget = draggingSlot != null && hoverSlot == pos && hoverSlot != draggingSlot
                        val primaryLabel = slot.primary.effectiveLabel(config.compactSymbols)
                        val slotDesc = stringResource(R.string.extra_key_slot, row + 1, col + 1, primaryLabel)

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .zIndex(if (isDragging) 2f else 0f)
                                .onGloballyPositioned { coords ->
                                    val topLeft = coords.positionInRoot() - gridOriginInRoot
                                    cellRects[pos] = Rect(topLeft, coords.size.toSize())
                                }
                                .semantics(mergeDescendants = true) {
                                    role = Role.Button
                                    contentDescription = slotDesc
                                }
                                .clickable(enabled = draggingSlot == null) {
                                    currentOnTap(pos)
                                }
                                .pointerInput(pos, config.editableColumns) {
                                    var ownedDrag = false
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            if (draggingSlot != null) {
                                                ownedDrag = false
                                                return@detectDragGesturesAfterLongPress
                                            }
                                            ownedDrag = true
                                            draggingSlot = pos
                                            hoverSlot = pos
                                            dragOffset = Offset.Zero
                                            dragTravel = 0f
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onDrag = { change, amount ->
                                            if (!ownedDrag || draggingSlot != pos) return@detectDragGesturesAfterLongPress
                                            change.consume()
                                            dragOffset += amount
                                            dragTravel += amount.getDistance()
                                            val startRect = cellRects[pos] ?: return@detectDragGesturesAfterLongPress
                                            val matched = nearestTargetSlot(startRect.topLeft + change.position)
                                            val prevHover = hoverSlot
                                            hoverSlot = matched
                                            if (matched != null && matched != prevHover && matched != draggingSlot) {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                        },
                                        onDragEnd = {
                                            if (!ownedDrag || draggingSlot != pos) {
                                                ownedDrag = false
                                                return@detectDragGesturesAfterLongPress
                                            }
                                            ownedDrag = false
                                            val from = draggingSlot
                                            val to = hoverSlot
                                            val moved = dragTravel >= viewConfiguration.touchSlop
                                            draggingSlot = null
                                            hoverSlot = null
                                            dragOffset = Offset.Zero
                                            if (from != null && to != null && from != to) {
                                                currentOnSwap(from, to)
                                            } else if (!moved && from != null) {
                                                currentOnTap(from)
                                            }
                                        },
                                        onDragCancel = {
                                            if (!ownedDrag || draggingSlot != pos) {
                                                ownedDrag = false
                                                return@detectDragGesturesAfterLongPress
                                            }
                                            ownedDrag = false
                                            draggingSlot = null
                                            hoverSlot = null
                                            dragOffset = Offset.Zero
                                        },
                                    )
                                },
                        ) {
                            EditableSlotKeyFace(
                                slot = slot,
                                compactSymbols = config.compactSymbols,
                                denseColumns = config.columns >= 8,
                                highlighted = isDropTarget || isDragging,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(46.dp)
                                    .graphicsLayer {
                                        if (isDragging) {
                                            translationX = dragOffset.x
                                            translationY = dragOffset.y
                                            scaleX = 1.06f
                                            scaleY = 1.06f
                                            shadowElevation = 8.dp.toPx()
                                        }
                                    },
                            )
                        }
                    }

                    // 行尾固定动作位（「更多」与「文本」只作空间对照，不可换掉）
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .alpha(0.45f),
                        shape = MokeShapes.keycap,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (row == 0) Icons.Filled.KeyboardArrowUp else Icons.Filled.EditNote,
                                contentDescription = stringResource(if (row == 0) R.string.key_more else R.string.key_text),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditableSlotKeyFace(
    slot: ExtraKeySlot,
    compactSymbols: Boolean,
    denseColumns: Boolean,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
) {
    val label = slot.primary.effectiveLabel(compactSymbols)
    val icon = slot.primary.customIcon?.imageVector()
    val secondary = slot.secondary
    val hasSecondary = secondary != null
    val secondaryIcon = secondary?.customIcon?.imageVector()
    val secondaryHint = secondary?.microHintLabel()
    val density = LocalDensity.current
    val clampedDensity = remember(density) {
        Density(density.density, density.fontScale.coerceAtMost(1.1f))
    }

    Surface(
        modifier = modifier,
        shape = MokeShapes.keycap,
        color = if (highlighted) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        border = if (highlighted) BorderStroke(1.5.dp, MaterialTheme.colorScheme.onSurface) else null,
    ) {
        CompositionLocalProvider(LocalDensity provides clampedDensity) {
            Box(Modifier.fillMaxSize()) {
                if (icon != null) {
                    val iconModifier = if (hasSecondary) {
                        Modifier.align(Alignment.Center).padding(top = 2.dp)
                    } else {
                        Modifier.align(Alignment.Center)
                    }
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = iconModifier.size(if (hasSecondary) 18.dp else 20.dp),
                    )
                } else {
                    val isSingleGlyph = label.length == 1 && label[0] in SINGLE_GLYPH_CHARS
                    val units = ExtraKeyLayout.visualUnits(label)
                    val fontSize = when {
                        isSingleGlyph -> 17.sp
                        units >= 6 -> if (denseColumns) 10.sp else 11.sp
                        units >= 4 -> if (denseColumns) 11.sp else 12.sp
                        else -> 13.sp
                    }
                    Text(
                        label,
                        fontFamily = MokeMono,
                        fontSize = fontSize,
                        lineHeight = fontSize,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                if (secondary != null) {
                    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 2.dp, end = 3.dp),
                    ) {
                        if (secondaryIcon != null) {
                            Icon(secondaryIcon, contentDescription = null, tint = hintColor, modifier = Modifier.size(10.dp))
                        } else if (!secondaryHint.isNullOrEmpty()) {
                            Text(
                                secondaryHint,
                                fontFamily = MokeMono,
                                fontSize = 8.5.sp,
                                lineHeight = 9.sp,
                                fontWeight = FontWeight.Medium,
                                color = hintColor,
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

private enum class SlotActionTab { PRIMARY, SECONDARY }
private enum class ActionSourceKind { NONE, BUILTIN, COMBO }
private enum class DisplayOverrideMode { DEFAULT, TEXT, ICON }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SlotEditorSheet(
    row: Int,
    col: Int,
    slot: ExtraKeySlot,
    compactSymbols: Boolean,
    onAssignPrimary: (ExtraKeySpec) -> Unit,
    onAssignSecondary: (ExtraKeySpec?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var activeTab by remember(row, col) { mutableStateOf(SlotActionTab.PRIMARY) }

    val activeSpec: ExtraKeySpec? = if (activeTab == SlotActionTab.PRIMARY) slot.primary else slot.secondary
    val sourceKind = when (activeSpec) {
        null -> ActionSourceKind.NONE
        is ExtraKeySpec.Builtin -> ActionSourceKind.BUILTIN
        is ExtraKeySpec.CustomCombo -> ActionSourceKind.COMBO
    }

    fun updateActiveSpec(next: ExtraKeySpec?) {
        if (activeTab == SlotActionTab.PRIMARY) {
            if (next != null) onAssignPrimary(next)
        } else {
            onAssignSecondary(next)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        CenteredItem(maxWidth = MokeDimens.contentMaxWidth) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    EditableSlotKeyFace(
                        slot = slot,
                        compactSymbols = compactSymbols,
                        denseColumns = false,
                        highlighted = true,
                        modifier = Modifier.width(62.dp).height(42.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.extra_keys_slot_editor_title, row + 1, col + 1),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        val primarySummary = slot.primary.effectiveLabel(compactSymbols)
                        val secondarySummary = slot.secondary?.effectiveLabel(compactSymbols)
                            ?: stringResource(R.string.extra_keys_secondary_none)
                        Text(
                            "${stringResource(R.string.extra_keys_tab_primary)}: $primarySummary · ${stringResource(R.string.extra_keys_tab_secondary)}: $secondarySummary",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.extra_keys_done))
                    }
                }

                // 主动作 / 上滑副动作切换
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ModePill(
                        text = stringResource(R.string.extra_keys_tab_primary),
                        selected = activeTab == SlotActionTab.PRIMARY,
                        onClick = { activeTab = SlotActionTab.PRIMARY },
                        modifier = Modifier.weight(1f),
                    )
                    ModePill(
                        text = stringResource(R.string.extra_keys_tab_secondary),
                        selected = activeTab == SlotActionTab.SECONDARY,
                        onClick = { activeTab = SlotActionTab.SECONDARY },
                        modifier = Modifier.weight(1f),
                    )
                }

                // 动作来源类型切换
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (activeTab == SlotActionTab.SECONDARY) {
                        ChoiceChipButton(
                            text = stringResource(R.string.extra_keys_secondary_none),
                            selected = sourceKind == ActionSourceKind.NONE,
                            onClick = { updateActiveSpec(null) },
                        )
                    }
                    ChoiceChipButton(
                        text = stringResource(R.string.extra_keys_type_builtin),
                        selected = sourceKind == ActionSourceKind.BUILTIN,
                        onClick = {
                            if (sourceKind != ActionSourceKind.BUILTIN) {
                                val defaultId = if (activeTab == SlotActionTab.PRIMARY) "esc" else "home"
                                updateActiveSpec(ExtraKeySpec.Builtin(defaultId))
                            }
                        },
                    )
                    ChoiceChipButton(
                        text = stringResource(R.string.extra_keys_type_combo),
                        selected = sourceKind == ActionSourceKind.COMBO,
                        onClick = {
                            if (sourceKind != ActionSourceKind.COMBO) {
                                updateActiveSpec(
                                    ExtraKeySpec.CustomCombo(
                                        steps = listOf(ComboStepSpec.PREFIX_CTRL_B, ComboStepSpec(ComboTarget.KEY_D)),
                                    ),
                                )
                            }
                        },
                    )
                }

                key(activeTab) {
                    when (activeSpec) {
                        null -> {
                            Text(
                                stringResource(R.string.extra_keys_secondary_none_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        is ExtraKeySpec.Builtin -> {
                            BuiltinKeyGridPicker(
                                selectedId = activeSpec.id,
                                allowModifiers = activeTab == SlotActionTab.PRIMARY,
                                compactSymbols = compactSymbols,
                                onSelect = { pickedId ->
                                    updateActiveSpec(ExtraKeySpec.Builtin(pickedId))
                                },
                            )
                            DisplayOverrideSection(
                                identityKey = Triple(row to col, activeTab, "builtin:${activeSpec.id}"),
                                spec = activeSpec,
                                compactSymbols = compactSymbols,
                                onChange = { updated -> updateActiveSpec(updated) },
                            )
                        }
                        is ExtraKeySpec.CustomCombo -> {
                            ComboKeyBuilder(
                                combo = activeSpec,
                                onChange = { nextSteps ->
                                    updateActiveSpec(activeSpec.copy(steps = nextSteps))
                                },
                            )
                            DisplayOverrideSection(
                                identityKey = Triple(row to col, activeTab, "combo"),
                                spec = activeSpec,
                                compactSymbols = compactSymbols,
                                onChange = { updated -> updateActiveSpec(updated) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BuiltinKeyGridPicker(
    selectedId: String,
    allowModifiers: Boolean,
    compactSymbols: Boolean,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.extra_keys_pick),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ExtraKeyLayout.groups.forEach { (titleRes, rawIds) ->
            val ids = if (allowModifiers) rawIds else rawIds.filterNot(ExtraKeyLayout::isModifierId)
            if (ids.isNotEmpty()) {
                Text(
                    stringResource(titleRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ids.chunked(6).forEach { rowIds ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            rowIds.forEach { id ->
                                val selected = id == selectedId
                                val label = ExtraKeyLayout.label(id, compactSymbols)
                                PickerKeyChip(
                                    label = label,
                                    selected = selected,
                                    onClick = { onSelect(id) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(6 - rowIds.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComboKeyBuilder(
    combo: ExtraKeySpec.CustomCombo,
    onChange: (List<ComboStepSpec>) -> Unit,
) {
    val isTwoStep = combo.steps.size == 2
    val targetStepIndex = if (isTwoStep) 1 else 0
    val targetStep = combo.steps[targetStepIndex]

    var selectedCategory by remember(isTwoStep) { mutableStateOf(targetStep.target.category) }
    var showCustomCtrlLetters by remember { mutableStateOf(false) }

    val encodedPreview = remember(combo.steps) {
        KeySeq.describeBytes(KeySeq.encode(combo.toKeyId()))
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ChoiceChipButton(
                    text = stringResource(R.string.extra_keys_combo_single),
                    selected = !isTwoStep,
                    onClick = {
                        if (isTwoStep) {
                            val second = combo.steps.last()
                            val next = if (!second.ctrl && !second.alt && !second.shift && second.target.category == ComboTargetCategory.LETTERS) {
                                second.copy(alt = true)
                            } else {
                                second
                            }
                            onChange(listOf(next))
                        }
                    },
                )
                ChoiceChipButton(
                    text = stringResource(R.string.extra_keys_combo_prefix),
                    selected = isTwoStep,
                    onClick = {
                        if (!isTwoStep) {
                            val first = ComboStepSpec.PREFIX_CTRL_B
                            val second = combo.steps.first().copy(ctrl = false, alt = false, shift = false)
                            onChange(listOf(first, second))
                        }
                    },
                )
            }
            Text(
                stringResource(R.string.extra_keys_combo_sends, encodedPreview),
                fontFamily = MokeMono,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp),
            )
        }

        if (isTwoStep) {
            val prefix = combo.steps[0]
            val presets = listOf(
                "^B" to ComboStepSpec.PREFIX_CTRL_B,
                "^A" to ComboStepSpec.PREFIX_CTRL_A,
                "^X" to ComboStepSpec.PREFIX_CTRL_X,
                "ESC" to ComboStepSpec.PREFIX_ESC,
            )
            val isCustomCtrlPrefix = presets.none { it.second == prefix }
            Text(
                stringResource(R.string.extra_keys_combo_step1),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                presets.forEach { (label, spec) ->
                    ChoiceChipButton(
                        text = label,
                        mono = true,
                        selected = prefix == spec && !showCustomCtrlLetters,
                        onClick = {
                            showCustomCtrlLetters = false
                            onChange(listOf(spec, combo.steps[1]))
                        },
                    )
                }
                val customTitle = if (isCustomCtrlPrefix) {
                    prefix.autoLabel(compactForTwoStep = true)
                } else {
                    stringResource(R.string.extra_keys_combo_custom_ctrl)
                }
                ChoiceChipButton(
                    text = customTitle,
                    mono = isCustomCtrlPrefix,
                    selected = isCustomCtrlPrefix || showCustomCtrlLetters,
                    onClick = { showCustomCtrlLetters = !showCustomCtrlLetters },
                )
            }
            if (showCustomCtrlLetters) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ComboTarget.letterTargets.chunked(7).forEach { rowTargets ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            rowTargets.forEach { letter ->
                                val candidate = ComboStepSpec(letter, ctrl = true)
                                PickerKeyChip(
                                    label = "^${letter.display}",
                                    selected = prefix == candidate,
                                    onClick = {
                                        showCustomCtrlLetters = false
                                        onChange(listOf(candidate, combo.steps[1]))
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(7 - rowTargets.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }

        Text(
            stringResource(if (isTwoStep) R.string.extra_keys_combo_step2 else R.string.extra_keys_combo_modifiers),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChoiceChipButton(
                text = "Ctrl",
                mono = true,
                selected = targetStep.ctrl,
                onClick = {
                    val updated = targetStep.copy(ctrl = !targetStep.ctrl)
                    onChange(if (isTwoStep) listOf(combo.steps[0], updated) else listOf(updated))
                },
            )
            ChoiceChipButton(
                text = "Alt",
                mono = true,
                selected = targetStep.alt,
                onClick = {
                    val updated = targetStep.copy(alt = !targetStep.alt)
                    onChange(if (isTwoStep) listOf(combo.steps[0], updated) else listOf(updated))
                },
            )
            ChoiceChipButton(
                text = "Shift",
                mono = true,
                selected = targetStep.shift,
                onClick = {
                    val updated = targetStep.copy(shift = !targetStep.shift)
                    onChange(if (isTwoStep) listOf(combo.steps[0], updated) else listOf(updated))
                },
            )
        }

        if (!isTwoStep) {
            Text(
                stringResource(R.string.extra_keys_combo_target),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val categoryLabels = listOf(
            ComboTargetCategory.COMMON to R.string.extra_keys_cat_common,
            ComboTargetCategory.NAVIGATION to R.string.extra_keys_cat_nav,
            ComboTargetCategory.LETTERS to R.string.extra_keys_cat_letters,
            ComboTargetCategory.DIGITS to R.string.extra_keys_cat_digits,
            ComboTargetCategory.FUNCTION to R.string.extra_keys_cat_fn,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            categoryLabels.forEach { (cat, labelRes) ->
                ChoiceChipButton(
                    text = stringResource(labelRes),
                    selected = selectedCategory == cat,
                    onClick = { selectedCategory = cat },
                )
            }
        }

        val targets = remember(selectedCategory) { ComboTarget.forCategory(selectedCategory) }
        val cols = if (selectedCategory == ComboTargetCategory.NAVIGATION || selectedCategory == ComboTargetCategory.FUNCTION) 6 else 7
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            targets.chunked(cols).forEach { rowTargets ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    rowTargets.forEach { target ->
                        val display = if (target.category == ComboTargetCategory.LETTERS && !targetStep.shift && !targetStep.ctrl) {
                            target.shortGlyph
                        } else {
                            target.display
                        }
                        PickerKeyChip(
                            label = display,
                            selected = targetStep.target == target,
                            onClick = {
                                val updated = targetStep.copy(target = target)
                                onChange(if (isTwoStep) listOf(combo.steps[0], updated) else listOf(updated))
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(cols - rowTargets.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun DisplayOverrideSection(
    identityKey: Any,
    spec: ExtraKeySpec,
    compactSymbols: Boolean,
    onChange: (ExtraKeySpec) -> Unit,
) {
    var mode by remember(identityKey) {
        mutableStateOf(
            when {
                spec.customIcon != null -> DisplayOverrideMode.ICON
                spec.customLabel.isNotEmpty() -> DisplayOverrideMode.TEXT
                else -> DisplayOverrideMode.DEFAULT
            },
        )
    }
    var textFieldValue by remember(identityKey) {
        mutableStateOf(TextFieldValue(spec.customLabel, TextRange(spec.customLabel.length)))
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.extra_keys_display_section),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChoiceChipButton(
                text = stringResource(R.string.extra_keys_display_default),
                selected = mode == DisplayOverrideMode.DEFAULT,
                onClick = {
                    mode = DisplayOverrideMode.DEFAULT
                    textFieldValue = TextFieldValue("")
                    onChange(spec.withDisplay("", null))
                },
            )
            ChoiceChipButton(
                text = stringResource(R.string.extra_keys_display_text),
                selected = mode == DisplayOverrideMode.TEXT,
                onClick = {
                    mode = DisplayOverrideMode.TEXT
                    if (spec.customIcon != null) {
                        onChange(spec.withDisplay(textFieldValue.text, null))
                    }
                },
            )
            ChoiceChipButton(
                text = stringResource(R.string.extra_keys_display_icon),
                selected = mode == DisplayOverrideMode.ICON,
                onClick = {
                    mode = DisplayOverrideMode.ICON
                    textFieldValue = TextFieldValue("")
                    val initialIcon = spec.customIcon ?: ExtraKeyIcon.TERMINAL
                    onChange(spec.withDisplay("", initialIcon))
                },
            )
        }

        when (mode) {
            DisplayOverrideMode.DEFAULT -> Unit
            DisplayOverrideMode.TEXT -> {
                OutlinedTextField(
                    value = textFieldValue,
                    onValueChange = { newValue ->
                        val cleanText = ExtraKeyLayout.stripControlChars(newValue.text)
                        if (newValue.composition != null) {
                            textFieldValue = if (cleanText == newValue.text) {
                                newValue
                            } else {
                                TextFieldValue(
                                    text = cleanText,
                                    selection = TextRange(
                                        newValue.selection.start.coerceIn(0, cleanText.length),
                                        newValue.selection.end.coerceIn(0, cleanText.length),
                                    ),
                                )
                            }
                        } else {
                            val capped = ExtraKeyLayout.takeCodePoints(
                                cleanText,
                                ExtraKeyLayout.MAX_CUSTOM_LABEL_CHARS,
                            )
                            val clampedSelection = TextRange(
                                newValue.selection.start.coerceIn(0, capped.length),
                                newValue.selection.end.coerceIn(0, capped.length),
                            )
                            textFieldValue = TextFieldValue(text = capped, selection = clampedSelection)
                            onChange(spec.withDisplay(capped, null))
                        }
                    },
                    singleLine = true,
                    colors = mokeTextFieldColors(),
                    placeholder = {
                        Text(
                            "${spec.defaultLabel(compactSymbols)} (${stringResource(R.string.extra_keys_display_text_hint)})",
                            fontFamily = MokeMono,
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MokeMono),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            DisplayOverrideMode.ICON -> {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ExtraKeyIcon.entries.chunked(6).forEach { iconRow ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            iconRow.forEach { iconSpec ->
                                val selected = spec.customIcon == iconSpec
                                Surface(
                                    onClick = { onChange(spec.withDisplay("", iconSpec)) },
                                    modifier = Modifier.weight(1f).height(38.dp),
                                    shape = MokeShapes.keycap,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.inverseSurface
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    },
                                    contentColor = if (selected) {
                                        MaterialTheme.colorScheme.inverseOnSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                ) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Icon(
                                            iconSpec.imageVector(),
                                            contentDescription = stringResource(iconSpec.labelRes()),
                                            modifier = Modifier.size(19.dp),
                                        )
                                    }
                                }
                            }
                            repeat(6 - iconRow.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModePill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 38.dp),
        shape = MokeShapes.control,
        color = if (selected) MaterialTheme.colorScheme.inverseSurface else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (selected) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onSurface,
    ) {
        Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ChoiceChipButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    mono: Boolean = false,
) {
    Surface(
        onClick = onClick,
        shape = MokeShapes.pill,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.onSurface) else null,
    ) {
        Text(
            text,
            fontFamily = if (mono) MokeMono else null,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            maxLines = 1,
        )
    }
}

@Composable
private fun PickerKeyChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val clampedDensity = remember(density) {
        Density(density.density, density.fontScale.coerceAtMost(1.1f))
    }
    val isSingleGlyph = label.length == 1 && label[0] in SINGLE_GLYPH_CHARS
    val units = ExtraKeyLayout.visualUnits(label)
    val fontSize = when {
        isSingleGlyph -> 16.sp
        units >= 5 -> 11.sp
        else -> 12.sp
    }
    Surface(
        onClick = onClick,
        modifier = modifier.height(36.dp),
        shape = MokeShapes.keycap,
        color = if (selected) MaterialTheme.colorScheme.inverseSurface else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (selected) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onSurface,
    ) {
        CompositionLocalProvider(LocalDensity provides clampedDensity) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    label,
                    fontFamily = MokeMono,
                    fontSize = fontSize,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}
