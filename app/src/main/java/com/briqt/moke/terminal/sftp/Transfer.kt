package com.briqt.moke.terminal.sftp

import org.json.JSONObject
import java.util.UUID

enum class TransferDirection { UPLOAD, DOWNLOAD }

enum class TransferState {
    /** 排队中，还没开始。 */
    QUEUED,
    RUNNING,
    DONE,
    /** 用户主动取消。已传的部分保留，可再「继续」。 */
    CANCELLED,
    /** 出错或被系统打断（进程被杀）。可「继续」，能续就续、不能续就重来。 */
    FAILED,
}

/**
 * 一条传输任务。**要能在进程被杀之后接着传**，所以它必须是可持久化的纯数据：
 * 断点信息（[done]）与远端身份（[remoteSize]/[remoteMtime]）都在这里，重启后据此判断能不能续。
 */
data class TransferTask(
    val id: String = UUID.randomUUID().toString(),
    val hostId: String,
    val hostLabel: String,
    val direction: TransferDirection,
    /** 远端文件完整路径。 */
    val remotePath: String,
    /** 展示名（= 文件名）。 */
    val name: String,
    /** 本地文件 URI：上传=来源；下载=已创建的目标文档（创建前为空）。 */
    val localUri: String = "",
    /** 下载目标所在的目录树 URI（用于第一次创建目标文档）。 */
    val treeUri: String = "",
    /** 总字节数；-1=未知。 */
    val total: Long = -1,
    /** 已完成字节数（续传起点）。 */
    val done: Long = 0,
    val state: TransferState = TransferState.QUEUED,
    /** 失败原因（用户可读）。 */
    val error: String = "",
    /** 上次看到的远端大小/修改时间：续传前用来判断远端文件是否还是同一个。 */
    val remoteSize: Long = -1,
    val remoteMtime: Long = -1,
    /** 本地目标是否支持追加写（部分 DocumentsProvider 不支持 "wa"）；false=只能整传。 */
    val appendSupported: Boolean = true,
    val createdAt: Long = 0L,
) {
    val finished: Boolean get() = state == TransferState.DONE
    val active: Boolean get() = state == TransferState.QUEUED || state == TransferState.RUNNING
    /** 0f~1f；总量未知时返回 null（UI 显示不确定进度）。 */
    val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("hostId", hostId)
        put("hostLabel", hostLabel)
        put("direction", direction.name)
        put("remotePath", remotePath)
        put("name", name)
        put("localUri", localUri)
        put("treeUri", treeUri)
        put("total", total)
        put("done", done)
        put("state", state.name)
        put("error", error)
        put("remoteSize", remoteSize)
        put("remoteMtime", remoteMtime)
        put("appendSupported", appendSupported)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = TransferTask(
            id = o.optString("id", UUID.randomUUID().toString()),
            hostId = o.optString("hostId", ""),
            hostLabel = o.optString("hostLabel", ""),
            direction = runCatching { TransferDirection.valueOf(o.optString("direction", "DOWNLOAD")) }
                .getOrDefault(TransferDirection.DOWNLOAD),
            remotePath = o.optString("remotePath", ""),
            name = o.optString("name", ""),
            localUri = o.optString("localUri", ""),
            treeUri = o.optString("treeUri", ""),
            total = o.optLong("total", -1),
            done = o.optLong("done", 0),
            state = runCatching { TransferState.valueOf(o.optString("state", "QUEUED")) }
                .getOrDefault(TransferState.QUEUED),
            error = o.optString("error", ""),
            remoteSize = o.optLong("remoteSize", -1),
            remoteMtime = o.optLong("remoteMtime", -1),
            appendSupported = o.optBoolean("appendSupported", true),
            createdAt = o.optLong("createdAt", 0L),
        )

        /**
         * 能不能从 [task] 的断点续下去：远端必须还是同一个文件。
         *
         * 大小或 mtime 任一不符 = 远端被改过，续传只会拼出一个损坏文件 → 从 0 重来。
         * 已完成字节大于当前远端大小同理（远端被截短）。本地不支持追加写时也只能重来。
         */
        fun canResume(task: TransferTask, current: RemoteStat?): Boolean {
            if (task.done <= 0L) return false
            if (!task.appendSupported) return false
            if (current == null || current.isDir) return false
            if (task.remoteSize >= 0 && current.size != task.remoteSize) return false
            if (task.remoteMtime >= 0 && current.mtime != task.remoteMtime) return false
            return task.done < current.size
        }

        /**
         * 上传续传的判定：远端已有的字节数必须**正好等于**我们记录的已传量。
         * 少了说明写入没落盘，多了说明有别人在写同一个文件——两种情况都从 0 重来。
         */
        fun canResumeUpload(task: TransferTask, remoteExisting: Long, localSize: Long): Boolean {
            if (task.done <= 0L) return false
            if (remoteExisting != task.done) return false
            if (localSize >= 0 && task.total >= 0 && localSize != task.total) return false
            return task.total < 0 || task.done < task.total
        }
    }
}
