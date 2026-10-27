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
import com.flashcut.app.databinding.ActivityOnscreenBinding
import com.flashcut.app.databinding.ItemTemplateBinding

/** 模板与特效：选风格子模板，确认自动特效开关。 */
class OnscreenActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnscreenBinding
    private var selectedStyle = "cinema"
    private val styles = listOf(
        Template("🎬", "电影叙事", "冷调 · 低饱和 · 慢节奏",
            "cinema", com.flashcut.app.media.EffectPreset.COOL, com.flashcut.app.media.MusicSynth.Style.CALM),
        Template("🌿", "清新日常", "明亮 · 高饱和 · 轻快",
            "fresh", com.flashcut.app.media.EffectPreset.SOFT, com.flashcut.app.media.MusicSynth.Style.LIGHT),
        Template("⚡", "燃向卡点", "强对比 · 高饱和 · 强节奏",
            "beat", com.flashcut.app.media.EffectPreset.VIVID, com.flashcut.app.media.MusicSynth.Style.ENERGY),
        Template("📼", "复古胶片", "低饱和 · 暖调 · 中速",
            "retro", com.flashcut.app.media.EffectPreset.FILM, com.flashcut.app.media.MusicSynth.Style.WARM),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnscreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnNext.setOnClickListener {
            startActivity(VoiceActivity.intent(this))
            finish()
        }

        buildTabs()
        binding.rvStyles.layoutManager = LinearLayoutManager(this)
        var styleAdapter: StyleAdapter? = null
        styleAdapter = StyleAdapter(styles) { picked ->
            selectedStyle = picked.style
            styleAdapter?.select(picked.style)
            // 真正写进渲染配置，导出时生效
            AnalysisCache.effectPreset = picked.effect.id
            AnalysisCache.musicStyle = picked.music.id
            binding.tvEffects.text = effectsFor(picked)
        }
        binding.rvStyles.adapter = styleAdapter
        binding.tvEffects.text = effectsFor(styles.first { it.style == selectedStyle })
    }

    /**
     * 描述这套模板实际会做什么。
     * 只写真正实现的能力，不再列「防抖 / 变速转场」这类没做的项。
     */
    private fun effectsFor(t: Template): String = buildString {
        appendLine("✓ 调色风格　${t.effect.label}　${t.effect.desc}")
        appendLine("✓ 背景音乐　${t.music.label}　${t.music.bpm} BPM")
        appendLine("✓ 字幕烧录　按脚本逐句叠加进画面")
        appendLine("✓ 多段合成　按剪辑脚本顺序拼接")
        appendLine("✓ 竖屏输出　9:16 · 1280P")
        append("✓ 配音　系统 TTS 合成（可选）")
    }

    private fun buildTabs() {
        val d = resources.displayMetrics.density
        binding.tabGroup.removeAllViews()
        listOf("旅行 Vlog", "情绪卡点", "口播切片", "好物分享").forEachIndexed { i, name ->
            val tv = TextView(this).apply {
                text = name
                textSize = 12f
                setTextColor(getColor(if (i == 0) R.color.text else R.color.sub))
                setBackgroundResource(if (i == 0) R.drawable.bg_chip_brand else R.drawable.bg_chip)
                setPadding((10 * d).toInt(), (5 * d).toInt(), (10 * d).toInt(), (5 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (6 * d).toInt() }
            }
            binding.tabGroup.addView(tv)
        }
    }

    companion object {
        fun intent(context: Context) = Intent(context, OnscreenActivity::class.java)
    }
}

private class StyleAdapter(
    private val items: List<Template>,
    private val onPick: (Template) -> Unit = {},
) : RecyclerView.Adapter<StyleAdapter.VH>() {
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
        holder.b.root.setOnClickListener { onPick(t) }
        val picked = t.style == selectedKey
        holder.b.root.setBackgroundResource(
            if (picked) R.drawable.bg_card_brand else R.drawable.bg_card
        )
    }

    private var selectedKey = "cinema"
    fun select(key: String) {
        selectedKey = key
        notifyDataSetChanged()
    }
}
