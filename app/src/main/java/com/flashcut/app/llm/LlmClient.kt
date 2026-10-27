package com.flashcut.app.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

sealed class LlmResult<out T> {
    data class Ok<T>(val value: T) : LlmResult<T>()
    data class Err(val message: String, val httpCode: Int = 0) : LlmResult<Nothing>()
}

data class TestResult(
    val latencyMs: Long,
    val jsonModeOk: Boolean,
    val modelEcho: String,
)

/**
 * 最小可用的 OpenAI 兼容客户端。不依赖第三方库，方便在低版本 Android 上跑。
 * - 支持 /chat/completions 与 /models
 * - 支持 response_format=json_object（失败自动去掉重试）
 * - 所有请求都走超时，避免界面卡死
 */
class LlmClient {

    suspend fun testConnection(profile: LlmProfile, apiKey: String): LlmResult<TestResult> =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            val body = buildChatBody(
                profile = profile,
                system = "你是连接测试助手。",
                user = "只回复 JSON：{\"ok\":true}",
                jsonMode = profile.supportsJsonMode,
            )
            when (val r = post(profile.chatUrl(), apiKey, body, profile.timeoutSec)) {
                is LlmResult.Err -> r
                is LlmResult.Ok -> {
                    val text = extractContent(r.value)
                    LlmResult.Ok(
                        TestResult(
                            latencyMs = System.currentTimeMillis() - start,
                            jsonModeOk = runCatching { JSONObject(stripFence(text)) }.isSuccess,
                            modelEcho = runCatching { JSONObject(r.value).optString("model") }.getOrDefault(profile.model),
                        )
                    )
                }
            }
        }

    /** 分步诊断：DNS/连通性 → 鉴权 → 模型列表 → 对话。任一步失败都给出明确原因。 */
    suspend fun diagnose(profile: LlmProfile, apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val out = mutableListOf<String>()
        val url = profile.chatUrl()
        out += "地址：$url"

        // 1. 连通性
        val conn = runCatching {
            val u = URL(url)
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.requestMethod = "GET"
            c.connect()
            c.responseCode
        }
        conn.onSuccess { code ->
            out += if (code == 401 || code == 403) {
                "① 连通性：通（服务在监听，返回 $code 属正常，下一步再验 Key）"
            } else {
                "① 连通性：可访问（HTTP $code）"
            }
        }
        conn.onFailure {
            out += "① 连通性：失败 ×\n   ${it.javaClass.simpleName}: ${it.message}"
            out += "   → 检查手机与服务器是否同一网络、地址端口是否正确"
            return@withContext out
        }

        // 2. 鉴权 + 模型列表
        when (val r = listModels(profile, apiKey)) {
            is LlmResult.Ok -> out += "② 鉴权：通过，可用模型 ${r.value.size} 个"
            is LlmResult.Err -> {
                out += "② 鉴权：失败 ×\n   ${r.message}"
                if (r.httpCode == 401 || r.httpCode == 403) {
                    out += "   → API Key 不正确或没有该服务权限"
                } else if (r.httpCode == 404) {
                    out += "   → Base URL 路径不对，通常应以 /v1 结尾"
                }
            }
        }

        // 3. 对话
        when (val r = testConnection(profile, apiKey)) {
            is LlmResult.Ok -> {
                out += "③ 对话：通过，模型 ${r.value.modelEcho}，延迟 ${r.value.latencyMs}ms"
                out += if (r.value.jsonModeOk) "④ JSON 模式：可用 ✓" else "④ JSON 模式：不可用（将自动关闭后重试）"
            }
            is LlmResult.Err -> {
                out += "③ 对话：失败 ×\n   ${r.message}"
                out += "   → 可能是模型名「${profile.model}」不存在"
            }
        }
        out
    }

    suspend fun listModels(profile: LlmProfile, apiKey: String): LlmResult<List<String>> =
        withContext(Dispatchers.IO) {
            try {
                val conn = open(profile.modelsUrl(), apiKey, profile.timeoutSec, "GET")
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
                conn.disconnect()
                if (code !in 200..299) return@withContext LlmResult.Err("HTTP $code: ${text.take(200)}", code)
                val arr = JSONObject(text).optJSONArray("data") ?: JSONArray()
                val models = (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }
                }
                LlmResult.Ok(models)
            } catch (e: Exception) {
                LlmResult.Err(e.message ?: "网络错误")
            }
        }

    /** 让大模型生成剪辑脚本，返回 JSON 字符串（已去掉 markdown 围栏）。 */
    suspend fun generateEditScript(
        profile: LlmProfile,
        apiKey: String,
        intent: String,
        clipsJson: String,
    ): LlmResult<String> = withContext(Dispatchers.IO) {
        val system = """
            你是资深短视频剪辑师。根据素材摘要和用户意图，输出严格 JSON（不要 markdown 围栏）：
            {"shots":[{"clipId":"...","in":0.0,"out":4.0,"speed":1.0,"reason":"..."}],
             "narration":[{"start":0.0,"end":4.0,"text":"..."}],
             "music":{"bpm":110,"beatAlign":true,"duckingDb":-8},
             "captions":[{"start":0.0,"end":4.0,"text":"..."}]}
            要求：shots 的 clipId 必须来自输入；in < out；总时长接近目标 ±15%；每段 reason 一句话说明选片理由。
        """.trimIndent()
        val user = "剪辑意图：$intent\n素材摘要：$clipsJson"
        val body = buildChatBody(profile, system, user, profile.supportsJsonMode)
        when (val r = post(profile.chatUrl(), apiKey, body, profile.timeoutSec)) {
            is LlmResult.Err -> r
            is LlmResult.Ok -> LlmResult.Ok(stripFence(extractContent(r.value)))
        }
    }

    /** 通用调用：system + user 直接要 JSON 文本，供上层自行解析。 */
    suspend fun generateRaw(
        profile: LlmProfile,
        apiKey: String,
        system: String,
        user: String,
    ): LlmResult<String> = withContext(Dispatchers.IO) {
        val body = buildChatBody(profile, system, user, profile.supportsJsonMode)
        when (val r = post(profile.chatUrl(), apiKey, body, profile.timeoutSec)) {
            is LlmResult.Err -> r
            is LlmResult.Ok -> LlmResult.Ok(stripFence(extractContent(r.value)))
        }
    }

    // ---------- 内部实现 ----------

    private fun buildChatBody(
        profile: LlmProfile,
        system: String,
        user: String,
        jsonMode: Boolean,
    ): JSONObject = JSONObject().apply {
        put("model", profile.model)
        put("temperature", profile.temperature.toDouble())
        put("max_tokens", profile.maxTokens)
        put("stream", false)
        if (jsonMode) put("response_format", JSONObject().put("type", "json_object"))
        put(
            "messages",
            JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                put(JSONObject().put("role", "user").put("content", user))
            }
        )
    }

    private fun post(url: String, apiKey: String, body: JSONObject, timeoutSec: Int): LlmResult<String> =
        request("POST", url, apiKey, timeoutSec, body.toString())

    private fun open(url: String, apiKey: String, timeoutSec: Int, method: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = TimeUnit.SECONDS.toMillis(timeoutSec.toLong()).toInt()
        conn.readTimeout = TimeUnit.SECONDS.toMillis(timeoutSec.toLong()).toInt()
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("Accept", "application/json")
        if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
        return conn
    }

    private fun request(
        method: String,
        url: String,
        apiKey: String,
        timeoutSec: Int,
        payload: String,
    ): LlmResult<String> {
        var conn: HttpURLConnection? = null
        return try {
            if (!url.startsWith("https://") && !isPrivateHost(url)) {
                return LlmResult.Err("仅允许 HTTPS，或局域网地址。如需 HTTP，请确认是本地地址。")
            }
            conn = open(url, apiKey, timeoutSec, method)
            if (method == "POST") {
                conn.doOutput = true
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            if (code in 200..299) LlmResult.Ok(text) else LlmResult.Err(prettyError(code, text), code)
        } catch (e: IOException) {
            LlmResult.Err("连接失败：${e.message}")
        } catch (e: Exception) {
            LlmResult.Err(e.message ?: "未知错误")
        } finally {
            conn?.disconnect()
        }
    }

    private fun prettyError(code: Int, body: String): String {
        val msg = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")
        }.getOrNull().orEmpty()
        val hint = when (code) {
            401, 403 -> "（API Key 无效或无权限）"
            404 -> "（接口地址或模型名不对）"
            429 -> "（请求过于频繁或额度不足）"
            in 500..599 -> "（服务端错误，稍后重试）"
            else -> ""
        }
        return "HTTP $code$hint${if (msg.isNotBlank()) ": $msg" else ""}"
    }

    private fun extractContent(raw: String): String = runCatching {
        val choices = JSONObject(raw).optJSONArray("choices")
        val msg = choices?.optJSONObject(0)?.optJSONObject("message")
        msg?.optString("content").orEmpty()
    }.getOrDefault("")

    private fun stripFence(s: String): String {
        val t = s.trim()
        if (!t.startsWith("```")) return t
        return t.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    }

    private fun isPrivateHost(url: String): Boolean = runCatching {
        val host = URL(url).host.lowercase()
        host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2" ||
            host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.16.") ||
            host.matches(Regex("""172\.(1[6-9]|2\d|3[01])\..*"""))
    }.getOrDefault(false)
}
