package com.briqt.moke.terminal.sftp

/**
 * 远端路径的纯逻辑处理（无 IO、无 Android 依赖，全部可单测）。
 *
 * 远端一律按 POSIX 处理：分隔符 `/`、大小写敏感、文件名里除 `/` 和 NUL 外什么都可能出现
 * （空格、引号、中文、换行）。所以拼路径不能用字符串加法，回传终端不能不转义。
 */
object RemotePath {

    const val ROOT = "/"

    /** 折叠重复分隔符、消解 `.` 与 `..`、去掉结尾 `/`（根除外）。相对路径保持相对。 */
    fun normalize(path: String): String {
        if (path.isEmpty()) return ""
        val absolute = path.startsWith("/")
        val out = ArrayDeque<String>()
        for (seg in path.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> {
                    // 相对路径开头的 `..` 无处可退，必须原样保留，否则 `../x` 会被悄悄变成 `x`。
                    if (out.isNotEmpty() && out.last() != "..") out.removeLast()
                    else if (!absolute) out.addLast("..")
                }
                else -> out.addLast(seg)
            }
        }
        val joined = out.joinToString("/")
        return if (absolute) "/$joined" else joined
    }

    /** 在 [base] 下拼一个子项；[name] 为绝对路径时直接取它（面包屑/跳转框都会给绝对路径）。 */
    fun join(base: String, name: String): String {
        if (name.startsWith("/")) return normalize(name)
        val b = if (base.endsWith("/")) base else "$base/"
        return normalize(b + name)
    }

    /** 上级目录；根的上级仍是根。 */
    fun parent(path: String): String {
        val n = normalize(path)
        if (n == ROOT || n.isEmpty()) return ROOT
        val idx = n.lastIndexOf('/')
        return when {
            idx <= 0 -> ROOT
            else -> n.substring(0, idx)
        }
    }

    /** 末段名（根返回 `/`）。 */
    fun name(path: String): String {
        val n = normalize(path)
        if (n == ROOT || n.isEmpty()) return ROOT
        return n.substringAfterLast('/')
    }

    /** 面包屑：从根到自身，每段给出可点击的绝对路径。 */
    fun crumbs(path: String): List<Pair<String, String>> {
        val n = normalize(path)
        if (n == ROOT || n.isEmpty()) return listOf(ROOT to ROOT)
        val out = mutableListOf(ROOT to ROOT)
        val sb = StringBuilder()
        for (seg in n.trimStart('/').split('/')) {
            sb.append('/').append(seg)
            out += seg to sb.toString()
        }
        return out
    }

    /**
     * 单引号包裹的 shell 转义（「发送到终端」用）。与 `Tmux.q` 同一套做法：单引号内除 `'` 外
     * 一切都是字面量，`'` 用 `'\''` 拼接闭合。空串必须显式给出 `''`。
     */
    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** 由扩展名猜 MIME（只为让系统"打开方式"有得挑，猜不到给通用类型）。 */
    fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "txt", "log", "md", "yml", "yaml", "conf", "ini", "sh", "py", "kt", "java", "c", "h", "go", "rs" -> "text/plain"
            "json" -> "application/json"
            "xml" -> "text/xml"
            "html", "htm" -> "text/html"
            "csv" -> "text/csv"
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "zip" -> "application/zip"
            "gz", "tgz" -> "application/gzip"
            "tar" -> "application/x-tar"
            else -> "application/octet-stream"
        }
    }

    /** 人类可读大小（10 进制单位，与系统文件管理器口径一致）。 */
    fun formatSize(bytes: Long): String {
        if (bytes < 0) return "—"
        if (bytes < 1000) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var v = bytes.toDouble() / 1000
        var i = 0
        while (v >= 1000 && i < units.lastIndex) {
            v /= 1000
            i++
        }
        return if (v >= 100) "${v.toLong()} ${units[i]}" else String.format("%.1f %s", v, units[i])
    }
}
