package com.briqt.moke.terminal

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.PublicKey

/**
 * 已知主机指纹存储（TOFU）。用 SharedPreferences（同步 API，数据量小，适合在连接线程读写）。
 * key = "host:port"，value = "SHA256:<base64>"。
 */
class KnownHosts(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("moke_known_hosts", Context.MODE_PRIVATE)

    fun fingerprint(key: PublicKey): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.encoded)
        return "SHA256:" + Base64.encodeToString(digest, Base64.NO_PADDING or Base64.NO_WRAP)
    }

    fun stored(id: String): String? = prefs.getString(id, null)

    fun store(id: String, fingerprint: String) {
        prefs.edit().putString(id, fingerprint).apply()
    }
}
