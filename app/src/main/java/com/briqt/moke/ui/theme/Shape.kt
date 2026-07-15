package com.briqt.moke.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * 墨客统一圆角档位。此前 3/4/5/6/8/12/20dp 散落在各组件里、相近元素各自为政；
 * 收敛成一套有语义的档位，让同类元素同档。数值本身仍可调，但只改这一处。
 */
object MokeShapes {
    /** 附加键键帽：刻意近乎平直，贴合终端观感。 */
    val keycap = RoundedCornerShape(3.dp)

    /** 极小圆角：色块、下拉项裁剪等。 */
    val xs = RoundedCornerShape(4.dp)

    /** 徽标 / 能力标签胶囊（统一原 4/5/6dp 三种）。 */
    val pill = RoundedCornerShape(6.dp)

    /** 可点的轻量面：标题栏下拉胶囊、分组头、弹窗选项行。 */
    val control = RoundedCornerShape(8.dp)

    /** 卡片 / 设置入口行 / 预览框。 */
    val card = RoundedCornerShape(12.dp)

    /** 浮层胶囊（如缩放提示）。 */
    val floating = RoundedCornerShape(20.dp)
}
