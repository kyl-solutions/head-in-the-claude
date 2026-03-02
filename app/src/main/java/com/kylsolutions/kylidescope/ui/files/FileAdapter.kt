package com.kylsolutions.kylidescope.ui.files

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.kylsolutions.kylidescope.FsEntry
import com.kylsolutions.kylidescope.R

class FileAdapter(
    private val onTap: (FsEntry) -> Unit,
    private val onLongPress: (FsEntry) -> Unit
) : RecyclerView.Adapter<FileAdapter.ViewHolder>() {

    private var entries: List<FsEntry> = emptyList()

    fun submitList(list: List<FsEntry>) {
        entries = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file_entry, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]
        val ctx = holder.itemView.context
        val isDir = entry.type == "directory"

        holder.name.text = entry.name
        holder.icon.setImageResource(if (isDir) R.drawable.ic_folder else R.drawable.ic_file)
        holder.icon.setColorFilter(
            ContextCompat.getColor(ctx, if (isDir) R.color.signal_green else R.color.text_dim)
        )
        holder.chevron.visibility = if (isDir) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener { onTap(entry) }
        holder.itemView.setOnLongClickListener { onLongPress(entry); true }
    }

    override fun getItemCount() = entries.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.entryIcon)
        val name: TextView = view.findViewById(R.id.entryName)
        val chevron: ImageView = view.findViewById(R.id.chevron)
    }
}
