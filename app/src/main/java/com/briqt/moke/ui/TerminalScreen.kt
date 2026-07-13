package com.briqt.moke.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.briqt.moke.R
import com.briqt.moke.terminal.TermSession
import com.briqt.moke.terminal.TerminalController
import com.briqt.moke.ui.theme.MokeMono
import com.termux.view.TerminalView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    ts: TermSession,
    primaryFontId: String,
    fallbackFontId: String,
    fontSizeSp: Float,
    lineSpacing: Float,
    letterSpacing: Float,
    cursorStyle: Int,
    cursorBlink: Boolean,
    schemeId: String,
    extraKeysVisible: Boolean,
    resolveTypeface: (String, String) -> android.graphics.Typeface,
    onBack: () -> Unit,
    onReconnect: () -> Unit,
    onClose: () -> Unit,
    onFontSize: (Float) -> Unit,
    onToggleExtraKeys: () -> Unit,
) {
    val context = LocalContext.current
    val title by ts.displayTitle.collectAsState()
    val alive by ts.alive.collectAsState()
    val latency by ts.latency.collectAsState()
    var ctrlOn by remember(ts.id) { mutableStateOf(false) }
    var altOn by remember(ts.id) { mutableStateOf(false) }

    var showComposer by remember(ts.id) { mutableStateOf(false) }
    // 顶栏 ⋮ 里的标题弹窗：null 无 / TITLE 自定义标题 / PREFIX 标题前缀。
    var titleDialog by remember(ts.id) { mutableStateOf<SessionTitleKind?>(null) }
    // 文本段草稿：提升到此，关闭 sheet 保留、发送后清空。
    var composerText by remember(ts.id) { mutableStateOf("") }
    // 捏合缩放提示（持有当前 sp，非空即显示；2 秒后自动消失）。
    var zoomHintSp by remember(ts.id) { mutableStateOf<Float?>(null) }

    val controller = ts.controller
    val scope = rememberCoroutineScope()
    // View 按会话 id 记忆：切换会话得到全新 View，attach 到既有 session 后滚屏/连接保留。
    val view = remember(ts.id) { TerminalView(context, null) }

    DisposableEffect(ts.id) {
        controller.view = view
        view.setTerminalViewClient(controller)
        // 必须可在触摸模式获焦，否则按键/IME 输入会落到其它可聚焦控件。
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.keepScreenOn = true
        controller.cursorStyle = cursorStyle
        controller.cursorBlink = cursorBlink
        controller.fontSizeSp = fontSizeSp
        controller.onFontSizeSp = { sp -> onFontSize(sp); zoomHintSp = sp }
        // one-shot 粘滞修饰被消费后，熄灭 Ctrl/Alt 高亮（用一次即取消）。
        controller.onModifiersConsumed = { ctrlOn = false; altOn = false }
        // 注意顺序：先 setTextSize 创建 renderer，再 setTypeface（其读取 mRenderer 不判空）。
        val px = Math.round(fontSizeSp * context.resources.displayMetrics.density)
        view.setTextSize(px)
        view.setTypeface(resolveTypeface(primaryFontId, fallbackFontId))
        view.setFontSpacing(lineSpacing, letterSpacingEm(letterSpacing))
        view.attachSession(ts.session)
        onDispose {
            // 仅解绑 View——会话保持存活以支持多会话跨页；真正结束由会话列表关闭或传输结束触发。
            if (controller.view === view) {
                controller.view = null
                controller.onFontSizeSp = null
                controller.onModifiersConsumed = null
            }
        }
    }

    // 热切换：设置变更时对当前活动终端即时生效（含空闲会话——强制重绘，不必等新输出）。
    LaunchedEffect(ts.id, primaryFontId, fallbackFontId) {
        view.setTypeface(resolveTypeface(primaryFontId, fallbackFontId))
        view.onScreenUpdated()
    }
    LaunchedEffect(ts.id, fontSizeSp) {
        val px = Math.round(fontSizeSp * context.resources.displayMetrics.density)
        view.setTextSize(px)
        controller.fontSizeSp = fontSizeSp
        view.onScreenUpdated()
    }
    LaunchedEffect(ts.id, lineSpacing, letterSpacing) {
        view.setFontSpacing(lineSpacing, letterSpacingEm(letterSpacing))
        view.onScreenUpdated()
    }
    LaunchedEffect(ts.id, cursorStyle) {
        controller.cursorStyle = cursorStyle
        ts.session.emulator?.setCursorStyle()
        view.onScreenUpdated()
    }
    LaunchedEffect(ts.id, cursorBlink) {
        controller.cursorBlink = cursorBlink
        view.setTerminalCursorBlinkerState(cursorBlink, true)
    }
    // 配色热切换：全局调色板已由 VM 应用，这里刷新本会话 emulator 的调色板并重绘（不清屏）。
    LaunchedEffect(ts.id, schemeId) {
        ts.session.emulator?.mColors?.reset()
        view.onScreenUpdated()
    }
    // 缩放提示 2 秒后消失（每次新的缩放会重置计时）。
    LaunchedEffect(zoomHintSp) {
        if (zoomHintSp != null) {
            delay(2000)
            zoomHintSp = null
        }
    }

    Scaffold(
        topBar = {
            // 双行顶栏：主标题（会话名）+ 细小副标题（user@host · 协议 · 延迟）。
            // 连接信息收进顶栏，不再单独占用终端区域。
            TerminalTopBar(
                title = title,
                host = "${ts.host.username}@${ts.host.host}",
                useMosh = ts.host.useMosh,
                alive = alive,
                latencyMs = latency,
                showLatency = !ts.host.useMosh,
                fontSizeSp = fontSizeSp,
                extraKeysVisible = extraKeysVisible,
                onFontSize = onFontSize,
                onToggleExtraKeys = onToggleExtraKeys,
                onSetTitle = { titleDialog = SessionTitleKind.TITLE },
                onSetPrefix = { titleDialog = SessionTitleKind.PREFIX },
                onShowKeyboard = { controller.showKeyboard() },
                onClose = onClose,
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // 消费 Scaffold 已给的系统栏 insets，再叠加 imePadding：
                // 键盘弹起时 ExtraKeysRow 精确浮在键盘上方，收起时贴导航栏，无重复留白。
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                AndroidView(
                    factory = { view },
                    modifier = Modifier.fillMaxSize(),
                )
                // 缩放提示浮层：字号 + 百分比，非默认给「恢复默认」。
                zoomHintSp?.let { sp ->
                    ZoomHint(
                        sp = sp,
                        onResetDefault = { onFontSize(TerminalController.DEFAULT_FONT_SIZE_SP) },
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
                    )
                }
            }

            if (!alive) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(stringResource(R.string.session_ended), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = onReconnect) {
                                Icon(Icons.Filled.Refresh, contentDescription = null)
                                Text("  " + stringResource(R.string.reconnect))
                            }
                            TextButton(onClick = onClose) {
                                Icon(Icons.Filled.Close, contentDescription = null)
                                Text("  " + stringResource(R.string.action_close))
                            }
                        }
                    }
                }
            }

            if (extraKeysVisible) {
                ExtraKeys(
                    rows = DEFAULT_EXTRA_KEYS,
                    ctrlOn = ctrlOn,
                    altOn = altOn,
                    onSeq = { seq -> ts.session.write(seq) },
                    onToggleCtrl = { ctrlOn = !ctrlOn; controller.ctrlActive = ctrlOn },
                    onToggleAlt = { altOn = !altOn; controller.altActive = altOn },
                    onAction = { id -> if (id == "composer") showComposer = true },
                )
            }
        }
    }

    if (showComposer) {
        TextBlockComposer(
            value = composerText,
            onValueChange = { composerText = it },
            onDismiss = { showComposer = false },
            onSend = { text, appendEnter ->
                composerText = ""      // 发送后清空草稿
                showComposer = false
                controller.showKeyboard()
                scope.launch {
                    if (text.isNotEmpty()) ts.session.write(text)
                    // 正文与回车分两次写、中间隔一个极小延时，让 CR 作为独立按键(单独一次 read)到达；
                    // 否则「正文+尾部 CR」会被 raw 模式 TUI(如 claude / vim 插入态)判为粘贴，只插入换行而不提交。
                    if (appendEnter) {
                        if (text.isNotEmpty()) delay(40)
                        ts.session.write("\r")
                    }
                }
            },
        )
    }

    when (titleDialog) {
        SessionTitleKind.TITLE -> SessionTitleDialog(
            dialogTitle = stringResource(R.string.session_set_title),
            hint = stringResource(R.string.session_title_hint),
            initial = ts.customTitle.value ?: "",
            onConfirm = { ts.setCustomTitle(it); titleDialog = null },
            onDismiss = { titleDialog = null },
        )
        SessionTitleKind.PREFIX -> SessionTitleDialog(
            dialogTitle = stringResource(R.string.session_set_prefix),
            hint = stringResource(R.string.session_prefix_hint),
            initial = ts.titlePrefix.value ?: "",
            onConfirm = { ts.setTitlePrefix(it); titleDialog = null },
            onDismiss = { titleDialog = null },
        )
        null -> {}
    }
}

