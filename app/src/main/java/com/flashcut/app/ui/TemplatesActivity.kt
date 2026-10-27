package com.flashcut.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.flashcut.app.databinding.ActivityTemplatesBinding
import com.flashcut.app.databinding.ItemTemplateBinding

class TemplatesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTemplatesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTemplatesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.rvTemplates.layoutManager = LinearLayoutManager(this)
        binding.rvTemplates.adapter = TemplateAdapter(TEMPLATES) { style ->
            startActivity(PickerActivity::class.java.let {
                android.content.Intent(this, it).putExtra(PickerActivity.EXTRA_STYLE, style)
            })
        }
    }

    companion object {
        val TEMPLATES = listOf(
            Triple("⛰", "旅行 / 生活 Vlog", "舒缓叙事 · 高光混剪 · 暖色 LUT") to "vlog",
            Triple("🔥", "情绪卡点 / 燃向", "音乐驱动 · 重特效 · 强节奏") to "beat",
            Triple("🎙", "口播 / 播客切片", "删停顿 · 强字幕 · 人声优先") to "talk",
            Triple("🛍", "好物 / 带货", "快节奏 · 卖点字幕 · 转化引导") to "shop",
        ).map { (t, style) -> Template(t.first, t.second, t.third, style) }
    }
}

data class Template(val icon: String, val name: String, val desc: String, val style: String)

private class TemplateAdapter(
    private val items: List<Template>,
    private val onPick: (String) -> Unit,
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
        holder.b.root.setOnClickListener { onPick(t.style) }
    }
}
