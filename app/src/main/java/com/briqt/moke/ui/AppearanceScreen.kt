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
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
    fontStates: Map<String, FontInstallState>,
    fontSizeSp: Int,
    lineSpacing: Float,
    letterSpacing: Float,
    cursorStyle: Int,
    cursorBlink: Boolean,
    resolveTypeface: (String, String) -> Typeface,
    onSelectScheme: (String) -> Unit,
    onSelectPrimary: (String) -> Unit,
    onSelectFallback: (String) -> Unit,
    onFontSize: (Int) -> Unit,
    onLineSpacing: (Float) -> Unit,
    onLetterSpacing: (Float) -> Unit,
    onCursorStyle: (Int) -> Unit,
    onCursorBlink: (Boolean) -> Unit,
    onOpenFonts: () -> Unit,
    onBack: () -> Unit,
) {
    fun installed(id: String) = fontStates[id] is FontInstallState.Installed
    fun capTags(spec: com.briqt.moke.terminal.FontSpec) = buildList {
        if (spec.bundled) add("内置")
        if (spec.cjk) add("含中文")
        if (spec.ligature) add("连字")
    }
    // 主字体：内置 + 已安装
    val primaryOptions = FontCatalog.all.filter { it.bundled || installed(it.id) }.map { spec ->
        DropdownOption(
            id = spec.id,
            title = spec.nameZh,
            subtitle = "${spec.name} · ${spec.license}",
            tags = capTags(spec),
        )
    }
    // 回退字体：无 + 已安装的含中文字体（回退用来补 Latin 缺失字形，如中文）
    val fallbackOptions = listOf(DropdownOption(id = "", title = "无（系统兜底）")) +
        FontCatalog.all.filter { (it.bundled || installed(it.id)) && it.cjk }.map { spec ->
            DropdownOption(id = spec.id, title = spec.nameZh, subtitle = spec.name, tags = listOf("含中文"))
        }
    // 下拉底部的「下载更多」入口（主字体 + 回退共用）——即便字体变多、菜单需滚动也在末尾可达。
    val downloadFooter: @Composable (dismiss: () -> Unit) -> Unit = { dismiss ->
        HorizontalDivider()
        DropdownMenuItem(
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.FontDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("  管理 / 下载更多字体…", color = MaterialTheme.colorScheme.primary)
                }
            },
            onClick = { dismiss(); onOpenFonts() },
        )
    }
    val schemeOptions = TerminalThemes.all.map { s ->
        DropdownOption(
            id = s.id,
            title = s.nameZh,
            subtitle = s.name,
            tags = listOf(if (s.isDark) "暗色" else "亮色"),
            leading = { SchemeSwatches(s) },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("外观") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
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
                    .height(200.dp)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // 控制区（内容较少，通常不需滚动；小屏/横屏兜底可滚）
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                RichDropdown(
                    label = "主字体",
                    options = primaryOptions,
                    selectedId = primaryFontId,
                    onSelect = onSelectPrimary,
                    footer = downloadFooter,
                )
                RichDropdown(
                    label = "回退字体（补中文等字形）",
                    options = fallbackOptions,
                    selectedId = fallbackFontId,
                    onSelect = onSelectFallback,
                    footer = downloadFooter,
                )
                // 独立常驻入口：不依赖下拉滚动，字体再多也点得到。
                FontManageEntry(onOpenFonts)

                RichDropdown(
                    label = "配色方案",
                    options = schemeOptions,
                    selectedId = schemeId,
                    onSelect = onSelectScheme,
                )

                // 字号
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("字号", color = MaterialTheme.colorScheme.onSurface)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onFontSize(fontSizeSp - 1) }) {
                            Icon(Icons.Filled.Remove, contentDescription = "减小", tint = MaterialTheme.colorScheme.primary)
                        }
                        Text("$fontSizeSp", fontFamily = MokeMono, modifier = Modifier.width(40.dp), textAlign = TextAlign.Center)
                        IconButton(onClick = { onFontSize(fontSizeSp + 1) }) {
                            Icon(Icons.Filled.Add, contentDescription = "增大", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                // 行距 / 字间距：0.1 步进微调（1.0=默认）。
                SpacingStepper("行距", lineSpacing, onLineSpacing)
                SpacingStepper("字间距", letterSpacing, onLetterSpacing)

                // 光标样式
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("光标", color = MaterialTheme.colorScheme.onSurface)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("方块", "下划线", "竖线").forEachIndexed { i, label ->
                            FilterChip(selected = cursorStyle == i, onClick = { onCursorStyle(i) }, label = { Text(label) })
                        }
                    }
                }

                // 光标闪烁
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

/** 行距/字间距步进器：0.1 步进，范围 0.8–1.6，1.0 为默认。 */
@Composable
private fun SpacingStepper(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurface)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onChange((value - 0.1f).coerceIn(0.8f, 1.6f)) }) {
                Icon(Icons.Filled.Remove, contentDescription = "减小", tint = MaterialTheme.colorScheme.primary)
            }
            Text(String.format("%.1f", value), fontFamily = MokeMono, modifier = Modifier.width(40.dp), textAlign = TextAlign.Center)
            IconButton(onClick = { onChange((value + 0.1f).coerceIn(0.8f, 1.6f)) }) {
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
    fontSizeSp: Int,
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
        val px = (fontSizeSp * density).toInt()
        view.setTextSize(px)
        view.setTypeface(resolveTypeface(primaryFontId, fallbackFontId))
        view.setFontSpacing(lineSpacing, letterSpacingEm(letterSpacing))
        view.attachSession(session)
        onDispose {
            if (controller.view === view) controller.view = null
            session.finishIfRunning()
        }
    }

    LaunchedEffect(primaryFontId, fallbackFontId) {
        view.setTypeface(resolveTypeface(primaryFontId, fallbackFontId))
    }
    LaunchedEffect(fontSizeSp) {
        view.setTextSize((fontSizeSp * density).toInt())
    }
    LaunchedEffect(lineSpacing, letterSpacing) {
        view.setFontSpacing(lineSpacing, letterSpacingEm(letterSpacing))
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
