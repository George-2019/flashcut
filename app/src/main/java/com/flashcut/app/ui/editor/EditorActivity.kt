package com.flashcut.app.ui.editor

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.flashcut.app.data.Project
import com.flashcut.app.data.TimelineClip
import com.flashcut.app.databinding.ActivityTimelineBinding
import com.flashcut.app.media.MediaProbe
import com.flashcut.app.ui.AnalysisCache
import com.flashcut.app.ui.ProjectStore
import com.flashcut.app.ui.export.ExportActivity

/**
 * 精剪页：多轨道时间线（画面轨 + 旁白轨 + 音乐轨）。
 * 可从 AI 脚本进入，也可从项目列表打开。
 */
class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTimelineBinding
    private lateinit var project: Project
    private val adapter = TrackAdapter()
    private var selected: TimelineClip? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTimelineBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val id = intent.getStringExtra(EXTRA_PROJECT_ID).orEmpty()
        project = if (id.isNotBlank()) ProjectStore.load(this, id) else Project("", "", emptyList(), emptyList())

        // 项目在磁盘上不存在时，才用内存缓存兜底（正常的进法是带 id 的）
        if (project.sources.isEmpty() && AnalysisCache.sources.isNotEmpty()) {
            project = Project.fromSources(
                id = if (project.id.isBlank()) "p_" + System.currentTimeMillis() else project.id,
                name = project.name.ifBlank { "闪剪成片" },
                sources = AnalysisCache.sources,
            )
            ProjectStore.save(this, project)
        }

        // 没有任何素材也不要静默退出——告诉用户发生了什么，并给一条出路
        if (project.sources.isEmpty() || project.clips.isEmpty()) {
            showEmptyState()
            return
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnExportTop.setOnClickListener { export() }
        binding.toolDelete.setOnClickListener { withSelection("删除") { deleteSelected() } }
        binding.toolSplit.setOnClickListener { withSelection("分割") { splitSelected() } }
        binding.toolSpeed.setOnClickListener { withSelection("变速") { toggleSpeed() } }
        binding.toolEffect.setOnClickListener { showEffectPicker() }
        binding.rowNarration.setOnClickListener { showNarration() }
        binding.rowMusic.setOnClickListener { showMusicPicker() }

        // 精度调整：改入点/出点，这是「精剪」的核心
        binding.btnInMinus.setOnClickListener { trim(inEdge = true, deltaMs = -200) }
        binding.btnInPlus.setOnClickListener { trim(inEdge = true, deltaMs = 200) }
        binding.btnOutMinus.setOnClickListener { trim(inEdge = false, deltaMs = -200) }
        binding.btnOutPlus.setOnClickListener { trim(inEdge = false, deltaMs = 200) }

        // 排序：调整片段在成片里的先后
        binding.btnMoveLeft.setOnClickListener { move(-1) }
        binding.btnMoveRight.setOnClickListener { move(1) }

        adapter.onTap = { clip ->
            selected = clip
            refreshSummary()
        }

        binding.rvVideoTrack.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvVideoTrack.adapter = adapter

        binding.tvProjectName.text = project.name
        render()
        refreshSummary()
    }

    /**
     * 空状态：以前直接 finish() 会让用户觉得「点了没反应」或「闪退」。
     * 现在留在页面上，说明原因并给出返回路径。
     */
    private fun showEmptyState() {
        binding.tvProjectName.text = "还没有可编辑的片段"
        binding.tvAdvice.text = "这个项目没有素材。请先导入视频跑一次 AI 成片，" +
            "成片页点「精剪」即可带着脚本进来。"
        binding.tvTimelineMeta.text = "0 秒 · 无片段"
        binding.tvSelected.text = "未选中片段"
        binding.rowNarration.text = "🎙 AI 旁白轨　等待生成"
        binding.rowMusic.text = "♫ 音乐轨　等待生成"
        binding.btnExportTop.setOnClickListener { finish() }
        binding.btnExportTop.text = "返回"
        listOf(
            binding.toolDelete, binding.toolSplit,
            binding.toolSpeed, binding.toolEffect,
        ).forEach { it.isEnabled = false; it.alpha = 0.4f }
        binding.btnBack.setOnClickListener { finish() }
    }

    private fun render() {
        adapter.submit(project)
        val s = AnalysisCache.script
        binding.tvAdvice.text = if (s != null && s.cuts.size >= 2) {
            "AI 精剪建议：镜头时长可能略晚于节拍点，可把入点提前约 0.3 秒，与音乐重拍对齐。"
        } else {
            "AI 精剪建议：点击片段选中后可分割、变速、删除。"
        }
        binding.rowNarration.text =
            "🎙 AI 旁白轨　" + (s?.narration?.take(16)?.let { "$it…" } ?: "未生成")
        binding.rowMusic.text = "♫ 音乐轨　" + (s?.music ?: "轻快电子")
    }

    /**
     * 微调入点/出点。
     * 入点不能小于 0，出点不能超过素材时长，且始终保持 out > in 至少 300ms。
     */
    private fun trim(inEdge: Boolean, deltaMs: Long) {
        val sel = selected ?: run { toast("请先点一个片段"); return }
        val src = project.sources.firstOrNull { it.id == sel.sourceId }
        val srcDur = src?.durationMs ?: Long.MAX_VALUE

        val updated = if (inEdge) {
            val newIn = (sel.inMs + deltaMs).coerceIn(0, sel.outMs - 300)
            if (newIn == sel.inMs) {
                toast(if (deltaMs < 0) "已到素材开头" else "片段太短，无法再缩")
                return
            }
            sel.copy(inMs = newIn)
        } else {
            val newOut = (sel.outMs + deltaMs).coerceIn(sel.inMs + 300, srcDur)
            if (newOut == sel.outMs) {
                toast(if (deltaMs > 0) "已到素材结尾" else "片段太短，无法再缩")
                return
            }
            sel.copy(outMs = newOut)
        }

        project = project.copy(clips = project.clips.map { if (it.id == sel.id) updated else it })
        selected = updated
        persist(); render(); refreshSummary()
        toast("${if (inEdge) "入点" else "出点"} ${MediaProbe.formatTime(if (inEdge) updated.inMs else updated.outMs)}")
    }

    /** 前移/后移一位，调整片段顺序 */
    private fun move(dir: Int) {
        val sel = selected ?: run { toast("请先点一个片段"); return }
        val clips = project.clips.toMutableList()
        val idx = clips.indexOfFirst { it.id == sel.id }
        if (idx < 0) return
        val target = idx + dir
        if (target !in clips.indices) {
            toast(if (dir < 0) "已经是第一个" else "已经是最后一个")
            return
        }
        val tmp = clips[idx]
        clips[idx] = clips[target]
        clips[target] = tmp
        project = project.copy(clips = clips)
        persist(); render(); refreshSummary()
        toast("已${if (dir < 0) "前移" else "后移"}到第 ${target + 1} 位")
    }

    /** 没选中片段时明确告诉用户先选，而不是点了没反应 */
    private fun withSelection(actionName: String, block: () -> Unit) {
        if (selected == null) {
            toast("请先点一个片段，再$actionName")
            return
        }
        block()
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun refreshSummary() {
        val sec = project.clips.sumOf { (it.outMs - it.inMs).coerceAtLeast(0) } / 1000
        binding.tvTimelineMeta.text = "$sec 秒 · 3 条轨道 · 已保存"
        binding.tvSelected.text = selected?.let {
            "已选 ${MediaProbe.formatTime(it.outMs - it.inMs)} · 入点 ${MediaProbe.formatTime(it.inMs)}"
        } ?: "未选中片段"
    }

    private fun deleteSelected() {
        val sel = selected ?: return
        val clips = project.clips.toMutableList()
        clips.removeAll { it.id == sel.id }
        project = project.copy(clips = clips)
        selected = null
        persist()
        render()
        refreshSummary()
        toast("已删除该片段，剩 ${project.clips.size} 段")
    }

    private fun splitSelected() {
        val sel = selected ?: return
        val mid = (sel.inMs + sel.outMs) / 2
        if (mid - sel.inMs < 300 || sel.outMs - mid < 300) {
            toast("片段太短（${MediaProbe.formatTime(sel.outMs - sel.inMs)}），无法分割")
            return
        }
        val a = sel.copy(id = sel.id + "_a", outMs = mid)
        val b = sel.copy(id = sel.id + "_b", inMs = mid)
        val clips = project.clips.toMutableList()
        val idx = clips.indexOfFirst { it.id == sel.id }
        if (idx < 0) return
        clips.removeAt(idx)
        clips.add(idx, a)
        clips.add(idx + 1, b)
        project = project.copy(clips = clips)
        selected = b
        persist()
        render()
        refreshSummary()
        toast("已在 ${MediaProbe.formatTime(mid)} 处分割")
    }

    private fun toggleSpeed() {
        val sel = selected ?: return
        val next = if (sel.speed >= 1.9f) 1f else 2f
        val clips = project.clips.map { if (it.id == sel.id) it.copy(speed = next) else it }
        project = project.copy(clips = clips)
        selected = clips.first { it.id == sel.id }
        persist()
        refreshSummary()
        toast("速度 ${next}x")
    }

    /**
     * 特效选择：之前这里只是把一段文字写进提示栏，用户感知不到任何变化。
     * 现在给出真实可用的调色预设，选择结果会写进导出配置。
     */
    private fun showEffectPicker() {
        val presets = com.flashcut.app.media.EffectPreset.entries
        val names = presets.map { "${it.label}　${it.desc}" }.toTypedArray()
        val current = presets.indexOfFirst { it.id == AnalysisCache.effectPreset }.coerceAtLeast(0)

        android.app.AlertDialog.Builder(this)
            .setTitle("成片风格")
            .setSingleChoiceItems(names, current) { dialog, which ->
                AnalysisCache.effectPreset = presets[which].id
                binding.tvAdvice.text = "已选风格：${presets[which].label}　${presets[which].desc}\n" +
                    "导出时会逐帧套用这个调色。"
                toast("已应用 ${presets[which].label}")
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showNarration() {
        val s = AnalysisCache.script
        binding.tvAdvice.text = "旁白：" + (s?.narration?.ifBlank { "本轮未生成旁白" } ?: "本轮未生成旁白")
    }

    /** 配乐选择：合成真实音轨，导出时混入 */
    private fun showMusicPicker() {
        val styles = com.flashcut.app.media.MusicSynth.Style.entries
        val labels = (styles.map { "${it.label}　${it.bpm} BPM" } + "不加配乐").toTypedArray()
        val currentIdx = styles.indexOfFirst { it.id == AnalysisCache.musicStyle }
            .let { if (it < 0) styles.size else it }

        android.app.AlertDialog.Builder(this)
            .setTitle("背景音乐")
            .setSingleChoiceItems(labels, currentIdx) { dialog, which ->
                AnalysisCache.musicStyle = if (which < styles.size) styles[which].id else null
                binding.rowMusic.text = if (which < styles.size) {
                    "♫ 音乐轨　${styles[which].label}"
                } else {
                    "♫ 音乐轨　不加配乐"
                }
                toast(if (which < styles.size) "已选 ${styles[which].label}" else "已关闭配乐")
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showMusic() {
        val s = AnalysisCache.script
        binding.tvAdvice.text = "音乐：" + (s?.music ?: "轻快电子") + "，人声处自动降 8dB"
    }

    private fun persist() {
        ProjectStore.save(this, project)
    }

    private fun export() {
        persist()
        startActivity(
            Intent(this, ExportActivity::class.java).putExtra(EXTRA_PROJECT_ID, project.id)
        )
    }

    companion object {
        const val EXTRA_PROJECT_ID = "project_id"

        fun intent(context: Context, projectId: String): Intent =
            Intent(context, EditorActivity::class.java).putExtra(EXTRA_PROJECT_ID, projectId)

        /** 从 AI 脚本进入：把脚本的 cut 转成时间线片段。 */
        fun intentFromCache(context: Context): Intent {
            val script = AnalysisCache.script
            val sources = AnalysisCache.sources
            val project = if (script != null && script.cuts.isNotEmpty() && sources.isNotEmpty()) {
                val clips = script.cuts.mapIndexed { i, c ->
                    TimelineClip("c_${i}_${c.shotId}", c.sourceId, c.inMs, c.outMs, c.speed, c.caption)
                }
                Project(
                    id = "p_" + System.currentTimeMillis(),
                    name = script.title.ifBlank { "闪剪成片" },
                    sources = sources,
                    clips = clips,
                )
            } else {
                Project.fromSources("p_" + System.currentTimeMillis(), "闪剪成片", sources)
            }
            ProjectStore.save(context, project)
            return intent(context, project.id)
        }
    }
}
