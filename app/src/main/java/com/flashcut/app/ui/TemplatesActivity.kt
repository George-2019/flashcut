package com.flashcut.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.flashcut.app.databinding.ActivityTemplatesBinding
import com.flashcut.app.databinding.ItemTemplateBinding
import com.flashcut.app.media.EffectPreset
import com.flashcut.app.media.MusicSynth

/**
 * 模板页：一套模板 = 剪辑风格 + 特效预设 + 配乐 + 节奏。
 *
 * 之前这页只有 62 行、只有一个返回按钮有事件，模板卡片点了什么都不发生。
 * 现在每个模板都带完整的渲染配置，选中后会一路带到导出。
 */
class TemplatesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTemplatesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTemplatesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.rvTemplates.layoutManager = LinearLayoutManager(this)
        binding.rvTemplates.adapter = TemplateAdapter(TEMPLATES) { tpl ->
            applyTemplate(tpl)
        }
    }

    /** 套用模板：写进全局配置，然后去选素材 */
    private fun applyTemplate(tpl: Template) {
        AnalysisCache.style = tpl.style
        AnalysisCache.effectPreset = tpl.effect.id
        AnalysisCache.musicStyle = tpl.music.id

        android.widget.Toast.makeText(
            this,
            "已套用「${tpl.name}」：${tpl.effect.label} + ${tpl.music.label}，请选择素材",
            android.widget.Toast.LENGTH_LONG,
        ).show()

        startActivity(
            Intent(this, PickerActivity::class.java)
                .putExtra(PickerActivity.EXTRA_STYLE, tpl.style)
        )
    }

    companion object {
        val TEMPLATES = listOf(
            Template(
                icon = "⛰",
                name = "旅行 / 生活 Vlog",
                desc = "舒缓叙事 · 高光混剪 · 暖色调",
                style = "vlog",
                effect = EffectPreset.WARM,
                music = MusicSynth.Style.WARM,
            ),
            Template(
                icon = "🔥",
                name = "情绪卡点 / 燃向",
                desc = "音乐驱动 · 高对比 · 强节奏",
                style = "beat",
                effect = EffectPreset.VIVID,
                music = MusicSynth.Style.ENERGY,
            ),
            Template(
                icon = "🎙",
                name = "口播 / 播客切片",
                desc = "删停顿 · 强字幕 · 人声优先",
                style = "talk",
                effect = EffectPreset.SOFT,
                music = MusicSynth.Style.CALM,
            ),
            Template(
                icon = "🛍",
                name = "好物 / 带货",
                desc = "快节奏 · 高饱和 · 卖点醒目",
                style = "shop",
                effect = EffectPreset.VIVID,
                music = MusicSynth.Style.LIGHT,
            ),
            Template(
                icon = "👶",
                name = "萌娃 / 家庭记录",
                desc = "柔光人像 · 温暖钢琴 · 慢节奏",
                style = "vlog",
                effect = EffectPreset.SOFT,
                music = MusicSynth.Style.WARM,
            ),
            Template(
                icon = "🌃",
                name = "城市 / 科技感",
                desc = "青蓝调 · 电子配乐 · 冷峻",
                style = "beat",
                effect = EffectPreset.COOL,
                music = MusicSynth.Style.ENERGY,
            ),
            Template(
                icon = "🕰",
                name = "怀旧 / 胶片质感",
                desc = "低饱和 · 胶片颗粒感 · 慢叙事",
                style = "vlog",
                effect = EffectPreset.FILM,
                music = MusicSynth.Style.CALM,
            ),
        )
    }
}

data class Template(
    val icon: String,
    val name: String,
    val desc: String,
    val style: String,
    val effect: EffectPreset,
    val music: MusicSynth.Style,
)

private class TemplateAdapter(
    private val items: List<Template>,
    private val onPick: (Template) -> Unit,
) : RecyclerView.Adapter<TemplateAdapter.VH>() {

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
    }
}