/** 字间距倍数（1.0=正常）→ Android Paint 的 letterSpacing（em）。±0.1 倍 ≈ ±0.05em，微调而不过火。 */
fun letterSpacingEm(mul: Float): Float = (mul - 1f) * 0.5f

/** 字号显示：整数省略小数（11），半档保留一位（11.5）。 */
fun fmtFontSize(sp: Float): String =
    if (sp % 1f == 0f) sp.toInt().toString() else String.format("%.1f", sp)

/**
 * 终端双行顶栏：返回 · 主标题（会话名）+ 副标题（user@host · 协议 · 延迟）· 文本段入口。
 * 连接信息收进副标题，弱化色、等宽、单行省略，不占用终端渲染区域。延迟仅 SSH 实时探测。
 */
@Composable
private fun TerminalTopBar(
    title: String,
    host: String,
    useMosh: Boolean,
    alive: Boolean,
    latencyMs: Int?,
    showLatency: Boolean,
    fontSizeSp: Float,
    extraKeysVisible: Boolean,
    onFontSize: (Float) -> Unit,
    onToggleExtraKeys: () -> Unit,
    onSetTitle: () -> Unit,
    onSetPrefix: () -> Unit,
    onShowKeyboard: () -> Unit,
    onClose: () -> Unit,
    onBack: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(54.dp)
                .padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        host,
                        fontFamily = MokeMono,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // 协议徽标：与连接列表一致，mosh 用强调色高亮标识。
                    ProtocolBadge(useMosh)
                    when {
                        !alive -> Text("· " + stringResource(R.string.offline), fontFamily = MokeMono, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        latencyMs != null -> Text(
                            "· $latencyMs ms",
                            fontFamily = MokeMono,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = latencyColor(latencyMs),
                            maxLines = 1,
                        )
                        showLatency -> Text("· …", fontFamily = MokeMono, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        else -> {}
                    }
                }
            }
            // 右上角折叠菜单：底部快捷键显隐 · 字号 ±0.5 · 恢复默认字号。
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // offset 把菜单右缘推到贴近屏幕右侧（抵消锚点内边距造成的缝隙）。
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    offset = androidx.compose.ui.unit.DpOffset(x = 8.dp, y = 0.dp),
                ) {
                    // 菜单文字统一 bodyMedium、图标 20dp，避免偏大。
                    // 会话标题：自定义标题（最高优先级）/ 标题前缀（叠加在动态标题前）。
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.session_set_title), style = MaterialTheme.typography.bodyMedium) },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        onClick = { menuOpen = false; onSetTitle() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.session_set_prefix), style = MaterialTheme.typography.bodyMedium) },
                        leadingIcon = { Icon(Icons.Filled.Label, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        onClick = { menuOpen = false; onSetPrefix() },
                    )
                    HorizontalDivider()
                    // 字号步进（点 ± 不关闭菜单，便于连续调整）。
                    Row(
                        modifier = Modifier.padding(start = 16.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.font_size), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                        IconButton(onClick = { onFontSize(fontSizeSp - 0.5f) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.decrease), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                        Text(fmtFontSize(fontSizeSp), fontFamily = MokeMono, style = MaterialTheme.typography.bodyMedium, maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.width(44.dp))
                        IconButton(onClick = { onFontSize(fontSizeSp + 0.5f) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.increase), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.reset_font_size), style = MaterialTheme.typography.bodyMedium) },
                        leadingIcon = { Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        onClick = { menuOpen = false; onFontSize(TerminalController.DEFAULT_FONT_SIZE_SP) },
                    )
                    HorizontalDivider()
                    // 弹出软键盘。
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.show_keyboard), style = MaterialTheme.typography.bodyMedium) },
                        leadingIcon = { Icon(Icons.Filled.Keyboard, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        onClick = { menuOpen = false; onShowKeyboard() },
                    )
                    // 显示 / 隐藏底部快捷键条（双箭头表意“底部工具条上/下”）。
                    DropdownMenuItem(
                        text = { Text(if (extraKeysVisible) stringResource(R.string.hide_extra_keys) else stringResource(R.string.show_extra_keys), style = MaterialTheme.typography.bodyMedium) },
                        leadingIcon = {
                            Icon(
                                if (extraKeysVisible) Icons.Filled.KeyboardDoubleArrowDown else Icons.Filled.KeyboardDoubleArrowUp,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        onClick = { menuOpen = false; onToggleExtraKeys() },
                    )
                    HorizontalDivider()
                    // 关闭连接：结束会话并返回。
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.close_connection), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp)) },
                        onClick = { menuOpen = false; onClose() },
                    )
                }
            }
        }
    }
}

@Composable
fun latencyColor(ms: Int): androidx.compose.ui.graphics.Color = when {
    ms < 120 -> MaterialTheme.colorScheme.primary
    ms < 300 -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.error
}

/** 捏合缩放提示：字号 sp + 相对默认的百分比；非默认时提供「恢复默认」。 */
@Composable
private fun ZoomHint(sp: Float, onResetDefault: () -> Unit, modifier: Modifier = Modifier) {
    val pct = Math.round(sp * 100 / TerminalController.DEFAULT_FONT_SIZE_SP)
    Surface(
        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shape = RoundedCornerShape(20.dp),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.zoom_hint, fmtFontSize(sp), pct), fontFamily = MokeMono, fontWeight = FontWeight.Medium)
            if (sp != TerminalController.DEFAULT_FONT_SIZE_SP) {
                TextButton(onClick = onResetDefault) {
                    Text(stringResource(R.string.reset_default), color = MaterialTheme.colorScheme.inversePrimary)
                }
            }
        }
    }
}
