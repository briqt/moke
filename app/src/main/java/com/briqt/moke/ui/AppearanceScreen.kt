package com.briqt.moke.ui

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FontDownload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.briqt.moke.terminal.FontCatalog
import com.briqt.moke.terminal.FontInstallState
import com.briqt.moke.terminal.PreviewTransport
import com.briqt.moke.terminal.TermColorScheme
import com.briqt.moke.terminal.TerminalController
import com.briqt.moke.terminal.TerminalThemes
import com.briqt.moke.ui.theme.MokeMono
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView

private fun hex(s: String): Color = Color(android.graphics.Color.parseColor(s))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(
    schemeId: String,
    primaryFontId: String,
    fallbackFontId: String,
    fonts: List<com.briqt.moke.terminal.FontSpec>,
    fontStates: Map<String, FontInstallState>,
    fontSizeSp: Float,
    lineSpacing: Float,
    letterSpacing: Float,
    cursorStyle: Int,
    cursorBlink: Boolean,
    resolveTypeface: (String, String) -> Typeface,
    onSelectScheme: (String) -> Unit,
    onSelectPrimary: (String) -> Unit,
    onSelectFallback: (String) -> Unit,
    onFontSize: (Float) -> Unit,
    onLineSpacing: (Float) -> Unit,
    onLetterSpacing: (Float) -> Unit,
    onCursorStyle: (Int) -> Unit,
    onCursorBlink: (Boolean) -> Unit,
    onResetDefaults: () -> Unit,
    onOpenFonts: () -> Unit,
    onBack: () -> Unit,
) {
    fun installed(id: String) = fontStates[id] is FontInstallState.Installed
    fun capTags(spec: com.briqt.moke.terminal.FontSpec) = buildList {
        if (spec.bundled) add("内置")
        if (spec.userUploaded) add("本地")
        if (spec.cjk) add("含中文")
        if (spec.ligature) add("连字")
    }
    // 主字体：内置 + 已安装（含用户上传）
    val primaryOptions = fonts.filter { it.bundled || installed(it.id) }.map { spec ->
        DropdownOption(
            id = spec.id,
            title = spec.nameZh,
            subtitle = "${spec.name} · ${spec.license}",
            tags = capTags(spec),
        )
    }
    // 回退字体：无 + 已安装的含中文字体（回退用来补 Latin 缺失字形，如中文）
    // 回退候选：含中文的字体（补 CJK 字形）+ 用户上传字体（未知覆盖，允许自选）。
    val fallbackOptions = listOf(DropdownOption(id = "", title = "无（系统兜底）")) +
        fonts.filter { (it.bundled || installed(it.id)) && (it.cjk || it.userUploaded) }.map { spec ->
            DropdownOption(id = spec.id, title = spec.nameZh, subtitle = spec.name, tags = if (spec.userUploaded) listOf("本地") else listOf("含中文"))
        }
    val schemeOptions = TerminalThemes.all.map { s ->
        DropdownOption(
            id = s.id,
            title = s.nameZh,
            subtitle = s.name,
            leading = { SchemeSwatches(s) },
        )
    }

    val snackbarState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var overflowOpen by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarState) },
        topBar = {
            TopAppBar(
                title = { Text("外观") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { overflowOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("恢复默认") },
                                leadingIcon = { Icon(Icons.Filled.RestartAlt, contentDescription = null) },
                                onClick = {
                                    overflowOpen = false
                                    // 先快照当前值，重置后用 Snackbar 提供「撤销」。
                                    val pScheme = schemeId; val pPrimary = primaryFontId; val pFallback = fallbackFontId
                                    val pSize = fontSizeSp; val pLine = lineSpacing; val pLetter = letterSpacing
                                    val pCursor = cursorStyle; val pBlink = cursorBlink
                                    onResetDefaults()
                                    scope.launch {
                                        val r = snackbarState.showSnackbar("已恢复默认", "撤销", duration = SnackbarDuration.Short)
                                        if (r == SnackbarResult.ActionPerformed) {
                                            onSelectScheme(pScheme); onSelectPrimary(pPrimary); onSelectFallback(pFallback)
                                            onFontSize(pSize); onLineSpacing(pLine); onLetterSpacing(pLetter)
                                            onCursorStyle(pCursor); onCursorBlink(pBlink)
                                        }
                                    }
                                },
                            )
                        }
                    }
                },
                expandedHeight = 52.dp,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 预览高度按样张 5 行估算：含中文回退时行高偏大（≈ 字号×行距×1.6dp）+ 上下 padding。
            // 上限 190dp：大字号时不至于吃掉整块滚动区（超出部分终端内部滚动，色带仍在底部可见）。
            val previewHeight = (fontSizeSp * lineSpacing * 1.6f * 5 + 28).dp.coerceAtMost(190.dp)
            // 顶部固定实时预览：字体/字号/配色/光标任一改动都即时反映
            AppearancePreview(
                schemeId = schemeId,
                primaryFontId = primaryFontId,
                fallbackFontId = fallbackFontId,
                fontSizeSp = fontSizeSp,
                lineSpacing = lineSpacing,
                letterSpacing = letterSpacing,
                cursorStyle = cursorStyle,
                cursorBlink = cursorBlink,
                resolveTypeface = resolveTypeface,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(previewHeight)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // 控制区分组：字体 → 排版 → 配色 → 光标。恢复默认收进顶栏 ⋮。
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SectionHeader("字体")
                RichDropdown(
                    label = "主字体",
                    options = primaryOptions,
                    selectedId = primaryFontId,
                    onSelect = onSelectPrimary,
                )
                RichDropdown(
                    label = "中文 / 回退字体",
                    options = fallbackOptions,
                    selectedId = fallbackFontId,
                    onSelect = onSelectFallback,
                )
                // 常驻入口：进入字体管理下载/上传/设角色。
                FontManageEntry(onOpenFonts)

                SectionHeader("排版")
                // 字号 0.5 步进；行距/字距 0.1 步进。滑块快调 + ± 精调。
                SliderRow(
                    label = "字号",
                    valueText = fmtFontSize(fontSizeSp),
                    value = fontSizeSp,
                    valueRange = 8f..32f,
                    steps = 47,
                    onValue = onFontSize,
                    onMinus = { onFontSize(fontSizeSp - 0.5f) },
                    onPlus = { onFontSize(fontSizeSp + 0.5f) },
                )
                SliderRow(
                    label = "行距",
                    valueText = String.format("%.1f", lineSpacing),
                    value = lineSpacing,
                    valueRange = 0.8f..1.6f,
                    steps = 7,
                    onValue = onLineSpacing,
                    onMinus = { onLineSpacing((lineSpacing - 0.1f).coerceIn(0.8f, 1.6f)) },
                    onPlus = { onLineSpacing((lineSpacing + 0.1f).coerceIn(0.8f, 1.6f)) },
                )
                SliderRow(
                    label = "字间距",
                    valueText = String.format("%.1f", letterSpacing),
                    value = letterSpacing,
                    valueRange = 0.8f..1.6f,
                    steps = 7,
                    onValue = onLetterSpacing,
                    onMinus = { onLetterSpacing((letterSpacing - 0.1f).coerceIn(0.8f, 1.6f)) },
                    onPlus = { onLetterSpacing((letterSpacing + 0.1f).coerceIn(0.8f, 1.6f)) },
                )

                SectionHeader("配色")
                RichDropdown(
                    label = "配色方案",
                    options = schemeOptions,
                    selectedId = schemeId,
                    onSelect = onSelectScheme,
                )

                SectionHeader("光标")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("方块", "下划线", "竖线").forEachIndexed { i, label ->
                        FilterChip(selected = cursorStyle == i, onClick = { onCursorStyle(i) }, label = { Text(label) })
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("光标闪烁", color = MaterialTheme.colorScheme.onSurface)
                    Switch(checked = cursorBlink, onCheckedChange = onCursorBlink)
                }
            }
        }
    }
}

