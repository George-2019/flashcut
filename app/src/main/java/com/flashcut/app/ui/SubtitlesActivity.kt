package com.flashcut.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.flashcut.app.R
import com.flashcut.app.databinding.ActivitySubtitlesBinding
import com.flashcut.app.databinding.ItemSubtitleBinding
import com.flashcut.app.media.MediaProbe

/** 字幕页：把脚本的 caption 摊成字幕条，可选样式。 */
class SubtitlesActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySubtitlesBinding
    private val subtitles = mutableListOf<Sub>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySubtitlesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnNext.setOnClickListener {
            startActivity(ComposeActivity.intent(this))
            finish()
        }

        listOf(binding.tabAi, binding.tabRaw, binding.tabBi).forEach { tv ->
            tv.setOnClickListener { selectTab(tv) }
        }

        buildSubs()
        binding.tvCount.text = "已生成 ${subtitles.size} 条"
        binding.rvSubs.layoutManager = LinearLayoutManager(this)
        binding.rvSubs.adapter = SubAdapter(subtitles)

        binding.tvOptions.text = buildString {
            appendLine("字号　中")
            appendLine("位置　底部安全区")
            appendLine("✓ 自动断句")
            append("✓ 关键词高亮")
        }
        buildStyles()
    }

    private var mode = "ai"

    private fun selectTab(picked: android.widget.TextView) {
        listOf(binding.tabAi, binding.tabRaw, binding.tabBi).forEach {
            it.setBackgroundResource(if (it === picked) R.drawable.bg_chip_brand else R.drawable.bg_chip)
            it.setTextColor(getColor(if (it === picked) R.color.text else R.color.sub))
        }
        mode = when (picked.id) {
            R.id.tabRaw -> "raw"
            R.id.tabBi -> "bi"
            else -> "ai"
        }
        if (mode == "raw") {
            binding.tvOptions.visibility = android.view.View.VISIBLE
            binding.tvOptions.text = "原声字幕需要端侧语音识别，当前版本暂未启用。\n" +
                "可以先使用 AI 旁白字幕，或返回改用「仅保留原声」模式。"
            binding.rvSubs.visibility = android.view.View.GONE
            binding.tvCount.text = "原声字幕未启用"
        } else {
            binding.rvSubs.visibility = android.view.View.VISIBLE
            binding.tvCount.text = "已生成 ${subtitles.size} 条"
            if (mode == "bi") {
                binding.tvOptions.text = "双语字幕：中文原文 + 英文翻译\n翻译能力待接入，当前显示中文。"
            } else {
                binding.tvOptions.text = "字号　中\n位置　底部安全区\n✓ 自动断句\n✓ 关键词高亮"
            }
        }
    }

    private fun buildSubs() {
        var t = 0L
        val s = AnalysisCache.script
        if (s != null) {
            s.cuts.forEach { c ->
                val dur = c.outMs - c.inMs
                val text = c.caption.ifBlank { "" }
                if (text.isNotBlank()) subtitles += Sub(t, t + dur, text)
                t += dur
            }
        }
        if (subtitles.isEmpty()) {
            val narration = AnalysisCache.narration.ifBlank {
                AnalysisCache.script?.narration.orEmpty()
            }
            if (narration.isNotBlank()) {
                val parts = narration.split("。", "，").filter { it.isNotBlank() }
                val per = 4200L
                var st = 0L
                parts.forEach { p ->
                    subtitles += Sub(st, st + per, p.trim())
                    st += per
                }
            }
        }
    }

    private fun buildStyles() {
        val d = resources.displayMetrics.density
        listOf("白字黑边", "手写体", "强调黄", "电影衬线").forEachIndexed { i, name ->
            val tv = TextView(this).apply {
                text = name
                textSize = 12f
                setTextColor(getColor(if (i == 0) R.color.text else R.color.sub))
                setBackgroundResource(if (i == 0) R.drawable.bg_chip_brand else R.drawable.bg_chip)
                setPadding((10 * d).toInt(), (5 * d).toInt(), (10 * d).toInt(), (5 * d).toInt())
                setOnClickListener { clicked ->
                    val v = clicked as TextView
                    for (i in 0 until binding.styleGroup.childCount) {
                        val c = binding.styleGroup.getChildAt(i) as TextView
                        c.setBackgroundResource(
                            if (c === v) R.drawable.bg_chip_brand else R.drawable.bg_chip
                        )
                        c.setTextColor(getColor(if (c === v) R.color.text else R.color.sub))
                    }
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (6 * d).toInt() }
            }
            binding.styleGroup.addView(tv)
        }
    }

    companion object {
        fun intent(context: Context) = Intent(context, SubtitlesActivity::class.java)
    }
}

data class Sub(val startMs: Long, val endMs: Long, val text: String)

private class SubAdapter(private val items: List<Sub>) :
    RecyclerView.Adapter<SubAdapter.VH>() {
    class VH(val b: ItemSubtitleBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemSubtitleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val sub = items[position]
        holder.b.tvTime.text = "${MediaProbe.formatTime(sub.startMs)}–${MediaProbe.formatTime(sub.endMs)}"
        holder.b.tvText.text = sub.text
    }
}
