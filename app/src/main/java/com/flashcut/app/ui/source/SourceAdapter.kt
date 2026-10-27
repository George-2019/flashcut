package com.flashcut.app.ui.source

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.flashcut.app.data.SourceClip
import com.flashcut.app.databinding.ItemSourceCardBinding
import com.flashcut.app.media.MediaProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 选片页的素材卡：缩略图 + 时长 + 分辨率。 */
class SourceAdapter(
    private val onRemove: (SourceClip) -> Unit = {},
) : RecyclerView.Adapter<SourceAdapter.VH>() {

    private val items = mutableListOf<SourceClip>()
    private val scope = CoroutineScope(Dispatchers.Main)
    private val thumbCache = mutableMapOf<String, Bitmap?>()

    fun submit(list: List<SourceClip>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(val b: ItemSourceCardBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemSourceCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val clip = items[position]
        val b = holder.b
        b.tvName.text = clip.displayName
        b.tvMeta.text = "${clip.width}×${clip.height} · ${MediaProbe.formatTime(clip.durationMs)}"
        b.tvDuration.text = MediaProbe.formatTime(clip.durationMs)
        b.tvChip.text = if (clip.durationMs >= 8000) "可剪 2 段" else "完整使用"
        b.ivThumb.setImageBitmap(null)

        val cached = thumbCache[clip.id]
        if (cached != null) {
            b.ivThumb.setImageBitmap(cached)
        } else {
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { MediaProbe.thumbnail(b.root.context, clip.uri) }
                thumbCache[clip.id] = bmp
                if (bmp != null && holder.bindingAdapterPosition == position) {
                    holder.b.ivThumb.setImageBitmap(bmp)
                }
            }
        }
        b.btnRemove.setOnClickListener { onRemove(clip) }
    }
}
