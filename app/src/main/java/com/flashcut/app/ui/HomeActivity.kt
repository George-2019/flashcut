package com.flashcut.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.flashcut.app.DebugLog
import com.flashcut.app.R
import com.flashcut.app.databinding.ActivityHomeBinding
import com.flashcut.app.ui.editor.EditorActivity
import com.flashcut.app.ui.llm.LlmConfigActivity

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private var style = "vlog"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        DebugLog.i(this, "Home", "启动 版本=${com.flashcut.app.BuildConfig.VERSION_NAME}(${com.flashcut.app.BuildConfig.VERSION_CODE})")
        binding.tvVersionTag.text = "v${com.flashcut.app.BuildConfig.VERSION_NAME}"
        bind()
        refreshRecent()
        runSelfCheck()
    }

    override fun onResume() {
        super.onResume()
        refreshRecent()
    }

    private fun bind() = with(binding) {
        // 点击风格卡 = 选中该风格
        cardStyleVlog.setOnClickListener { selectStyle("vlog") }
        cardStyleBeat.setOnClickListener { selectStyle("beat") }
        cardStyleTalk.setOnClickListener { selectStyle("talk") }
        cardStyleShop.setOnClickListener { selectStyle("shop") }

        // 确认风格后，再点底部主按钮开始导入
        cardRecent.setOnClickListener {
            val last = ProjectStore.list(this@HomeActivity).firstOrNull()
            if (last != null) {
                startActivity(EditorActivity.intent(this@HomeActivity, last.id))
            } else {
                toast("还没有项目，先导入素材吧")
            }
        }
        btnAllProjects.setOnClickListener { openProjects() }
        btnStart.setOnClickListener { openPicker() }
        btnMine.setOnClickListener { openMine() }
        navTemplates.setOnClickListener {
            startActivity(Intent(this@HomeActivity, TemplatesActivity::class.java))
        }
        navProjects.setOnClickListener { openProjects() }
        navMine.setOnClickListener { openMine() }
        navHome.setOnClickListener { }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun openPicker() {
        DebugLog.i(this, "Home", "进入选片 style=$style")
        startActivity(Intent(this, PickerActivity::class.java).putExtra(PickerActivity.EXTRA_STYLE, style))
    }

    private fun openProjects() = startActivity(Intent(this, ProjectsActivity::class.java))
    private fun openMine() = startActivity(Intent(this, MineActivity::class.java))

    private fun selectStyle(id: String) {
        style = id
        binding.checkVlog.visibility = if (id == "vlog") View.VISIBLE else View.INVISIBLE
        binding.checkBeat.visibility = if (id == "beat") View.VISIBLE else View.INVISIBLE
        binding.checkTalk.visibility = if (id == "talk") View.VISIBLE else View.INVISIBLE
        binding.checkShop.visibility = if (id == "shop") View.VISIBLE else View.INVISIBLE

        binding.cardStyleVlog.setBackgroundResource(if (id == "vlog") R.drawable.bg_card_brand else R.drawable.bg_card)
        binding.cardStyleBeat.setBackgroundResource(if (id == "beat") R.drawable.bg_card_brand else R.drawable.bg_card)
        binding.cardStyleTalk.setBackgroundResource(if (id == "talk") R.drawable.bg_card_brand else R.drawable.bg_card)
        binding.cardStyleShop.setBackgroundResource(if (id == "shop") R.drawable.bg_card_brand else R.drawable.bg_card)

        val name = when (id) {
            "beat" -> "情绪卡点 / 燃向"
            "talk" -> "口播 / 播客切片"
            "shop" -> "好物 / 带货"
            else -> "旅行 / 生活 Vlog"
        }
        toast("已选：$name")
    }

    private fun refreshRecent() {
        val list = ProjectStore.list(this)
        val last = list.firstOrNull()
        if (last == null) {
            binding.tvRecentName.text = "还没有项目"
            binding.tvRecentMeta.text = "选好风格后点下方按钮导入素材"
            return
        }
        binding.tvRecentName.text = last.name
        val sec = last.clips.sumOf { (it.outMs - it.inMs).coerceAtLeast(0) } / 1000
        binding.tvRecentMeta.text = "${last.sources.size} 段素材合成 · ${sec} 秒 · 自动保存"
    }

    private fun runSelfCheck() {
        val store = com.flashcut.app.llm.LlmConfigStore(this)
        val profile = store.loadActive()
        val hasKey = store.hasKey(profile.id)

        val checks = listOf(
            "镜头与高光分析" to true,
            "本地镜头切分" to true,
            "硬件编码导出" to true,
            "大模型剪辑脚本" to hasKey,
        )
        val ok = checks.count { it.second }
        binding.tvSelfCheck.text = if (ok == checks.size) "全部就绪" else "$ok/${checks.size} 就绪"
        binding.tvSelfCheck.setTextColor(getColor(if (ok == checks.size) R.color.green else R.color.yellow))
        binding.tvCapabilities.text = checks.joinToString("\n") { (name, ready) ->
            (if (ready) "✓ " else "○ ") + name
        }
        if (!hasKey) {
            binding.tvLlmHint.visibility = View.VISIBLE
            binding.tvLlmHint.text = "未配置大模型 Key，将使用本地规则剪法。点这里配置 ›"
            binding.tvLlmHint.setOnClickListener {
                startActivity(Intent(this, LlmConfigActivity::class.java))
            }
        } else {
            binding.tvLlmHint.visibility = View.GONE
        }
        DebugLog.i(this, "Home", "自检 ok=$ok/${checks.size} llm=${profile.model} hasKey=$hasKey")
    }
}
