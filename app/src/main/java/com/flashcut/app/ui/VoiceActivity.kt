package com.flashcut.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.flashcut.app.R
import com.flashcut.app.databinding.ActivityVoiceBinding
import com.flashcut.app.databinding.ItemTemplateBinding

/** 配音与配乐：旁白模式、可编辑解说词、音乐候选。 */
class VoiceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVoiceBinding
    private var selectedMusic = ""
    private val musics = listOf(
        Template("♫", "Sunny Days", "轻快 · 110 BPM · 适合旅行", "sunny"),
        Template("♫", "Golden Hour", "温暖 · 96 BPM · 适合落日", "golden"),
        Template("♫", "City Run", "动感 · 128 BPM · 适合卡点", "city"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVoiceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnNext.setOnClickListener {
            AnalysisCache.narration = binding.etNarration.text?.toString().orEmpty()
            startActivity(SubtitlesActivity.intent(this))
            finish()
        }

        val s = AnalysisCache.script
        binding.etNarration.setText(
            s?.narration?.ifBlank { defaultNarration() } ?: defaultNarration()
        )
        binding.tvVoiceInfo.text = buildString {
            appendLine("🎙 温柔女声 · 中文 · 语速 0.95× · 情绪舒缓")
            append("试听 00:08　▶ 点击试听")
        }
        binding.tvVoiceInfo.setOnClickListener {
            shown("试听功能需要 TTS 引擎，当前版本暂未接入；导出的成片不含配音。")
        }
        binding.tvMusicRules.text = buildString {
            appendLine("✓ 自动卡点对齐")
            appendLine("✓ 人声时音乐自动降低 8dB")
            appendLine("✓ 保留原片环境声")
            appendLine("✓ 结尾淡出 1.5 秒")
            append("曲库只使用可商用授权音乐；导入的音乐会在导出时提示版权风险。")
        }

        binding.rvMusic.layoutManager = LinearLayoutManager(this)
        var musicAdapter: MusicAdapter? = null
        musicAdapter = MusicAdapter(musics) { picked ->
            selectedMusic = picked.name
            musicAdapter?.select(picked.name)
            shown("配乐已选：${picked.name}（${picked.desc}）")
        }
        binding.rvMusic.adapter = musicAdapter

        listOf(binding.tabAi, binding.tabRecord, binding.tabRaw).forEach { it.setOnClickListener { v -> selectTab(v as android.widget.TextView) } }
    }

    private fun shown(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun selectTab(picked: android.widget.TextView) {
        listOf(binding.tabAi, binding.tabRecord, binding.tabRaw).forEach {
            it.setBackgroundResource(if (it === picked) R.drawable.bg_chip_brand else R.drawable.bg_chip)
            it.setTextColor(getColor(if (it === picked) R.color.text else R.color.sub))
        }
        binding.etNarration.isEnabled = picked === binding.tabAi
    }

    private fun defaultNarration() =
        "那天傍晚，风从海面吹过来。我们沿着环海路一直往前开，直到太阳把整片洱海染成金色。有些瞬间，值得慢一点记住。"

    companion object {
        fun intent(context: Context) = Intent(context, VoiceActivity::class.java)
    }
}

private class MusicAdapter(
    private val items: List<Template>,
    private val onPick: (Template) -> Unit = {},
) : RecyclerView.Adapter<MusicAdapter.VH>() {
    class VH(val b: ItemTemplateBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemTemplateBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = items[position]
        holder.b.tvIcon.text = t.icon
        holder.b.tvName.text = t.name
        holder.b.tvDesc.text = t.desc
        holder.b.root.setBackgroundResource(
            if (t.name == selectedName) R.drawable.bg_card_brand else R.drawable.bg_card
        )
        holder.b.root.setOnClickListener { onPick(t) }
    }

    private var selectedName = ""
    fun select(name: String) {
        selectedName = name
        notifyDataSetChanged()
    }
}
