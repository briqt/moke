package com.briqt.moke.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.briqt.moke.terminal.FontSpec
import com.briqt.moke.ui.theme.MokeDimens
import com.briqt.moke.ui.theme.MokeShapes

/** 徽标 / 能力标签规格：文案 + 强调色（背景取该色的淡版）。 */
data class BadgeSpec(val text: String, val color: Color)

/**
 * 统一小徽标（能力标签 / 状态标签）：强调色文字 + 同色淡背景 + 统一胶囊圆角。
 * 收敛此前分散的 TagChip(secondaryContainer,5dp) 与 FontsScreen.Badge(0.16f,6dp)，
 * 使同一批标签在下拉与卡片里长得一致。协议徽标（等宽、紧贴文字的内联变体）见 ProtocolBadge。
 */
@Composable
fun MokeBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        color = color.copy(alpha = MokeDimens.badgeAlpha),
        shape = MokeShapes.pill,
        modifier = modifier,
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

/**
 * 字体能力标签（内置 / 本地 / 中文 / 连字）及其配色，供字体管理卡片与外观页下拉共用，
 * 保证同一字体的标签在两处颜色/文案一致。为可在非 @Composable 的 map 中调用，
 * 颜色与文案由调用方在 @Composable 作用域预取后传入。
 */
fun fontCapabilityBadges(
    spec: FontSpec,
    bundled: String,
    local: String,
    cjk: String,
    ligature: String,
    tertiary: Color,
    primary: Color,
    secondary: Color,
): List<BadgeSpec> = buildList {
    if (spec.bundled) add(BadgeSpec(bundled, tertiary))
    if (spec.userUploaded) add(BadgeSpec(local, tertiary))
    if (spec.cjk) add(BadgeSpec(cjk, primary))
    if (spec.ligature) add(BadgeSpec(ligature, secondary))
}

/**
 * 统一「设置入口行」：图标 + 标题 + 副标题 + 右向箭头，整行可点。
 * 收敛此前 MenuCard(Card) 与 FontManageEntry(Surface) 两套实现，统一为浮起卡片；
 * 箭头用 auto-mirror 图标（取代字面 "›"，可随 RTL 镜像、粗细可控）。
 */
@Composable
fun NavRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MokeShapes.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
