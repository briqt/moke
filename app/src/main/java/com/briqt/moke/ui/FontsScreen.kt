package com.briqt.moke.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.briqt.moke.R
import com.briqt.moke.terminal.FontInstallState
import com.briqt.moke.terminal.FontSpec
import com.briqt.moke.ui.theme.MokeMono

private fun fmtSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes >= 1_000_000 -> "${bytes / 1_000_000} MB"
    else -> "${bytes / 1000} KB"
}

/** 当前 UI 是否中文（决定字体展示用中文名/说明还是英文名）。 */
@Composable
private fun isZh(): Boolean = LocalConfiguration.current.locales[0].language == "zh"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FontsScreen(
    primaryId: String,
    fallbackId: String,
    fonts: List<FontSpec>,
    states: Map<String, FontInstallState>,
    onDownload: (String) -> Unit,
    onDelete: (String) -> Unit,
    onSetPrimary: (String) -> Unit,
    onSetFallback: (String) -> Unit,
    onImport: (android.net.Uri) -> Unit,
    importError: String?,
    onClearImportError: () -> Unit,
    importing: Boolean,
    importSuccess: String?,
    onClearImportSuccess: () -> Unit,
    onBack: () -> Unit,
) {
    // 系统文件选择器：选本地 TTF/OTF 导入。用 */* 最大兼容（部分文件管理器不识别字体 MIME），导入时再校验。
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onImport(uri) }
    // 导入错误 / 成功提示 4 秒后自动清除。
    androidx.compose.runtime.LaunchedEffect(importError) {
        if (importError != null) { kotlinx.coroutines.delay(4000); onClearImportError() }
    }
    androidx.compose.runtime.LaunchedEffect(importSuccess) {
        if (importSuccess != null) { kotlinx.coroutines.delay(4000); onClearImportSuccess() }
    }
    // 已下载 / 已上传的排在前面，其次内置，未安装的可下载项垫底（同级保持目录顺序）。
    val sortedFonts = remember(fonts, states) {
        fonts.sortedBy { spec ->
            when {
                spec.userUploaded -> 0
                !spec.bundled && states[spec.id] is FontInstallState.Installed -> 1
                spec.bundled -> 2
                else -> 3
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.fonts_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                expandedHeight = 49.dp,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.fonts_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("*/*")) },
                        enabled = !importing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (importing) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Text("  " + stringResource(R.string.font_importing))
                        } else {
                            Text(stringResource(R.string.font_upload))
                        }
                    }
                    if (importError != null) {
                        Text(importError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (importSuccess != null) {
                        Text(stringResource(R.string.font_imported, importSuccess), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            items(sortedFonts, key = { it.id }) { spec ->
                FontCard(
                    spec = spec,
                    state = states[spec.id] ?: FontInstallState.Absent,
                    isPrimary = spec.id == primaryId,
                    isFallback = spec.id == fallbackId,
                    onDownload = { onDownload(spec.id) },
                    onDelete = { onDelete(spec.id) },
                    onSetPrimary = { onSetPrimary(spec.id) },
                    onSetFallback = { onSetFallback(if (fallbackId == spec.id) "" else spec.id) },
                )
            }
        }
    }
}

@Composable
private fun FontCard(
    spec: FontSpec,
    state: FontInstallState,
    isPrimary: Boolean,
    isFallback: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onSetPrimary: () -> Unit,
    onSetFallback: () -> Unit,
) {
    val zh = isZh()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    // 中文界面显示中文名，英文界面显示字体本名（英文）。
                    Text(if (zh) spec.nameZh else spec.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        spec.name + "  ·  " + spec.license,
                        fontFamily = MokeMono,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 能力标签（比「中/英」二分更有信息量）：含中文可作回退；连字/内置各表其义。
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (spec.bundled) Badge(stringResource(R.string.tag_bundled), MaterialTheme.colorScheme.tertiary)
                    if (spec.userUploaded) Badge(stringResource(R.string.tag_local), MaterialTheme.colorScheme.tertiary)
                    if (spec.cjk) Badge(stringResource(R.string.tag_cjk), MaterialTheme.colorScheme.primary)
                    if (spec.ligature) Badge(stringResource(R.string.tag_ligature), MaterialTheme.colorScheme.secondary)
                }
            }

            // 字体说明仅中文目录内有，英文界面不显示（避免英文里夹中文）。
            if (zh && spec.note.isNotBlank()) {
                Text(spec.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // 状态 / 下载
            when (state) {
                is FontInstallState.Downloading -> {
                    Column {
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            stringResource(R.string.font_downloading, (state.progress * 100).toInt()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is FontInstallState.Failed -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = onDownload) { Text(stringResource(R.string.action_retry)) }
                    }
                }
                FontInstallState.Installed -> {
                    // 角色选择 + 删除
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = isPrimary,
                            onClick = onSetPrimary,
                            label = { Text(stringResource(R.string.role_primary)) },
                            colors = FilterChipDefaults.filterChipColors(),
                        )
                        FilterChip(
                            selected = isFallback,
                            onClick = onSetFallback,
                            label = { Text(stringResource(R.string.role_fallback)) },
                        )
                        Box(Modifier.weight(1f))
                        if (!spec.bundled) {
                            TextButton(onClick = onDelete) {
                                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                FontInstallState.Absent -> {
                    AssistChip(
                        onClick = onDownload,
                        label = { Text(stringResource(R.string.font_download) + fmtSize(spec.approxBytes).let { if (it.isNotBlank()) " · $it" else "" }) },
                        colors = AssistChipDefaults.assistChipColors(
                            labelColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(
        color = color.copy(alpha = 0.16f),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
