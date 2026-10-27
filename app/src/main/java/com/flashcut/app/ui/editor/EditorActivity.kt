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
        project = ProjectStore.load(this, id)
        if (project.sources.isEmpty() && AnalysisCache.sources.isNotEmpty()) {
            project = Project.fromSources(
                id = if (project.id.isBlank()) "p_" + System.currentTimeMillis() else project.id,
                name = project.name.ifBlank { "闪剪成片" },
                sources = AnalysisCache.sources,
            )
        }
        if (project.sources.isEmpty()) {
            finish()
            return
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnExportTop.setOnClickListener { export() }
        binding.toolDelete.setOnClickListener { deleteSelected() }
        binding.toolSplit.setOnClickListener { splitSelected() }
        binding.toolSpeed.setOnClickListener { toggleSpeed() }
        binding.toolEffect.setOnClickListener { showNarration() }
        binding.rowNarration.setOnClickListener { showNarration() }
        binding.rowMusic.setOnClickListener { showMusic() }

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
    }

    private fun splitSelected() {
        val sel = selected ?: return
        val mid = (sel.inMs + sel.outMs) / 2
        if (mid - sel.inMs < 300 || sel.outMs - mid < 300) return
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
    }

    private fun toggleSpeed() {
        val sel = selected ?: return
        val next = if (sel.speed >= 1.9f) 1f else 2f
        val clips = project.clips.map { if (it.id == sel.id) it.copy(speed = next) else it }
        project = project.copy(clips = clips)
        selected = clips.first { it.id == sel.id }
        persist()
        refreshSummary()
    }

    private fun showNarration() {
        val s = AnalysisCache.script
        binding.tvAdvice.text = "旁白：" + (s?.narration?.ifBlank { "本轮未生成旁白" } ?: "本轮未生成旁白")
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
