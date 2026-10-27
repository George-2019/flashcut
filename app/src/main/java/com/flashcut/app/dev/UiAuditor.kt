package com.flashcut.app.dev

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.flashcut.app.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * 自动 UI 审计：由 App 自己逐个打开页面、遍历控件、模拟点击、检查反应。
 *
 * 之所以放在 App 内，是因为外部无法注入点击（设备策略禁用 input/am/uiautomator，
 * 无障碍服务也未启用）。审计结果写入 debug 日志，外部通过 ADB 读取即可。
 */
class UiAuditor(private val app: Application) : Application.ActivityLifecycleCallbacks {

    private val lock = Object()
    private var resumed: Activity? = null
    private var lastLog = StringBuilder()
    private val seq = AtomicInteger(0)

    private val interactivePrefix = listOf("btn", "tab", "chip", "row", "nav", "sw")

    fun install() {
        app.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        synchronized(lock) {
            resumed = activity
            lock.notifyAll()
        }
    }

    override fun onActivityCreated(a: Activity, b: Bundle?) {}
    override fun onActivityStarted(a: Activity) {}
    override fun onActivityPaused(a: Activity) {}
    override fun onActivityStopped(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
    override fun onActivityDestroyed(a: Activity) {
        synchronized(lock) { if (resumed === a) resumed = null }
    }

    private suspend fun awaitResume(timeoutMs: Long = 4000): Activity? =
        withContext(Dispatchers.Default) {
            val deadline = System.currentTimeMillis() + timeoutMs
            synchronized(lock) {
                while (System.currentTimeMillis() < deadline) {
                    val a = resumed
                    if (a != null && !a.isFinishing && !a.isDestroyed) return@withContext a
                    lock.wait(80)
                }
            }
            null
        }

    private suspend fun onUi(block: () -> Unit) = withContext(Dispatchers.Main) { block() }

    // ---------- 主流程 ----------

    suspend fun runAll(): String {
        lastLog = StringBuilder()
        val ctx = app.applicationContext
        line("════ 自动 UI 审计开始 ════")

        seedTestData(ctx)

        val screens = listOf(
            "首页" to { HomeIntent(ctx) },
            "选片" to { Intent(ctx, com.flashcut.app.ui.PickerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            "理解结果" to { Intent(ctx, com.flashcut.app.ui.BriefActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            "剪辑脚本" to { Intent(ctx, com.flashcut.app.ui.ScriptActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            "模板特效" to { Intent(ctx, com.flashcut.app.ui.OnscreenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            "配音配乐" to { Intent(ctx, com.flashcut.app.ui.VoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            "字幕" to { Intent(ctx, com.flashcut.app.ui.SubtitlesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            "成片预览" to { Intent(ctx, com.flashcut.app.ui.ComposeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            "模板中心" to { Intent(ctx, com.flashcut.app.ui.TemplatesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            "我的项目" to { Intent(ctx, com.flashcut.app.ui.ProjectsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            "我的" to { Intent(ctx, com.flashcut.app.ui.MineActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            "大模型配置" to { Intent(ctx, com.flashcut.app.ui.llm.LlmConfigActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
        )

        var bugTotal = 0
        for ((name, mk) in screens) {
            bugTotal += auditScreen(name, mk())
        }

        line("════ 审计结束：发现 $bugTotal 个问题 ════")
        DebugLog.i(app, "UiAudit", lastLog.toString().replace("\n", " ⏎ "))
        return lastLog.toString()
    }

    private fun HomeIntent(ctx: Context) =
        Intent(ctx, com.flashcut.app.ui.HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private suspend fun auditScreen(name: String, intent: Intent): Int {
        line("")
        line("── $name ──")
        val before = currentResumed()
        runCatching { app.startActivity(intent) }
            .onFailure { line("  ✗ 无法打开：${it.javaClass.simpleName}"); return 0 }

        val act = awaitResume(4000)
        if (act == null) {
            line("  ✗ 页面未进入（可能被 finish）")
            return 0
        }
        pause(600)

        // 1) 静态检查：应该可交互却没有监听器
        val controls = mutableListOf<Pair<View, String>>()
        onUi { collect(act.window.decorView, controls) }

        var bugs = 0
        for ((v, label) in controls) {
            val clickable = v.isClickable && v.hasOnClickListeners()
            if (!clickable) {
                val idName = idName(v)
                if (idName != null && interactivePrefix.any { idName.startsWith(it) }) {
                    line("  ✗ [$label] 不可点击（控件存在但未绑定事件）")
                    bugs++
                }
            }
        }

        val clickable = controls.filter { it.first.isClickable && it.first.hasOnClickListeners() }
        line("  可点击控件 ${clickable.size} 个，待逐个验证")

        // 2) 动态检查：模拟点击，看是否引发变化
        for ((v, label) in clickable) {
            val idName = idName(v).orEmpty()
            // 跳过会打开系统选择器/退出页面的控件
            if (idName.contains("pick", true) || idName.contains("import", true)) {
                line("  – [$label] 跳过（会打开系统界面）")
                continue
            }
            if (idName == "btnBack" || idName == "navHome") {
                line("  – [$label] 跳过（导航退回）")
                continue
            }

            val sigBefore = signature(act)
            var crashed: Throwable? = null
            onUi {
                try {
                    v.performClick()
                } catch (t: Throwable) {
                    crashed = t
                }
            }
            pause(320)

            if (crashed != null) {
                line("  ✗ [$label] 点击抛异常：${crashed!!.javaClass.simpleName}: ${crashed!!.message}")
                bugs++
                continue
            }

            val now = currentResumed()
            if (now != null && now !== act && now.packageName == app.packageName) {
                line("  ✓ [$label] 跳转到 ${now.javaClass.simpleName}")
                onUi { runCatching { now.finish() } }
                pause(250)
                continue
            }
            if (now != null && now.packageName != app.packageName) {
                line("  ! [$label] 打开外部应用（${now.packageName}）")
                onUi { runCatching { act.finish() } }
                pause(300)
                return bugs
            }

            val sigAfter = signature(act)
            if (sigBefore == sigAfter) {
                line("  ✗ [$label] 点击后界面无任何变化")
                bugs++
            } else {
                line("  ✓ [$label] 界面有响应")
            }
            // 恢复状态，避免后续点击受前面影响
            if (signature(act) != sigBefore) {
                onUi { runCatching { (act as? android.app.Activity)?.recreate() } }
                pause(300)
            }
        }

        // 收尾：关闭本页
        onUi { runCatching { act.finish() } }
        pause(250)
        if (before != null && !before.isFinishing) {
            runCatching { app.startActivity(Intent(app, before.javaClass).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            awaitResume(3000)
        }
        return bugs
    }

    // ---------- 工具 ----------

    private fun currentResumed(): Activity? = synchronized(lock) { resumed }

    private fun pause(ms: Long) = Thread.sleep(ms)

    private fun line(s: String) {
        lastLog.append(s).append('\n')
    }

    private fun idName(v: View): String? = runCatching {
        if (v.id == View.NO_ID) null else app.resources.getResourceEntryName(v.id)
    }.getOrNull()

    private fun label(v: View): String {
        val id = idName(v) ?: v.javaClass.simpleName
        val txt = (v as? TextView)?.text?.toString()?.take(10).orEmpty()
        return if (txt.isBlank()) id else "$id(\"$txt\")"
    }

    /** 递归收集可交互候选控件 */
    private fun collect(root: View, out: MutableList<Pair<View, String>>, depth: Int = 0) {
        if (depth > 14) return
        if (root.visibility != View.VISIBLE) return
        if (root.id != View.NO_ID && root !is ViewGroup) {
            val id = idName(root).orEmpty()
            if (id.startsWith("btn") || id.startsWith("tab") || id.startsWith("chip") ||
                id.startsWith("row") || id.startsWith("nav") || root.isClickable
            ) {
                out += root to label(root)
            }
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) collect(root.getChildAt(i), out, depth + 1)
        } else if (root.id != View.NO_ID && root is ViewGroup) {
            // unreachable, kept for clarity
        }
    }

    /** 页面状态指纹：所有文字 + 可见性 */
    private fun signature(act: Activity): String {
        val sb = StringBuilder()
        walkText(act.window.decorView, sb, 0)
        return sb.toString()
    }

    private fun walkText(v: View, sb: StringBuilder, depth: Int) {
        if (depth > 14) return
        sb.append(v.visibility)
        if (v is TextView) sb.append(v.text)
        if (v is ViewGroup) for (i in 0 until v.childCount) walkText(v.getChildAt(i), sb, depth + 1)
    }

    /** 造一份可渲染的测试数据，让各页面不必依赖真实素材 */
    private fun seedTestData(ctx: Context) {
        runCatching {
            val shots = (1..6).map { i ->
                com.flashcut.app.ai.ShotInsight(
                    id = "s$i",
                    sourceId = "test",
                    index = i,
                    startMs = (i - 1) * 2000L,
                    endMs = i * 2000L,
                    title = "测试镜头$i",
                    reason = "自动审计",
                    score = 60 + i * 5,
                )
            }
            val brief = com.flashcut.app.ai.MaterialBrief(
                theme = "自动审计素材", confidence = 90, tags = listOf("测试"),
                shots = shots, speech = "", jitterDropped = 0,
            )
            val script = com.flashcut.app.ai.EditScript(
                title = "审计成片",
                narration = "这是自动审计生成的解说词。",
                music = "轻快", effectNote = "叠化",
                cuts = shots.map { s ->
                    com.flashcut.app.ai.ScriptCut(
                        s.id, s.sourceId, s.startMs, s.endMs, "镜头${s.index}字幕", 1f
                    )
                },
            )
            com.flashcut.app.ui.AnalysisCache.brief = brief
            com.flashcut.app.ui.AnalysisCache.script = script
            com.flashcut.app.ui.AnalysisCache.style = "vlog"
            line("已注入测试数据：${shots.size} 个镜头")
        }.onFailure { line("测试数据注入失败：${it.message}") }
    }
}
