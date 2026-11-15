package com.briqt.moke.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.briqt.moke.R
import com.briqt.moke.data.NetworkEntry
import com.briqt.moke.sync.ApplyResult
import com.briqt.moke.sync.BackupName
import com.briqt.moke.sync.DecryptOutcome
import com.briqt.moke.sync.LocalState
import com.briqt.moke.sync.RemoteBackup
import com.briqt.moke.sync.RestoreCategory
import com.briqt.moke.sync.RestoreMode
import com.briqt.moke.sync.RestorePlan
import com.briqt.moke.sync.RestorePlanner
import com.briqt.moke.sync.SyncBundle
import com.briqt.moke.sync.SyncError
import com.briqt.moke.sync.SyncOutcome
import com.briqt.moke.sync.SyncSettings
import com.briqt.moke.sync.WebDavConfig
import com.briqt.moke.sync.WebDavFailure
import com.briqt.moke.ui.theme.MokeDimens
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** 同步失败 → 文案。 */
@Composable
fun syncErrorText(error: SyncError): String = when (error) {
    SyncError.LocalUnreadable -> stringResource(R.string.sync_err_local_unreadable)
    SyncError.NotConfigured -> stringResource(R.string.sync_err_not_configured)
    SyncError.Storage -> stringResource(R.string.sync_err_storage)
    is SyncError.WebDav -> when (error.failure) {
        WebDavFailure.INVALID_URL -> stringResource(R.string.sync_err_invalid_url)
        WebDavFailure.AUTH -> stringResource(R.string.sync_err_auth)
        WebDavFailure.FORBIDDEN -> stringResource(R.string.sync_err_forbidden)
        WebDavFailure.NOT_FOUND -> stringResource(R.string.sync_err_not_found)
        WebDavFailure.QUOTA -> stringResource(R.string.sync_err_quota)
        WebDavFailure.CERTIFICATE -> stringResource(R.string.sync_err_certificate)
        WebDavFailure.NETWORK -> stringResource(R.string.sync_err_network)
        WebDavFailure.SERVER -> stringResource(R.string.sync_err_server, error.status)
        WebDavFailure.PROTOCOL -> stringResource(R.string.sync_err_protocol)
        WebDavFailure.TOO_LARGE -> stringResource(R.string.sync_err_too_large)
    }
}

/** 设置行副标题：未设置 / 服务器主机名。 */
fun syncSummary(settings: SyncSettings?): String? = settings?.webDav?.url?.toHttpUrlOrNull()?.host

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SyncTopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
        actions = { actions() },
        expandedHeight = MokeDimens.topBarHeight,
        colors = mokeTopBarColors(),
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp))
}

@Composable
private fun platformLabel(platform: String): String = if (platform == BackupName.PLATFORM_ANDROID) "Android" else platform

/** 「2026年11月15日 14:30 · Android · v0.2.2」（随应用语言）。 */
@Composable
fun backupSubtitle(b: RemoteBackup): String {
    val locale = LocalConfiguration.current.locales[0]
    val p = b.parsed ?: return b.name
    val time = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).format(p.time)
    return listOf(time, platformLabel(p.platform), "v" + p.version).joinToString(" · ")
}

private sealed interface Listing {
    data object Loading : Listing
    data class Ready(val items: List<RemoteBackup>) : Listing
    data class Failed(val error: SyncError) : Listing
}

