package com.choon.presence

import java.net.HttpURLConnection
import java.net.URL

data class UploadResult(val code: Int, val error: String?) {
    val ok: Boolean get() = code in 200..299
    /** 재시도해도 소용없는 오류(잘못된 주소/인증/형식). 사용자가 설정을 고쳐야 한다. */
    val permanent: Boolean get() = code in 400..499 && code != 408 && code != 429
    override fun toString() = if (ok) "성공(HTTP $code)" else "실패(${error ?: "HTTP $code"})"
}

object HttpUploader {
    fun postJson(url: String, token: String?, body: String, timeoutMs: Int = 15_000): UploadResult {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                doOutput = true
                instanceFollowRedirects = false
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer ${token.trim()}")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            UploadResult(code, if (code in 200..299) null else "HTTP $code")
        } catch (e: Exception) {
            UploadResult(-1, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        } finally {
            conn?.disconnect()
        }
    }
}
