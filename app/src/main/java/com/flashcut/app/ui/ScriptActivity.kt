package com.flashcut.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.flashcut.app.ai.ScriptCut
import com.flashcut.app.ai.ScriptGenerator
import com.flashcut.app.databinding.ActivityScriptBinding
import com.flashcut.app.databinding.ItemShotCardBinding
import com.flashcut.app.llm.LlmConfigStore
import com.flashcut.app.llm.LlmResult
import kotlinx.coroutines.launch

class ScriptActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScriptBinding
    private val adapter = ScriptCutAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScriptBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRegenerate.setOnClickListener { regenerate() }
        binding.btnCompose.setOnClickListener {
            startActivity(OnscreenActivity.intent(this))
            finish()
        }

        binding.rvScript.layoutManager = LinearLayoutManager(this)
        binding.rvScript.adapter = adapter

        val s = AnalysisCache.script
        binding.tvStyle.text = styleLabel(AnalysisCache.style) + " · ${s?.targetDurationSec ?: 30} 秒"
        render()
    }

    private fun render() {
        val s = AnalysisCache.script ?: return
        binding.tvNarration.text = s.narration.ifBlank {
            "解说词：本轮使用本地规则剪法生成，未调用大模型。"
        }
        adapter.submit(s.cuts, AnalysisCache.brief?.shots?.associateBy { it.id }.orEmpty())
    }

    private fun regenerate() {
        val brief = AnalysisCache.brief ?: return
        val hint = binding.etHint.text?.toString()?.trim().orEmpty()
        binding.btnRegenerate.isEnabled = false
        binding.btnRegenerate.text = "重新生成中…"

        lifecycleScope.launch {
            val store = LlmConfigStore(this@ScriptActivity)
            val profile = store.loadActive()
            val key = store.apiKey(profile.id)
            val gen = ScriptGenerator()
            val result = if (key.isNullOrBlank()) {
                LlmResult.Err("未配置 API Key")
            } else {
                gen.generate(profile, key, brief, AnalysisCache.style, 30, hint)
            }
            when (result) {
                is LlmResult.Ok -> AnalysisCache.script = result.value
                is LlmResult.Err -> {
                    AnalysisCache.script = gen.fallback(brief, AnalysisCache.style, 30)
                    binding.tvNarration.text = "大模型调用失败（${result.message}），已用本地规则重排。"
                }
            }
            render()
            binding.btnRegenerate.isEnabled = true
            binding.btnRegenerate.text = "重新生成脚本"
        }
    }

    private fun styleLabel(style: String) = when (style) {
        "beat" -> "情绪卡点"
        "talk" -> "口播切片"
        "shop" -> "好物带货"
        else -> "Vlog"
    }

    companion object {
        fun intent(context: Context) = Intent(context, ScriptActivity::class.java)
    }
}

private class ScriptCutAdapter : RecyclerView.Adapter<ScriptCutAdapter.VH>() {

    private var cuts: List<ScriptCut> = emptyList()
    private var shots: Map<String, com.flashcut.app.ai.ShotInsight> = emptyMap()

    fun submit(c: List<ScriptCut>, m: Map<String, com.flashcut.app.ai.ShotInsight>) {
        cuts = c
        shots = m
        notifyDataSetChanged()
    }

    class VH(val b: ItemShotCardBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemShotCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun getItemCount() = cuts.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = cuts[position]
        val shot = shots[c.shotId]
        val sec = (c.outMs - c.inMs) / 1000f
        holder.b.tvIndex.text = String.format("%02d", position + 1)
        holder.b.tvTitle.text = buildString {
            append(shot?.title ?: "镜头")
            append(" · ")
            append(String.format("%.1f 秒", sec))
            if (c.caption.isNotBlank()) {
                append("\n")
                append(c.caption)
            }
        }
        holder.b.tvReason.text = shot?.reason ?: ""
        holder.b.tvScore.text = shot?.score?.toString() ?: ""
    }
}
