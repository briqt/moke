package com.briqt.moke.data

import org.json.JSONObject
import java.util.UUID

enum class AuthType { PASSWORD, KEY }

/**
 * 会话持久化：连接这台主机时是否自动接入多路复用器。
 * [NONE] 与历史行为一致（普通登录壳）；[TMUX] 连接即进 tmux（存在则附加、不存在则创建）。
 */
enum class SessionPersistence { NONE, TMUX }

/** 连接主机配置。v0.1 持久化在 DataStore（明文 JSON），后续再上安全存储。 */
data class Host(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "",
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val authType: AuthType = AuthType.PASSWORD,
    val password: String = "",
    val privateKeyPem: String = "",
    val passphrase: String = "",
    /** 是否偏好 mosh。 */
    val useMosh: Boolean = false,
    /** 跳板机：引用另一台已存主机的 id（空=直连）。经其 direct-tcpip 转发到本机。 */
    val jumpHostId: String = "",
    /**
     * 协议级启动命令（空=远端默认 login shell）。与 [loginCommand] 语义不同：
     * 这条由 SSH `exec` / `mosh-server -- …` 直接启动（Windows 主机指定 cmd/powershell/wsl 靠它），
     * 而 [loginCommand] 是 shell 起来之后往 PTY 里敲的一行。
     */
    val startupCommand: String = "",
    /** 连接成功后自动执行的命令（空=无）。 */
    val loginCommand: String = "",
    /** 分组（可选，空=未分组）。用于连接页可选分组展示。 */
    val group: String = "",
    /** 最近连接时间（epoch ms，0=从未），用于"最近使用"排序。 */
    val lastConnectedAt: Long = 0L,
    /** 会话持久化方式（默认 NONE=保持历史行为）。 */
    val persistence: SessionPersistence = SessionPersistence.NONE,
    /**
     * 上次在这台主机选择的 tmux 会话名（空=还没选过）。
     * [SessionPersistence.TMUX] 下：非空则连接时直接按名附加；空则先开普通壳并弹选择器。
     */
    val tmuxSessionName: String = "",
) {
    val displayName: String get() = label.ifBlank { if (host.isBlank()) "未命名" else "$username@$host" }

    /**
     * 本次连接实际要用的协议级启动命令（空=默认 login shell）。
     * 会话持久化=tmux 时这个位置由 tmux 附加命令占用（见 `Tmux.attachOrCreateCommand`），
     * 用户设的启动命令让位——否则要么覆盖掉 tmux、要么两条命令抢同一个 exec 通道。
     */
    val effectiveStartupCommand: String
        get() = if (persistence == SessionPersistence.TMUX) "" else startupCommand.trim()

    /** 协议短名（连接列表徽标用）。 */
    val protocol: String get() = if (useMosh) "mosh" else "SSH"

    /** 可复制到剪贴板的连接命令。 */
    val connectCommand: String get() = if (useMosh) {
        "mosh ${username}@${host}" + (if (port != 22) " --ssh=\"ssh -p $port\"" else "")
    } else {
        "ssh " + (if (port != 22) "-p $port " else "") + "${username}@${host}"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("host", host)
        put("port", port)
        put("username", username)
        put("authType", authType.name)
        put("password", password)
        put("privateKeyPem", privateKeyPem)
        put("passphrase", passphrase)
        put("useMosh", useMosh)
        put("jumpHostId", jumpHostId)
        put("startupCommand", startupCommand)
        put("loginCommand", loginCommand)
        put("group", group)
        put("lastConnectedAt", lastConnectedAt)
        put("persistence", persistence.name)
        put("tmuxSessionName", tmuxSessionName)
    }

    companion object {
        fun fromJson(o: JSONObject) = Host(
            id = o.optString("id", UUID.randomUUID().toString()),
            label = o.optString("label", ""),
            host = o.optString("host", ""),
            port = o.optInt("port", 22),
            username = o.optString("username", ""),
            authType = runCatching { AuthType.valueOf(o.optString("authType", "PASSWORD")) }
                .getOrDefault(AuthType.PASSWORD),
            password = o.optString("password", ""),
            privateKeyPem = o.optString("privateKeyPem", ""),
            passphrase = o.optString("passphrase", ""),
            useMosh = o.optBoolean("useMosh", false),
            jumpHostId = o.optString("jumpHostId", ""),
            startupCommand = o.optString("startupCommand", ""),
            loginCommand = o.optString("loginCommand", ""),
            group = o.optString("group", ""),
            lastConnectedAt = o.optLong("lastConnectedAt", 0L),
            persistence = runCatching {
                SessionPersistence.valueOf(o.optString("persistence", "NONE"))
            }.getOrDefault(SessionPersistence.NONE),
            tmuxSessionName = o.optString("tmuxSessionName", ""),
        )
    }
}
