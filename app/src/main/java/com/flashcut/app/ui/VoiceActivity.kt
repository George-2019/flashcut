package com.flashcut.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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
    // 配乐直接对应可合成的真实风格
    private val musics = listOf(
        Template("♫", "轻快电子", "110 BPM · 适合旅行/日常",
            "light", com.flashcut.app.media.EffectPreset.ORIGINAL,
            com.flashcut.app.media.MusicSynth.Style.LIGHT),
        Template("♫", "温暖钢琴", "82 BPM · 适合萌娃/回忆",
            "warm", com.flashcut.app.media.EffectPreset.ORIGINAL,
            com.flashcut.app.media.MusicSynth.Style.WARM),
        Template("♫", "燃向鼓点", "128 BPM · 适合卡点混剪",
            "energy", com.flashcut.app.media.EffectPreset.ORIGINAL,
            com.flashcut.app.media.MusicSynth.Style.ENERGY),
        Template("♫", "舒缓氛围", "70 BPM · 适合情绪片",
            "calm", com.flashcut.app.media.EffectPreset.ORIGINAL,
            com.flashcut.app.media.MusicSynth.Style.CALM),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVoiceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnNext.setOnClickListener {
            AnalysisCache.narration = binding.etNarration.text?.toString().orEmpty()
            AnalysisCache.musicStyle = selectedMusicStyleId()
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
        // 试听走真实 TTS，合成成功就直接播
        binding.tvVoiceInfo.setOnClickListener {
            val text = binding.etNarration.text?.toString().orEmpty()
            if (text.isBlank()) { shown("请先输入配音文案"); return@setOnClickListener }
            shown("正在合成试听…")
            lifecycleScope.launch {
                val r = com.flashcut.app.media.TtsEngine().synthesize(this@VoiceActivity, text)
                val ok = r.getOrNull()
                if (ok != null) {
                    playAudio(ok.file)
                } else {
                    shown(
                        "配音不可用：" + (r.exceptionOrNull()?.message ?: "未知原因") +
                            "\n导出的成片会自动跳过配音，其余效果正常。"
                    )
                }
            }
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
            selectedMusic = picked.style          // 存 id，不是显示名
            AnalysisCache.musicStyle = picked.music.id   // 立刻生效
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

    /** 播放合成出来的音频 */
    private fun playAudio(file: java.io.File) {
        runCatching {
            val mp = android.media.MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                start()
                setOnCompletionListener { it.release() }
            }
            shown("试听中…（${file.length() / 1024}KB）")
        }.onFailure { shown("播放失败：${it.message}") }
    }

    /** 当前选中的配乐风格 id，未选则用默认 */
    private fun selectedMusicStyleId(): String =
        musics.firstOrNull { it.style == selectedMusic }?.music?.id
            ?: com.flashcut.app.media.MusicSynth.Style.LIGHT.id

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
