package com.flashcut.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.flashcut.app.ai.MaterialBrief
import com.flashcut.app.ai.ScriptGenerator
import com.flashcut.app.ai.ShotAnalyzer
import com.flashcut.app.data.SourceClip
import com.flashcut.app.databinding.ActivityAnalysisBinding
import com.flashcut.app.llm.LlmConfigStore
import com.flashcut.app.llm.LlmResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 分析页：本地抽帧理解素材 → 调大模型生成剪辑脚本。
 * 分析结果写进 AnalysisCache，后续页面直接取，不必重复跑。
 */
class AnalysisActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAnalysisBinding
    private lateinit var sources: List<SourceClip>
    private var style = "vlog"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAnalysisBinding.inflate(layoutInflater)
        setContentView(binding.root)

        style = intent.getStringExtra(EXTRA_STYLE) ?: "vlog"
        val uris = intent.getStringArrayListExtra(EXTRA_URIS).orEmpty()
        sources = uris.mapNotNull { u ->
            com.flashcut.app.media.MediaProbe.probe(this, android.net.Uri.parse(u))
        }
        com.flashcut.app.DebugLog.i(this, "Analysis", "收到素材 ${sources.size}/${uris.size} 段 style=$style")
        if (sources.isEmpty()) {
            android.widget.Toast.makeText(this, "素材读取失败，请重新选择", android.widget.Toast.LENGTH_LONG).show()
            finish()
            return
        }
        binding.btnBack.setOnClickListener { finish() }
        binding.btnViewResult.setOnClickListener { goBrief() }
        startAnalysis()
    }

    private fun startAnalysis() {
        val analyzer = ShotAnalyzer(this)
        lifecycleScope.launch {
            try {
                step(1, "进行中")
                val brief = analyzer.analyze(sources, style) { p ->
                    withContext(Dispatchers.Main) { setProgress(p.percent, p.message) }
                }
                com.flashcut.app.DebugLog.i(this@AnalysisActivity, "Analysis",
                    "镜头数=${brief.totalShots} 剔除=${brief.jitterDropped} 主题=${brief.theme}")
                stepDone(1, "${sources.size} 段素材共识别 ${brief.totalShots} 个镜头" +
                    if (brief.jitterDropped > 0) "，剔除 ${brief.jitterDropped} 个抖动镜头" else "")

                setProgress(60, "本地识别完成")
                stepDone(2, "端侧语音识别暂未启用，已跳过")
                stepDone(3, "已按清晰度与亮度完成画面打分")

                setProgress(75, "请求大模型生成剪辑脚本")
                step(4, "进行中")

                val store = LlmConfigStore(this@AnalysisActivity)
                val profile = store.loadActive()
                val key = store.apiKey(profile.id)
                val gen = ScriptGenerator()

                com.flashcut.app.DebugLog.i(this@AnalysisActivity, "LLM",
                    "provider=${profile.id} url=${profile.baseUrl} model=${profile.model} keyLen=${key?.length ?: 0}")

                val script: com.flashcut.app.llm.LlmResult<com.flashcut.app.ai.EditScript> =
                    if (key.isNullOrBlank()) {
                        com.flashcut.app.llm.LlmResult.Err("未配置 API Key")
                    } else {
                        gen.generate(profile, key, brief, style, targetSec = 30)
                    }

                val finalScript = when (script) {
                    is com.flashcut.app.llm.LlmResult.Ok -> {
                        com.flashcut.app.DebugLog.i(this@AnalysisActivity, "LLM",
                            "脚本 OK cuts=${script.value.cuts.size}")
                        stepDone(4, "已生成 ${script.value.cuts.size} 个镜头安排")
                        script.value
                    }
                    is com.flashcut.app.llm.LlmResult.Err -> {
                        com.flashcut.app.DebugLog.e(this@AnalysisActivity, "LLM", "调用失败: ${script.message}")
                        stepDone(4, "大模型调用失败：${script.message}")
                        // 明确告诉用户降级了，而不是静默继续
                        binding.tvError.visibility = android.view.View.VISIBLE
                        binding.tvError.text = "⚠ 大模型没有成功返回剪辑脚本\n原因：${script.message}\n" +
                            "已改用本地规则剪法，成片流程不中断。\n" +
                            "可到「我的 → 大模型 API」检查地址、Key 与模型名。"
                        gen.fallback(brief, style, 30)
                    }
                }

                AnalysisCache.brief = brief
                AnalysisCache.script = finalScript
                AnalysisCache.sources = sources
                AnalysisCache.style = style

                setProgress(100, "分析完成")
                binding.btnViewResult.isEnabled = true
                binding.btnViewResult.alpha = 1f
            } catch (t: Throwable) {
                com.flashcut.app.DebugLog.e(this@AnalysisActivity, "Analysis", "分析异常", t)
                binding.tvError.visibility = android.view.View.VISIBLE
                binding.tvError.text = "⚠ 分析过程中出错：${t.javaClass.simpleName}: ${t.message}"
            }
        }
    }

    private fun goBrief() {
        startActivity(BriefActivity.intent(this))
        finish()
    }

    private fun setProgress(percent: Int, msg: String) {
        binding.progressBar.progress = percent
        binding.tvPercent.text = "$percent%"
        binding.tvPercent.contentDescription = msg
    }

    private fun step(n: Int, status: String) {
        val ico = listOf(binding.ico1, binding.ico2, binding.ico3, binding.ico4)[n - 1]
        ico.text = if (status == "完成") "✓" else "◔"
        ico.setTextColor(getColor(com.flashcut.app.R.color.brand))
    }

    private fun stepDone(n: Int, detail: String) {
        val ico = listOf(binding.ico1, binding.ico2, binding.ico3, binding.ico4)[n - 1]
        val det = listOf(binding.det1, binding.det2, binding.det3, binding.det4)[n - 1]
        ico.text = "✓"
        ico.setTextColor(getColor(com.flashcut.app.R.color.green))
        det.text = detail
    }

    companion object {
        const val EXTRA_STYLE = "style"

        const val EXTRA_URIS = "source_uris"

        fun intent(context: Context, sources: List<SourceClip>, style: String): Intent {
            AnalysisCache.style = style
            return Intent(context, AnalysisActivity::class.java)
                .putExtra(EXTRA_STYLE, style)
                .putStringArrayListExtra(EXTRA_URIS, ArrayList(sources.map { it.uri.toString() }))
        }
    }
}

/** 跨页传递分析结果（够用即可，不引 EventBus）。 */
object AnalysisCache {
    var pendingSources: List<SourceClip> = emptyList()
    var sources: List<SourceClip> = emptyList()
    var brief: MaterialBrief? = null
    var script: com.flashcut.app.ai.EditScript? = null
    var style: String = "vlog"
    var narration: String = ""

    fun takePendingSources(): List<SourceClip> {
        val s = pendingSources
        pendingSources = emptyList()
        return s
    }
}
