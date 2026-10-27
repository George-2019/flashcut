package com.flashcut.app.ui.export

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.flashcut.app.R
import com.flashcut.app.databinding.ActivityExportBinding
import com.flashcut.app.media.MediaProbe
import com.flashcut.app.media.VideoExporter
import com.flashcut.app.ui.AnalysisCache
import com.flashcut.app.ui.ProjectStore
import com.flashcut.app.ui.editor.EditorActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ExportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExportBinding
    private var outputPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val id = intent.getStringExtra(EditorActivity.EXTRA_PROJECT_ID).orEmpty()
        val project = ProjectStore.load(this, id)
        val totalSec = project.clips.sumOf { (it.outMs - it.inMs).coerceAtLeast(0) } / 1000

        binding.btnBack.setOnClickListener { finish() }
        binding.tvName.text = project.name.ifBlank { "我的成片" }
        binding.tvMeta.text = "$totalSec 秒 · 9:16 · ${project.sources.size} 段素材 · 已对齐"

        binding.btnExport.setOnClickListener {
            if (outputPath != null) {
                share(outputPath!!)
            } else {
                runExport(project)
            }
        }
        // 三个可选项：点一下切换勾选
        listOf(binding.rowVideo, binding.rowProject, binding.rowSrt, binding.rowSubBurn).forEach { row ->
            row.setOnClickListener { toggleRow(row) }
        }
        binding.rowEffect.setOnClickListener { showEffectPicker() }
        binding.rowTransition.setOnClickListener { showTransitionPicker() }
        binding.rowMusic.setOnClickListener { showMusicPicker() }
        binding.rowNarration.setOnClickListener { showNarrationInfo() }
        refreshOptionLabels()
        binding.btnShare.setOnClickListener { outputPath?.let { share(it) } }
        binding.btnPlay.setOnClickListener { outputPath?.let { play(it) } }
    }

    private fun refreshOptionLabels() {
        binding.rowEffect.text =
            "成片风格：${com.flashcut.app.media.EffectPreset.of(AnalysisCache.effectPreset).label}"
        binding.rowTransition.text =
            "转场：${com.flashcut.app.media.TransitionFactory.Type.of(AnalysisCache.transition).label}"
        binding.rowMusic.text = "配乐：" + (
            AnalysisCache.musicStyle?.let { com.flashcut.app.media.MusicSynth.Style.of(it).label }
                ?: "不加配乐"
            )
        val n = AnalysisCache.narration.ifBlank { AnalysisCache.script?.narration.orEmpty() }
        binding.rowNarration.text = if (n.isBlank()) "配音：未生成" else "配音：${n.take(14)}…"
    }

    private fun showEffectPicker() {
        val items = com.flashcut.app.media.EffectPreset.entries
        val names = items.map { "${it.label}　${it.desc}" }.toTypedArray()
        val cur = items.indexOfFirst { it.id == AnalysisCache.effectPreset }.coerceAtLeast(0)
        android.app.AlertDialog.Builder(this)
            .setTitle("成片风格")
            .setSingleChoiceItems(names, cur) { d, w ->
                AnalysisCache.effectPreset = items[w].id
                refreshOptionLabels()
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showTransitionPicker() {
        val items = com.flashcut.app.media.TransitionFactory.Type.entries
        val names = items.map { "${it.label}　${it.desc}" }.toTypedArray()
        val cur = items.indexOfFirst { it.id == AnalysisCache.transition }.coerceAtLeast(0)
        android.app.AlertDialog.Builder(this)
            .setTitle("转场")
            .setSingleChoiceItems(names, cur) { d, w ->
                AnalysisCache.transition = items[w].id
                refreshOptionLabels()
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showMusicPicker() {
        val items = com.flashcut.app.media.MusicSynth.Style.entries
        val names = (items.map { "${it.label}　${it.bpm} BPM" } + "不加配乐").toTypedArray()
        val cur = items.indexOfFirst { it.id == AnalysisCache.musicStyle }.let { if (it < 0) items.size else it }
        android.app.AlertDialog.Builder(this)
            .setTitle("背景音乐")
            .setSingleChoiceItems(names, cur) { d, w ->
                AnalysisCache.musicStyle = if (w < items.size) items[w].id else null
                refreshOptionLabels()
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showNarrationInfo() {
        val n = AnalysisCache.narration.ifBlank { AnalysisCache.script?.narration.orEmpty() }
        android.app.AlertDialog.Builder(this)
            .setTitle("配音")
            .setMessage(
                if (n.isBlank()) "还没有配音文案。\n\n在成片页点「配音」可以先写一段解说词，" +
                    "导出时会用系统 TTS 合成并混入成片。"
                else "将要合成：\n\n$n\n\n导出时用系统 TTS 转成语音，作为独立音轨混入。"
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun runExport(project: com.flashcut.app.data.Project) {
        binding.btnExport.isEnabled = false
        binding.btnExport.text = "导出中…"
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            // 从界面上当前选中的项拼出导出配置
            val opts = com.flashcut.app.media.ExportOptions(
                effectPreset = com.flashcut.app.media.EffectPreset.of(AnalysisCache.effectPreset),
                transition = com.flashcut.app.media.TransitionFactory.Type.of(AnalysisCache.transition),
                burnSubtitles = binding.rowSubBurn.text.startsWith("✓"),
                narrationText = AnalysisCache.narration.ifBlank {
                    AnalysisCache.script?.narration.orEmpty()
                },
                musicStyle = AnalysisCache.musicStyle?.let {
                    com.flashcut.app.media.MusicSynth.Style.of(it)
                },
            )
            com.flashcut.app.DebugLog.i(
                this@ExportActivity, "Export",
                "配置 风格=${opts.effectPreset.label} 转场=${opts.transition.label} " +
                    "字幕烧录=${opts.burnSubtitles} 配乐=${opts.musicStyle?.label ?: "无"}"
            )
            val result = VideoExporter().export(
                this@ExportActivity, project, opts,
                onPrepare = { msg ->
                    binding.tvPercent.text = msg
                },
            ) { p ->
                binding.progressBar.progress = p.percent
                binding.tvPercent.text = "${p.percent}%　${p.message}"
            }
            result.onSuccess { file ->
                outputPath = file.absolutePath
                // 关键：把成片写进系统相册，否则用户在相册里找不到
                val saved = withContext(Dispatchers.IO) {
                    com.flashcut.app.media.MediaSaver.saveToGallery(this@ExportActivity, file)
                }
                // 勾选了字幕就生成 SRT
                if (binding.rowSrt.text.startsWith("✓")) {
                    runCatching {
                        val srt = java.io.File(file.parentFile, file.nameWithoutExtension + ".srt")
                        if (project.clips.any { it.caption.isNotBlank() }) {
                            withContext(Dispatchers.IO) { com.flashcut.app.media.SrtWriter.writeFromClips(srt, project.clips) }
                            com.flashcut.app.DebugLog.i(this@ExportActivity, "Export", "SRT 已生成 ${srt.name}")
                        }
                    }
                }
                val mb = String.format("%.1f", file.length() / 1024.0 / 1024.0)
                binding.progressBar.progress = 100
                binding.tvPercent.text = "100%"
                binding.tvDone.visibility = View.VISIBLE
                binding.tvDone.text = if (saved) {
                    "✓ 已存入相册：Movies/FlashCut/${file.name} · $mb MB"
                } else {
                    "✓ 已生成：${file.name} · $mb MB\n（存入相册失败，可用下方分享保存）"
                }
                binding.shareRow.visibility = View.VISIBLE
                binding.btnExport.text = "分享成片"
                binding.btnExport.isEnabled = true
            }.onFailure { e ->
                binding.tvPercent.text = "导出失败：${e.message}"
                binding.tvTip.text = "常见原因：1) 素材被其他应用占用；2) 存储空间不足；" +
                    "3) 编码器不支持该分辨率。可返回重试或减少素材数量。"
                binding.tvTip.visibility = android.view.View.VISIBLE
                binding.btnExport.text = "重试导出"
                binding.btnExport.isEnabled = true
            }
        }
    }

    private fun toggleRow(row: android.widget.TextView) {
        val base = row.text.toString().removePrefix("✓ ").removePrefix("○ ")
        val on = !row.text.startsWith("✓")
        row.text = (if (on) "✓ " else "○ ") + base
        row.setTextColor(getColor(if (on) R.color.text else R.color.sub))
        if (row === binding.rowSrt && on) {
            android.widget.Toast.makeText(
                this, "导出时会同时生成同名字幕文件", android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun uriOf(path: String): Uri =
        FileProvider.getUriForFile(this, "$packageName.fileprovider", File(path))

    private fun play(path: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uriOf(path), "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }
    }

    private fun share(path: String) {
        runCatching {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uriOf(path))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "分享成片"))
        }
    }
}
