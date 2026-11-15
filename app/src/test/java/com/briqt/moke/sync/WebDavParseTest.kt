package com.briqt.moke.sync

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class WebDavParseTest {
    private val dir = "https://dav.example.com/remote.php/dav/files/me/moke/".toHttpUrl()
    private val name = "moke_20261115-143005_%E6%88%91%E7%9A%84%E6%89%8B%E6%9C%BA_android_0.2.2.mokesync"

    @Test
    fun `路径形式的 href：跳过目录本身、子目录和非备份文件，名称解码`() {
        val xml = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
 <d:response><d:href>/remote.php/dav/files/me/moke/</d:href>
  <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
 <d:response><d:href>/remote.php/dav/files/me/moke/$name</d:href>
  <d:propstat><d:prop><d:resourcetype/><d:getcontentlength>1234</d:getcontentlength>
   <d:getlastmodified>Sun, 15 Nov 2026 06:30:05 GMT</d:getlastmodified></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
  <d:propstat><d:prop><oc:size/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>
 <d:response><d:href>/remote.php/dav/files/me/moke/old/</d:href>
  <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
 <d:response><d:href>/remote.php/dav/files/me/moke/notes.txt</d:href>
  <d:propstat><d:prop><d:resourcetype/><d:getcontentlength>3</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
</d:multistatus>"""
        val files = WebDavClient.parseMultistatus(xml, dir)
        assertEquals(1, files.size)
        assertEquals("moke_20261115-143005_我的手机_android_0.2.2.mokesync", files[0].name)
        assertEquals(1234L, files[0].size)
        assertEquals(1794724205000L, files[0].modifiedMillis)
    }

    @Test
    fun `绝对 URL 形式的 href 与大写前缀命名空间`() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:">
<D:response><D:href>https://dav.example.com/remote.php/dav/files/me/moke</D:href>
<D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
<D:response><D:href>https://dav.example.com/remote.php/dav/files/me/moke/a.mokesync</D:href>
<D:propstat><D:prop><D:resourcetype></D:resourcetype><D:getcontentlength>7</D:getcontentlength></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
</D:multistatus>"""
        assertEquals(listOf(RemoteFile("a.mokesync", 7, null)), WebDavClient.parseMultistatus(xml, dir))
    }

    @Test
    fun `带 DTD 的响应被拒绝，不解析外部实体`() {
        val xml = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]>
<d:multistatus xmlns:d="DAV:"><d:response><d:href>&e;</d:href></d:response></d:multistatus>"""
        try {
            WebDavClient.parseMultistatus(xml, dir)
            fail()
        } catch (e: WebDavException) {
            assertEquals(WebDavFailure.PROTOCOL, e.failure)
        }
    }

    @Test
    fun `状态码分类`() {
        assertEquals(WebDavFailure.AUTH, WebDavClient.failure(401).failure)
        assertEquals(WebDavFailure.NOT_FOUND, WebDavClient.failure(409).failure)
        assertEquals(WebDavFailure.QUOTA, WebDavClient.failure(507).failure)
        assertEquals(WebDavFailure.SERVER, WebDavClient.failure(503).failure)
    }

    @Test
    fun `只接受 https，目录 URL 以斜杠结尾`() {
        try {
            WebDavClient(WebDavConfig("http://dav.example.com/", "u", "p"))
            fail()
        } catch (e: WebDavException) {
            assertEquals(WebDavFailure.INVALID_URL, e.failure)
        }
        assertEquals("https://dav.example.com/dav/a/b/", WebDavClient(WebDavConfig("https://dav.example.com/dav/", "u", "p", "a/b")).directoryUrl.toString())
        assertEquals("https://dav.example.com/dav/moke/", WebDavClient(WebDavConfig("https://dav.example.com/dav", "u", "p")).directoryUrl.toString())
    }
}
