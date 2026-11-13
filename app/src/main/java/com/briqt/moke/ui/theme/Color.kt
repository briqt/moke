package com.briqt.moke.ui.theme

import androidx.compose.ui.graphics.Color

// 墨客配色「墨分五色」：一锭墨分浓淡管层次，唯一的彩色是朱砂印。
// 深处是墨（略偏冷），浅处是纸（略偏暖），中间的灰由两头混出来。
// 品牌色不进可点元素：主色就是正文墨色，强调靠字重；彩色只留给状态（绿）、危险（胭脂）和朱砂。
// 命名保持稳定，供 Theme.kt 映射到 Material3 全量角色，避免未定义角色回退默认紫。

// ---------------- 深色 ----------------

val InkBg = Color(0xFF0C0D0E)            // 页面底
val InkSurface = Color(0xFF161718)       // 栏、卡片
val InkSurfaceLow = Color(0xFF121314)
val InkDialog = Color(0xFF1D1E1F)        // 弹窗、菜单、底部弹层
val InkControl = Color(0xFF272829)       // 控件底、输入框底、按键
val InkLifted = Color(0xFF323334)        // 选中块、导航指示条
val InkHairline = Color(0xFF2C2D2E)      // 卡片细线、分隔线
val InkOutline = Color(0xFF73726C)       // 描边（输入框、按下态）
val InkMuted = Color(0xFFA6A59F)         // 说明字
val InkText = Color(0xFFECEAE4)          // 正文，也是主色

val InkSeal = Color(0xFFD9532C)          // 朱砂：光标
val InkOk = Color(0xFF72C48F)            // 状态绿
val InkDanger = Color(0xFFF07892)        // 胭脂：危险操作与报错
val InkOnDanger = Color(0xFF2A0A12)
val InkDangerContainer = Color(0xFF3D1620)
val InkOnDangerContainer = Color(0xFFFFD9E0)

// ---------------- 浅色 ----------------

val PaperBg = Color(0xFFF2F1EC)
val PaperSurface = Color(0xFFFCFBF8)
val PaperSurfaceLow = Color(0xFFF7F6F2)
val PaperDialog = Color(0xFFFCFBF8)
val PaperControl = Color(0xFFE6E4DD)
val PaperLifted = Color(0xFFDAD8D0)
val PaperHairline = Color(0xFFDEDCD5)
val PaperOutline = Color(0xFF86857F)
val PaperMuted = Color(0xFF55544E)
val PaperText = Color(0xFF17181A)

val PaperSeal = Color(0xFFBC3D17)
val PaperOk = Color(0xFF2A7449)
val PaperDanger = Color(0xFFA8244D)
val PaperOnDanger = Color(0xFFFFFFFF)
val PaperDangerContainer = Color(0xFFF8DDE3)
val PaperOnDangerContainer = Color(0xFF3E0718)

// ---------------- 两套共用 ----------------

/** 朱砂实心块（角标、小印）：两套主题同一个值，其上白字对比 5.5。也是启动图标的印色。 */
val SealFill = Color(0xFFBC3D17)
val OnSealFill = Color(0xFFFFFFFF)
