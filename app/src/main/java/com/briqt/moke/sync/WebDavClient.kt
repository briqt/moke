package com.briqt.moke.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.xml.parsers.DocumentBuilderFactory

/** WebDAV 服务配置。[directory] 是相对 [url] 的目录（如 `/moke/`）。 */
data class WebDavConfig(
    val url: String,
    val username: String,
    val password: String,
    val directory: String = DEFAULT_DIRECTORY,
) {
    companion object {
        const val DEFAULT_DIRECTORY = "/moke/"
    }
}

data class RemoteFile(val name: String, val size: Long, val modifiedMillis: Long?)

enum class WebDavFailure { INVALID_URL, AUTH, FORBIDDEN, NOT_FOUND, QUOTA, CERTIFICATE, NETWORK, SERVER, PROTOCOL, TOO_LARGE }

class WebDavException(val failure: WebDavFailure, val status: Int = 0, cause: Throwable? = null) : IOException(failure.name, cause)

/**
 * 最小 WebDAV 客户端：PROPFIND（列目录）、MKCOL（建目录）、GET、PUT、DELETE；Basic 认证，仅 https。
 * 系统自带的 HttpURLConnection 不允许 PROPFIND，所以用 OkHttp。
 */
class WebDavClient(private val config: WebDavConfig, private val http: OkHttpClient = sharedClient) {

    private val base: HttpUrl = config.url.trim().toHttpUrlOrNull()?.takeIf { it.isHttps }
        ?: throw WebDavException(WebDavFailure.INVALID_URL)

    private val dirSegments: List<String> = config.directory.split('/').filter { it.isNotBlank() }

    /** 目录 URL，以斜杠结尾。 */
    val directoryUrl: HttpUrl = base.newBuilder().apply {
        dirSegments.forEach { addPathSegment(it) }
        addPathSegment("")
    }.build()

    private fun fileUrl(name: String): HttpUrl = directoryUrl.newBuilder().addPathSegment(name).build()

    private val auth = Credentials.basic(config.username, config.password, Charsets.UTF_8)

    /** 列出目录里的备份文件（不含子目录）。 */
    suspend fun list(): List<RemoteFile> = withContext(Dispatchers.IO) {
        val body = PROPFIND_BODY.toRequestBody(XML)
        call(request(directoryUrl).method("PROPFIND", body).header("Depth", "1")) { r ->
            when (r.code) {
                207 -> parseMultistatus(r.body.string(), directoryUrl)
                else -> throw failure(r.code)
            }
        }
    }

    /** 目录不存在就逐级创建（已存在的层级服务器回 405，视为成功）。 */
    suspend fun ensureDirectory() = withContext(Dispatchers.IO) {
        var url = base
        for (segment in dirSegments) {
            url = url.newBuilder().addPathSegment(segment).addPathSegment("").build()
            call(request(url).method("MKCOL", null)) { r ->
                if (r.code !in setOf(200, 201, 204, 301, 405)) throw failure(r.code)
            }
        }
        // 用一次列目录确认确实可读写到这里（认证、权限都在这一步暴露）。
        list()
    }

    /** 新建文件；同名已存在时返回 false（绝不覆盖）。 */
    suspend fun create(name: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val body: RequestBody = bytes.toRequestBody(OCTET)
        call(request(fileUrl(name)).put(body).header("If-None-Match", "*")) { r ->
            when (r.code) {
                200, 201, 204 -> true
                412 -> false
                else -> throw failure(r.code)
            }
        }
    }

