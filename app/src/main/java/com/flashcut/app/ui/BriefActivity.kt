package com.flashcut.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.flashcut.app.ai.ShotInsight
import com.flashcut.app.databinding.ActivityBriefBinding
import com.flashcut.app.databinding.ItemShotCardBinding

class BriefActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBriefBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBriefBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val brief = AnalysisCache.brief
        if (brief == null) {
            finish()
            return
        }
        binding.btnBack.setOnClickListener { finish() }
        binding.btnNext.setOnClickListener {
            startActivity(ScriptActivity.intent(this))
            finish()
        }

        binding.tvShotCount.text = "${brief.totalShots} 个镜头"
        binding.tvConfidence.text = "置信度 ${brief.confidence}%"
        binding.tvTheme.text = brief.theme
        binding.tvThemeTags.text = brief.tags.joinToString(" · ")
        binding.tvSpeech.text = brief.speech.ifBlank {
            "端侧语音识别尚未启用，本版只用画面信息生成脚本。"
        }

        binding.rvShots.layoutManager = LinearLayoutManager(this)
        binding.rvShots.adapter = ShotAdapter(brief.shots.take(8))
    }

    companion object {
        fun intent(context: Context) = Intent(context, BriefActivity::class.java)
    }
}

private class ShotAdapter(private val items: List<ShotInsight>) :
    RecyclerView.Adapter<ShotAdapter.VH>() {

    class VH(val b: ItemShotCardBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemShotCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = items[position]
        holder.b.tvIndex.text = String.format("%02d", position + 1)
        holder.b.tvTitle.text = s.title
        holder.b.tvReason.text = s.reason
        holder.b.tvScore.text = s.score.toString()
        holder.b.tvScore.setTextColor(
            holder.b.root.context.getColor(
                when {
                    s.score >= 80 -> com.flashcut.app.R.color.green
                    s.score >= 60 -> com.flashcut.app.R.color.yellow
                    else -> com.flashcut.app.R.color.sub
                }
            )
        )
    }
}
