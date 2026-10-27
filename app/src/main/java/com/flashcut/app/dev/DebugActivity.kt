package com.flashcut.app.dev

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import java.io.File
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
            val uris = scanVideoFiles().ifEmpty { findSampleVideos() }
            DebugLog.i(this@DebugActivity, "Debug", "自检素材 ${uris.size} 段")
            val result = SelfTest.run(this@DebugActivity, uris)
            binding.tvOutput.text = result + "\n\n--- 完整日志 ---\n" + DebugLog.recent()
        }
    }

    /** 从相册取最多 2 个视频用于自检 */
    /**
     * 直接扫文件系统找视频。
     * MediaStore 需要 READ_MEDIA_VIDEO 权限，而 App 里申请权限又要用户点确认，
     * 自动化测试不该卡在这里，所以用文件路径直接读。
     */
    private fun scanVideoFiles(): List<Uri> {
        val dirs = listOf(
            "/sdcard/DCIM/Camera",
            "/sdcard/Movies",
            "/sdcard/Download",
            "/sdcard/Pictures",
        )
        val out = mutableListOf<Uri>()
        for (d in dirs) {
            runCatching {
                File(d).listFiles()?.asSequence()
                    ?.filter { it.isFile && it.length() > 200_000 }
                    ?.filter { f ->
                        val n = f.name.lowercase()
                        n.endsWith(".mp4") || n.endsWith(".mov") || n.endsWith(".mkv") || n.endsWith(".3gp")
                    }
                    ?.sortedByDescending { it.lastModified() }
                    ?.take(2)
                    ?.forEach { out += Uri.fromFile(it) }
            }
            if (out.size >= 2) break
        }
        return out.take(2)
    }

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
