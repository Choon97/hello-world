package com.choon.presence

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

private data class Req(val method: String, val path: String, val headers: Map<String, String>, val body: String)
private data class Resp(val status: Int, val headers: Map<String, String> = emptyMap(), val body: String = "")

/** 요청마다 [responder] 가 응답을 정하는 최소 HTTP 서버 (연결 1개당 요청 1개). */
class HttpUploaderTest {
    private var socket: ServerSocket? = null
    private val requests = Collections.synchronizedList(mutableListOf<Req>())

    private fun start(responder: (Req) -> Resp): String {
        val ss = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        socket = ss
        thread(isDaemon = true) {
            while (!ss.isClosed) {
                val c = runCatching { ss.accept() }.getOrNull() ?: break
                runCatching {
                    c.use {
                        val input = it.getInputStream()
                        val head = StringBuilder()
                        while (!head.endsWith("\r\n\r\n")) head.append(input.read().also { b -> check(b >= 0) }.toChar())
                        val lines = head.lines()
                        val (method, path) = lines[0].split(' ').let { p -> p[0] to p[1] }
                        val h = lines.drop(1).filter { l -> l.contains(':') }
                            .associate { l -> l.substringBefore(':').lowercase() to l.substringAfter(':').trim() }
                        val body = String(input.readNBytes(h["content-length"]?.toInt() ?: 0), Charsets.UTF_8)
                        val req = Req(method, path, h, body)
                        requests += req
                        val r = responder(req)
                        val bytes = r.body.toByteArray()
                        val extra = r.headers.entries.joinToString("") { e -> "${e.key}: ${e.value}\r\n" }
                        it.getOutputStream().write(
                            ("HTTP/1.1 ${r.status} X\r\n${extra}Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray() + bytes)
                    }
                }
            }
        }
        return "http://127.0.0.1:${ss.localPort}"
    }

    @After fun stop() { socket?.close() }

    @Test fun sendsBodyAndBearerToken() {
        val base = start { Resp(200) }
        val r = HttpUploader.postJson("$base/ingest", "secret", """{"a":"한글"}""")
        assertTrue(r.ok); assertNull(r.error)
        assertEquals("""{"a":"한글"}""", requests[0].body)
        assertEquals("Bearer secret", requests[0].headers["authorization"])
        assertTrue(requests[0].headers["content-type"]!!.startsWith("application/json"))
    }

    @Test fun noAuthHeaderWithoutToken() {
        val base = start { Resp(204) }
        HttpUploader.postJson("$base/ingest", "", "{}")
        assertNull(requests[0].headers["authorization"])
    }

    @Test fun clientErrorIsPermanent() {
        val base = start { Resp(401) }
        val r = HttpUploader.postJson("$base/x", "x", "{}")
        assertFalse(r.ok); assertTrue(r.permanent)
    }

    @Test fun serverErrorIsRetryable() {
        val base = start { Resp(503) }
        val r = HttpUploader.postJson("$base/x", "x", "{}")
        assertFalse(r.ok); assertFalse(r.permanent)
    }

    @Test fun connectionFailureIsRetryable() {
        val r = HttpUploader.postJson("http://127.0.0.1:1/ingest", null, "{}", timeoutMs = 1000)
        assertEquals(-1, r.code); assertFalse(r.ok); assertFalse(r.permanent)
    }

    // 구글 Apps Script 처럼: POST 는 302 로 응답하고, Location 을 GET 하면 결과가 나온다.
    @Test fun followsRedirectWithGetAndWithoutAuthHeader() {
        val base = start { req ->
            if (req.method == "POST") Resp(302, mapOf("Location" to "/echo?id=1"))
            else Resp(200, mapOf("Content-Type" to "application/json"), """{"ok":true}""")
        }
        val r = HttpUploader.postJson("$base/exec?token=t", "secret", """{"d":1}""")
        assertTrue(r.toString(), r.ok)
        assertEquals(2, requests.size)
        assertEquals("GET", requests[1].method)
        assertEquals("/echo?id=1", requests[1].path)
        assertNull("리다이렉트 대상에는 토큰 헤더를 보내면 안 됨", requests[1].headers["authorization"])
        assertEquals("""{"d":1}""", requests[0].body)
    }

    @Test fun htmlResponseIsPermanentFailure() {
        val base = start { Resp(200, mapOf("Content-Type" to "text/html; charset=utf-8"), "<html>로그인</html>") }
        val r = HttpUploader.postJson("$base/exec", null, "{}")
        assertFalse(r.ok); assertTrue(r.permanent)
    }

    @Test fun okFalseBodyIsPermanentFailure() {
        val base = start { Resp(200, mapOf("Content-Type" to "application/json"), """{"ok": false, "error":"unauthorized"}""") }
        val r = HttpUploader.postJson("$base/exec", null, "{}")
        assertFalse(r.ok); assertTrue(r.permanent)
        assertTrue(r.error!!.contains("unauthorized"))
    }

    @Test fun okTrueBodyIsSuccess() {
        val base = start { Resp(200, mapOf("Content-Type" to "application/json"), """{"ok":true}""") }
        assertTrue(HttpUploader.postJson("$base/exec", null, "{}").ok)
    }

    @Test fun redirectLoopGivesUp() {
        val base = start { Resp(302, mapOf("Location" to "/again")) }
        val r = HttpUploader.postJson("$base/exec", null, "{}")
        assertFalse(r.ok); assertTrue(r.permanent)
    }
}
