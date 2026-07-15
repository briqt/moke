package com.briqt.moke.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import com.briqt.moke.LocaleManager
import com.briqt.moke.R
import com.briqt.moke.data.GroupBy
import com.briqt.moke.data.Host
import com.briqt.moke.data.SortBy
import com.briqt.moke.terminal.TermSession
import com.briqt.moke.ui.theme.MokeDimens
import com.briqt.moke.ui.theme.MokeMono
import com.briqt.moke.ui.theme.MokeShapes

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
    hostGroupOrder: List<String>,
    hostCollapsedGroups: Set<String>,
    onReorderHostGroups: (List<String>) -> Unit,
    onToggleHostGroupCollapse: (String) -> Unit,
    sessionGroupBy: GroupBy,
    sessionSortBy: SortBy,
    onSessionGroupBy: (GroupBy) -> Unit,
    onSessionSortBy: (SortBy) -> Unit,
    sessionGroupOrder: List<String>,
    sessionCollapsedGroups: Set<String>,
    onReorderSessionGroups: (List<String>) -> Unit,
    onToggleSessionGroupCollapse: (String) -> Unit,
    onAddHost: () -> Unit,
    onEditHost: (Host) -> Unit,
    onDuplicateHost: (Host) -> Unit,
    onDeleteHost: (Host) -> Unit,
    onConnectHost: (Host) -> Unit,
    onReorderHosts: (List<Host>) -> Unit,
    onOpenSession: (String) -> Unit,
    onCloseSession: (String) -> Unit,
    onDuplicateSession: (String) -> Unit,
    onReorderSessions: (List<String>) -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (tab) {
                            HomeTab.Connections -> stringResource(R.string.app_title)
                            HomeTab.Sessions -> stringResource(R.string.nav_sessions)
                            HomeTab.Settings -> stringResource(R.string.nav_settings)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                // 会话页把分组/排序收进标题栏右侧（两个紧凑胶囊）；连接页固定按项目分组、无排序，故不放按钮。
                actions = {
                    when (tab) {
                        HomeTab.Connections -> {}
                        HomeTab.Sessions -> if (sessions.size > 1) GroupSortActions(
                            groupBy = sessionGroupBy,
                            groupOptions = listOf(GroupBy.NONE, GroupBy.HOST, GroupBy.PROJECT),
                            onGroupBy = onSessionGroupBy,
                            sortBy = sessionSortBy,
                            sortOptions = listOf(SortBy.CREATED, SortBy.UPDATED, SortBy.MANUAL),
                            onSortBy = onSessionSortBy,
                        )
                        HomeTab.Settings -> {}
                    }
                },
                // 略压高度（默认 64 → 49，较原 56 再收约 1/8）扩大可见区；标题在栏内垂直居中，收窄自然把上/下间距平分。
                expandedHeight = MokeDimens.topBarHeight,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        bottomBar = {
            // 略压高度（默认 80 → 72）扩大可见区。在系统底部 inset 之上再叠 6dp 顶部内边距，
            // 让图标+文字整体下移一点、不贴底栏上边界。
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.height(72.dp),
                windowInsets = NavigationBarDefaults.windowInsets.add(WindowInsets(top = 6.dp)),
            ) {
                NavItem(tab, HomeTab.Connections, Icons.Filled.Dns, stringResource(R.string.nav_connections), null, onTab)
                NavItem(tab, HomeTab.Sessions, Icons.Filled.Terminal, stringResource(R.string.nav_sessions), sessions.size.takeIf { it > 0 }, onTab)
                NavItem(tab, HomeTab.Settings, Icons.Filled.Settings, stringResource(R.string.nav_settings), null, onTab)
            }
        },
        floatingActionButton = {
            if (tab == HomeTab.Connections) {
                FloatingActionButton(onClick = onAddHost) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_host))
                }
            }
        },
    ) { padding ->
        when (tab) {
            HomeTab.Connections -> ConnectionsContent(padding, hosts, hostGroupOrder, hostCollapsedGroups, onToggleHostGroupCollapse, onReorderHostGroups, onReorderHosts, onEditHost, onDuplicateHost, onDeleteHost, onConnectHost)
            HomeTab.Sessions -> SessionsContent(padding, sessions, sessionGroupBy, sessionSortBy, onSessionGroupBy, onSessionSortBy, sessionGroupOrder, sessionCollapsedGroups, onToggleSessionGroupCollapse, onReorderSessionGroups, onOpenSession, onCloseSession, onDuplicateSession, onReorderSessions)
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
    val selected = current == target
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    // 图标+文字整体塞进 icon 槽，用一个很小的 2dp 间隔（取代 M3 默认较大的图标-文字间距）；label 留空。
    // indicator 透明 → 无选中背景块；颜色显式按选中态给，选中 primary、未选中中性色。
    NavigationBarItem(
        selected = selected,
        onClick = { onTab(target) },
        icon = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(2.dp))
                Text(
                    if (count != null) "$label · $count" else label,
                    color = color,
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        },
        colors = NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent),
    )
}