/** 设置 → 同步：WebDAV 服务一行、上传按钮、远端备份列表（单击进详情）。 */
@Composable
fun SyncScreen(
    state: MokeViewModel.SyncSettingsState,
    onList: suspend () -> SyncOutcome<List<RemoteBackup>>,
    onUpload: suspend () -> SyncOutcome<String>,
    onSetup: () -> Unit,
    onOpen: (RemoteBackup) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val settings = (state as? MokeViewModel.SyncSettingsState.Ready)?.settings
    var listing by remember { mutableStateOf<Listing>(Listing.Loading) }
    var reload by remember { mutableIntStateOf(0) }
    var uploading by remember { mutableStateOf(false) }
    var uploadNote by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var uploadError by remember { mutableStateOf<SyncError?>(null) }

    LaunchedEffect(settings, reload) {
        if (settings == null) return@LaunchedEffect
        listing = Listing.Loading
        listing = when (val r = onList()) {
            is SyncOutcome.Ok -> Listing.Ready(r.value)
            is SyncOutcome.Failed -> Listing.Failed(r.error)
        }
    }

    Scaffold(topBar = {
        SyncTopBar(stringResource(R.string.menu_sync), onBack) {
            if (settings != null) IconButton(onClick = { reload++ }, enabled = listing !is Listing.Loading) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
        }
    }) { padding ->
        when {
            state is MokeViewModel.SyncSettingsState.Loading -> Unit
            settings == null -> SyncEmpty(padding, onSetup)
            else -> CenteredScrollColumn(
                contentPadding = padding,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                NavRow(Icons.Filled.Cloud, "WebDAV",
                    listOfNotNull(settings.webDav.username.takeIf { it.isNotBlank() }, syncSummary(settings)).joinToString(" · "),
                    onClick = onSetup)
                Button(
                    onClick = {
                        uploading = true
                        uploadNote = null
                        uploadError = null
                        scope.launch {
                            when (val r = onUpload()) {
                                is SyncOutcome.Ok -> { uploadNote = r.value to true; reload++ }
                                is SyncOutcome.Failed -> uploadError = r.error
                            }
                            uploading = false
                        }
                    },
                    enabled = !uploading,
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                ) {
                    if (uploading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.sync_uploading), modifier = Modifier.padding(start = 8.dp))
                    } else Text(stringResource(R.string.sync_upload))
                }
                uploadError?.let { InfoNote(syncErrorText(it), error = true) }
                uploadNote?.let { (name, _) ->
                    val parsed = BackupName.parse(name)
                    InfoNote(stringResource(R.string.sync_uploaded, parsed?.let { backupSubtitle(RemoteBackup(com.briqt.moke.sync.RemoteFile(name, 0, null), it, true)) } ?: name))
                }
                SectionTitle(stringResource(R.string.sync_backups))
                when (val l = listing) {
                    Listing.Loading -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.sync_loading), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
                    }
                    is Listing.Failed -> {
                        InfoNote(syncErrorText(l.error), error = true)
                        TextButton(onClick = { reload++ }) { Text(stringResource(R.string.action_retry)) }
                    }
                    is Listing.Ready -> if (l.items.isEmpty()) {
                        Text(stringResource(R.string.sync_no_backups), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
                    } else {
                        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SegmentGap)) {
                            l.items.forEachIndexed { i, b ->
                                CompositionLocalProvider(LocalRowPosition provides RowPosition(i, l.items.size)) {
                                    ListRow(
                                        icon = Icons.Filled.PhoneAndroid,
                                        title = b.parsed?.device ?: b.name,
                                        subtitle = AnnotatedString(backupSubtitle(b)),
                                        onClick = { onOpen(b) },
                                        trailing = {
                                            if (b.fromThisDevice) MokeBadge(stringResource(R.string.sync_this_device),
                                                MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp))
                                        },
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

@Composable
private fun SyncEmpty(padding: PaddingValues, onSetup: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
        Column(modifier = Modifier.widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.CloudSync, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(44.dp))
            Text(stringResource(R.string.sync_empty_title), fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text(stringResource(R.string.sync_empty_hint), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Button(onClick = onSetup, modifier = Modifier.padding(top = 8.dp).sizeIn(minHeight = 48.dp)) { Text(stringResource(R.string.sync_setup_webdav)) }
        }
    }
}

/** WebDAV 服务与同步密码（草稿页：保存时先连上、建好目录才落盘）。 */
@Composable
fun WebDavEditScreen(
    initial: SyncSettings?,
    onSave: suspend (WebDavConfig, String?) -> SyncOutcome<Unit>,
    onRemove: suspend () -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(initial?.webDav?.url.orEmpty()) }
    var username by remember { mutableStateOf(initial?.webDav?.username.orEmpty()) }
    var password by remember { mutableStateOf(initial?.webDav?.password.orEmpty()) }
    var directory by remember { mutableStateOf(initial?.webDav?.directory ?: WebDavConfig.DEFAULT_DIRECTORY) }
    var syncPassword by remember { mutableStateOf("") }
    var syncConfirm by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<SyncError?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    val baseline = remember { listOf(url, username, password, directory, "", "") }
    val dirty = listOf(url, username, password, directory, syncPassword, syncConfirm) != baseline

    val urlError = if (url.trim().toHttpUrlOrNull()?.isHttps != true) stringResource(R.string.webdav_url_invalid) else null
    val userError = if (username.isBlank()) stringResource(R.string.webdav_required) else null
    val passError = if (password.isEmpty()) stringResource(R.string.webdav_required) else null
    val needSync = initial == null || syncPassword.isNotEmpty() || syncConfirm.isNotEmpty()
    val syncError = when {
        !needSync -> null
        syncPassword.length < 8 -> stringResource(R.string.sync_password_short)
        else -> null
    }
    val confirmError = if (needSync && syncError == null && syncConfirm != syncPassword) stringResource(R.string.sync_password_mismatch) else null

    fun submit() {
        submitted = true
        error = null
        if (listOf(urlError, userError, passError, syncError, confirmError).any { it != null }) return
        submitting = true
        scope.launch {
            val dir = directory.trim().ifEmpty { WebDavConfig.DEFAULT_DIRECTORY }
            val result = onSave(WebDavConfig(url.trim(), username.trim(), password, dir), syncPassword.takeIf { needSync })
            submitting = false
            when (result) {
                is SyncOutcome.Ok -> onClose()
                is SyncOutcome.Failed -> error = result.error
            }
        }
    }

    EditorScaffold(
        title = "WebDAV",
        dirty = dirty,
        submitting = submitting,
        onLeave = onClose,
        onSave = ::submit,
        submittingLabel = R.string.sync_connecting,
    ) { padding ->
        CenteredScrollColumn(
            contentPadding = padding,
            extraContainerModifier = Modifier.consumeWindowInsets(padding).imePadding(),
            innerPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FormTextField(url, { url = it }, stringResource(R.string.webdav_url), error = urlError, submitted = submitted,
                hint = stringResource(R.string.webdav_url_hint), enabled = !submitting,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            FormTextField(username, { username = it }, stringResource(R.string.webdav_username), error = userError, submitted = submitted,
                enabled = !submitting, modifier = Modifier.fillMaxWidth())
            FormTextField(password, { password = it }, stringResource(R.string.webdav_password), error = passError, submitted = submitted,
                hint = stringResource(R.string.webdav_password_hint), enabled = !submitting,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = stringResource(if (showPassword) R.string.action_hide else R.string.action_reveal))
                    }
                },
                modifier = Modifier.fillMaxWidth())
            FormTextField(directory, { directory = it }, stringResource(R.string.webdav_directory),
                hint = stringResource(R.string.webdav_directory_hint), enabled = !submitting, modifier = Modifier.fillMaxWidth())
            SectionTitle(stringResource(R.string.sync_password))
            FormTextField(syncPassword, { syncPassword = it }, stringResource(R.string.sync_password), error = syncError, submitted = submitted,
                hint = if (initial != null) stringResource(R.string.sync_password_keep_hint) else null, enabled = !submitting,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            FormTextField(syncConfirm, { syncConfirm = it }, stringResource(R.string.sync_password_confirm), error = confirmError, submitted = submitted,
                enabled = !submitting, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            InfoNote(stringResource(R.string.sync_password_note))
            error?.let { InfoNote(syncErrorText(it), error = true) }
            if (initial != null) {
                TextButton(onClick = { confirmRemove = true }, enabled = !submitting, modifier = Modifier.padding(top = 8.dp).sizeIn(minHeight = 48.dp)) {
                    Text(stringResource(R.string.webdav_remove), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (confirmRemove) {
        ConfirmDialog(
            title = stringResource(R.string.webdav_remove_title),
            message = stringResource(R.string.webdav_remove_message),
            confirmLabel = stringResource(R.string.webdav_remove_action),
            destructive = true,
            onConfirm = {
                confirmRemove = false
                scope.launch { onRemove(); onClose() }
            },
            onDismiss = { confirmRemove = false },
        )
    }
}

private sealed interface Detail {
    data object Loading : Detail
    data class Failed(val error: SyncError) : Detail
    data class NeedsPassword(val blob: ByteArray, val wrong: Boolean) : Detail
    data object Newer : Detail
    data object Corrupt : Detail
    data class Ready(val bundle: SyncBundle, val local: LocalState?) : Detail
}

/** 一个备份的详情：下载解密 → 勾选类别与方式 → 差异预览 → 恢复；删除是这里的上下文动作。 */
@Composable
fun BackupScreen(
    backup: RemoteBackup,
    onDownload: suspend (String) -> SyncOutcome<ByteArray>,
    onDecrypt: suspend (ByteArray, String?) -> DecryptOutcome,
    onLocalState: suspend () -> LocalState?,
    onPlan: (SyncBundle, LocalState, Set<RestoreCategory>, RestoreMode) -> RestorePlan,
    onApply: suspend (RestorePlan, SyncBundle) -> ApplyResult,
    onDelete: suspend (String) -> SyncOutcome<Unit>,
    lastResult: ApplyResult?,
    networkLive: MokeViewModel.NetworkLive,
    loginFlows: Map<String, MokeViewModel.LoginFlow>,
    onWatchNetworks: (Boolean) -> Unit,
    onLogin: (NetworkEntry) -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var detail by remember { mutableStateOf<Detail>(Detail.Loading) }
    var attempt by remember { mutableIntStateOf(0) }
    var deleting by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<SyncError?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(attempt) {
        if (lastResult != null) return@LaunchedEffect
        detail = Detail.Loading
        detail = when (val r = onDownload(backup.name)) {
            is SyncOutcome.Failed -> Detail.Failed(r.error)
            is SyncOutcome.Ok -> decryptToDetail(r.value, null, onDecrypt, onLocalState)
        }
    }

    Scaffold(topBar = { SyncTopBar(backup.parsed?.device ?: backup.name, onBack) }) { padding ->
        CenteredScrollColumn(
            contentPadding = padding,
            extraContainerModifier = Modifier.consumeWindowInsets(padding).imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(backupSubtitle(backup), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp))
            if (lastResult != null) {
                RestoreResultView(lastResult, networkLive, loginFlows, onWatchNetworks, onLogin, onDone)
                return@CenteredScrollColumn
            }
            when (val d = detail) {
                Detail.Loading -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.sync_backup_loading), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
                }
                is Detail.Failed -> {
                    InfoNote(syncErrorText(d.error), error = true)
                    TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.action_retry)) }
                }
                Detail.Newer -> InfoNote(stringResource(R.string.sync_backup_newer), error = true)
                Detail.Corrupt -> InfoNote(stringResource(R.string.sync_backup_corrupt), error = true)
                is Detail.NeedsPassword -> PasswordPrompt(d.wrong) { entered ->
                    detail = Detail.Loading
                    scope.launch { detail = decryptToDetail(d.blob, entered, onDecrypt, onLocalState) }
                }
                is Detail.Ready -> RestoreForm(d.bundle, d.local, onPlan, onApply = { plan ->
                    val result = onApply(plan, d.bundle)
                    if (result.languageChanged) (context as? Activity)?.recreate()
                })
            }
            deleteError?.let { InfoNote(syncErrorText(it), error = true) }
            TextButton(onClick = { confirmDelete = true }, enabled = !deleting, modifier = Modifier.padding(top = 12.dp).sizeIn(minHeight = 48.dp)) {
                Text(stringResource(if (deleting) R.string.sync_deleting else R.string.sync_delete_backup), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.sync_delete_title),
            message = stringResource(R.string.sync_delete_message, backup.name),
            confirmLabel = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = {
                confirmDelete = false
                deleting = true
                deleteError = null
                scope.launch {
                    when (val r = onDelete(backup.name)) {
                        is SyncOutcome.Ok -> onBack()
                        is SyncOutcome.Failed -> deleteError = r.error
                    }
                    deleting = false
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

private suspend fun decryptToDetail(
    blob: ByteArray,
    password: String?,
    onDecrypt: suspend (ByteArray, String?) -> DecryptOutcome,
    onLocalState: suspend () -> LocalState?,
): Detail = when (val r = onDecrypt(blob, password)) {
    is DecryptOutcome.Ok -> Detail.Ready(r.bundle, onLocalState())
    DecryptOutcome.WrongPassword -> Detail.NeedsPassword(blob, wrong = password != null)
    DecryptOutcome.NewerFormat -> Detail.Newer
    DecryptOutcome.Corrupt -> Detail.Corrupt
}

@Composable
private fun PasswordPrompt(wrong: Boolean, onUnlock: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    Text(stringResource(R.string.sync_backup_password_title), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 4.dp))
    FormTextField(value, { value = it }, stringResource(R.string.sync_password),
        error = if (wrong) stringResource(R.string.sync_backup_password_wrong) else null, submitted = wrong,
        hint = stringResource(R.string.sync_backup_password_hint),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    Button(onClick = { onUnlock(value) }, enabled = value.isNotEmpty(), modifier = Modifier.sizeIn(minHeight = 48.dp)) {
        Text(stringResource(R.string.sync_backup_unlock))
    }
}

@Composable
private fun RestoreForm(
    bundle: SyncBundle,
    local: LocalState?,
    onPlan: (SyncBundle, LocalState, Set<RestoreCategory>, RestoreMode) -> RestorePlan,
    onApply: suspend (RestorePlan) -> Unit,
) {
    val scope = rememberCoroutineScope()
    if (local == null) {
        InfoNote(stringResource(R.string.sync_err_local_unreadable), error = true)
        return
    }
    var categories by remember { mutableStateOf(RestoreCategory.entries.toSet()) }
    var mode by remember { mutableStateOf(RestoreMode.MERGE) }
    var autoNetworks by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val plan = remember(categories, mode) { onPlan(bundle, local, categories, mode) }

    fun toggle(c: RestoreCategory, on: Boolean) {
        var next = if (on) categories + c else categories - c
        autoNetworks = false
        if (on && c == RestoreCategory.HOSTS && RestoreCategory.NETWORKS !in next && RestorePlanner.networksRequiredByHosts(bundle, local)) {
            next = next + RestoreCategory.NETWORKS
            autoNetworks = true
        }
        categories = next
    }

    SectionTitle(stringResource(R.string.sync_restore_what))
    SettingsGroup {
        CategoryRow(stringResource(R.string.sync_cat_hosts), pluralStringResource(R.plurals.sync_items, bundle.hosts.size, bundle.hosts.size),
            RestoreCategory.HOSTS in categories, !applying) { toggle(RestoreCategory.HOSTS, it) }
        CategoryRow(stringResource(R.string.sync_cat_networks), pluralStringResource(R.plurals.sync_items, bundle.networks.size, bundle.networks.size),
            RestoreCategory.NETWORKS in categories, !applying) { toggle(RestoreCategory.NETWORKS, it) }
        CategoryRow(stringResource(R.string.sync_cat_fingerprints), pluralStringResource(R.plurals.sync_items, bundle.knownHosts.size, bundle.knownHosts.size),
            RestoreCategory.FINGERPRINTS in categories, !applying) { toggle(RestoreCategory.FINGERPRINTS, it) }
        CategoryRow(stringResource(R.string.sync_cat_settings), pluralStringResource(R.plurals.sync_items, bundle.settings.size, bundle.settings.size),
            RestoreCategory.SETTINGS in categories, !applying) { toggle(RestoreCategory.SETTINGS, it) }
    }
    if (autoNetworks) InfoNote(stringResource(R.string.sync_auto_networks))

    SectionTitle(stringResource(R.string.sync_mode))
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(selected = mode == RestoreMode.MERGE, onClick = { mode = RestoreMode.MERGE }, enabled = !applying,
            shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.sync_mode_merge)) }
        SegmentedButton(selected = mode == RestoreMode.REPLACE, onClick = { mode = RestoreMode.REPLACE }, enabled = !applying,
            shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text(stringResource(R.string.sync_mode_replace)) }
    }
    Text(stringResource(if (mode == RestoreMode.MERGE) R.string.sync_mode_merge_hint else R.string.sync_mode_replace_hint),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))

    SectionTitle(stringResource(R.string.sync_preview))
    PlanPreview(plan)

    Button(onClick = { confirm = true }, enabled = plan.hasChanges && !applying, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)) {
        if (applying) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.sync_restoring), modifier = Modifier.padding(start = 8.dp))
        } else Text(stringResource(R.string.sync_restore))
    }

    if (confirm) {
        val removedNames = plan.hosts?.diff?.removed.orEmpty().map { it.displayName } + plan.networks?.diff?.removed.orEmpty().map { it.name }
        val fpRemoved = plan.fingerprints?.removed ?: 0
        val removalText = (removedNames + if (fpRemoved > 0) listOf(pluralStringResource(R.plurals.sync_fingerprints, fpRemoved, fpRemoved)) else emptyList())
            .joinToString(stringResource(R.string.sync_list_separator))
        ConfirmDialog(
            title = stringResource(R.string.sync_restore_confirm_title),
            message = if (plan.hasRemovals) stringResource(R.string.sync_restore_confirm_removal, removalText)
                else stringResource(R.string.sync_restore_confirm_message),
            confirmLabel = stringResource(R.string.sync_restore),
            destructive = plan.hasRemovals,
            onConfirm = {
                confirm = false
                applying = true
                scope.launch {
                    try { onApply(plan) } finally { applying = false }
                }
            },
            onDismiss = { confirm = false },
        )
    }
}

