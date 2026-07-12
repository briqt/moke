package com.briqt.moke.data

/** 连接列表排序方式。 */
enum class HostSort(val label: String) {
    GROUP("分组"),
    NAME("名称"),
    RECENT("最近连接"),
    ;

    companion object {
        val DEFAULT = GROUP
        fun fromName(s: String?): HostSort = entries.firstOrNull { it.name == s } ?: DEFAULT
    }
}
