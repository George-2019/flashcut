package com.flashcut.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.flashcut.app.data.Project
import com.flashcut.app.databinding.ActivityProjectsBinding
import com.flashcut.app.databinding.ItemProjectBinding
import com.flashcut.app.ui.editor.EditorActivity

class ProjectsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProjectsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProjectsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnNew.setOnClickListener {
            startActivity(Intent(this, PickerActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        val list = ProjectStore.list(this)
        binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.rvProjects.layoutManager = LinearLayoutManager(this)
        binding.rvProjects.adapter = ProjectAdapter(list) { p ->
            startActivity(EditorActivity.intent(this, p.id))
        }
    }
}

private class ProjectAdapter(
    private val items: List<Project>,
    private val onOpen: (Project) -> Unit,
) : RecyclerView.Adapter<ProjectAdapter.VH>() {

    class VH(val b: ItemProjectBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemProjectBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = items[position]
        val sec = p.clips.sumOf { (it.outMs - it.inMs).coerceAtLeast(0) } / 1000
        holder.b.tvName.text = p.name
        holder.b.tvMeta.text = "${p.sources.size} 段素材 · ${p.clips.size} 个片段 · $sec 秒"
        holder.b.tvTime.text = "自动保存"
        holder.b.root.setOnClickListener { onOpen(p) }
    }
}