// ---------- 连接 ----------

@Composable
private fun ConnectionsContent(
    padding: PaddingValues,
    hosts: List<Host>,
    groupOrder: List<String>,
    collapsed: Set<String>,
    onToggleCollapse: (String) -> Unit,
    onReorderGroups: (List<String>) -> Unit,
    onReorderHosts: (List<Host>) -> Unit,
    onEdit: (Host) -> Unit,
    onDuplicate: (Host) -> Unit,
    onDelete: (Host) -> Unit,
    onConnect: (Host) -> Unit,
) {
    if (hosts.isEmpty()) {
        EmptyState(
            padding = padding,
            icon = Icons.Filled.Dns,
            title = stringResource(R.string.empty_hosts_title),
            hint = stringResource(R.string.empty_hosts_hint),
        )
        return
    }
    fun keyOf(h: Host) = h.group.ifBlank { UNGROUPED_KEY }
    // 固定按项目分组。分组显示顺序=持久化顺序（过滤到当前存在的组）+ 首次出现的新组补末尾。
    val present = hosts.map { keyOf(it) }.distinct()
    val orderedKeys = groupOrder.filter { it in present } + present.filter { it !in groupOrder }
    val hasNamedGroup = present.any { it != UNGROUPED_KEY }

    Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
        if (!hasNamedGroup) {
            // 一台主机都没设分组 → 退化成无分组头的平铺列表，仍可长按拖动重排。
            ReorderableColumn(
                items = hosts,
                key = { it.id },
                onReorder = onReorderHosts,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) { host, dragging, handle ->
                HostCard(host, { onConnect(host) }, { onEdit(host) }, { onDuplicate(host) }, { onDelete(host) }, dragHandle = handle, dragging = dragging)
            }
        } else {
            val groups = orderedKeys.map { k -> ReorderGroup(k, hosts.filter { keyOf(it) == k }) }
            GroupedReorderableList(
                groups = groups,
                itemKey = { it.id },
                collapsed = collapsed,
                onReorderGroups = onReorderGroups,
                onReorderItems = { groupKey, newItems ->
                    // 组内新序映射回扁平主机列表：其余主机位置不动，本组位置按新序回填。
                    val iter = newItems.iterator()
                    onReorderHosts(hosts.map { if (keyOf(it) == groupKey) iter.next() else it })
                },
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 12.dp),
                header = { key, dragging, handle ->
                    GroupHeaderRow(
                        name = if (key == UNGROUPED_KEY) stringResource(R.string.ungrouped) else key,
                        count = groups.firstOrNull { it.key == key }?.items?.size ?: 0,
                        collapsed = key in collapsed,
                        onToggle = { onToggleCollapse(key) },
                        dragHandle = handle,
                        dragging = dragging,
                    )
                },
            ) { host, dragging, handle ->
                // 已按项目分组，分组头展示组名 → 卡片副标题尾部不再重复。
                HostCard(host, { onConnect(host) }, { onEdit(host) }, { onDuplicate(host) }, { onDelete(host) }, showGroup = false, dragHandle = handle, dragging = dragging)
            }
        }
    }
}

// 未分组分桶的哨兵键（不直接展示，展示时本地化为 R.string.ungrouped）。
private const val UNGROUPED_KEY = " __ungrouped__"

/**
 * 分组 / 排序控制（标题栏右侧两个紧凑胶囊：[▤ 值 ▾] [↕ 值 ▾]），连接页与会话页共用。
 * 两者正交；每页只传入适用维度子集。图标区分分组/排序，胶囊上显示当前值。
 */
