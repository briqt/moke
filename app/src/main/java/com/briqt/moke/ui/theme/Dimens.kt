package com.briqt.moke.ui.theme

import androidx.compose.ui.unit.dp

/** 墨客杂项设计常量（非颜色 / 非形状）。集中放置，避免同一数值在多页各写一遍。 */
object MokeDimens {
    /** 顶栏统一高度：各页 TopAppBar 的 expandedHeight 与终端自定义顶栏一致（略压默认 56）。 */
    val topBarHeight = 49.dp

    /** 徽标 / 能力标签底色透明度：在强调色上叠一层淡背景（统一原 0.15/0.16 两种取值）。 */
    const val badgeAlpha = 0.16f
}
