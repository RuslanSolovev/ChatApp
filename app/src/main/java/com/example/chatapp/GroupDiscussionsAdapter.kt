package com.example.chatapp.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.chatapp.R
import com.example.chatapp.models.Discussion
import java.text.SimpleDateFormat
import java.util.*

class GroupDiscussionsAdapter(
    private val onItemClick: (Discussion) -> Unit
) : ListAdapter<Discussion, GroupDiscussionsAdapter.ViewHolder>(DiscussionDiffCallback()) {

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvGroupName: TextView = itemView.findViewById(R.id.tvGroupName)
        val tvLastMessage: TextView = itemView.findViewById(R.id.tvLastMessage)
        val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_group_discussion, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val discussion = getItem(position)
        holder.tvGroupName.text = discussion.title // используем поле title как имя группы

        // Исправляем lastMessageText (может быть null)
        holder.tvLastMessage.text = discussion.lastMessageText ?: "Нет сообщений"

        // Исправляем обработку nullable timestamp
        holder.tvTimestamp.text = discussion.lastMessageTimestamp?.let {
            formatTime(it)
        } ?: "--:--"

        holder.itemView.setOnClickListener {
            onItemClick(discussion)
        }
    }

    private fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}

class DiscussionDiffCallback : DiffUtil.ItemCallback<Discussion>() {
    override fun areItemsTheSame(oldItem: Discussion, newItem: Discussion): Boolean {
        return oldItem.discussionId == newItem.discussionId
    }

    override fun areContentsTheSame(oldItem: Discussion, newItem: Discussion): Boolean {
        return oldItem == newItem
    }
}