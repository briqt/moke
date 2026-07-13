package com.briqt.moke.data

import androidx.annotation.StringRes
import com.briqt.moke.R

/** 连接列表排序方式。标签用字符串资源（随语言切换）。 */
enum class HostSort(@StringRes val labelRes: Int) {
    GROUP(R.string.sort_group),
    NAME(R.string.sort_name),
    RECENT(R.string.sort_recent),
    MANUAL(R.string.sort_manual),
    ;

    companion object {
        val DEFAULT = GROUP
        fun fromName(s: String?): HostSort = entries.firstOrNull { it.name == s } ?: DEFAULT
    }
}
