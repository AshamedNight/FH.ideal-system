package com.autoreply.app.core.llm

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 极简的 OpenAI 兼容客户端。
 *
 * 为什么手写而不用 OkHttp：这个项目刻意零第三方依赖（见 app/build.gradle.kts），
 * 而一次 chat/completions 请求就是"拼 JSON → POST → 读 JSON"，用系统自带的
 * HttpURLConnection 几十行就够。多引一个库就多一处 CI 首次构建失败的可能。
 *
 * **这个类不管理线程**：网络请求不能在主线程跑（NetworkOnMainThreadException），
 * 调用方必须放到后台线程。故意不在内部起线程 —— 同步接口更容易在调用处
 * 看清"这一下会阻塞"。
 */
class LlmClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val timeoutMs: Int = 30_000,
) {

    sealed class Result {
        data class Ok(val text: String) : Result()
        data class Err(val reason: String) : Result()
    }

    fun chat(system: String, user: String, maxTokens: Int = 400): Result {
        if (baseUrl.isBlank() || apiKey.isBlank()) {
            return Result.Err("模型接口未配置：设置里填接口地址和 API Key")
        }

        var conn: HttpURLConnection? = null
        return try {
            val payload = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", system))
                    put(JSONObject().put("role", "user").put("content", user))
                })
                put("max_tokens", maxTokens)
                // 0.8 而不是 1.0：太高会出现莫名其妙的用词，太低每条都一个腔调
                put("temperature", 0.8)
            }.toString()

            val url = URL(baseUrl.trim().trimEnd('/') + "/chat/completions")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $apiKey")
            }

            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (code !in 200..299) {
                return Result.Err("HTTP $code：${raw.take(180)}")
            }

            val text = JSONObject(raw)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.trim()
                .orEmpty()

            if (text.isEmpty()) Result.Err("返回里没有 content") else Result.Ok(text)
        } catch (e: Exception) {
            Result.Err("请求失败：${e.javaClass.simpleName} ${e.message.orEmpty().take(120)}")
        } finally {
            conn?.disconnect()
        }
    }
}
