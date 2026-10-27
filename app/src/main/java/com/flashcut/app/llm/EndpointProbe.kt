package com.flashcut.app.llm

import com.flashcut.app.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL

/**
 * 端点自动探测：容器服务地址会随网络变化，逐个试出可用地址。
 * 只要有一个候选返回 < 500 就认为可用（401 说明服务在，只是 Key 在下一步验）。
 */
object EndpointProbe {

    suspend fun pickBest(candidates: List<String>, timeoutMs: Long = 2500): String? =
        withContext(Dispatchers.IO) {
            if (candidates.isEmpty()) return@withContext null
            coroutineScope {
                candidates.map { url ->
                    async {
                        val ms = measure(url, timeoutMs)
                        url to ms
                    }
                }.awaitAll().filter { it.second > 0 }.minByOrNull { it.second }?.first
            }
        }

    private suspend fun measure(baseUrl: String, timeoutMs: Long): Long = withTimeoutOrNull(timeoutMs) {
        val t0 = System.currentTimeMillis()
        runCatching {
            val conn = URL("$baseUrl/models").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = timeoutMs.toInt()
            conn.readTimeout = timeoutMs.toInt()
            conn.setRequestProperty("Authorization", "Bearer probe")
            val code = conn.responseCode
            conn.disconnect()
            if (code < 500) System.currentTimeMillis() - t0 else -1L
        }.getOrDefault(-1L)
    } ?: -1L

    /** 探测并把结果写日志，供外部查看 */
    suspend fun pickAndLog(candidates: List<String>, tag: String = "Probe"): String? {
        val best = pickBest(candidates)
        DebugLog.i(
            com.flashcut.app.FlashCutApp.ctx, tag,
            "候选 ${candidates.size} 个，选中 ${best ?: "无可用地址"}"
        )
        return best
    }
}
