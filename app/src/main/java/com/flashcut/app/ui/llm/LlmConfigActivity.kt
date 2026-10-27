package com.flashcut.app.ui.llm

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.flashcut.app.R
import com.flashcut.app.databinding.ActivityLlmConfigBinding
import com.flashcut.app.llm.LlmClient
import com.flashcut.app.llm.LlmConfigStore
import com.flashcut.app.llm.LlmPresets
import com.flashcut.app.llm.LlmProfile
import com.flashcut.app.llm.LlmResult
import kotlinx.coroutines.launch

/**
 * 大模型 API 配置页：预设 chip → 可改任意字段 → 拉取模型 → 测试连接 → 保存。
 * 默认预置 WorkBuddy（本机服务）。
 */
class LlmConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLlmConfigBinding
    private lateinit var store: LlmConfigStore
    private val client = LlmClient()
    private var current: LlmProfile = LlmPresets.all.first()
    private var availableModels: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLlmConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = LlmConfigStore(this)
        current = store.loadActive()

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSave.setOnClickListener { save() }
        binding.btnTest.setOnClickListener { testConnection() }
        binding.btnListModels.setOnClickListener { listModels() }
        binding.btnPickModel.setOnClickListener { pickModel() }
        binding.btnDiagnose.setOnClickListener { diagnose() }

        buildChips()
        bind(current)
        renderRouting()
        buildCandidateChips()
        loadCachedModels()
    }

    private fun loadCachedModels() {
        availableModels = store.loadModels(current.id)
        if (availableModels.isNotEmpty()) {
            binding.tvModelCount.visibility = View.VISIBLE
            binding.tvModelCount.text = "已缓存 ${availableModels.size} 个模型，点「选择」切换"
        }
    }

    private fun buildChips() {
        binding.chipGroup.removeAllViews()
        val d = resources.displayMetrics.density
        LlmPresets.all.forEach { preset ->
            val tv = TextView(this).apply {
                text = preset.name
                setTextColor(getColor(if (preset.id == current.id) R.color.text else R.color.sub))
                textSize = 12f
                setBackgroundResource(
                    if (preset.id == current.id) R.drawable.bg_chip_brand else R.drawable.bg_chip
                )
                setPadding((10 * d).toInt(), (5 * d).toInt(), (10 * d).toInt(), (5 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (6 * d).toInt() }
                setOnClickListener { pickPreset(preset) }
            }
            binding.chipGroup.addView(tv)
        }
    }

    /** WorkBuddy 可能有多个可用地址，提供一键切换 */
    private fun buildCandidateChips() {
        if (current.id != "workbuddy") {
            binding.chipCandidates.visibility = android.view.View.GONE
            return
        }
        val list = store.workbuddyCandidates()
        if (list.isEmpty()) {
            binding.chipCandidates.visibility = android.view.View.GONE
            return
        }
        binding.chipCandidates.visibility = android.view.View.VISIBLE
        binding.chipCandidates.removeAllViews()
        val d = resources.displayMetrics.density
        list.forEach { url ->
            val host = url.removePrefix("http://").removePrefix("https://").substringBefore("/v1")
            val tv = TextView(this).apply {
                text = host
                textSize = 11f
                setTextColor(getColor(R.color.sub))
                setBackgroundResource(R.drawable.bg_chip)
                setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (6 * d).toInt() }
                setOnClickListener {
                    binding.etBaseUrl.setText(url)
                    showResult("已切换到 $host，点「一键诊断」验证", true)
                }
            }
            binding.chipCandidates.addView(tv)
        }
    }

    private fun pickPreset(preset: LlmProfile) {
        current = preset
        bind(preset)
        buildChips()
        buildCandidateChips()
        loadCachedModels()
        binding.tvResult.visibility = View.GONE
        renderRouting()
    }

    private fun bind(p: LlmProfile) = with(binding) {
        etBaseUrl.setText(p.baseUrl)
        etModel.setText(p.model)
        etApiKey.setText(store.apiKey(p.id).orEmpty())
        etTemperature.setText(p.temperature.toString())
        etMaxTokens.setText(p.maxTokens.toString())
        etTimeout.setText(p.timeoutSec.toString())
        swJsonMode.isChecked = p.supportsJsonMode
    }

    private fun collect(): LlmProfile = current.copy(
        baseUrl = binding.etBaseUrl.text.toString().trim(),
        model = binding.etModel.text.toString().trim(),
        temperature = binding.etTemperature.text.toString().toFloatOrNull() ?: 0.7f,
        maxTokens = binding.etMaxTokens.text.toString().toIntOrNull() ?: 4096,
        timeoutSec = binding.etTimeout.text.toString().toIntOrNull() ?: 60,
        supportsJsonMode = binding.swJsonMode.isChecked,
    )

    private fun renderRouting() = with(binding) {
        val model = if (current.model.isNotBlank()) current.model else "未配置"
        tvRouting.text = buildString {
            appendLine("剪辑脚本生成　理解材料、挑高光、出 EDL　$model")
            appendLine("解说词撰写　　配合画面写旁白　$model")
            appendLine("字幕润色　　　可选，弱网可关　同主模型")
            append("TTS 配音　　　OpenAI 兼容 /audio/speech　未配置")
        }
    }

    private fun save() {
        val p = collect()
        val key = binding.etApiKey.text.toString().trim()
        store.save(p, key.ifBlank { null })
        Toast.makeText(this, "已保存：${p.name}", Toast.LENGTH_SHORT).show()
        renderRouting()
    }

    private fun testConnection() {
        val p = collect()
        val key = binding.etApiKey.text.toString().trim()
        if (key.isBlank()) {
            showResult("请先填写 API Key", false)
            return
        }
        binding.tvResult.visibility = View.VISIBLE
        binding.tvResult.setTextColor(getColor(R.color.sub))
        binding.tvResult.text = "测试中…"
        lifecycleScope.launch {
            when (val r = client.testConnection(p, key)) {
                is LlmResult.Ok -> showResult(
                    "✓ 连接成功　模型 ${r.value.modelEcho}　延迟 ${r.value.latencyMs}ms" +
                        if (r.value.jsonModeOk) "　JSON 模式可用" else "　JSON 模式不可用",
                    true
                )
                is LlmResult.Err -> showResult("✕ ${r.message}", false)
            }
        }
    }

    private fun listModels() {
        val p = collect()
        val key = binding.etApiKey.text.toString().trim()
        if (p.baseUrl.isBlank()) {
            showResult("请先填写接口地址", false)
            return
        }
        binding.tvResult.visibility = View.VISIBLE
        binding.tvResult.setTextColor(getColor(R.color.sub))
        binding.tvResult.text = "拉取模型列表…"
        com.flashcut.app.DebugLog.i(this, "LLM", "拉取模型 url=${p.modelsUrl()}")

        lifecycleScope.launch {
            when (val r = client.listModels(p, key)) {
                is LlmResult.Ok -> {
                    availableModels = r.value
                    store.saveModels(p.id, r.value)
                    com.flashcut.app.DebugLog.i(this@LlmConfigActivity, "LLM", "拉到 ${r.value.size} 个模型")
                    binding.tvModelCount.visibility = View.VISIBLE
                    binding.tvModelCount.text = "已拉取 ${r.value.size} 个可用模型，点「选择」切换"
                    if (availableModels.isEmpty()) {
                        showResult("服务商没有返回模型列表，可手动填写模型名", false)
                    } else {
                        showResult("已拉取 ${availableModels.size} 个模型", true)
                        showModelPicker()
                    }
                }
                is LlmResult.Err -> {
                    com.flashcut.app.DebugLog.e(this@LlmConfigActivity, "LLM", "拉取失败: ${r.message}")
                    showResult("拉取失败：${r.message}", false)
                }
            }
        }
    }

    /** 点「选择」：有缓存直接弹，没有就先拉 */
    private fun pickModel() {
        if (availableModels.isNotEmpty()) {
            showModelPicker()
        } else {
            listModels()
        }
    }

    private fun showModelPicker() {
        val listed = availableModels
        if (listed.isEmpty()) {
            showResult("还没有模型列表，请先点「拉取可用模型」", false)
            return
        }
        val currentModel = binding.etModel.text.toString().trim()
        val items = listed.map { m -> if (m == currentModel) "✓ $m" else "　 $m" }.toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("选择模型（${listed.size}）")
            .setItems(items) { _, which ->
                val chosen = listed[which]
                binding.etModel.setText(chosen)
                renderRouting()
                showResult("已选择模型：$chosen", true)
                com.flashcut.app.DebugLog.i(this, "LLM", "选择模型 $chosen")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun diagnose() {
        val p = collect()
        val key = binding.etApiKey.text.toString().trim()
        binding.tvResult.visibility = View.VISIBLE
        binding.tvResult.setTextColor(getColor(R.color.sub))
        binding.tvResult.text = "诊断中…"
        com.flashcut.app.DebugLog.i(this, "LLM", "诊断 base=${p.baseUrl} model=${p.model} keyLen=${key.length}")

        if (p.baseUrl.isBlank()) {
            showResult("✕ 请先填写接口地址", false)
            return
        }
        if (p.model.isBlank()) {
            showResult("✕ 请先填写模型名称", false)
            return
        }
        lifecycleScope.launch {
            val steps = client.diagnose(p, key)
            binding.tvResult.setTextColor(getColor(R.color.text))
            binding.tvResult.text = steps.joinToString("\n\n")
            com.flashcut.app.DebugLog.i(this@LlmConfigActivity, "LLM", "诊断结果: " + steps.joinToString(" | "))
        }
    }

    private fun showResult(text: String, ok: Boolean) {
        binding.tvResult.visibility = View.VISIBLE
        binding.tvResult.text = text
        binding.tvResult.setTextColor(getColor(if (ok) R.color.green else R.color.yellow))
    }
}
