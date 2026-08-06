package com.briqt.moke.ui

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.briqt.moke.R
import com.briqt.moke.data.FilesSort
import com.briqt.moke.terminal.sftp.RemoteEntry
import com.briqt.moke.terminal.sftp.RemotePath
import com.briqt.moke.terminal.sftp.TransferState
import com.briqt.moke.terminal.sftp.TransferTask
import com.briqt.moke.ui.theme.MokeMono
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    hasDownloadDir: Boolean,
    onNavigate: (String) -> Unit,
    onUp: () -> Unit,
    onRefresh: () -> Unit,
    onGoto: (String) -> Unit,
    onMkdir: (String) -> Unit,
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
    onTaskResume: (String) -> Unit,
    onTaskCancel: (String) -> Unit,
    onTaskRemove: (String) -> Unit,
    onClearFinished: () -> Unit,
    onBack: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var menuOpen by remember { mutableStateOf(false) }
    var mkdirOpen by remember { mutableStateOf(false) }
    var gotoOpen by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<RemoteEntry?>(null) }
    var showQueue by remember { mutableStateOf(false) }

    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) onUpload(uris) }

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
                            state.host?.displayName ?: stringResource(R.string.files_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                        )
                        Text(
                            state.path.ifBlank { stringResource(R.string.files_connecting) },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = MokeMono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
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
            if (state.host != null && !state.loading) {
                ExtendedFloatingActionButton(
                    text = { Text(stringResource(R.string.files_upload_here)) },
                    icon = { Icon(Icons.Filled.Upload, contentDescription = null) },
                    onClick = { uploadLauncher.launch(arrayOf("*/*")) },
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Crumbs(path = state.path, onNavigate = onNavigate)
            HorizontalDivider()

            if (state.error.isNotBlank()) {
                ErrorBanner(state.error, onClearError)
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    visible.isEmpty() && state.path.isNotBlank() -> Text(
                        stringResource(R.string.files_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
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
            hasDownloadDir = hasDownloadDir,
            canSendToTerminal = onSendToTerminal != null,
            onDismiss = { selected = null },
            onDownload = { selected = null; onDownload(e) },
            onCopyPath = {
                selected = null
                clipboard.setText(AnnotatedString(e.path))
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
            onConfirm = { mkdirOpen = false; if (it.isNotBlank()) onMkdir(it) },
            onDismiss = { mkdirOpen = false },
        )
    }

    if (gotoOpen) {
        TextPrompt(
            title = stringResource(R.string.files_goto),
            label = stringResource(R.string.files_goto_hint),
            initial = state.path,
            onConfirm = { gotoOpen = false; if (it.isNotBlank()) onGoto(it) },
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
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(e.mtime * 1000))
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
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
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
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
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
    hasDownloadDir: Boolean,
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
                if (!hasDownloadDir) {
                    Text(
                        stringResource(R.string.files_pick_download_dir_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 56.dp, end = 20.dp, bottom = 8.dp),
                    )
                }
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
    val running = tasks.firstOrNull { it.state == TransferState.RUNNING }
        ?: tasks.firstOrNull { it.active }
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
            tasks.forEach { t ->
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

@Composable
private fun stateLabel(t: TransferTask): String = when (t.state) {
    TransferState.QUEUED -> stringResource(R.string.files_transfer_queued)
    TransferState.RUNNING -> t.fraction?.let { "${(it * 100).toInt()}%" } ?: ""
    TransferState.DONE -> stringResource(R.string.files_transfer_done)
    TransferState.CANCELLED -> stringResource(R.string.files_transfer_cancelled)
    TransferState.FAILED -> t.error.ifBlank { stringResource(R.string.files_transfer_cancelled) }
}

@Composable
private fun TextPrompt(
    title: String,
    label: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
