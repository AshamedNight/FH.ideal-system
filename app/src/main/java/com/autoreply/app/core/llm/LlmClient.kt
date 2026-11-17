package com.autoreply.app.core.llm

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
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
    /**
     * 只给这次请求用的代理，如 `http://127.0.0.1:7890` / `socks5://127.0.0.1:1080`。
     * 留空直连。
     *
     * 这是"手机不挂系统级 VPN"的关键：系统级 VPN 会让所有 App 都从境外出口出去
     * （目标平台看到境外 IP），而这里只影响我们发往模型接口的这一次请求。
     */
    private val proxyUrl: String = "",
    private val timeoutMs: Int = 30_000,
) {

    sealed class Result {
        data class Ok(val text: String) : Result()
        data class Err(val reason: String) : Result()
    }

    /** 地址填 mock 时走本地假回复：不联网、不花钱，用来验证管道。 */
    val isMock: Boolean get() = baseUrl.trim().equals(MOCK, ignoreCase = true)

    /**
     * 拼请求用的 base。
     *
     * 用户很可能把**完整接口**贴进来（Termux 日志里常直接打印
     * `http://127.0.0.1:8080/v1/chat/completions`），再拼一次就成了
     * `/v1/chat/completions/chat/completions`，报 404 还很难看出来。
     * 这里统一剥掉，两种写法都能用。
     */
    private val endpointBase: String = baseUrl.trim().trimEnd('/').removeSuffix("/chat/completions")

    fun chat(system: String, user: String, maxTokens: Int = 400): Result {
        // mock 必须排在"未配置"检查之前 —— 假模型本来就不需要 Key
        if (isMock) return Result.Ok(mockReply(user))

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

            val url = URL(endpointBase + "/chat/completions")
            conn = openConnection(url).apply {
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
                // 把实际请求的地址带出来：404 最常见的原因是 /v1 少写或重复，
                // 不给地址的话用户只能靠猜。
                return Result.Err("HTTP $code（请求了 $url）：${raw.take(180)}")
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

    /**
     * 按 [proxyUrl] 建连接。地址写错时**退回直连**而不是直接失败 ——
     * 代理是可选项，配错了不该让整条链路瘫掉，只是这一次可能连不上上游，
     * 报错会落在网络层，仍然能看出是"连不上"而不是"配置崩了"。
     */
    private fun openConnection(url: URL): HttpURLConnection {
        val raw = proxyUrl.trim()
        if (raw.isEmpty()) return url.openConnection() as HttpURLConnection

        return try {
            val uri = URI(if (raw.contains("://")) raw else "http://$raw")
            val host = uri.host
            if (host.isNullOrEmpty()) {
                url.openConnection() as HttpURLConnection
            } else {
                val port = when {
                    uri.port > 0 -> uri.port
                    uri.scheme.equals("socks5", true) || uri.scheme.equals("socks", true) -> 1080
                    uri.scheme.equals("https", true) -> 443
                    else -> 80
                }
                val type = if (uri.scheme?.startsWith("socks", ignoreCase = true) == true) {
                    Proxy.Type.SOCKS
                } else {
                    Proxy.Type.HTTP
                }
                url.openConnection(Proxy(type, InetSocketAddress(host, port))) as HttpURLConnection
            }
        } catch (_: Exception) {
            url.openConnection() as HttpURLConnection
        }
    }

    /**
     * 本地假回复。刻意写得**不像**真人 —— 目的是验证管道，
     * 如果它伪装得太像，就没法一眼分清"这是模型写的"还是"这是假模型写的"。
     *
     * 从提示词里取最后一条「对方：...」，按几种粗糙规则选一句。
     * 能验证的东西：prompt 里的对话是否完整、清洗是否生效、
     * 禁忌词检查是否会被触发、能不能填进输入框。
     * 不能验证的：生成质量（这本来就只有真模型能给）。
     */
    private fun mockReply(user: String): String {
        val last = Regex("对方：(.+)").findAll(user).lastOrNull()
            ?.groupValues?.get(1)?.trim().orEmpty()
        return when {
            last.isEmpty() -> "嗯"
            last.length <= 2 -> "咋了"
            last.endsWith("？") || last.endsWith("?") -> "让我想想"
            else -> "哈哈 是"
        }
    }

    private companion object {
        const val MOCK = "mock"
    }
}
