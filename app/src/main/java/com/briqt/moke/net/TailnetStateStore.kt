package com.briqt.moke.net

import android.content.Context
import com.briqt.moke.data.CredentialCrypto
import com.briqt.moke.go.netbridge.StateStore
import java.io.File
import java.util.Base64

/**
 * tailnet 节点状态的落盘：每个网络一个目录、每个键一个文件，内容经 [encrypt]（线上是 Keystore
 * 那把 AES-GCM）加密。放在 `noBackupFilesDir`：节点密钥不随备份/换机迁移，新设备重新登录。
 *
 * 这里只管字节，不理解内容；读不到返回 null（netbridge 视为「无此键」），写 null 即删除。
 */
class EncryptedStateFiles(
    private val root: File,
    private val encrypt: (String) -> String,
    private val decrypt: (String) -> String,
) {
    @Synchronized
    fun read(networkId: String, key: String): ByteArray? {
        val f = fileOf(networkId, key)
        if (!f.exists()) return null
        return Base64.getDecoder().decode(decrypt(f.readText()))
    }

    @Synchronized
    fun write(networkId: String, key: String, value: ByteArray?) {
        val f = fileOf(networkId, key)
        if (value == null) {
            f.delete()
            return
        }
        f.parentFile!!.mkdirs()
        // 先写临时文件再改名：进程中途被杀也不会留下半个密文（读到就是整份或旧份）。
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(encrypt(Base64.getEncoder().encodeToString(value)))
        if (!tmp.renameTo(f)) {
            tmp.delete()
            throw IllegalStateException("cannot replace node state")
        }
    }

    /** 删除网络时连同节点状态一起删掉。 */
    @Synchronized
    fun deleteNetwork(networkId: String) {
        dirOf(networkId).deleteRecursively()
    }

    private fun dirOf(networkId: String): File {
        require(networkId.isNotEmpty() && networkId.length <= 64 && networkId.all { it.isLetterOrDigit() || it in "-_." } &&
            networkId != "." && networkId != "..") { "invalid network id" }
        return File(root, networkId)
    }

    /** 键名转十六进制做文件名：不受键里任何字符影响，也不会跳出目录。 */
    private fun fileOf(networkId: String, key: String): File =
        File(dirOf(networkId), key.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) })
}

/** 交给 netbridge 的状态存储（Keystore 加密）。 */
class TailnetStateStore(context: Context) : StateStore {
    val files = EncryptedStateFiles(
        File(context.noBackupFilesDir, "tailnet-state"),
        CredentialCrypto::encrypt,
        CredentialCrypto::decrypt,
    )

    override fun read(networkID: String, key: String): ByteArray? = files.read(networkID, key)
    override fun write(networkID: String, key: String, value: ByteArray?) = files.write(networkID, key, value)
}
