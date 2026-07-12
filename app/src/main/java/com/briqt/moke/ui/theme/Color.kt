package com.briqt.moke.ui.theme

import androidx.compose.ui.graphics.Color

// 墨客配色（暗色，GitHub-dark 基调 + 墨玉绿点缀）。
// 命名保持稳定，供 Theme.kt 映射到 Material3 全量角色，避免未定义角色回退默认紫。

// 基础
val MokeBg = Color(0xFF0E1116)
val MokeSurface = Color(0xFF161B22)
val MokeSurfaceVariant = Color(0xFF21262D)
val MokeInk = Color(0xFFE6EDF3)
val MokeJade = Color(0xFF7FE3C4)
val MokeMuted = Color(0xFF8B949E)
val MokeDanger = Color(0xFFF07178)

// 深浅墨玉（primary/secondary 的 container 与反色）
val MokeOnJade = Color(0xFF08110D)         // 墨玉上的深字
val MokeJadeContainer = Color(0xFF10362B)  // FAB 等 primaryContainer
val MokeOnJadeContainer = Color(0xFFA7F0D9)
val MokeJadeInverse = Color(0xFF2C6B58)

val MokeTeal = Color(0xFF6FD3C0)           // secondary
val MokeTealContainer = Color(0xFF22403A)  // TonalButton / Chip 选中
val MokeOnTealContainer = Color(0xFFB8E8DD)

val MokeCyan = Color(0xFF8CC7E3)           // tertiary（互补冷色）
val MokeOnCyan = Color(0xFF06121A)
val MokeCyanContainer = Color(0xFF1E3A47)
val MokeOnCyanContainer = Color(0xFFBEE3F2)

// 分层表面（M3 surfaceContainer* 阶梯）
val MokeSurfaceLowest = Color(0xFF0B0E12)
val MokeSurfaceLow = Color(0xFF12171E)
val MokeSurfaceHigh = Color(0xFF1C222B)
val MokeSurfaceHighest = Color(0xFF262D37)

// 文字/描边
val MokeOnSurfaceVariant = Color(0xFFB0BAC5)
val MokeOutline = Color(0xFF5A6572)
val MokeOutlineVariant = Color(0xFF333B44)

// 错误
val MokeOnDanger = Color(0xFF2A0E10)
val MokeDangerContainer = Color(0xFF4A1B1F)
val MokeOnDangerContainer = Color(0xFFFFD9DB)
