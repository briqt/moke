package com.briqt.moke.data

import androidx.annotation.StringRes
import com.briqt.moke.R

/**
 * 会话页分组维度（连接页已固定按项目、不再用本枚举）。与 [SortBy] 是两个正交维度。
 * 会话页：NONE（无）/ HOST（按连接=会话所属主机）/ PROJECT（按项目=主机 group 字段）。
 */
enum class GroupBy(@StringRes val labelRes: Int) {
    NONE(R.string.group_none),
    HOST(R.string.group_host),         // 按连接（会话所属主机）
    PROJECT(R.string.group_project),   // 按项目（主机 group 字段）
    ;

    companion object {
        fun fromName(s: String?, def: GroupBy): GroupBy = entries.firstOrNull { it.name == s } ?: def
    }
}

/** 会话页排序维度（均为组内排序）。CREATED=创建时间、UPDATED=最后活动时间（均倒序）；MANUAL=长按拖动的自定义顺序。 */
enum class SortBy(@StringRes val labelRes: Int) {
    CREATED(R.string.sort_created),
    UPDATED(R.string.sort_updated),
    MANUAL(R.string.sort_manual),
    ;

    companion object {
        fun fromName(s: String?, def: SortBy): SortBy = entries.firstOrNull { it.name == s } ?: def
    }
}
