package com.briqt.moke.terminal

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.res.ResourcesCompat
import com.briqt.moke.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * 终端字体的下载/存储/合成。下载的 ttf 存 `filesDir/fonts/<id>.ttf`；bundled 字体（JetBrains Mono）
 * 在 res/font。核心能力：**主字体 + 回退字体**合成——用 [Typeface.CustomFallbackBuilder]（API 29+）
 * 让 Latin 走主字体、CJK 落回退字体（如 Maple Mono）、其余系统兜底。
 */
class FontRepository(private val context: Context) {

    private val fontsDir = File(context.filesDir, "fonts")

    fun fileFor(id: String): File = File(fontsDir, "$id.ttf")

    fun isInstalled(spec: FontSpec): Boolean = spec.bundled || fileFor(spec.id).exists()

    fun delete(id: String) {
        fileFor(id).takeIf { it.exists() }?.delete()
    }

    /** 下载（archive 则解压提取 Regular ttf）。onProgress 报告 0f..1f。 */
    suspend fun download(spec: FontSpec, onProgress: (Float) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = spec.url ?: error("字体无下载地址")
                fontsDir.mkdirs()
                val tmp = File(context.cacheDir, "${spec.id}.dl")
                var conn = URL(url).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 20_000
                conn.readTimeout = 60_000
                conn.connect()
                // 手动跟随跨协议重定向（GitHub → objects.githubusercontent，一般同为 https）
                var redirects = 0
                while (conn.responseCode in listOf(301, 302, 303, 307, 308) && redirects < 5) {
                    val loc = conn.getHeaderField("Location") ?: break
                    conn.disconnect()
                    conn = URL(loc).openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = true
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 30_000
                    conn.connect()
                    redirects++
                }
                if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong.let { if (it > 0) it else spec.approxBytes }
                conn.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var readTotal = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            readTotal += n
                            if (total > 0) onProgress((readTotal.toFloat() / total).coerceIn(0f, 0.98f))
                        }
                    }
                }
                conn.disconnect()
                // 完整性校验：HTTPS 已保证传输，sha256 再防损坏/篡改。
                spec.sha256?.let { expected ->
                    val actual = sha256Of(tmp)
                    if (!actual.equals(expected, ignoreCase = true)) {
                        tmp.delete()
                        error("校验失败（sha256 不匹配）")
                    }
                }
                if (spec.archive) {
                    extractTtf(tmp, spec)
                    tmp.delete()
                } else {
                    tmp.copyTo(fileFor(spec.id), overwrite = true)
                    tmp.delete()
                }
                onProgress(1f)
            }.onFailure {
                // 失败清理半成品
                delete(spec.id)
            }
        }

    private fun sha256Of(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 从 zip 里提取匹配 entryHint 的 Regular 直立 ttf（排除 Italic）。 */
    private fun extractTtf(zip: File, spec: FontSpec) {
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val name = e.name.substringAfterLast('/')
                if (!e.isDirectory && name.endsWith(".ttf", true) &&
                    name.contains(spec.entryHint, true) && !name.contains("Italic", true)
                ) {
                    fileFor(spec.id).outputStream().use { zin.copyTo(it) }
                    return
                }
                zin.closeEntry()
            }
        }
        error("压缩包内未找到匹配 '${spec.entryHint}' 的 ttf")
    }

    /** 合成主字体 + 回退字体为一个 Typeface（供 TerminalView.setTypeface）。 */
    fun resolveTypeface(primaryId: String?, fallbackId: String?): Typeface {
        val primary = specFor(primaryId) ?: FontCatalog.byId(FontCatalog.DEFAULT_ID)
        val fallback = fallbackId?.takeIf { it.isNotBlank() }?.let { specFor(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return buildWithFallback(primary, fallback) ?: bundledTypeface()
        }
        // API < 29：无自定义回退。优先已装的主字体文件，否则内置。
        val f = if (!primary.bundled) fileFor(primary.id) else null
        return if (f != null && f.exists()) runCatching { Typeface.createFromFile(f) }.getOrNull()
            ?: bundledTypeface() else bundledTypeface()
    }

    /** id → FontSpec：先查内置目录；否则若本地已有该字体文件（下载/用户上传）则合成一个非内置 spec。 */
    private fun specFor(id: String?): FontSpec? {
        if (id.isNullOrBlank()) return null
        FontCatalog.all.firstOrNull { it.id == id }?.let { return it }
        if (fileFor(id).exists()) {
            return FontSpec(
                id = id, name = id, nameZh = id, license = "", cjk = true,
                bundled = false, url = null, archive = false, entryHint = "", approxBytes = 0,
            )
        }
        return null
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun buildWithFallback(primary: FontSpec, fallback: FontSpec?): Typeface? = runCatching {
        val pf = loadFont(primary) ?: return@runCatching null
        val builder = Typeface.CustomFallbackBuilder(
            android.graphics.fonts.FontFamily.Builder(pf).build()
        )
        if (fallback != null && fallback.id != primary.id) {
            loadFont(fallback)?.let {
                builder.addCustomFallback(android.graphics.fonts.FontFamily.Builder(it).build())
            }
        }
        // 符号回退（内置 Noto Sans Symbols 2）：补 ⏵/媒体/几何/杂项符号等主-回退字体常缺的终端字形。
        loadSymbolsFont()?.let {
            builder.addCustomFallback(android.graphics.fonts.FontFamily.Builder(it).build())
        }
        builder.setSystemFallback("sans-serif")
        builder.build()
    }.getOrNull()

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun loadSymbolsFont(): android.graphics.fonts.Font? = runCatching {
        android.graphics.fonts.Font.Builder(context.resources, R.font.noto_sans_symbols2).build()
    }.getOrNull()

    /** 导入本地字体文件（TTF/OTF）：拷进 fonts 目录并校验可加载，返回生成的字体 id。 */
    suspend fun importFont(uri: android.net.Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            fontsDir.mkdirs()
            val id = "user_" + java.util.UUID.randomUUID().toString().take(8)
            val out = fileFor(id)
            context.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { input.copyTo(it) }
            } ?: error("无法读取所选文件")
            if (runCatching { Typeface.createFromFile(out) }.getOrNull() == null) {
                out.delete(); error("不是有效的字体文件（需 TTF / OTF）")
            }
            id
        }
    }

    /** 内置字体 id → res/font 资源。 */
    private fun bundledResId(id: String): Int? = when (id) {
        "jetbrains_mono" -> R.font.jetbrains_mono
        "noto_sans_sc" -> R.font.noto_sans_sc
        else -> null
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun loadFont(spec: FontSpec): android.graphics.fonts.Font? = runCatching {
        if (spec.bundled) {
            val resId = bundledResId(spec.id) ?: R.font.jetbrains_mono
            android.graphics.fonts.Font.Builder(context.resources, resId).build()
        } else {
            val f = fileFor(spec.id)
            if (f.exists()) android.graphics.fonts.Font.Builder(f).build() else null
        }
    }.getOrNull()

    private fun bundledTypeface(): Typeface =
        ResourcesCompat.getFont(context, R.font.jetbrains_mono) ?: Typeface.MONOSPACE
}
