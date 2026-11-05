package com.briqt.moke.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.briqt.moke.R
import com.briqt.moke.ui.theme.MokeDimens
import com.briqt.moke.ui.theme.MokeMono
import com.briqt.moke.ui.theme.MokeShapes
import com.briqt.moke.update.UpdateChecker
import com.briqt.moke.update.UpdateInfo
import com.briqt.moke.update.UpdateStatus
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    updateInfo: UpdateInfo? = null,
    includePrerelease: Boolean = false,
    onIncludePrerelease: (Boolean) -> Unit = {},
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "-" }
            .getOrDefault("-")
    }
    // 启动时的静默检查若已发现新版，进来就直接呈现"有新版本"，不用再点一次检查。
    // 跳转目标用静默检查当时记下的该发布页地址（rc 就跳 rc 页）；
    // 旧版本只存了 tag 没存地址，为空时退回 releases/latest 兜底。
    var update by remember {
        mutableStateOf<UpdateStatus>(
            updateInfo?.let { UpdateStatus.Available(it.tag, it.url.ifBlank { "${UpdateChecker.REPO_URL}/releases/latest" }) }
                ?: UpdateStatus.Idle
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.about_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                expandedHeight = MokeDimens.topBarHeight,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        Column(
            // 可滚：横屏（或加了行之后）内容会高于视口，否则最后一行被裁掉。
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 头部：名称 + 定位
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.app_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.app_tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 版本 + 检查更新：固定最小行高（=按钮触控高度），避免"检查更新→进度→结果"三态控件高度不同导致整行重排、文字跳动。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                InfoLabel(stringResource(R.string.label_version))
                Text("v$version", fontFamily = MokeMono, modifier = Modifier.weight(1f))
                when (val u = update) {
                    UpdateStatus.Idle -> TextButton(onClick = {
                        update = UpdateStatus.Checking
                        scope.launch { update = UpdateChecker.check(version, context, includePrerelease) }
                    }) { Text(stringResource(R.string.check_update)) }
                    UpdateStatus.Checking -> CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                    is UpdateStatus.UpToDate -> Text(stringResource(R.string.up_to_date), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    is UpdateStatus.Available -> Button(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.url))) }
                    }) { Text(stringResource(R.string.new_version, u.latest)) }
                    is UpdateStatus.Failed -> TextButton(onClick = {
                        update = UpdateStatus.Checking
                        scope.launch { update = UpdateChecker.check(version, context, includePrerelease) }
                    }) { Text(stringResource(R.string.retry_with_msg, u.message)) }
                }
            }

            SwitchRow(
                icon = Icons.Filled.Science,
                title = stringResource(R.string.include_prerelease),
                checked = includePrerelease,
                onCheckedChange = {
                    onIncludePrerelease(it)
                    // 口径变了，界面上已有的结论就不再成立——回到未检查态，别让旧结果留着骗人。
                    update = UpdateStatus.Idle
                },
            )

            NavRow(
                Icons.Filled.Code,
                stringResource(R.string.github_repo),
                REPO_LABEL,
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.REPO_URL)))
                    }
                },
            )

            val shareText = stringResource(R.string.about_share_text, UpdateChecker.REPO_URL)
            val shareChooser = stringResource(R.string.about_share_chooser)
            PitchCard(
                onFeedback = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(FEEDBACK_URL)))
                    }
                },
                onShare = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareText)
                    }
                    runCatching {
                        context.startActivity(Intent.createChooser(send, shareChooser))
                    }
                },
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/** 反馈落到 GitHub 的新建 issue 页，由仓库已有的 issue 模板引导填写。 */
private val FEEDBACK_URL = "${UpdateChecker.REPO_URL}/issues/new/choose"

/**
 * 寄语卡片：项目目标 + 欢迎反馈 + 欢迎分享。沿用与设置/入口行相同的卡片底色（只取主题的颜色角色，
 * 不另调色），深浅色与壁纸取色下自动跟随；两个动作就近放在文字下方，各一步可达。
 */
@Composable
private fun PitchCard(onFeedback: () -> Unit, onShare: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        shape = MokeShapes.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.about_pitch_title),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(R.string.about_pitch_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            ) {
                FilledTonalButton(onClick = onFeedback, modifier = Modifier.weight(1f)) {
                    ButtonLabel(Icons.Outlined.RateReview, stringResource(R.string.about_feedback))
                }
                OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                    ButtonLabel(Icons.Outlined.Share, stringResource(R.string.about_share))
                }
            }
        }
    }
}

@Composable
private fun ButtonLabel(icon: ImageVector, text: String) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
    Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** 仓库地址的展示形态（去掉协议前缀，副标题里更干净）。 */
private val REPO_LABEL = UpdateChecker.REPO_URL.removePrefix("https://")

@Composable
private fun InfoLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.width(72.dp),
    )
}
