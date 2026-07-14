package com.briqt.moke.data

import androidx.annotation.StringRes
import com.briqt.moke.R

/**
 * 列表分组维度（连接页 / 会话页共用；每页只暴露适用子集）。与 [SortBy] 是两个正交维度。
 * - 连接页：NONE / PROJECT
 * - 会话页：NONE / PROJECT / HOST / DATE
 */
enum class GroupBy(@StringRes val labelRes: Int) {
    NONE(R.string.group_none),
    PROJECT(R.string.group_project),   // 按连接的 group 字段（项目）
    HOST(R.string.group_host),         // 按主机（会话页）
    DATE(R.string.group_date),         // 按会话开始日期（会话页）
    ;

    companion object {
        fun fromName(s: String?, def: GroupBy): GroupBy = entries.firstOrNull { it.name == s } ?: def
    }
}

/** 列表排序维度（连接页 / 会话页共用）。MANUAL=长按拖动的自定义顺序（此时忽略分组、平铺）。 */
enum class SortBy(@StringRes val labelRes: Int) {
    NAME(R.string.sort_name),
    RECENT(R.string.sort_recent),
    MANUAL(R.string.sort_manual),
    ;

    companion object {
        fun fromName(s: String?, def: SortBy): SortBy = entries.firstOrNull { it.name == s } ?: def
    }
}