@Composable
private fun GroupSortActions(
    groupBy: GroupBy,
    groupOptions: List<GroupBy>,
    onGroupBy: (GroupBy) -> Unit,
    sortBy: SortBy,
    sortOptions: List<SortBy>,
    onSortBy: (SortBy) -> Unit,
) {
    Row(
        modifier = Modifier.padding(end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PickerChip(
            label = stringResource(R.string.sort_group),
            leadingIcon = Icons.Filled.Folder,
            valueText = stringResource(groupBy.labelRes),
            options = groupOptions.map { it to stringResource(it.labelRes) },
            selected = groupBy,
            onSelect = onGroupBy,
            menuHeader = stringResource(R.string.group_menu_hint),
        )
        PickerChip(
            label = stringResource(R.string.sort_label),
            leadingIcon = Icons.AutoMirrored.Filled.Sort,
            valueText = stringResource(sortBy.labelRes),
            options = sortOptions.map { it to stringResource(it.labelRes) },
            selected = sortBy,
            onSelect = onSortBy,
            menuHeader = stringResource(R.string.sort_menu_hint),
        )
    }
}

/** 通用下拉胶囊：显示「图标/标签 + 当前值 + ▾」，点开列出候选、当前项打勾。[leadingIcon] 非空则用图标取代文字标签（标签作无障碍描述）。 */
@Composable
private fun <T> PickerChip(
    label: String,
    valueText: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    menuHeader: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        // 标题栏里的轻量下拉：透明底、紧凑，读作「图标 值 ▾」的内联控件，不做成突兀的实心块。
        Surface(
            onClick = { open = true },
            shape = MokeShapes.control,
            color = androidx.compose.ui.graphics.Color.Transparent,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (leadingIcon != null) {
                    Icon(leadingIcon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                } else {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(valueText, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // 顶部小字提示当前维度（如「选择分组」/「选择排序」），点明这个下拉是干嘛的（无分隔线，避免小菜单里太重）。
            if (menuHeader != null) {
                Text(
                    menuHeader,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 4.dp),
                )
            }
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = { onSelect(value); open = false },
                    trailingIcon = if (value == selected) {
                        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else null,
                )
            }
        }
    }
}

/** 连接页 / 会话页共用的可折叠 + 可拖动的分组头：[chevron] 组名 计数 …… [拖动手柄]。点行体折叠/展开，长按手柄调分组顺序。 */
@Composable
private fun GroupHeaderRow(
    name: String,
    count: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
    dragHandle: Modifier,
    dragging: Boolean,
) {
    Surface(
        onClick = onToggle,
        shape = MokeShapes.control,
        color = if (dragging) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                if (collapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(if (collapsed) R.string.group_expand else R.string.group_collapse),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Box(modifier = dragHandle) {
                Icon(
                    Icons.Filled.DragHandle,
                    contentDescription = stringResource(R.string.drag_to_reorder),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun HostCard(
    host: Host,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    // 是否在副标题尾部展示分组名：按项目分组时分组头已展示、置 false 免重复；平铺/不分组时置 true。
    showGroup: Boolean = true,
    dragHandle: Modifier? = null,
    dragging: Boolean = false,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onConnect),
        colors = CardDefaults.cardColors(
            containerColor = if (dragging) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface,
        ),
        elevation = if (dragging) CardDefaults.cardElevation(defaultElevation = 6.dp) else CardDefaults.cardElevation(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = if (dragHandle != null) 4.dp else 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (dragHandle != null) {
                Box(modifier = dragHandle) {
                    Icon(
                        Icons.Filled.DragHandle,
                        contentDescription = stringResource(R.string.drag_to_reorder),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(host.displayName, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    ProtocolBadge(host.useMosh)
                }
                Text(
                    "${host.username}@${host.host}:${host.port}" +
                        (if (host.group.isNotBlank() && showGroup) "  · ${host.group}" else ""),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = MokeMono,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.host_edit)) },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = { menuOpen = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.host_duplicate)) },
                        leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                        onClick = { menuOpen = false; onDuplicate() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.host_copy_command)) },
                        leadingIcon = { Icon(Icons.Filled.Terminal, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("moke", host.connectCommand))
                            Toast.makeText(context, context.getString(R.string.host_copied, host.connectCommand), Toast.LENGTH_SHORT).show()
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}

/** 协议徽标：mosh 用主色强调、SSH 用中性色。背景块紧贴文字（去字体额外行距，收紧内边距），供连接列表 / 会话列表 / 终端顶栏共用。 */
@Composable
fun ProtocolBadge(mosh: Boolean) {
    val color = if (mosh) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(color = color.copy(alpha = MokeDimens.badgeAlpha), shape = MokeShapes.pill) {
        Text(
            if (mosh) "mosh" else "SSH",
            color = color,
            fontSize = 10.sp,
            lineHeight = 10.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = MokeMono,
            maxLines = 1,
            // 去掉字体自带上下额外行距，让背景高度≈字形本身（约原 3/5）；水平内边距收窄（约原 4/5）。
            style = LocalTextStyle.current.copy(
                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                    alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                    trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.Both,
                ),
            ),
            modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp),
        )
    }
}

// ---------- 会话 ----------

@Composable
private fun SessionsContent(
    padding: PaddingValues,
    sessions: List<TermSession>,
    groupBy: GroupBy,
    sortBy: SortBy,
    onGroupBy: (GroupBy) -> Unit,
    onSortBy: (SortBy) -> Unit,
    groupOrder: List<String>,
    collapsed: Set<String>,
    onToggleCollapse: (String) -> Unit,
    onReorderGroups: (List<String>) -> Unit,
    onOpen: (String) -> Unit,
    onClose: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
) {
    if (sessions.isEmpty()) {
        EmptyState(
            padding = padding,
            icon = Icons.Filled.Terminal,
            title = stringResource(R.string.empty_sessions_title),
            hint = stringResource(R.string.empty_sessions_hint),
        )
        return
    }
    // 会话所属分组的键（按当前分组维度）。
    fun keyOf(ts: TermSession): String = when (groupBy) {
        GroupBy.PROJECT -> ts.host.group.ifBlank { UNGROUPED_KEY }
        GroupBy.HOST -> ts.host.displayName
        GroupBy.NONE -> ""
    }
    val manual = sortBy == SortBy.MANUAL
    val cmp = sessionComparator(sortBy)

    Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
        if (groupBy == GroupBy.NONE) {
            if (manual) {
                // 无分组 + 手动：整列长按拖动重排（仅内存顺序）。
                ReorderableColumn(
                    items = sessions,
                    key = { it.id },
                    onReorder = { list -> onReorder(list.map { it.id }) },
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 12.dp),
                ) { ts, dragging, handle ->
                    SessionCard(ts, onOpen = { onOpen(ts.id) }, onClose = { onClose(ts.id) }, onDuplicate = { onDuplicate(ts.id) }, dragHandle = handle, dragging = dragging)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 12.dp),
                ) {
                    items(sessions.sortedWith(cmp), key = { it.id }) { ts ->
                        SessionCard(ts, onOpen = { onOpen(ts.id) }, onClose = { onClose(ts.id) }, onDuplicate = { onDuplicate(ts.id) })
                    }
                }
            }
        } else {
            // 分组显示顺序：内存顺序（过滤到当前存在的组）+ 首次出现的新组补末尾。
            val present = sessions.map { keyOf(it) }.distinct()
            val orderedKeys = groupOrder.filter { it in present } + present.filter { it !in groupOrder }
            val groups = orderedKeys.map { k ->
                val items = sessions.filter { keyOf(it) == k }
                ReorderGroup(k, if (manual) items else items.sortedWith(cmp))
            }
            GroupedReorderableList(
                groups = groups,
                itemKey = { it.id },
                collapsed = collapsed,
                onReorderGroups = onReorderGroups,
                onReorderItems = { groupKey, newItems ->
                    // 组内新序映射回完整会话顺序（其他组位置不动），仅内存。
                    val iter = newItems.iterator()
                    onReorder(sessions.map { if (keyOf(it) == groupKey) iter.next() else it }.map { it.id })
                },
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 12.dp),
                header = { key, dragging, handle ->
                    GroupHeaderRow(
                        name = if (key == UNGROUPED_KEY) stringResource(R.string.ungrouped) else key,
                        count = groups.firstOrNull { it.key == key }?.items?.size ?: 0,
                        collapsed = key in collapsed,
                        onToggle = { onToggleCollapse(key) },
                        dragHandle = handle,
                        dragging = dragging,
                    )
                },
            ) { ts, dragging, handle ->
                // 手动排序时组内卡片可拖（接 handle）；创建/更新时间排序时按比较器排、卡片不可拖。
                SessionCard(
                    ts, onOpen = { onOpen(ts.id) }, onClose = { onClose(ts.id) }, onDuplicate = { onDuplicate(ts.id) },
                    dragHandle = if (manual) handle else null, dragging = dragging,
                )
            }
        }
    }
}

