package com.flashcut.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.flashcut.app.R
import com.flashcut.app.data.SourceClip
import com.flashcut.app.databinding.ActivityPickerBinding
import com.flashcut.app.databinding.ItemSourceCardBinding
import com.flashcut.app.media.MediaProbe
import com.flashcut.app.ui.source.SourceAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPickerBinding
    private lateinit var adapter: SourceAdapter
    private val sources = mutableListOf<SourceClip>()
    private var style = "vlog"

    private val pickMedia = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(20)
    ) { uris ->
        if (uris.isNotEmpty()) addUris(uris)
    }

    private val pickLegacy = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val uris = res.data?.let { intent ->
            intent.clipData?.let { c -> (0 until c.itemCount).map { c.getItemAt(it).uri } }
                ?: intent.data?.let { listOf(it) }
                ?: emptyList()
        } ?: emptyList()
        if (uris.isNotEmpty()) addUris(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        style = intent.getStringExtra(EXTRA_STYLE) ?: "vlog"

        adapter = SourceAdapter(
            onRemove = { clip ->
                sources.removeAll { it.id == clip.id }
                adapter.submit(sources)
                refreshSummary()
            }
        )
        binding.rvSources.layoutManager = LinearLayoutManager(this)
        binding.rvSources.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
        binding.btnPick.setOnClickListener { launchPicker() }
        binding.btnNext.setOnClickListener { goAnalyze() }
        refreshSummary()
    }

    private fun launchPicker() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        } else {
            val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "video/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            pickLegacy.launch(Intent.createChooser(i, "选择视频"))
        }
    }

    private fun addUris(uris: List<Uri>) {
        val existing = sources.map { it.uri }.toSet()
        val fresh = uris.filter { it !in existing }
        if (fresh.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val probed = MediaProbe.probeAll(this@PickerActivity, fresh)
            withContext(Dispatchers.Main) {
                sources.addAll(probed)
                adapter.submit(sources)
                refreshSummary()
            }
        }
    }

    private fun refreshSummary() {
        val totalMs = sources.sumOf { it.durationMs }
        val shots = sources.size * 2
        binding.tvCount.text = "已选 ${sources.size} 段"
        binding.tvTotal.text = "共 ${totalMs / 1000} 秒"
        binding.tvShots.text = "预计 ${shots} 个可用镜头"
        binding.btnNext.isEnabled = sources.isNotEmpty()
        binding.btnNext.alpha = if (sources.isNotEmpty()) 1f else 0.5f
    }

    private fun goAnalyze() {
        if (sources.isEmpty()) {
            Toast.makeText(this, "请先选择至少一段视频", Toast.LENGTH_SHORT).show()
            return
        }
        com.flashcut.app.DebugLog.i(this, "Picker", "进入分析 ${sources.size} 段 style=$style")
        startActivity(AnalysisActivity.intent(this, sources.toList(), style))
    }

    companion object {
        const val EXTRA_STYLE = "style"
    }
}
