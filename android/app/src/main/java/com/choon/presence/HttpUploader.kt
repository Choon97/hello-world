package com.choon.presence

import java.net.HttpURLConnection
import java.net.URL

data class UploadResult(val code: Int, val error: String?, val forcePermanent: Boolean = false) {
    val ok: Boolean get() = code in 200..299 && error == null
    /** 재시도해도 소용없는 오류(잘못된 주소/인증/형식/배포 권한). 사용자가 설정을 고쳐야 한다. */
    val permanent: Boolean get() = forcePermanent || (code in 400..499 && code != 408 && code != 429)
    override fun toString() = if (ok) "성공(HTTP $code)" else "실패(${error ?: "HTTP $code"})"
}

object HttpUploader {
    private const val MAX_REDIRECTS = 4
    private val OK_FALSE = Regex(""""ok"\s*:\s*false""")

    /**
     * JSON 을 POST 한다.
     * - 301/302/303 은 직접 따라간다(구글 Apps Script 는 항상 302 로 응답). 이때 인증 헤더는 보내지 않고,
     *   처음 주소와 다른 프로토콜(https→http)로의 이동은 거부한다.
     * - HTTP 200 이어도 HTML 응답(로그인 페이지 등)이거나 본문에 "ok":false 가 있으면 실패로 본다.
     */
    fun postJson(url: String, token: String?, body: String, timeoutMs: Int = 15_000): UploadResult {
        return try {
            val origin = URL(url)
            var target = origin
            var first = true
            repeat(MAX_REDIRECTS + 1) {
                val conn = (target.openConnection() as HttpURLConnection).apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    instanceFollowRedirects = false
                    if (first) {
                        requestMethod = "POST"
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer ${token.trim()}")
                    } else {
                        requestMethod = "GET"
                    }
                }
                try {
                    if (first) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    if (code == 301 || code == 302 || code == 303) {
                        val loc = conn.getHeaderField("Location")
                            ?: return UploadResult(code, "리다이렉트 대상 없음")
                        val next = URL(target, loc)
                        if (next.protocol != origin.protocol) return UploadResult(code, "다른 프로토콜로의 리다이렉트 거부", true)
                        target = next
                        first = false
                        return@repeat
                    }
                    return judge(code, conn)
                } finally {
                    conn.disconnect()
                }
            }
            UploadResult(-1, "리다이렉트가 너무 많음", true)
        } catch (e: Exception) {
            UploadResult(-1, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        }
    }

    private fun judge(code: Int, conn: HttpURLConnection): UploadResult {
        if (code !in 200..299) return UploadResult(code, "HTTP $code")
        val type = conn.contentType.orEmpty()
        val text = runCatching {
            conn.inputStream.use { s ->
                val buf = ByteArray(4096)
                val n = s.read(buf)
                if (n > 0) String(buf, 0, n, Charsets.UTF_8) else ""
            }
        }.getOrDefault("")
        return when {
            type.contains("text/html", ignoreCase = true) ->
                UploadResult(code, "서버가 HTML 을 돌려줌 (주소/배포 권한 확인)", true)
            OK_FALSE.containsMatchIn(text) ->
                UploadResult(code, "서버가 거부함: ${text.take(100)}", true)
            else -> UploadResult(code, null)
        }
    }
}
