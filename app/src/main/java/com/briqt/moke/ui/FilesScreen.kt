package com.briqt.moke.ui

import android.content.ClipData
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.briqt.moke.R
import com.briqt.moke.data.FilesSort
import com.briqt.moke.net.NetworkRecovery
import com.briqt.moke.net.RecoveryKind
import com.briqt.moke.terminal.sftp.RemoteEntry
import com.briqt.moke.terminal.sftp.RemotePath
import com.briqt.moke.terminal.sftp.TransferState
import com.briqt.moke.terminal.sftp.TransferTask
import com.briqt.moke.ui.theme.MokeMono
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 远端文件页：面包屑 + 列表 + 传输队列条。
 *
 * 它永远从属于某台主机（从终端 ⋮ 或连接列表 ⋮ 进），不是一个全局分区——见
 * `设计/文件传输-SFTP.md` §5.1。[onSendToTerminal] 为空表示不是从会话进来的，隐藏"发到终端"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    state: FilesUiState,
    tasks: List<TransferTask>,
    sort: FilesSort,
    showHidden: Boolean,
    onNavigate: (String) -> Unit,
    onUp: () -> Unit,
    onRefresh: () -> Unit,
    onGoto: suspend (String) -> Boolean,
    onMkdir: suspend (String) -> Boolean,
    onSort: (FilesSort) -> Unit,
    onShowHidden: (Boolean) -> Unit,
    onUpload: (List<Uri>) -> Unit,
    overwrite: UploadConflict?,
    onOverwriteConfirm: () -> Unit,
    onOverwriteCancel: () -> Unit,
    onDownload: (RemoteEntry) -> Unit,
    onPickDownloadDir: () -> Unit,
    onSendToTerminal: ((String) -> Unit)?,
    onClearError: () -> Unit,
    onLoginNetwork: (com.briqt.moke.data.NetworkEntry) -> Unit = {},
    onTaskResume: (String) -> Unit,
    onTaskCancel: (String) -> Unit,
    onTaskRemove: (String) -> Unit,
    onClearFinished: () -> Unit,
    onBack: () -> Unit,
    onRecover: (NetworkRecovery) -> Unit = { recovery ->
        recovery.network?.takeIf { recovery.kind == RecoveryKind.LOGIN_NETWORK }?.let(onLoginNetwork)
    },
    onBeforePickUpload: () -> Boolean = { true },
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var mkdirOpen by remember { mutableStateOf(false) }
    var gotoOpen by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<RemoteEntry?>(null) }
    var showQueue by remember { mutableStateOf(false) }
    var uploadFabTop by remember { mutableStateOf<Float?>(null) }
    var entriesBottom by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    // Measure overlap after Scaffold insets and the transfer bar have been consumed.
    val tailPadding = if (state.canUpload) uploadFabTop?.let { top ->
        with(density) { (entriesBottom - top).coerceAtLeast(0f).toDp() } + 12.dp
    } ?: 0.dp else 0.dp

    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> onUpload(uris) }

    val visible = remember(state.entries, sort, showHidden) {
        sortEntries(state.entries, sort, showHidden)
    }
    val activeTasks = tasks.filter { it.active }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            state.host?.displayName?.ifBlank { stringResource(R.string.unnamed) }
                                ?: stringResource(R.string.files_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                        )
                        if (state.path.isNotBlank() || state.loading) {
                            Text(
                                state.path.ifBlank { stringResource(R.string.files_connecting) },
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = MokeMono,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.files_refresh))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.files_new_folder)) },
                                leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null, Modifier.size(20.dp)) },
                                onClick = { menuOpen = false; mkdirOpen = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.files_goto)) },
                                onClick = { menuOpen = false; gotoOpen = true },
                            )
                            if (state.terminalPath.isNotBlank()) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.files_from_terminal)) },
                                    leadingIcon = { Icon(Icons.Filled.Terminal, null, Modifier.size(20.dp)) },
                                    onClick = { menuOpen = false; onNavigate(state.terminalPath) },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.files_show_hidden)) },
                                trailingIcon = { if (showHidden) Icon(Icons.Filled.Close, null, Modifier.size(16.dp)) },
                                onClick = { menuOpen = false; onShowHidden(!showHidden) },
                            )
                            listOf(
                                FilesSort.NAME to R.string.files_sort_name,
                                FilesSort.TIME to R.string.files_sort_time,
                                FilesSort.SIZE to R.string.files_sort_size,
                            ).forEach { (s, label) ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(label),
                                            color = if (s == sort) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface,
                                        )
                                    },
                                    onClick = { menuOpen = false; onSort(s) },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.files_change_download_dir)) },
                                onClick = { menuOpen = false; onPickDownloadDir() },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        // 队列条走 bottomBar：放在内容 Column 末尾会被上传 FAB 压住右半边（真机实测「已完成」被挡）。
        bottomBar = {
            if (tasks.isNotEmpty()) {
                TransferBar(
                    tasks = tasks,
                    activeCount = activeTasks.size,
                    onExpand = { showQueue = true },
                )
            }
        },
        floatingActionButton = {
            if (state.canUpload) {
                ExtendedFloatingActionButton(
                    text = { Text(stringResource(R.string.files_upload_here)) },
                    icon = { Icon(Icons.Filled.Upload, contentDescription = null) },
                    onClick = { if (onBeforePickUpload()) uploadLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.onGloballyPositioned { uploadFabTop = it.boundsInRoot().top },
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Crumbs(path = state.path, onNavigate = onNavigate)
            HorizontalDivider()

            if (state.error.isNotBlank()) {
                val recovery = state.recovery ?: state.loginNetwork?.let { NetworkRecovery(RecoveryKind.LOGIN_NETWORK, it) }
                ErrorBanner(state.error, onClearError, recovery, onRecover)
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { entriesBottom = it.boundsInRoot().bottom }) {
                when {
                    state.loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    visible.isEmpty() && state.path.isNotBlank() && state.error.isBlank() -> Text(
                        stringResource(if (state.entries.isEmpty()) R.string.files_empty else R.string.files_only_hidden),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = tailPadding),
                    ) {
                        if (state.path != RemotePath.ROOT && state.path.isNotBlank()) {
                            item {
                                EntryRow(
                                    icon = Icons.Filled.ArrowUpward,
                                    title = "..",
                                    subtitle = stringResource(R.string.files_parent),
                                    onClick = onUp,
                                    onLongClick = null,
                                )
                            }
                        }
                        items(visible, key = { it.path }) { e ->
                            EntryRow(
                                icon = if (e.isDir) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                                title = e.name,
                                subtitle = subtitleOf(e),
                                titleDescription = stringResource(
                                    if (e.isDir) R.string.files_folder_description else R.string.files_file_description,
                                    e.name,
                                ),
                                onClick = { if (e.isDir) onNavigate(e.path) else selected = e },
                                onLongClick = { selected = e },
                            )
                        }
                    }
                }
            }

        }
    }

    selected?.let { e ->
        EntryActions(
            entry = e,
            canSendToTerminal = onSendToTerminal != null,
            onDismiss = { selected = null },
            onDownload = { selected = null; onDownload(e) },
            onCopyPath = {
                selected = null
                scope.launch { clipboard.setClipEntry(ClipData.newPlainText("path", e.path).toClipEntry()) }
            },
            onSendToTerminal = {
                selected = null
                onSendToTerminal?.invoke(RemotePath.shellQuote(e.path))
            },
        )
    }

    if (mkdirOpen) {
        TextPrompt(
            title = stringResource(R.string.files_new_folder),
            label = stringResource(R.string.files_folder_name),
            initial = "",
            action = stringResource(R.string.action_create),
            invalidMessage = stringResource(R.string.files_folder_name_invalid),
            validate = ::validDirectoryName,
            onConfirm = onMkdir,
            onDismiss = { mkdirOpen = false },
        )
    }

    if (gotoOpen) {
        TextPrompt(
            title = stringResource(R.string.files_goto),
            label = stringResource(R.string.files_goto_hint),
            initial = state.path,
            action = stringResource(R.string.action_go),
            invalidMessage = stringResource(R.string.files_path_invalid),
            validate = ::validRemotePath,
            imeAction = ImeAction.Go,
            onConfirm = onGoto,
            onDismiss = { gotoOpen = false },
        )
    }

    // 覆盖远端文件必须问一次：被覆盖的是服务器上的东西，误操作代价比本地大。
    overwrite?.let { conflict ->
        AlertDialog(
            onDismissRequest = onOverwriteCancel,
            title = { Text(stringResource(R.string.files_overwrite_title)) },
            text = {
                Text(
                    stringResource(R.string.files_overwrite_message, conflict.names.joinToString("\n")),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = onOverwriteConfirm) {
                    Text(stringResource(R.string.files_overwrite_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = onOverwriteCancel) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (showQueue) {
        TransferQueueSheet(
            tasks = tasks,
            onResume = onTaskResume,
            onCancel = onTaskCancel,
            onRemove = onTaskRemove,
            onClearFinished = onClearFinished,
            onDismiss = { showQueue = false },
        )
    }
}

/** 目录优先，同类内按所选维度。与 [FilesController.sorted] 同一口径，UI 侧本地排序避免多一次往返。 */
private fun sortEntries(entries: List<RemoteEntry>, sort: FilesSort, showHidden: Boolean): List<RemoteEntry> {
    val visible = if (showHidden) entries else entries.filterNot { it.name.startsWith(".") }
    val cmp = when (sort) {
        FilesSort.NAME -> compareBy<RemoteEntry> { it.name.lowercase() }
        FilesSort.TIME -> compareByDescending { it.mtime }
        FilesSort.SIZE -> compareByDescending { it.size }
    }
    return visible.sortedWith(compareByDescending<RemoteEntry> { it.isDir }.then(cmp))
}

@Composable
private fun subtitleOf(e: RemoteEntry): String {
    val time = if (e.mtime > 0) {
        SimpleDateFormat("MM-dd HH:mm", LocalLocale.current.platformLocale).format(Date(e.mtime * 1000))
    } else ""
    val size = if (e.isDir) "" else RemotePath.formatSize(e.size)
    val link = if (e.isLink) stringResource(R.string.files_link) else ""
    return listOf(size, time, link).filter { it.isNotBlank() }.joinToString(" · ")
}

@Composable
private fun Crumbs(path: String, onNavigate: (String) -> Unit) {
    if (path.isBlank()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemotePath.crumbs(path).forEachIndexed { i, (label, target) ->
            if (i > 0) {
                Text(
                    "/",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = MokeMono,
                color = if (target == path) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(enabled = target != path) { onNavigate(target) }
                    .padding(vertical = 4.dp, horizontal = 2.dp),
            )
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit, recovery: NetworkRecovery?, onRecover: (NetworkRecovery) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            if (recovery != null) {
                val label = when (recovery.kind) {
                    RecoveryKind.LOGIN_NETWORK -> R.string.action_login
                    RecoveryKind.VIEW_NETWORK -> R.string.action_view_network
                    RecoveryKind.EDIT_NETWORK -> R.string.action_edit_network
                    RecoveryKind.EDIT_HOST -> R.string.host_edit_title
                    RecoveryKind.RETRY -> R.string.action_retry
                }
                TextButton(onClick = { onRecover(recovery) }) {
                    Text(stringResource(label), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.action_close),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    titleDescription: String = title,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickableCompat(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                // Replace only the spoken title; the row still merges its metadata and click actions.
                modifier = Modifier.clearAndSetSemantics { text = AnnotatedString(titleDescription) },
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 点=进入/预览，长按=操作菜单。`combinedClickable` 是 Modifier 的扩展，必须这样包一层。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: (() -> Unit)?) =
    this.then(
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryActions(
    entry: RemoteEntry,
    canSendToTerminal: Boolean,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onCopyPath: () -> Unit,
    onSendToTerminal: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Text(
                entry.path,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = MokeMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            if (entry.permissions.isNotBlank()) {
                Text(
                    entry.permissions,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = MokeMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            if (!entry.isDir) {
                SheetAction(Icons.Filled.Download, stringResource(R.string.files_download), onDownload)
            }
            SheetAction(Icons.Filled.ContentCopy, stringResource(R.string.files_copy_path), onCopyPath)
            if (canSendToTerminal) {
                SheetAction(Icons.Filled.Terminal, stringResource(R.string.files_send_to_terminal), onSendToTerminal)
            }
        }
    }
}

@Composable
private fun SheetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 16.dp))
    }
}

@Composable
private fun TransferBar(tasks: List<TransferTask>, activeCount: Int, onExpand: () -> Unit) {
    // 没有进行中的任务时展示**最近**那一条，而不是列表里第一条（最早的）：
    // 先下载再上传时，上传完成后条上还挂着刚才那次下载的「已完成」，看着像上传没发生。
    val running = tasks.firstOrNull { it.state == TransferState.RUNNING }
        ?: tasks.firstOrNull { it.active }
        ?: tasks.maxByOrNull { it.createdAt }
        ?: tasks.first()
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onExpand),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (running.direction == com.briqt.moke.terminal.sftp.TransferDirection.UPLOAD) Icons.Filled.Upload
                    else Icons.Filled.Download,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    running.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                )
                Text(
                    if (activeCount > 1) "$activeCount" else stateLabel(running),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val fraction = running.fraction
            if (running.active) {
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransferQueueSheet(
    tasks: List<TransferTask>,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClearFinished: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.files_transfers),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClearFinished) { Text(stringResource(R.string.files_transfer_clear)) }
            }
            HorizontalDivider()
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                items(tasks, key = { it.id }) { t ->
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(t.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.weight(1f))
                            when {
                                t.active -> TextButton(onClick = { onCancel(t.id) }) {
                                    Text(stringResource(R.string.files_transfer_cancel))
                                }
                                t.state == TransferState.DONE -> TextButton(onClick = { onRemove(t.id) }) {
                                    Text(stringResource(R.string.files_transfer_remove))
                                }
                                else -> TextButton(onClick = { onResume(t.id) }) {
                                    Text(stringResource(R.string.files_transfer_resume))
                                }
                            }
                        }
                        Text(
                            buildString {
                                append(t.hostLabel)
                                append(" · ")
                                append(RemotePath.formatSize(t.done))
                                if (t.total > 0) {
                                    append(" / ")
                                    append(RemotePath.formatSize(t.total))
                                }
                                append(" · ")
                                append(stateLabel(t))
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (t.error.isNotBlank()) {
                            Text(
                                t.error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun stateLabel(t: TransferTask): String = when (t.state) {
    TransferState.QUEUED -> stringResource(R.string.files_transfer_queued)
    TransferState.RUNNING -> t.fraction?.let { "${(it * 100).toInt()}%" } ?: ""
    TransferState.DONE -> stringResource(R.string.files_transfer_done)
    TransferState.CANCELLED -> stringResource(R.string.files_transfer_cancelled)
    TransferState.FAILED -> stringResource(R.string.files_transfer_failed)
}

@Composable
private fun TextPrompt(
    title: String,
    label: String,
    initial: String,
    action: String,
    invalidMessage: String,
    validate: (String) -> Boolean,
    imeAction: ImeAction = ImeAction.Done,
    onConfirm: suspend (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var submitted by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val error = if (submitted && !validate(text)) invalidMessage else if (failed) stringResource(R.string.form_action_failed) else null
    fun submit() {
        if (busy) return
        submitted = true
        if (!validate(text)) return
        busy = true
        failed = false
        scope.launch {
            try {
                if (onConfirm(text)) onDismiss() else failed = true
            } catch (t: CancellationException) { throw t }
            catch (_: Throwable) { failed = true }
            finally { busy = false }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; failed = false },
                enabled = !busy,
                label = { Text(label) },
                isError = error != null,
                supportingText = error?.let { message -> { Text(message) } },
                keyboardOptions = KeyboardOptions(imeAction = imeAction),
                keyboardActions = KeyboardActions(onAny = { submit() }),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { submit() }, enabled = !busy) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) } },
    )
}
