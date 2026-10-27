package com.flashcut.app.ui.editor

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.flashcut.app.data.Project
import com.flashcut.app.data.SourceClip
import com.flashcut.app.data.TimelineClip
import com.flashcut.app.databinding.ItemTrackClipBinding
import com.flashcut.app.media.MediaProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 画面轨：横向排列的片段卡片，宽度按时长自适应。 */
class TrackAdapter : RecyclerView.Adapter<TrackAdapter.VH>() {

    private var clips: List<TimelineClip> = emptyList()
    private var sources: Map<String, SourceClip> = emptyMap()
    private val thumbs = mutableMapOf<String, Bitmap?>()
    private val scope = CoroutineScope(Dispatchers.Main)

    var onTap: (TimelineClip) -> Unit = {}

    fun submit(project: Project) {
        clips = project.clips
        sources = project.sources.associateBy { it.id }
        notifyDataSetChanged()
    }

    class VH(val b: ItemTrackClipBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemTrackClipBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun getItemCount() = clips.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = clips[position]
        val src = sources[c.sourceId]
        val b = holder.b
        val sec = (c.outMs - c.inMs) / 1000f

        // 宽度按时长映射到 64..140dp
        val widthDp = (64 + (sec * 8).toInt()).coerceIn(64, 140)
        b.root.layoutParams = b.root.layoutParams.apply {
            width = (widthDp * b.root.resources.displayMetrics.density).toInt()
        }

        b.tvOrder.text = (position + 1).toString()
        b.tvClipLabel.text = buildString {
            append(String.format("%.1fs", sec))
            if (c.speed != 1f) append(" · ${c.speed}×")
        }
        b.ivClip.setImageBitmap(null)

        if (src != null) {
            val cached = thumbs[src.id]
            if (cached != null) {
                b.ivClip.setImageBitmap(cached)
            } else {
                scope.launch {
                    val bmp = withContext(Dispatchers.IO) {
                        MediaProbe.thumbnail(b.root.context, src.uri, 120)
                    }
                    thumbs[src.id] = bmp
                    if (bmp != null && holder.bindingAdapterPosition == position) {
                        b.ivClip.setImageBitmap(bmp)
                    }
                }
            }
        }
        b.root.setOnClickListener { onTap(c) }
    }
}
