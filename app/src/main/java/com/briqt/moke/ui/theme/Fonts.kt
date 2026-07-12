package com.briqt.moke.ui.theme

import android.content.Context
import android.graphics.Typeface
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.core.content.res.ResourcesCompat
import com.briqt.moke.R

/**
 * 字体策略：
 * - UI chrome（含中文）用系统比例字体（[FontFamily.Default]）——等宽渲染中文会把每字塞进全宽格，显得零散、横向过长。
 * - 终端与技术字段（host:port、密钥等 ASCII 对齐场景）用等宽 [MokeMono]（JetBrains Mono, OFL）。
 *   JetBrains Mono 无 CJK，CJK 由 Android Typeface fallback 自动补（系统 Noto CJK）。
 * 更漂亮的中文等宽留给"字体管理"下载（P4）。
 */
val MokeMono: FontFamily = FontFamily(Font(R.font.jetbrains_mono))

/** 供 TerminalView（Android View 层）使用的等宽 Typeface。加载失败回退系统 MONOSPACE。 */
fun mokeMonoTypeface(context: Context): Typeface =
    ResourcesCompat.getFont(context, R.font.jetbrains_mono) ?: Typeface.MONOSPACE
