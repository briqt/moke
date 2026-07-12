package com.briqt.moke.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.PaddingValues
import com.briqt.moke.data.Host
import com.briqt.moke.data.HostSort
import com.briqt.moke.terminal.TermSession
import com.briqt.moke.ui.theme.MokeMono

/**
 * 主界面：底部导航「连接 · 会话 · 设置」三分区。终端本体是独立全屏页（不带底栏），
 * 由 MokeApp 在此之上导航打开。会话对象常驻 ViewModel，切分区不销毁。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    tab: HomeTab,
    onTab: (HomeTab) -> Unit,
    hosts: List<Host>,
    sessions: List<TermSession>,
    sort: HostSort,
    onSort: (HostSort) -> Unit,
    onAddHost: () -> Unit,
    onEditHost: (Host) -> Unit,
    onDuplicateHost: (Host) -> Unit,
    onDeleteHost: (Host) -> Unit,
    onConnectHost: (Host) -> Unit,
    onOpenSession: (String) -> Unit,
    onCloseSession: (String) -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (tab) {
                            HomeTab.Connections -> "Moke · 墨客"
                            HomeTab.Sessions -> "会话"
                            HomeTab.Settings -> "设置"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                // 略压高度（默认 64 → 56）扩大可见区。
                expandedHeight = 56.dp,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        bottomBar = {
            // 略压高度（默认 80 → 72）扩大可见区，图标+文字不变。
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.height(72.dp),
            ) {
                NavItem(tab, HomeTab.Connections, Icons.Filled.Dns, "连接", null, onTab)
                NavItem(tab, HomeTab.Sessions, Icons.Filled.Terminal, "会话", sessions.size.takeIf { it > 0 }, onTab)
                NavItem(tab, HomeTab.Settings, Icons.Filled.Settings, "设置", null, onTab)
            }
        },
        floatingActionButton = {
            if (tab == HomeTab.Connections) {
                FloatingActionButton(onClick = onAddHost) {
                    Icon(Icons.Filled.Add, contentDescription = "添加主机")
                }
            }
        },
    ) { padding ->
        when (tab) {
            HomeTab.Connections -> ConnectionsContent(padding, hosts, sort, onSort, onEditHost, onDuplicateHost, onDeleteHost, onConnectHost)
            HomeTab.Sessions -> SessionsContent(padding, sessions, onOpenSession, onCloseSession)
            HomeTab.Settings -> SettingsMenuContent(padding, onOpenAppearance, onOpenAbout)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(
    current: HomeTab,
    target: HomeTab,
    icon: ImageVector,
    label: String,
    count: Int?,
    onTab: (HomeTab) -> Unit,
) {
    NavigationBarItem(
        selected = current == target,
        onClick = { onTab(target) },
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(if (count != null) "$label · $count" else label) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    )
}

// ---------- 连接 ----------

@Composable
private fun ConnectionsContent(
    padding: PaddingValues,
    hosts: List<Host>,
    sort: HostSort,
    onSort: (HostSort) -> Unit,
    onEdit: (Host) -> Unit,
    onDuplicate: (Host) -> Unit,
    onDelete: (Host) -> Unit,
    onConnect: (Host) -> Unit,
) {
    if (hosts.isEmpty()) {
        EmptyState(
            padding = padding,
            icon = Icons.Filled.Dns,
            title = "还没有主机",
            hint = "点右下角 + 添加一台 SSH 服务器",
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        // 排序选择（分组 / 名称 / 最近连接）——仅多于 1 台时显示
        if (hosts.size > 1) {
            item(key = "sort") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("排序", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HostSort.entries.forEach { s ->
                        FilterChip(selected = sort == s, onClick = { onSort(s) }, label = { Text(s.label) })
                    }
                }
            }
        }

        when (sort) {
            HostSort.GROUP -> {
                // 未分组排在最后，其余按组名字母序；组内按名称
                val groups = hosts.groupBy { it.group.ifBlank { UNGROUPED } }
                    .toSortedMap(compareBy({ it == UNGROUPED }, { it }))
                groups.forEach { (g, list) ->
                    item(key = "hdr_$g") { GroupHeader(g) }
                    items(list.sortedBy { it.displayName.lowercase() }, key = { it.id }) { host ->
                        HostCard(host, { onConnect(host) }, { onEdit(host) }, { onDuplicate(host) }, { onDelete(host) })
                    }
                }
            }
            else -> {
                val sorted = if (sort == HostSort.NAME) {
                    hosts.sortedBy { it.displayName.lowercase() }
                } else {
                    hosts.sortedByDescending { it.lastConnectedAt }
                }
                items(sorted, key = { it.id }) { host ->
                    HostCard(host, { onConnect(host) }, { onEdit(host) }, { onDuplicate(host) }, { onDelete(host) })
                }
            }
        }
    }
}

private const val UNGROUPED = "未分组"

@Composable
private fun GroupHeader(name: String) {
    Text(
        name,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 6.dp, top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun HostCard(host: Host, onConnect: () -> Unit, onEdit: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onConnect),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(host.displayName, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    ProtocolBadge(host.useMosh)
                }
                Text(
                    "${host.username}@${host.host}:${host.port}" +
                        (if (host.group.isNotBlank()) "  · ${host.group}" else ""),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = MokeMono,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("编辑") },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = { menuOpen = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text("创建副本") },
                        leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                        onClick = { menuOpen = false; onDuplicate() },
                    )
                    DropdownMenuItem(
                        text = { Text("复制连接命令") },
                        leadingIcon = { Icon(Icons.Filled.Terminal, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("moke", host.connectCommand))
                            Toast.makeText(context, "已复制：${host.connectCommand}", Toast.LENGTH_SHORT).show()
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}

/** 协议徽标：mosh 用主色、SSH 用中性色。 */
@Composable
private fun ProtocolBadge(mosh: Boolean) {
    val color = if (mosh) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(5.dp)) {
        Text(
            if (mosh) "mosh" else "SSH",
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = MokeMono,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

// ---------- 会话 ----------

@Composable
private fun SessionsContent(
    padding: PaddingValues,
    sessions: List<TermSession>,
    onOpen: (String) -> Unit,
    onClose: (String) -> Unit,
) {
    if (sessions.isEmpty()) {
        EmptyState(
            padding = padding,
            icon = Icons.Filled.Terminal,
            title = "暂无会话",
            hint = "在「连接」里选择一台主机开始",
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        items(sessions, key = { it.id }) { ts ->
            SessionCard(ts, onOpen = { onOpen(ts.id) }, onClose = { onClose(ts.id) })
        }
    }
}

@Composable
private fun SessionCard(ts: TermSession, onOpen: () -> Unit, onClose: () -> Unit) {
    val title by ts.title.collectAsState()
    val alive by ts.alive.collectAsState()
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(if (alive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
            )
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    "${ts.host.username}@${ts.host.host}:${ts.host.port}" + if (!alive) "  · 已结束" else "",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = MokeMono,
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "关闭会话", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---------- 设置（菜单，二级页承载具体项） ----------

@Composable
private fun SettingsMenuContent(padding: PaddingValues, onOpenAppearance: () -> Unit, onOpenAbout: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MenuCard(Icons.Filled.Palette, "外观", "字体 · 配色 · 字号 · 光标", onOpenAppearance)
        MenuCard(Icons.Filled.Info, "关于", "版本 · 开源许可", onOpenAbout)
    }
}

@Composable
private fun MenuCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
        }
    }
}

// ---------- 通用空态 ----------

@Composable
private fun EmptyState(padding: PaddingValues, icon: ImageVector, title: String, hint: String) {
    Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(44.dp))
            Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
