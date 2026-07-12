package com.briqt.moke.ui

import android.graphics.Typeface
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.briqt.moke.terminal.FontCatalog
import com.briqt.moke.terminal.FontInstallState
import com.briqt.moke.terminal.FontSpec
import com.briqt.moke.ui.theme.MokeMono

private const val SAMPLE = "moke:~\$ ls -la  ● main\n墨客 · 你好世界 0123456789\n{} () []  != >= -> ::  ✓ ★ "

private fun fmtSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes >= 1_000_000 -> "${bytes / 1_000_000} MB"
    else -> "${bytes / 1000} KB"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FontsScreen(
    primaryId: String,
    fallbackId: String,
    fonts: List<FontSpec>,
    states: Map<String, FontInstallState>,
    resolveTypeface: (String, String) -> Typeface,
    onDownload: (String) -> Unit,
    onDelete: (String) -> Unit,
    onSetPrimary: (String) -> Unit,
    onSetFallback: (String) -> Unit,
    onImport: (android.net.Uri) -> Unit,
    importError: String?,
    onClearImportError: () -> Unit,
    onBack: () -> Unit,
) {
    val previewTf = remember(primaryId, fallbackId, states) { resolveTypeface(primaryId, fallbackId) }
    val onSurfaceArgb = MaterialTheme.colorScheme.onSurface.toArgb()
    // 系统文件选择器：选本地 TTF/OTF 导入。用 */* 最大兼容（部分文件管理器不识别字体 MIME），导入时再校验。
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onImport(uri) }
    // 导入错误 4 秒后自动清除。
    androidx.compose.runtime.LaunchedEffect(importError) {
        if (importError != null) { kotlinx.coroutines.delay(4000); onClearImportError() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("字体") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                expandedHeight = 52.dp,
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
                // 实时预览：用合成后的 Typeface 渲染样张（Latin+中文+符号）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(12.dp))
                        .padding(14.dp),
                ) {
                    AndroidView(
                        factory = { ctx ->
                            TextView(ctx).apply {
                                textSize = 15f
                                setLineSpacing(6f, 1f)
                            }
                        },
                        update = { tv ->
                            tv.typeface = previewTf
                            tv.setTextColor(onSurfaceArgb)
                            tv.text = SAMPLE
                        },
                    )
                }
            }
            item {
                Text(
                    "主字体决定 Latin 观感；回退字体补 Latin 缺失的字形（如中文）。选一款含中文的作回退，即可漂亮显示中文。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("上传本地字体（TTF / OTF）") }
                    if (importError != null) {
                        Text(importError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            items(fonts, key = { it.id }) { spec ->
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(spec.nameZh, fontWeight = FontWeight.SemiBold)
                    Text(
                        spec.name + "  ·  " + spec.license,
                        fontFamily = MokeMono,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 能力标签（比「中/英」二分更有信息量）：含中文可作回退；连字/内置各表其义。
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (spec.bundled) Badge("内置", MaterialTheme.colorScheme.tertiary)
                    if (spec.userUploaded) Badge("本地", MaterialTheme.colorScheme.tertiary)
                    if (spec.cjk) Badge("含中文", MaterialTheme.colorScheme.primary)
                    if (spec.ligature) Badge("连字", MaterialTheme.colorScheme.secondary)
                }
            }

            if (spec.note.isNotBlank()) {
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
                            "下载中 ${(state.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is FontInstallState.Failed -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = onDownload) { Text("重试") }
                    }
                }
                FontInstallState.Installed -> {
                    // 角色选择 + 删除
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = isPrimary,
                            onClick = onSetPrimary,
                            label = { Text("主字体") },
                            colors = FilterChipDefaults.filterChipColors(),
                        )
                        FilterChip(
                            selected = isFallback,
                            onClick = onSetFallback,
                            label = { Text("回退") },
                        )
                        Box(Modifier.weight(1f))
                        if (!spec.bundled) {
                            TextButton(onClick = onDelete) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                FontInstallState.Absent -> {
                    AssistChip(
                        onClick = onDownload,
                        label = { Text("下载" + fmtSize(spec.approxBytes).let { if (it.isNotBlank()) " · $it" else "" }) },
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
