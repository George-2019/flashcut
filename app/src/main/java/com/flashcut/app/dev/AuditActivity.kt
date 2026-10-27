package com.flashcut.app.dev

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.flashcut.app.databinding.ActivityDebugBinding
import kotlinx.coroutines.launch

/** 自动 UI 审计入口：一键跑完所有页面并输出问题清单。 */
class AuditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDebugBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDebugBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRunSelfTest.setOnClickListener { runAudit() }
        binding.btnShowLog.setOnClickListener {
            binding.tvOutput.text = com.flashcut.app.DebugLog.recent()
        }
        binding.btnClearLog.setOnClickListener { binding.tvOutput.text = "" }
        binding.btnPickTestVideo.visibility = android.view.View.GONE
        binding.btnRunSelfTest.text = "跑 UI 审计"

        // 自动开始
        binding.tvOutput.postDelayed({ runAudit() }, 300)
    }

    private fun runAudit() {
        binding.tvOutput.text = "正在逐个页面自动点击验证…\n共 12 个页面，预计 1-2 分钟。"
        lifecycleScope.launch {
            val report = UiAuditor(application).runAll()
            binding.tvOutput.text = report
        }
    }

    companion object {
        fun intent(context: Context) = Intent(context, AuditActivity::class.java)
    }
}
