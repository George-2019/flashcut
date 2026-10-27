package com.flashcut.app

import android.app.Application
import kotlinx.coroutines.Dispatchers
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
        autoProbeWorkbuddy()
        installCrashHandler()
        runCatching { com.flashcut.app.dev.UiAuditor(this).install() }
        maybeRunRenderSelfTest()
    }

    /**
     * 渲染自检的触发开关：外部在私有目录放一个 run_render_test 文件，
     * App 启动时就会跑一遍渲染自检并把结果写进日志。
     * 这样自动化测试不用点任何按钮。
     */
    private fun maybeRunRenderSelfTest() {
        val flag = File(filesDir, "run_render_test")
        if (!flag.exists()) return
        flag.delete()
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val video = findTestVideo()
            DebugLog.i(this@FlashCutApp, "RenderTest", "开始，测试素材=$video")
            com.flashcut.app.dev.RenderSelfTest.run(this@FlashCutApp, video)
            DebugLog.i(this@FlashCutApp, "RenderTest", "全部完成")
        }
    }

    /** 从常见相册目录里挑一个够长的视频做测试素材 */
    private fun findTestVideo(): android.net.Uri? {
        val dirs = listOf("/sdcard/DCIM/Camera", "/sdcard/Movies", "/sdcard/Download")
        for (d in dirs) {
            val f = File(d).listFiles()?.firstOrNull {
                it.isFile && it.length() > 500_000 && it.name.lowercase().endsWith(".mp4")
            }
            if (f != null) return android.net.Uri.fromFile(f)
        }
        return null
    }

    private fun cleanupTmp() {
        runCatching {
            File(cacheDir, TmpManager.DIR).listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    /**
     * 启动时自动定位 WorkBuddy 服务。
     *
     * App 和服务跑在同一台设备上，127.0.0.1 与各网卡 IP 都可达；
     * 网络切换后 IP 会变，所以每次启动重新枚举并探测，不写死地址。
     * 当前地址可用就保留（尊重用户手动配置）。
     */
    private fun autoProbeWorkbuddy() {
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val store = com.flashcut.app.llm.LlmConfigStore(this@FlashCutApp)
                val profile = store.loadActive()
                if (profile.id != "workbuddy") return@launch

                // 当前地址可用则不动
                if (com.flashcut.app.llm.EndpointProbe.pickBest(listOf(profile.baseUrl)) != null) {
                    DebugLog.i(this@FlashCutApp, "Probe", "当前地址可用：${profile.baseUrl}")
                    return@launch
                }

                // 本机地址 + 历史候选一起探
                val candidates = com.flashcut.app.llm.EndpointProbe.localHosts() +
                    store.workbuddyCandidates()
                val best = com.flashcut.app.llm.EndpointProbe.pickBest(candidates)
                if (best == null) {
                    DebugLog.i(this@FlashCutApp, "Probe", "未找到可用服务，保持原配置")
                    return@launch
                }
                if (best != profile.baseUrl) {
                    store.save(profile.copy(baseUrl = best), store.apiKey(profile.id))
                    DebugLog.i(this@FlashCutApp, "Probe", "自动切换 ${profile.baseUrl} → $best")
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