/** 分组小标题（字体 / 排版 / 配色 / 光标）。 */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/**
 * 数值滑块行：标题 + 当前值在上，滑块 + 两侧 ± 精调在下。
 * 滑块快调（离散步进 [steps]），± 做单档精调（字号 0.5 / 行距字距 0.1）。
 */
@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValue: (Float) -> Unit,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurface)
            Text(valueText, fontFamily = MokeMono, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMinus) {
                Icon(Icons.Filled.Remove, contentDescription = "减小", tint = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = value.coerceIn(valueRange.start, valueRange.endInclusive),
                onValueChange = onValue,
                valueRange = valueRange,
                steps = steps,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onPlus) {
                Icon(Icons.Filled.Add, contentDescription = "增大", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** 独立「字体管理」入口行：始终可见（不藏在下拉里），点进 FontsScreen 下载/管理。 */
@Composable
private fun FontManageEntry(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.FontDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text("字体管理", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "下载更多字体 · 设置主字体 / 回退",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
        }
    }
}

/** 配色前导：5 个小色块（背景 / 红 / 绿 / 蓝 / 前景）。 */
@Composable
private fun SchemeSwatches(s: TermColorScheme) {
    Row(
        modifier = Modifier.clip(RoundedCornerShape(4.dp)),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        listOf(s.bg, s.ansi[1], s.ansi[2], s.ansi[4], s.fg).forEach { c ->
            Box(modifier = Modifier.size(width = 7.dp, height = 22.dp).background(hex(c)))
        }
    }
}

/**
 * 实时预览：真 TerminalView 渲染紧凑样张（Latin+中文+符号+0-15 色带），随外观设置即时变化。
 * 配色即时换：重新 applyToTerminal + 对预览 emulator `mColors.reset()` + 重绘（不清屏）。
 */
@Composable
private fun AppearancePreview(
    schemeId: String,
    primaryFontId: String,
    fallbackFontId: String,
    fontSizeSp: Float,
    lineSpacing: Float,
    letterSpacing: Float,
    cursorStyle: Int,
    cursorBlink: Boolean,
    resolveTypeface: (String, String) -> Typeface,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val controller = remember { TerminalController(context) }
    val view = remember { TerminalView(context, null) }
    val session = remember { TerminalSession(PreviewTransport(), 200, controller) }
    val density = context.resources.displayMetrics.density

    DisposableEffect(Unit) {
        controller.view = view
        view.setTerminalViewClient(controller)
        view.isFocusable = false
        view.isFocusableInTouchMode = false
        controller.cursorStyle = cursorStyle
        controller.cursorBlink = cursorBlink
        val px = Math.round(fontSizeSp * density)
        view.setTextSize(px)
        view.setTypeface(resolveTypeface(primaryFontId, fallbackFontId))
        view.setFontSpacing(lineSpacing, letterSpacingEm(letterSpacing))
        view.attachSession(session)
        onDispose {
            if (controller.view === view) controller.view = null
            session.finishIfRunning()
        }
    }

    // 字号/字距/字体改列数 → 内核 reflow 会丢弃色带这类"全空格"行 → 每次重绘样张兜住（内容顶格、色带常在）。
    LaunchedEffect(primaryFontId, fallbackFontId) {
        view.setTypeface(resolveTypeface(primaryFontId, fallbackFontId))
        PreviewTransport.redraw(session)
        view.onScreenUpdated()
    }
    LaunchedEffect(fontSizeSp) {
        view.setTextSize(Math.round(fontSizeSp * density))
        PreviewTransport.redraw(session)
        view.onScreenUpdated()
    }
    LaunchedEffect(lineSpacing, letterSpacing) {
        view.setFontSpacing(lineSpacing, letterSpacingEm(letterSpacing))
        PreviewTransport.redraw(session)
        view.onScreenUpdated()
    }
    LaunchedEffect(cursorStyle) {
        controller.cursorStyle = cursorStyle
        session.emulator?.setCursorStyle()
        view.onScreenUpdated()
    }
    LaunchedEffect(cursorBlink) {
        controller.cursorBlink = cursorBlink
        view.setTerminalCursorBlinkerState(cursorBlink, true)
    }
    LaunchedEffect(schemeId) {
        // 确保全局调色板=当前所选，再刷新预览 emulator 的调色板（顺序无关）。
        TerminalThemes.byId(schemeId).applyToTerminal()
        session.emulator?.mColors?.reset()
        view.onScreenUpdated()
    }

    AndroidView(factory = { view }, modifier = modifier)
}
