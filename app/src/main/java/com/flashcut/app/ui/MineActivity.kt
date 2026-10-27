package com.flashcut.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.flashcut.app.BuildConfig
import com.flashcut.app.databinding.ActivityMineBinding
import com.flashcut.app.ui.llm.LlmConfigActivity

class MineActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMineBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMineBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.rowLlm.setOnClickListener {
            startActivity(Intent(this, LlmConfigActivity::class.java))
        }
        binding.rowAbout.setOnClickListener {
            startActivity(Intent(this, TemplatesActivity::class.java))
        }
        binding.tvVersion.text = "闪剪 FlashCut v${BuildConfig.VERSION_NAME}"
        binding.rowLog.setOnClickListener { exportLog() }
        binding.rowAudit.setOnClickListener {
            startActivity(com.flashcut.app.dev.AuditActivity.intent(this))
        }
        binding.rowDebug.setOnClickListener {
            startActivity(com.flashcut.app.dev.DebugActivity.intent(this))
        }
    }

    /** 把调试日志写到公共 Download 目录，方便外部读取 */
    private fun exportLog() {
        val src = java.io.File(getExternalFilesDir(null), "flashcut-debug.log")
        if (!src.exists()) {
            android.widget.Toast.makeText(this, "还没有日志", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val dir = java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                ),
                "DSHA"
            )
            dir.mkdirs()
            val dst = java.io.File(dir, "flashcut-debug.log")
            src.copyTo(dst, overwrite = true)
            android.widget.Toast.makeText(
                this, "日志已导出：Download/DSHA/flashcut-debug.log",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }.onFailure {
            android.widget.Toast.makeText(this, "导出失败：${it.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        binding.tvProjectCount.text = "${ProjectStore.list(this).size} 个项目"
    }
}