    suspend fun download(name: String): ByteArray = withContext(Dispatchers.IO) {
        call(request(fileUrl(name)).get()) { r ->
            if (r.code != 200) throw failure(r.code)
            val declared = r.body.contentLength()
            if (declared > MAX_FILE_BYTES) throw WebDavException(WebDavFailure.TOO_LARGE)
            val out = ByteArrayOutputStream()
            r.body.byteStream().use { input ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_FILE_BYTES) throw WebDavException(WebDavFailure.TOO_LARGE)
                }
            }
            out.toByteArray()
        }
    }

    suspend fun delete(name: String) = withContext(Dispatchers.IO) {
        call(request(fileUrl(name)).delete()) { r ->
            if (r.code !in setOf(200, 202, 204, 404)) throw failure(r.code)
        }
    }

    private fun request(url: HttpUrl) = Request.Builder().url(url).header("Authorization", auth)

    private inline fun <T> call(builder: Request.Builder, handle: (Response) -> T): T {
        val response = try {
            http.newCall(builder.build()).execute()
        } catch (e: WebDavException) {
            throw e
        } catch (e: SSLException) {
            throw WebDavException(WebDavFailure.CERTIFICATE, cause = e)
        } catch (e: UnknownHostException) {
            throw WebDavException(WebDavFailure.NETWORK, cause = e)
        } catch (e: ConnectException) {
            throw WebDavException(WebDavFailure.NETWORK, cause = e)
        } catch (e: NoRouteToHostException) {
            throw WebDavException(WebDavFailure.NETWORK, cause = e)
        } catch (e: SocketTimeoutException) {
            throw WebDavException(WebDavFailure.NETWORK, cause = e)
        } catch (e: IOException) {
            throw WebDavException(WebDavFailure.NETWORK, cause = e)
        }
        return response.use { r ->
            try {
                handle(r)
            } catch (e: WebDavException) {
                throw e
            } catch (e: IOException) {
                throw WebDavException(WebDavFailure.NETWORK, cause = e)
            }
        }
    }

    companion object {
        const val MAX_FILE_BYTES = 32L * 1024 * 1024
        private val XML = "application/xml; charset=utf-8".toMediaType()
        private val OCTET = "application/octet-stream".toMediaType()
        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""

        val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        }

        fun failure(code: Int): WebDavException = WebDavException(
            when (code) {
                401 -> WebDavFailure.AUTH
                403 -> WebDavFailure.FORBIDDEN
                404, 409 -> WebDavFailure.NOT_FOUND
                507 -> WebDavFailure.QUOTA
                in 500..599 -> WebDavFailure.SERVER
                else -> WebDavFailure.PROTOCOL
            },
            code,
        )

        /**
         * 解析 PROPFIND 的 207 响应：只要 [dir] 里直接的备份文件，跳过目录本身与子目录。
         * DTD 与外部实体一律关闭（响应来自外部服务器）。
         */
        fun parseMultistatus(xml: String, dir: HttpUrl): List<RemoteFile> {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
                listOf(
                    "http://apache.org/xml/features/disallow-doctype-decl" to true,
                    "http://xml.org/sax/features/external-general-entities" to false,
                    "http://xml.org/sax/features/external-parameter-entities" to false,
                ).forEach { (feature, on) -> runCatching { setFeature(feature, on) } }
            }
            val doc = try {
                factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
            } catch (e: Exception) {
                throw WebDavException(WebDavFailure.PROTOCOL, cause = e)
            }
            val dirPath = dir.encodedPath.trimEnd('/')
            val responses = doc.getElementsByTagNameNS(DAV, "response")
            val out = mutableListOf<RemoteFile>()
            for (i in 0 until responses.length) {
                val resp = responses.item(i) as Element
                val href = resp.firstText("href") ?: continue
                val url = dir.resolve(href.trim()) ?: continue
                if (url.encodedPath.trimEnd('/') == dirPath) continue
                val okProps = resp.childrenNS("propstat").filter { ps ->
                    val status = ps.firstText("status").orEmpty()
                    status.isEmpty() || status.contains(" 200")
                }.flatMap { it.childrenNS("prop") }
                if (okProps.any { p -> p.childrenNS("resourcetype").any { it.childrenNS("collection").isNotEmpty() } }) continue
                val name = url.pathSegments.lastOrNull { it.isNotEmpty() } ?: continue
                if (!BackupName.isBackup(name)) continue
                val size = okProps.firstNotNullOfOrNull { it.firstText("getcontentlength")?.trim()?.toLongOrNull() } ?: -1L
                val modified = okProps.firstNotNullOfOrNull { it.firstText("getlastmodified") }?.let(::parseHttpDate)
                out += RemoteFile(name, size, modified)
            }
            return out
        }

        private const val DAV = "DAV:"

        private fun Element.childrenNS(local: String): List<Element> {
            val nodes = childNodes
            return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
                .filter { it.namespaceURI == DAV && it.localName == local }
        }

        private fun Element.firstText(local: String): String? =
            getElementsByTagNameNS(DAV, local).let { if (it.length == 0) null else it.item(0).textContent }

        private fun parseHttpDate(s: String): Long? =
            runCatching { ZonedDateTime.parse(s.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
    }
}
