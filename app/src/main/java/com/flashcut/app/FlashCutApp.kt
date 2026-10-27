package com.flashcut.app

import android.app.Application
import kotlinx.coroutines.launch
import com.flashcut.app.llm.LlmConfigStore
import com.flashcut.app.tmp.TmpManager
import java.io.File

class FlashCutApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        cleanupTmp()
        seedDefaultLlm()
        repairWorkbuddyUrl()
        autoProbeWorkbuddy()
        installCrashHandler()
        runCatching { com.flashcut.app.dev.UiAuditor(this).install() }
    }

    private fun cleanupTmp() {
        runCatching {
            File(cacheDir, TmpManager.DIR).listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    /**
     * 修复 WorkBuddy 地址：127.0.0.1 / localhost 在手机上指向手机自身，
     * 那里没有服务。若检测到这类无效地址，自动恢复成可用的默认地址。
     */
    private fun repairWorkbuddyUrl() {
        runCatching {
            val store = com.flashcut.app.llm.LlmConfigStore(this)
            val profile = store.loadActive()
            if (profile.id != "workbuddy") return@runCatching

            // 已知失效地址：127.0.0.1（手机自身）与旧版遗留的 10.66.11.69
            val bad = profile.baseUrl.contains("127.0.0.1") ||
                profile.baseUrl.contains("localhost") ||
                profile.baseUrl.contains("10.66.11.69") ||
                profile.baseUrl.contains("172.19.0.1")
            if (!bad) return@runCatching

            val fixed = profile.copy(baseUrl = BuildConfig.WORKBUDDY_CANDIDATES.substringBefore(","))
            store.save(fixed, store.apiKey(profile.id))
            DebugLog.i(this, "Config", "地址 ${profile.baseUrl} 在手机上不可用，已恢复为 ${fixed.baseUrl}")
        }
    }

    /**
     * 容器服务地址会随网络变化（10.x 私网、宿主 IP 等）。
     * 启动时并发探测候选地址，选中可用的那个，避免用户手动改。
     */
    private fun autoProbeWorkbuddy() {
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val store = com.flashcut.app.llm.LlmConfigStore(this@FlashCutApp)
                val profile = store.loadActive()
                if (profile.id != "workbuddy") return@launch

                val candidates = store.workbuddyCandidates()
                if (candidates.isEmpty()) return@launch

                // 当前地址已经可用就不用折腾
                val currentOk = com.flashcut.app.llm.EndpointProbe.pickBest(listOf(profile.baseUrl)) != null
                if (currentOk) {
                    DebugLog.i(this@FlashCutApp, "Probe", "当前地址可用：${profile.baseUrl}")
                    return@launch
                }

                val best = com.flashcut.app.llm.EndpointProbe.pickBest(candidates) ?: return@launch
                if (best != profile.baseUrl) {
                    store.save(profile.copy(baseUrl = best), store.apiKey(profile.id))
                    DebugLog.i(this@FlashCutApp, "Probe", "已自动切换地址 ${profile.baseUrl} → $best")
                }
            }.onFailure {
                DebugLog.e(this@FlashCutApp, "Probe", "地址探测失败", it)
            }
        }
    }

    /** 崩溃写进调试日志，方便在容器里直接排查 */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                DebugLog.e(this, "CRASH", "线程 ${thread.name} 未捕获异常", throwable)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** 首次启动预置 WorkBuddy：服务在本机，Key 通过 gradle 属性注入 */
    private fun seedDefaultLlm() {
        runCatching {
            LlmConfigStore(this).seedWorkBuddyIfEmpty(BuildConfig.WORKBUDDY_API_KEY)
        }
    }

    companion object {
        lateinit var instance: FlashCutApp
            private set

        /** 供无 Context 场合获取应用上下文 */
        val ctx: FlashCutApp get() = instance
    }
}
