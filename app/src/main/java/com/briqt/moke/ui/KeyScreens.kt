package com.briqt.moke.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.briqt.moke.R
import com.briqt.moke.data.KeyEntry
import com.briqt.moke.data.ListState
import com.briqt.moke.data.StoreFailure
import com.briqt.moke.data.StoreResult
import com.briqt.moke.data.uniqueKeyName
import com.briqt.moke.localized
import com.briqt.moke.terminal.KeyGenAlgorithm
import com.briqt.moke.terminal.KeyParseError
import com.briqt.moke.terminal.KeyParseResult
import com.briqt.moke.terminal.SshKeys
import com.briqt.moke.ui.theme.MokeDimens
import com.briqt.moke.ui.theme.MokeMono
import com.briqt.moke.ui.theme.MokeShapes
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class KeyCreateMode { GENERATE, IMPORT }

private const val MAX_KEY_FILE_BYTES = 256 * 1024

internal fun copyPublicKey(context: Context, publicKey: String) {
    if (publicKey.isBlank()) return
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("moke", publicKey))
    Toast.makeText(context, context.localized(R.string.key_public_copied), Toast.LENGTH_SHORT).show()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeysScreen(
    keys: List<KeyEntry>,
    keyState: ListState<KeyEntry>,
    hostsUsing: (String) -> Int,
    onAdd: (KeyCreateMode) -> Unit,
    onOpen: (KeyEntry) -> Unit,
    onDelete: suspend (KeyEntry) -> StoreResult<Unit>,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries = (keyState as? ListState.Ready)?.items ?: keys
    var addMenu by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<KeyEntry?>(null) }
    var deleteFailure by remember { mutableStateOf<StoreFailure?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_keys), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (keyState is ListState.Ready) {
                        Box {
                            IconButton(onClick = { addMenu = true }) {
                                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_add))
                            }
                            DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.key_action_generate)) },
                                    leadingIcon = { Icon(Icons.Filled.Key, contentDescription = null) },
                                    onClick = { addMenu = false; onAdd(KeyCreateMode.GENERATE) },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.key_action_import)) },
                                    leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null) },
                                    onClick = { addMenu = false; onAdd(KeyCreateMode.IMPORT) },
                                )
                            }
                        }
                    }
                },
                expandedHeight = MokeDimens.topBarHeight,
                colors = mokeTopBarColors(),
            )
        },
    ) { padding ->
        when (keyState) {
            ListState.Loading -> CenteredScrollColumn(contentPadding = padding) {
                InfoNote(stringResource(R.string.keys_loading))
            }
            ListState.Unreadable -> CenteredScrollColumn(contentPadding = padding) {
                InfoNote(stringResource(R.string.keys_unreadable), error = true)
                TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }
            is ListState.Ready -> if (entries.isEmpty()) {
                KeysEmpty(padding, onAdd)
            } else {
                CenteredScrollColumn(
                    contentPadding = padding,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    deleteFailure?.let {
                        InfoNote(stringResource(R.string.form_action_failed) + "\n" + stringResource(storeFailureMessage(it)), error = true)
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(SegmentGap),
                    ) {
                        entries.forEachIndexed { index, entry ->
                            val used = hostsUsing(entry.id)
                            val usedText = if (used > 0) pluralStringResource(R.plurals.key_used_by_hosts, used, used) else null
                            val subtitle = buildAnnotatedString {
                                var hasPart = false
                                if (entry.algorithmLabel.isNotBlank()) {
                                    append(entry.algorithmLabel)
                                    hasPart = true
                                }
                                if (entry.fingerprint.isNotBlank()) {
                                    if (hasPart) append(" · ")
                                    pushStyle(SpanStyle(fontFamily = MokeMono))
                                    append(entry.fingerprint)
                                    pop()
                                    hasPart = true
                                }
                                if (usedText != null) {
                                    if (hasPart) append(" · ")
                                    append(usedText)
                                }
                            }
                            CompositionLocalProvider(LocalRowPosition provides RowPosition(index, entries.size)) {
                                Box {
                                    ListRow(
                                        icon = Icons.Filled.Key,
                                        title = entry.name,
                                        subtitle = subtitle.takeIf { it.isNotEmpty() },
                                        onClick = { onOpen(entry) },
                                        onLongClick = { menuFor = entry.id },
                                        trailing = {
                                            if (entry.publicKey.isNotBlank()) {
                                                IconButton(onClick = { copyPublicKey(context, entry.publicKey) }) {
                                                    Icon(
                                                        Icons.Filled.ContentCopy,
                                                        contentDescription = stringResource(R.string.key_copy_public),
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                        },
                                    )
                                    DropdownMenu(
                                        expanded = menuFor == entry.id,
                                        onDismissRequest = { menuFor = null },
                                    ) {
                                        if (entry.publicKey.isNotBlank()) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.key_copy_public)) },
                                                leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                                                onClick = {
                                                    menuFor = null
                                                    copyPublicKey(context, entry.publicKey)
                                                },
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.host_edit)) },
                                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                            onClick = {
                                                menuFor = null
                                                onOpen(entry)
                                            },
                                        )
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                            onClick = {
                                                menuFor = null
                                                pendingDelete = entry
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

    pendingDelete?.let { target ->
        KeyDeleteConfirmDialog(
            entry = target,
            dependentHosts = hostsUsing(target.id),
            onConfirm = {
                pendingDelete = null
                deleteFailure = null
                scope.launch {
                    val res = onDelete(target)
                    if (res is StoreResult.Failed) deleteFailure = res.reason
                }
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun KeysEmpty(padding: PaddingValues, onAdd: (KeyCreateMode) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Filled.Key,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(44.dp),
            )
            Text(stringResource(R.string.keys_empty_title), fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text(
                stringResource(R.string.keys_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = { onAdd(KeyCreateMode.GENERATE) },
                modifier = Modifier.padding(top = 8.dp).sizeIn(minHeight = 48.dp),
            ) {
                Text(stringResource(R.string.key_action_generate))
            }
            FilledTonalButton(
                onClick = { onAdd(KeyCreateMode.IMPORT) },
                modifier = Modifier.sizeIn(minHeight = 48.dp),
            ) {
                Text(stringResource(R.string.key_action_import))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyEditScreen(
    initial: KeyEntry?,
    createMode: KeyCreateMode,
    keys: List<KeyEntry>,
    keyState: ListState<KeyEntry> = ListState.Ready(keys),
    hostsUsing: (String) -> Int = { 0 },
    onSave: suspend (KeyEntry, Boolean) -> StoreResult<KeyEntry>,
    onDelete: suspend (KeyEntry) -> StoreResult<Unit>,
    onRetryKeys: () -> Unit = {},
    onClose: (savedId: String?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Keep key material strictly in memory (`remember`), never `rememberSaveable` / Bundle.
    val base = remember(initial?.id) { initial ?: KeyEntry() }
    val id = base.id
    val editing = initial != null

    var name by remember(id) { mutableStateOf(base.name) }
    var replacingKey by remember(id) { mutableStateOf(false) }
    var replaceMode by remember(id) { mutableStateOf(KeyCreateMode.IMPORT) }
    var algorithm by remember(id) { mutableStateOf(KeyGenAlgorithm.ED25519) }
    var privateKeyPem by remember(id) { mutableStateOf("") }
    var passphrase by remember(id) { mutableStateOf("") }
    var showPassphrase by remember(id) { mutableStateOf(false) }
    var parseError by remember(id) { mutableStateOf<KeyParseError?>(null) }
    var fileError by remember(id) { mutableStateOf<String?>(null) }
    var submitted by remember(id) { mutableStateOf(false) }
    var submitting by remember(id) { mutableStateOf(false) }
    var actionFailure by remember(id) { mutableStateOf(false) }
    var failure by remember(id) { mutableStateOf<StoreFailure?>(null) }
    var confirmDelete by remember(id) { mutableStateOf(false) }
    val targets = remember(id) { List(4) { EditorTarget() } }

    val entries = (keyState as? ListState.Ready)?.items ?: keys
    val activeMode: KeyCreateMode? = when {
        !editing -> createMode
        replacingKey -> replaceMode
        else -> null
    }
    val needsLegacyPassphrase = editing && !replacingKey &&
        base.publicKey.isBlank() && SshKeys.isEncryptedPrivateKey(base.privateKeyPem)

    val nameError = when {
        editing && name.isBlank() -> stringResource(R.string.form_name_required)
        name.isNotBlank() && FormValidation.duplicateKeyName(name, id, entries) -> stringResource(R.string.key_name_duplicate)
        else -> null
    }
    val pemError = when {
        activeMode == KeyCreateMode.IMPORT && privateKeyPem.isBlank() -> stringResource(R.string.form_private_key_required)
        parseError == KeyParseError.EMPTY_KEY -> stringResource(R.string.form_private_key_required)
        parseError == KeyParseError.INVALID_KEY -> stringResource(R.string.form_private_key_invalid)
        else -> null
    }
    val passError = when (parseError) {
        KeyParseError.PASSPHRASE_REQUIRED -> stringResource(R.string.form_key_passphrase_required)
        KeyParseError.WRONG_PASSPHRASE -> stringResource(R.string.form_key_passphrase_wrong)
        else -> null
    }

    fun rawDraft() = listOf(name, replacingKey, replaceMode, algorithm, privateKeyPem, passphrase)
    val baseline = remember(id) { rawDraft() }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            fileError = null
            val loaded = withContext(Dispatchers.IO) { readBoundedUtf8(context, uri) }
            when (loaded) {
                is FileReadOutcome.Ok -> {
                    privateKeyPem = loaded.text
                    parseError = null
                    fileError = null
                }
                FileReadOutcome.Empty -> {
                    fileError = context.localized(R.string.key_file_empty)
                    targets[3].reveal(false)
                }
                FileReadOutcome.TooLarge -> {
                    fileError = context.localized(R.string.key_file_too_large)
                    targets[3].reveal(false)
                }
                FileReadOutcome.Binary -> {
                    fileError = context.localized(R.string.form_private_key_invalid)
                    targets[3].reveal(false)
                }
                FileReadOutcome.Failed -> {
                    fileError = context.localized(R.string.key_file_read_failed)
                    targets[3].reveal(false)
                }
            }
        }
    }

    fun submit() {
        if (submitting) return
        submitted = true
        fileError = null
        if (keyState !is ListState.Ready) {
            scope.launch { targets[3].reveal(false) }
            return
        }
        if (nameError != null) {
            scope.launch { targets[0].reveal() }
            return
        }
        if (activeMode == KeyCreateMode.IMPORT && privateKeyPem.isBlank()) {
            scope.launch { targets[1].reveal() }
            return
        }

        actionFailure = false
        submitting = true
        failure = null
        scope.launch {
            try {
                val draft: KeyEntry = when (activeMode) {
                    null -> {
                        val finalName = name.trim()
                        if (needsLegacyPassphrase && (passphrase.isNotEmpty() || finalName == base.name)) {
                            val inspected = withContext(Dispatchers.Default) {
                                SshKeys.inspectPrivateKey(
                                    pem = base.privateKeyPem,
                                    passphrase = passphrase,
                                    commentOverride = finalName.takeIf { it.isNotEmpty() },
                                )
                            }
                            currentCoroutineContext().ensureActive()
                            when (inspected) {
                                is KeyParseResult.Invalid -> {
                                    parseError = inspected.error
                                    val errIdx = if (inspected.error == KeyParseError.PASSPHRASE_REQUIRED || inspected.error == KeyParseError.WRONG_PASSPHRASE) 2 else 3
                                    targets[errIdx].reveal(errIdx == 2)
                                    return@launch
                                }
                                is KeyParseResult.Ok -> {
                                    val parsed = inspected.key
                                    base.copy(
                                        name = finalName,
                                        privateKeyPem = parsed.privateKeyPem,
                                        passphrase = passphrase,
                                        publicKey = SshKeys.withCommentIfBlank(parsed, finalName),
                                        fingerprint = parsed.fingerprint,
                                        algorithmLabel = parsed.algorithmLabel,
                                    )
                                }
                            }
                        } else {
                            base.copy(name = finalName)
                        }
                    }
                    KeyCreateMode.GENERATE -> {
                        val finalName = if (editing) name.trim() else {
                            name.trim().ifEmpty { uniqueKeyName(algorithm.defaultName, entries, ignoreId = id) }
                        }
                        val generated = withContext(Dispatchers.Default) {
                            SshKeys.generate(algorithm, passphrase, comment = finalName)
                        }
                        KeyEntry(
                            id = id,
                            name = finalName,
                            privateKeyPem = generated.privateKeyPem,
                            passphrase = passphrase,
                            publicKey = generated.publicKey,
                            fingerprint = generated.fingerprint,
                            algorithmLabel = generated.algorithmLabel,
                            createdAt = if (editing) base.createdAt else System.currentTimeMillis(),
                        )
                    }
                    KeyCreateMode.IMPORT -> {
                        val explicitName = name.trim()
                        val inspected = withContext(Dispatchers.Default) {
                            SshKeys.inspectPrivateKey(
                                pem = privateKeyPem,
                                passphrase = passphrase,
                                commentOverride = explicitName.takeIf { it.isNotEmpty() },
                            )
                        }
                        currentCoroutineContext().ensureActive()
                        when (inspected) {
                            is KeyParseResult.Invalid -> {
                                parseError = inspected.error
                                val errIdx = if (inspected.error == KeyParseError.PASSPHRASE_REQUIRED || inspected.error == KeyParseError.WRONG_PASSPHRASE) 2 else 1
                                targets[errIdx].reveal()
                                return@launch
                            }
                            is KeyParseResult.Ok -> {
                                val parsed = inspected.key
                                val finalName = if (editing) explicitName else {
                                    explicitName.ifEmpty {
                                        uniqueKeyName(parsed.comment.ifBlank { parsed.algorithmLabel }, entries, ignoreId = id)
                                    }
                                }
                                KeyEntry(
                                    id = id,
                                    name = finalName,
                                    privateKeyPem = parsed.privateKeyPem,
                                    passphrase = passphrase,
                                    publicKey = SshKeys.withCommentIfBlank(parsed, finalName),
                                    fingerprint = parsed.fingerprint,
                                    algorithmLabel = parsed.algorithmLabel,
                                    createdAt = if (editing) base.createdAt else System.currentTimeMillis(),
                                )
                            }
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                when (val result = onSave(draft, editing)) {
                    is StoreResult.Saved -> onClose(result.value.id)
                    is StoreResult.Failed -> {
                        failure = result.reason
                        targets[3].reveal(false)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failure = StoreFailure.IO
                targets[3].reveal(false)
            } finally {
                submitting = false
            }
        }
    }

    fun deleteStored() {
        val entry = initial ?: return
        if (submitting) return
        actionFailure = true
        submitting = true
        failure = null
        scope.launch {
            try {
                when (val result = onDelete(entry)) {
                    is StoreResult.Saved -> onClose(null)
                    is StoreResult.Failed -> {
                        failure = result.reason
                        targets[3].reveal(false)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failure = StoreFailure.IO
                targets[3].reveal(false)
            } finally {
                submitting = false
            }
        }
    }

    val title = stringResource(when {
        editing -> R.string.key_edit_title
        createMode == KeyCreateMode.GENERATE -> R.string.key_generate_title
        else -> R.string.key_import_title
    })
    val submittingLabel = when {
        actionFailure -> R.string.action_working
        activeMode == KeyCreateMode.GENERATE -> R.string.key_generating
        else -> R.string.action_saving
    }

    EditorScaffold(
        title = title,
        dirty = rawDraft() != baseline,
        submitting = submitting,
        onLeave = { onClose(null) },
        onSave = ::submit,
        submittingLabel = submittingLabel,
    ) { padding ->
        CenteredScrollColumn(
            contentPadding = padding,
            extraContainerModifier = Modifier.consumeWindowInsets(padding).imePadding(),
            innerPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.editorTarget(targets[3])) {
                failure?.let {
                    InfoNote(
                        stringResource(if (actionFailure) R.string.form_action_failed else R.string.store_save_failed) +
                            "\n" + stringResource(storeFailureMessage(it)),
                        error = true,
                    )
                }
                fileError?.let { InfoNote(it, error = true) }
                if (activeMode == null && parseError == KeyParseError.INVALID_KEY) {
                    InfoNote(stringResource(R.string.form_private_key_invalid), error = true)
                }
                when (keyState) {
                    ListState.Loading -> InfoNote(stringResource(R.string.keys_loading))
                    ListState.Unreadable -> {
                        InfoNote(stringResource(R.string.keys_unreadable), error = true)
                        TextButton(onClick = onRetryKeys, enabled = !submitting) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                    is ListState.Ready -> Unit
                }
            }

            FormTextField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(if (editing) R.string.field_name_required else R.string.field_name),
                error = nameError,
                submitted = submitted,
                hint = when {
                    editing -> null
                    createMode == KeyCreateMode.GENERATE -> stringResource(R.string.key_name_generate_hint, algorithm.defaultName)
                    else -> stringResource(R.string.key_name_import_hint)
                },
                enabled = !submitting,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.editorTarget(targets[0]),
            )

            if (editing && !replacingKey) {
                KeyMetadataSummary(
                    entry = base,
                    enabled = !submitting,
                    showUnavailableNote = !needsLegacyPassphrase,
                ) {
                    copyPublicKey(context, base.publicKey)
                }
                if (needsLegacyPassphrase) {
                    InfoNote(stringResource(R.string.key_legacy_passphrase_hint))
                    FormTextField(
                        value = passphrase,
                        onValueChange = {
                            passphrase = it
                            parseError = null
                        },
                        label = stringResource(R.string.field_key_passphrase),
                        error = passError,
                        submitted = submitted || passError != null,
                        enabled = !submitting,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        visualTransformation = if (showPassphrase) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassphrase = !showPassphrase }) {
                                Icon(
                                    if (showPassphrase) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = stringResource(if (showPassphrase) R.string.action_hide else R.string.action_reveal),
                                )
                            }
                        },
                        modifier = Modifier.editorTarget(targets[2]),
                    )
                }
                TextButton(
                    onClick = {
                        replacingKey = true
                        replaceMode = KeyCreateMode.IMPORT
                        algorithm = KeyGenAlgorithm.ED25519
                        privateKeyPem = ""
                        passphrase = ""
                        parseError = null
                        fileError = null
                    },
                    enabled = !submitting,
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) {
                    Text(stringResource(R.string.key_replace_private))
                }
            } else if (editing && replacingKey) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.key_replace_private),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            replacingKey = false
                            replaceMode = KeyCreateMode.IMPORT
                            algorithm = KeyGenAlgorithm.ED25519
                            privateKeyPem = ""
                            passphrase = ""
                            parseError = null
                            fileError = null
                        },
                        enabled = !submitting,
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) {
                        Text(stringResource(R.string.key_cancel_replace))
                    }
                }
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = replaceMode == KeyCreateMode.IMPORT,
                        onClick = { replaceMode = KeyCreateMode.IMPORT; parseError = null; fileError = null },
                        enabled = !submitting,
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) {
                        Text(stringResource(R.string.key_action_import))
                    }
                    SegmentedButton(
                        selected = replaceMode == KeyCreateMode.GENERATE,
                        onClick = { replaceMode = KeyCreateMode.GENERATE; parseError = null; fileError = null },
                        enabled = !submitting,
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) {
                        Text(stringResource(R.string.key_action_generate))
                    }
                }
            }

            when (activeMode) {
                null -> Unit
                KeyCreateMode.GENERATE -> {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(R.string.key_algorithm),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            KeyGenAlgorithm.entries.forEachIndexed { idx, alg ->
                                SegmentedButton(
                                    selected = algorithm == alg,
                                    onClick = { algorithm = alg },
                                    enabled = !submitting,
                                    shape = SegmentedButtonDefaults.itemShape(idx, KeyGenAlgorithm.entries.size),
                                ) {
                                    Text(alg.defaultName)
                                }
                            }
                        }
                    }
                    FormTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = stringResource(R.string.key_passphrase_optional),
                        enabled = !submitting,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        visualTransformation = if (showPassphrase) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassphrase = !showPassphrase }) {
                                Icon(
                                    if (showPassphrase) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = stringResource(if (showPassphrase) R.string.action_hide else R.string.action_reveal),
                                )
                            }
                        },
                    )
                }
                KeyCreateMode.IMPORT -> {
                    Row {
                        FilledTonalButton(
                            onClick = { filePicker.launch(arrayOf("*/*")) },
                            enabled = !submitting,
                            modifier = Modifier.sizeIn(minHeight = 44.dp),
                        ) {
                            Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.key_import_file), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    FormTextField(
                        value = privateKeyPem,
                        onValueChange = {
                            privateKeyPem = it
                            parseError = null
                            fileError = null
                        },
                        label = stringResource(R.string.field_private_key),
                        error = pemError,
                        submitted = submitted || parseError != null,
                        hint = stringResource(R.string.key_private_pem_hint),
                        enabled = !submitting,
                        singleLine = false,
                        minLines = 5,
                        maxLines = 10,
                        textStyle = LocalTextStyle.current.copy(fontFamily = MokeMono),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        modifier = Modifier.editorTarget(targets[1]),
                    )
                    FormTextField(
                        value = passphrase,
                        onValueChange = {
                            passphrase = it
                            if (parseError == KeyParseError.PASSPHRASE_REQUIRED || parseError == KeyParseError.WRONG_PASSPHRASE) {
                                parseError = null
                            }
                        },
                        label = stringResource(R.string.key_passphrase_optional),
                        error = passError,
                        submitted = submitted || passError != null,
                        enabled = !submitting,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        visualTransformation = if (showPassphrase) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassphrase = !showPassphrase }) {
                                Icon(
                                    if (showPassphrase) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = stringResource(if (showPassphrase) R.string.action_hide else R.string.action_reveal),
                                )
                            }
                        },
                        modifier = Modifier.editorTarget(targets[2]),
                    )
                }
            }

            if (editing) {
                TextButton(
                    onClick = { confirmDelete = true },
                    enabled = !submitting,
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) {
                    Text(stringResource(R.string.key_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (confirmDelete && initial != null) {
        KeyDeleteConfirmDialog(
            entry = initial,
            dependentHosts = hostsUsing(initial.id),
            onConfirm = {
                confirmDelete = false
                deleteStored()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun KeyMetadataSummary(
    entry: KeyEntry,
    enabled: Boolean,
    showUnavailableNote: Boolean = true,
    onCopyPublic: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MokeShapes.card,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (entry.algorithmLabel.isNotBlank()) {
                Text(
                    entry.algorithmLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (entry.fingerprint.isNotBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(R.string.key_label_fingerprint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        entry.fingerprint,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = MokeMono,
                    )
                }
            }
            if (entry.publicKey.isNotBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.key_label_public),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        entry.publicKey,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = MokeMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FilledTonalButton(
                    onClick = onCopyPublic,
                    enabled = enabled,
                    modifier = Modifier.sizeIn(minHeight = 44.dp),
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.key_copy_public), modifier = Modifier.padding(start = 8.dp))
                }
            } else if (showUnavailableNote) {
                InfoNote(stringResource(R.string.key_public_unavailable))
            }
        }
    }
}

@Composable
private fun KeyDeleteConfirmDialog(
    entry: KeyEntry,
    dependentHosts: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val parts = buildList {
        if (dependentHosts > 0) {
            add(pluralStringResource(R.plurals.key_delete_hosts, dependentHosts, dependentHosts))
        }
        add(stringResource(R.string.network_delete_plain))
    }
    ConfirmDialog(
        title = stringResource(R.string.key_delete_title, entry.name),
        message = parts.joinToString("\n"),
        confirmLabel = stringResource(R.string.action_delete),
        destructive = true,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

private sealed interface FileReadOutcome {
    data class Ok(val text: String) : FileReadOutcome
    data object Empty : FileReadOutcome
    data object TooLarge : FileReadOutcome
    data object Binary : FileReadOutcome
    data object Failed : FileReadOutcome
}

private fun readBoundedUtf8(context: Context, uri: android.net.Uri): FileReadOutcome = try {
    val stream = context.contentResolver.openInputStream(uri) ?: return FileReadOutcome.Failed
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    var tooLarge = false
    stream.use { input ->
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > MAX_KEY_FILE_BYTES) {
                tooLarge = true
                break
            }
            out.write(buf, 0, n)
        }
    }
    if (tooLarge) {
        FileReadOutcome.TooLarge
    } else {
        val bytes = out.toByteArray()
        if (bytes.any { it == 0.toByte() }) FileReadOutcome.Binary
        else {
            val text = bytes.toString(Charsets.UTF_8)
            if (text.isBlank()) FileReadOutcome.Empty else FileReadOutcome.Ok(text)
        }
    }
} catch (_: Exception) {
    FileReadOutcome.Failed
}
