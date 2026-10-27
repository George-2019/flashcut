package com.flashcut.app.llm

import com.flashcut.app.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL

/**
 * 端点自动探测。
 *
 * 关键前提：App 与 WorkBuddy 服务跑在同一台设备上（同一网络命名空间），
 * 所以 127.0.0.1 一定可达；本机各网卡 IP 也能通。
 * 网络切换（WiFi ↔ 蜂窝）后 IP 会变，因此每次启动都重新枚举 + 探测，
 * 不依赖任何写死的地址。
 */
object EndpointProbe {

    private const val NO_MATCH = -1L

    /** 枚举本机所有 IPv4 地址，拼上端口 */
    fun localHosts(port: Int = 8791): List<String> {
        val out = linkedSetOf<String>()
        out += "127.0.0.1"
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<java.net.Inet4Address>()
                .mapNotNull { it.hostAddress }
                .filter { !it.startsWith("169.254.") }   // 链路本地地址无意义
                .forEach { out += it }
        }.onFailure {
            DebugLog.e(com.flashcut.app.FlashCutApp.ctx, "Probe", "枚举网卡失败", it)
        }
        return out.map { "http://$it:$port/v1" }
    }

    /** 并发探测，返回最快可用的地址 */
    suspend fun pickBest(candidates: List<String>, timeoutMs: Long = 2000): String? =
        withContext(Dispatchers.IO) {
            if (candidates.isEmpty()) return@withContext null
            coroutineScope {
                candidates.distinct().map { url ->
                    async { url to measure(url, timeoutMs) }
                }.awaitAll()
                    .filter { it.second > 0 }
                    .minByOrNull { it.second }?.first
            }
        }

    /** 返回耗时毫秒，失败返回 -1 */
    private suspend fun measure(baseUrl: String, timeoutMs: Long): Long =
        withTimeoutOrNull(timeoutMs) {
            val t0 = System.currentTimeMillis()
            runCatching {
                val conn = URL("$baseUrl/models").openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = timeoutMs.toInt()
                conn.readTimeout = timeoutMs.toInt()
                conn.setRequestProperty("Authorization", "Bearer probe")
                val code = conn.responseCode
                conn.disconnect()
                // 401/403/404 都说明服务在监听，只是未带正确 Key
                if (code in 200..499) System.currentTimeMillis() - t0 else NO_MATCH
            }.getOrDefault(NO_MATCH)
        } ?: NO_MATCH

    /** 探测并写日志 */
    suspend fun pickAndLog(candidates: List<String>, tag: String = "Probe"): String? {
        val best = pickBest(candidates)
        DebugLog.i(
            com.flashcut.app.FlashCutApp.ctx, tag,
            "探测 ${candidates.size} 个地址 → ${best ?: "全部不可用"}"
        )
        return best
    }
}
