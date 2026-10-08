package com.choon.presence

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/** 한 번의 요청만 받아 지정한 상태코드를 돌려주는 최소 HTTP 서버. */
class HttpUploaderTest {
    private var socket: ServerSocket? = null
    @Volatile private var headers = mapOf<String, String>()
    @Volatile private var gotBody: String? = null

    private fun start(status: Int): String {
        val ss = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        socket = ss
        thread(isDaemon = true) {
            runCatching {
                ss.accept().use { c ->
                    val input = c.getInputStream()
                    val head = StringBuilder()
                    while (!head.endsWith("\r\n\r\n")) head.append(input.read().also { check(it >= 0) }.toChar())
                    val h = head.lines().drop(1).filter { it.contains(':') }
                        .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
                    headers = h
                    val len = h["content-length"]?.toInt() ?: 0
                    gotBody = String(input.readNBytes(len), Charsets.UTF_8)
                    c.getOutputStream().write("HTTP/1.1 $status X\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                }
            }
        }
        return "http://127.0.0.1:${ss.localPort}/ingest"
    }

    @After fun stop() { socket?.close() }

    @Test fun sendsBodyAndBearerToken() {
        val r = HttpUploader.postJson(start(200), "secret", """{"a":"한글"}""")
        assertTrue(r.ok); assertNull(r.error)
        assertEquals("""{"a":"한글"}""", gotBody)
        assertEquals("Bearer secret", headers["authorization"])
        assertTrue(headers["content-type"]!!.startsWith("application/json"))
    }

    @Test fun noAuthHeaderWithoutToken() {
        HttpUploader.postJson(start(204), "", "{}")
        assertNull(headers["authorization"])
    }

    @Test fun clientErrorIsPermanent() {
        val r = HttpUploader.postJson(start(401), "x", "{}")
        assertFalse(r.ok); assertTrue(r.permanent)
    }

    @Test fun serverErrorIsRetryable() {
        val r = HttpUploader.postJson(start(503), "x", "{}")
        assertFalse(r.ok); assertFalse(r.permanent)
    }

    @Test fun connectionFailureIsRetryable() {
        val r = HttpUploader.postJson("http://127.0.0.1:1/ingest", null, "{}", timeoutMs = 1000)
        assertEquals(-1, r.code); assertFalse(r.ok); assertFalse(r.permanent)
    }
}
