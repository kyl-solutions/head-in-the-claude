package com.kylsolutions.kylidescope.ui.sessions

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.kylsolutions.kylidescope.R
import com.kylsolutions.kylidescope.SessionInfo
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class SessionAdapter(
    private val onTap: (SessionInfo) -> Unit,
    private val onLongPress: (SessionInfo) -> Unit
) : RecyclerView.Adapter<SessionAdapter.ViewHolder>() {

    private var sessions: List<SessionInfo> = emptyList()

    fun submitList(list: List<SessionInfo>) {
        sessions = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_session, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val session = sessions[position]

        holder.title.text = session.title
        holder.model.text = session.model?.let { shortModelName(it) } ?: "unknown"
        holder.messageCount.text = "${session.messageCount} msgs"
        holder.timestamp.text = formatTimestamp(session.updatedAt)

        holder.itemView.setOnClickListener { onTap(session) }
        holder.itemView.setOnLongClickListener { onLongPress(session); true }
    }

    override fun getItemCount() = sessions.size

    private fun shortModelName(modelId: String): String {
        return when {
            modelId.contains("opus") -> "opus"
            modelId.contains("sonnet") -> "sonnet"
            modelId.contains("haiku") -> "haiku"
            modelId.contains("gpt-4o") -> "gpt-4o"
            modelId.contains("qwen") -> modelId.substringAfterLast("/").take(12)
            else -> modelId.take(12)
        }
    }

    private fun formatTimestamp(iso: String): String {
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            parser.timeZone = TimeZone.getTimeZone("UTC")
            val date = parser.parse(iso) ?: return iso
            val now = System.currentTimeMillis()
            val diff = now - date.time

            when {
                diff < 60_000 -> "just now"
                diff < 3600_000 -> "${diff / 60_000}m ago"
                diff < 86400_000 -> "${diff / 3600_000}h ago"
                diff < 604800_000 -> "${diff / 86400_000}d ago"
                else -> SimpleDateFormat("MMM d", Locale.US).format(date)
            }
        } catch (_: Exception) {
            iso.take(10)
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.sessionTitle)
        val model: TextView = view.findViewById(R.id.sessionModel)
        val messageCount: TextView = view.findViewById(R.id.sessionMessageCount)
        val timestamp: TextView = view.findViewById(R.id.sessionTimestamp)
    }
}