/** 会话组内排序比较器（CREATED=创建时间倒序、UPDATED=最后活动时间倒序；MANUAL 不走此处）。 */
private fun sessionComparator(sortBy: SortBy): Comparator<TermSession> = when (sortBy) {
    SortBy.UPDATED -> compareByDescending { it.lastActivityAt }
    else -> compareByDescending { it.startedAt }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionCard(
    ts: TermSession,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onDuplicate: () -> Unit,
    dragHandle: Modifier? = null,
    dragging: Boolean = false,
) {
    val title by ts.displayTitle.collectAsState()
    val alive by ts.alive.collectAsState()
    val latencyMs by ts.latency.collectAsState()
    // 长按普通区域直接打开「修改标题」（长按拖动手柄由手柄自身处理，是拖动而非改名）。
    var showTitleDialog by remember { mutableStateOf(false) }
    Box {
        Card(
            // 单击进入会话；长按普通区域打开「修改标题」。
            modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = { showTitleDialog = true }),
            colors = CardDefaults.cardColors(
                containerColor = if (dragging) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface,
            ),
            elevation = if (dragging) CardDefaults.cardElevation(defaultElevation = 6.dp) else CardDefaults.cardElevation(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = if (dragHandle != null) 4.dp else 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (dragHandle != null) {
                    Box(modifier = dragHandle) {
                        Icon(
                            Icons.Filled.DragHandle,
                            contentDescription = stringResource(R.string.drag_to_reorder),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                }
                // 双行布局：第1行动态标题，第2行 设备名 · 协议徽标 · 延迟/状态（不再单列 user@host）。
                Column(modifier = Modifier.weight(1f).padding(start = if (dragHandle != null) 4.dp else 0.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        // 第 2 行身份：设备名（连接名，未命名回落 user@host）。
                        Text(
                            ts.host.displayName,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        ProtocolBadge(ts.host.useMosh)
                        when {
                            !alive -> Text("· " + stringResource(R.string.session_ended_short), fontFamily = MokeMono, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            latencyMs != null -> Text("· $latencyMs ms", fontFamily = MokeMono, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = latencyColor(latencyMs!!), maxLines = 1)
                            !ts.host.useMosh -> Text("· …", fontFamily = MokeMono, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            else -> {}
                        }
                    }
                }
                // 复制：用同一主机再开一个独立会话（新连接，非克隆 live 状态）；沿用标题并加不重复标记。
                IconButton(onClick = onDuplicate) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.duplicate_session), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close_session), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (showTitleDialog) {
        SessionTitleDialog(
            dialogTitle = stringResource(R.string.session_set_title),
            hint = stringResource(R.string.session_title_hint),
            initial = ts.customTitle.value ?: "",
            onConfirm = { ts.setCustomTitle(it); showTitleDialog = false },
            onDismiss = { showTitleDialog = false },
        )
    }
}

// ---------- 设置（菜单，二级页承载具体项） ----------

@Composable
private fun SettingsMenuContent(padding: PaddingValues, onOpenAppearance: () -> Unit, onOpenAbout: () -> Unit) {
    val context = LocalContext.current
    var langDialog by remember { mutableStateOf(false) }
    val langTag = LocaleManager.currentTag(context)
    val langLabel = when (langTag) {
        LocaleManager.ZH -> stringResource(R.string.lang_zh)
        LocaleManager.EN -> stringResource(R.string.lang_en)
        else -> stringResource(R.string.lang_system)
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NavRow(Icons.Filled.Palette, stringResource(R.string.menu_appearance), stringResource(R.string.menu_appearance_sub), onOpenAppearance)
        NavRow(Icons.Filled.Language, stringResource(R.string.menu_language), langLabel, onClick = { langDialog = true })
        NavRow(Icons.Filled.Info, stringResource(R.string.menu_about), stringResource(R.string.menu_about_sub), onOpenAbout)
    }

    if (langDialog) {
        LanguageDialog(
            current = langTag,
            onDismiss = { langDialog = false },
            onPick = { tag ->
                langDialog = false
                if (tag != langTag) {
                    LocaleManager.setTag(context, tag)
                    (context as? Activity)?.recreate()   // 重建 Activity → attachBaseContext 重新包裹语言
                }
            },
        )
    }
}

/** 语言选择弹窗：跟随系统 / 中文 / English。 */
@Composable
private fun LanguageDialog(current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val options = listOf(
        LocaleManager.SYSTEM to stringResource(R.string.lang_system),
        LocaleManager.ZH to stringResource(R.string.lang_zh),
        LocaleManager.EN to stringResource(R.string.lang_en),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        // 标题收小（默认 headlineSmall 偏大、留白多），选项占满宽度、字号提到 bodyLarge，减少空旷感。
        title = { Text(stringResource(R.string.menu_language), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                options.forEach { (tag, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(MokeShapes.control).clickable { onPick(tag) }.padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = current == tag, onClick = { onPick(tag) })
                        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
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
