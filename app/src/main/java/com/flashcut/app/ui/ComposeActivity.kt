package com.flashcut.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.flashcut.app.ai.ComposeSummary
import com.flashcut.app.databinding.ActivityComposeBinding
import com.flashcut.app.ui.editor.EditorActivity
import com.flashcut.app.ui.export.ExportActivity

class ComposeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityComposeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityComposeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val s = AnalysisCache.script
        if (s == null || s.cuts.isEmpty()) {
            finish()
            return
        }
        binding.btnBack.setOnClickListener { finish() }
        binding.btnFine.setOnClickListener {
            startActivity(EditorActivity.intentFromCache(this))
            finish()
        }
        binding.btnExport.setOnClickListener {
            startActivity(Intent(this, ExportActivity::class.java))
        }

        val totalSec = s.durationMs / 1000
        val sourceCount = s.cuts.map { it.sourceId }.toSet().size
        binding.tvDuration.text = "$totalSec 秒"
        binding.chipClips.text = "${s.cuts.size} 镜头"
        binding.chipSources.text = "$sourceCount 素材来源"
        binding.chipEffects.text = "2 特效"
        binding.tvPreviewLabel.text = "9:16 · 1080P"

        val first = AnalysisCache.brief?.shots?.firstOrNull { it.id == s.cuts.firstOrNull()?.shotId }
        binding.tvSubtitle.text = s.cuts.firstOrNull()?.caption.orEmpty()

        val summary = ComposeSummary(
            clipCount = s.cuts.size,
            sourceCount = sourceCount,
            effectCount = 2,
            narrationSec = s.narration.length / 5,
        )
        Analytics.summary = summary

        binding.tvAiSummary.text = buildString {
            appendLine("多段合成 · ${s.cuts.size} 个镜头")
            appendLine("按${s.effectNote}衔接，去重后保留高光。")
            appendLine("声音 · ${s.music}" + if (s.narration.isNotBlank()) "，配乐在人声处自动避让。" else "。")
            if (first != null) append("首选镜头：${first.title}（评分 ${first.score}）")
        }
    }

    companion object {
        fun intent(context: Context) = Intent(context, ComposeActivity::class.java)
    }
}

object Analytics {
    var summary: ComposeSummary? = null
}