@Composable
private fun CategoryRow(title: String, detail: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(androidx.compose.ui.graphics.RectangleShape)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(enabled = enabled) { onChange(!checked) }
            .sizeIn(minHeight = 56.dp).padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 4.dp))
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PlanPreview(plan: RestorePlan) {
    val sep = stringResource(R.string.sync_list_separator)
    @Composable
    fun names(list: List<String>): String =
        if (list.size <= 5) list.joinToString(sep) else stringResource(R.string.sync_preview_more, list.take(5).joinToString(sep), list.size - 5)

    val lines = mutableListOf<Pair<String, Boolean>>()
    @Composable
    fun counts(category: Int, added: Int, updated: Int, removed: Int): String =
        if (added == 0 && updated == 0 && removed == 0) stringResource(R.string.sync_preview_unchanged, stringResource(category))
        else stringResource(R.string.sync_preview_counts, stringResource(category), added, updated, removed)
    plan.networks?.let { np ->
        lines += counts(R.string.sync_cat_networks, np.diff.added.size, np.diff.updated.size, np.diff.removed.size) to false
        if (np.diff.added.isNotEmpty()) lines += stringResource(R.string.sync_preview_added, names(np.diff.added.map { it.name })) to false
        if (np.diff.removed.isNotEmpty()) lines += stringResource(R.string.sync_preview_removed, names(np.diff.removed.map { it.name })) to true
        np.identityReset.forEach { lines += stringResource(R.string.sync_preview_relogin, it.name) to true }
        if (np.skipped.isNotEmpty()) lines += stringResource(R.string.sync_preview_skipped, names(np.skipped.map { it.name })) to true
    }
    plan.hosts?.let { hp ->
        lines += counts(R.string.sync_cat_hosts, hp.diff.added.size, hp.diff.updated.size, hp.diff.removed.size) to false
        if (hp.diff.added.isNotEmpty()) lines += stringResource(R.string.sync_preview_added, names(hp.diff.added.map { it.displayName })) to false
        if (hp.diff.removed.isNotEmpty()) lines += stringResource(R.string.sync_preview_removed, names(hp.diff.removed.map { it.displayName })) to true
    }
    plan.fingerprints?.let { fp ->
        lines += counts(R.string.sync_cat_fingerprints, fp.added, fp.updated, fp.removed) to false
    }
    if (plan.settings) lines += stringResource(R.string.sync_preview_settings) to false
    if (plan.danglingNetworkRefs > 0) lines += pluralStringResource(R.plurals.sync_preview_dangling, plan.danglingNetworkRefs, plan.danglingNetworkRefs) to true
    if (!plan.hasChanges) lines.clear()
    if (lines.isEmpty()) {
        Text(stringResource(R.string.sync_preview_none), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
        lines.forEach { (text, warn) ->
            Text(text, style = MaterialTheme.typography.bodyMedium,
                color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun RestoreResultView(
    result: ApplyResult,
    live: MokeViewModel.NetworkLive,
    loginFlows: Map<String, MokeViewModel.LoginFlow>,
    onWatch: (Boolean) -> Unit,
    onLogin: (NetworkEntry) -> Unit,
    onDone: () -> Unit,
) {
    androidx.compose.runtime.DisposableEffect(Unit) { onWatch(true); onDispose { onWatch(false) } }
    Text(stringResource(R.string.sync_result_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 4.dp))
    if (result.failed.isNotEmpty()) {
        val names = result.failed.map { c ->
            stringResource(when (c) {
                RestoreCategory.HOSTS -> R.string.sync_cat_hosts
                RestoreCategory.NETWORKS -> R.string.sync_cat_networks
                RestoreCategory.FINGERPRINTS -> R.string.sync_cat_fingerprints
                RestoreCategory.SETTINGS -> R.string.sync_cat_settings
            })
        }
        InfoNote(stringResource(R.string.sync_result_failed, names.joinToString(stringResource(R.string.sync_list_separator))), error = true)
    }
    if (result.needsLogin.isNotEmpty()) {
        SectionTitle(stringResource(R.string.sync_result_login))
        SettingsGroup {
            result.needsLogin.forEach { e ->
                val phase = tailnetPhase(e, live, loginFlows[e.id])
                Row(
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                        .sizeIn(minHeight = 56.dp).padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(e.name, style = MaterialTheme.typography.bodyLarge)
                        Text(tailnetPhaseLabel(phase), style = MaterialTheme.typography.bodyMedium, color = tailnetPhaseColor(phase))
                    }
                    if (phase != TailnetPhase.ACTIVE && phase != TailnetPhase.LOGGED_IN) {
                        OutlinedButton(onClick = { onLogin(e) }, modifier = Modifier.sizeIn(minHeight = 40.dp)) { Text(stringResource(R.string.action_login)) }
                    }
                }
            }
        }
        InfoNote(stringResource(R.string.sync_result_login_hint))
    }
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).sizeIn(minHeight = 48.dp)) { Text(stringResource(R.string.sync_done)) }
}
