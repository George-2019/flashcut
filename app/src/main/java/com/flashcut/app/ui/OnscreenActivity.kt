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
        Template("🎬", "电影叙事", "冷调黑边 · 宽银幕 · 变速转场 · 低饱和字幕 · 节奏慢", "cinema"),
        Template("🌿", "清新日常", "日系明亮 · 高频剪辑 · 手写体字幕 · 节奏快", "fresh"),
        Template("⚡", "燃向卡点", "强对比 · 快速切换 · 重特效 · 节奏极快", "beat"),
        Template("📼", "复古胶片", "颗粒感 · 暖色调 · 慢推镜头 · 节奏中", "retro"),
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
            binding.tvEffects.text = effectsFor(picked.style)
        }
        binding.rvStyles.adapter = styleAdapter
        binding.tvEffects.text = effectsFor(selectedStyle)
    }

    private fun effectsFor(key: String): String = when (key) {
        "fresh" -> buildString {
            appendLine("✓ 调色 LUT　日系明亮")
            appendLine("✓ 镜头稳定　自动防抖")
            appendLine("✓ 速度曲线　高频切换")
            appendLine("✓ 转场特效　硬切为主")
            append("○ 画面动态　轻微呼吸感")
        }
        "beat" -> buildString {
            appendLine("✓ 调色 LUT　强对比")
            appendLine("✓ 镜头稳定　自动防抖")
            appendLine("✓ 速度曲线　鼓点卡点")
            appendLine("✓ 转场特效　闪白 / 缩放冲击")
            append("✓ 画面动态　节奏抖动")
        }
        "retro" -> buildString {
            appendLine("✓ 调色 LUT　复古胶片颗粒")
            appendLine("✓ 镜头稳定　自动防抖")
            appendLine("✓ 速度曲线　慢推镜头")
            appendLine("✓ 转场特效　叠化 0.5s")
            append("○ 画面动态　缓慢推进")
        }
        else -> buildString {
            appendLine("✓ 调色 LUT　冷调低饱和")
            appendLine("✓ 镜头稳定　自动防抖")
            appendLine("✓ 速度曲线　高潮慢放 0.8×")
            appendLine("✓ 转场特效　叠化 0.3s")
            append("○ 画面动态　宽银幕黑边")
        }
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
