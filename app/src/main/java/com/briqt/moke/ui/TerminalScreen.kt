package com.briqt.moke.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.briqt.moke.terminal.TermSession
import com.briqt.moke.terminal.TerminalController
import com.briqt.moke.ui.theme.MokeMono
import com.termux.view.TerminalView
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    ts: TermSession,
    primaryFontId: String,
    fallbackFontId: String,
    fontSizeSp: Int,
    lineSpacing: Float,
    letterSpacing: Float,
    cursorStyle: Int,
    cursorBlink: Boolean,
    schemeId: String,
    resolveTypeface: (String, String) -> android.graphics.Typeface,
    onBack: () -> Unit,
    onReconnect: () -> Unit,
    onClose: () -> Unit,
    onFontSize: (Int) -> Unit,
) {
    val context = LocalContext.current
    val title by ts.title.collectAsState()
    val alive by ts.alive.collectAsState()
    val latency by ts.latency.collectAsState()
    var ctrlOn by remember(ts.id) { mutableStateOf(false) }
    var altOn by remember(ts.id) { mutableStateOf(false) }

    var showComposer by remember(ts.id) { mutableStateOf(false) }
    // 文本段草稿：提升到此，关闭 sheet 保留、发送后清空。
    var composerText by remember(ts.id) { mutableStateOf("") }
    // 捏合缩放提示（持有当前 sp，非空即显示；2 秒后自动消失）。
    var zoomHintSp by remember(ts.id) { mutableStateOf<Int?>(null) }

    val controller = ts.controller
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
        // 注意顺序：先 setTextSize 创建 renderer，再 setTypeface（其读取 mRenderer 不判空）。
        val px = (fontSizeSp * context.resources.displayMetrics.density).toInt()
        view.setTextSize(px)
        view.setTypeface(resolveTypeface(primaryFontId, fallbackFontId))
        view.setFontSpacing(lineSpacing, letterSpacingEm(letterSpacing))
        view.attachSession(ts.session)
        onDispose {
            // 仅解绑 View——会话保持存活以支持多会话跨页；真正结束由会话列表关闭或传输结束触发。
            if (controller.view === view) {
                controller.view = null
                controller.onFontSizeSp = null
            }
        }
    }

    // 热切换：设置变更时对当前活动终端即时生效。
    LaunchedEffect(ts.id, primaryFontId, fallbackFontId) {
        view.setTypeface(resolveTypeface(primaryFontId, fallbackFontId))
    }
    LaunchedEffect(ts.id, fontSizeSp) {
        val px = (fontSizeSp * context.resources.displayMetrics.density).toInt()
        view.setTextSize(px)
        controller.fontSizeSp = fontSizeSp
    }
    LaunchedEffect(ts.id, lineSpacing, letterSpacing) {
        view.setFontSpacing(lineSpacing, letterSpacingEm(letterSpacing))
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
            TopAppBar(
                title = { Text(title, maxLines = 1, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 文本段输入入口（点击终端本身即可唤起软键盘，故不再放键盘按钮）。
                    IconButton(onClick = { showComposer = true }) {
                        Icon(Icons.Filled.EditNote, contentDescription = "文本段输入")
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // 消费 Scaffold 已给的系统栏 insets，再叠加 imePadding：
                // 键盘弹起时 ExtraKeysRow 精确浮在键盘上方，收起时贴导航栏，无重复留白。
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            TerminalStatusBar(ts = ts, alive = alive, latencyMs = latency)
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
                        Text("会话已结束", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = onReconnect) {
                                Icon(Icons.Filled.Refresh, contentDescription = null)
                                Text("  重新连接")
                            }
                            TextButton(onClick = onClose) {
                                Icon(Icons.Filled.Close, contentDescription = null)
                                Text("  关闭")
                            }
                        }
                    }
                }
            }

            ExtraKeys(
                rows = DEFAULT_EXTRA_KEYS,
                ctrlOn = ctrlOn,
                altOn = altOn,
                onSeq = { seq -> ts.session.write(seq) },
                onToggleCtrl = { ctrlOn = !ctrlOn; controller.ctrlActive = ctrlOn },
                onToggleAlt = { altOn = !altOn; controller.altActive = altOn },
                onAction = { },
            )
        }
    }

    if (showComposer) {
        TextBlockComposer(
            value = composerText,
            onValueChange = { composerText = it },
            onDismiss = { showComposer = false },
            onSend = { text, appendEnter ->
                if (text.isNotEmpty() || appendEnter) {
                    ts.session.write(if (appendEnter) text + "\r" else text)
                }
                composerText = ""      // 发送后清空草稿
                showComposer = false
                controller.showKeyboard()
            },
        )
    }
}

/** 字间距倍数（1.0=正常）→ Android Paint 的 letterSpacing（em）。±0.1 倍 ≈ ±0.05em，微调而不过火。 */
fun letterSpacingEm(mul: Float): Float = (mul - 1f) * 0.5f

/**
 * 终端顶部状态条（克制）：协议 + user@host:port（左）· 网络往返延迟（右，SSH 实时探测）。
 * 单行、等宽、弱化色，与连接卡副标题风格一致，不喧宾夺主。
 */
@Composable
private fun TerminalStatusBar(ts: TermSession, alive: Boolean, latencyMs: Int?) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "${ts.host.protocol} · ${ts.host.username}@${ts.host.host}:${ts.host.port}",
                fontFamily = MokeMono,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            when {
                !alive -> Text("离线", fontFamily = MokeMono, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                latencyMs != null -> Text(
                    "$latencyMs ms",
                    fontFamily = MokeMono,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = latencyColor(latencyMs),
                )
                ts.host.useMosh -> {} // mosh 暂不显示延迟
                else -> Text("…", fontFamily = MokeMono, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun latencyColor(ms: Int): androidx.compose.ui.graphics.Color = when {
    ms < 120 -> MaterialTheme.colorScheme.primary
    ms < 300 -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.error
}

/** 捏合缩放提示：字号 sp + 相对默认(14sp)的百分比；非默认时提供「恢复默认」。 */
@Composable
private fun ZoomHint(sp: Int, onResetDefault: () -> Unit, modifier: Modifier = Modifier) {
    val pct = (sp * 100) / TerminalController.DEFAULT_FONT_SIZE_SP
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
            Text("字号 ${sp}sp · $pct%", fontFamily = MokeMono, fontWeight = FontWeight.Medium)
            if (sp != TerminalController.DEFAULT_FONT_SIZE_SP) {
                TextButton(onClick = onResetDefault) {
                    Text("恢复默认", color = MaterialTheme.colorScheme.inversePrimary)
                }
            }
        }
    }
}
