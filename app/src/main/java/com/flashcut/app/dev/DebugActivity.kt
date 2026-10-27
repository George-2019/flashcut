package com.flashcut.app.dev

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import android.content.ContentUris
import com.flashcut.app.DebugLog
import com.flashcut.app.databinding.ActivityDebugBinding
import kotlinx.coroutines.launch

/** 调试页：跑自检、看日志、导出日志。 */
class DebugActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDebugBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDebugBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRunSelfTest.setOnClickListener { runSelfTest() }
        binding.btnShowLog.setOnClickListener {
            binding.tvOutput.text = DebugLog.recent().ifBlank { "(日志为空)" }
        }
        binding.btnClearLog.setOnClickListener {
            binding.tvOutput.text = ""
            Toast.makeText(this, "已清空显示", Toast.LENGTH_SHORT).show()
        }
        val launcher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
        ) { uri -> if (uri != null) runOn(uri) }
        binding.btnPickTestVideo.setOnClickListener { launcher.launch(arrayOf("video/*")) }
        binding.btnRunSelfTest.performClick()
    }

    private fun runOn(uri: Uri) {
        binding.tvOutput.text = "自检运行中…"
        lifecycleScope.launch {
            DebugLog.i(this@DebugActivity, "Debug", "手动指定自检素材 $uri")
            binding.tvOutput.text = SelfTest.run(this@DebugActivity, listOf(uri)) +
                "\n\n--- 完整日志 ---\n" + DebugLog.recent()
        }
    }

    private fun runSelfTest() {
        binding.tvOutput.text = "自检运行中…"
        lifecycleScope.launch {
            val uris = findSampleVideos()
            DebugLog.i(this@DebugActivity, "Debug", "自检素材 ${uris.size} 段")
            val result = SelfTest.run(this@DebugActivity, uris)
            binding.tvOutput.text = result + "\n\n--- 完整日志 ---\n" + DebugLog.recent()
        }
    }

    /** 从相册取最多 2 个视频用于自检 */
    private fun findSampleVideos(): List<Uri> {
        val out = mutableListOf<Uri>()
        runCatching {
            MediaStore.Video.query(
                contentResolver,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media._ID),
            )?.use { c ->
                while (c.moveToNext() && out.size < 2) {
                    val id = c.getLong(0)
                    out += ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id
                    )
                }
            }
        }
        return out
    }

    companion object {
        fun intent(context: Context) = Intent(context, DebugActivity::class.java)
    }
}
