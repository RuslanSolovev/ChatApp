package com.example.chatapp.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

import com.example.chatapp.R
import com.example.chatapp.models.Chat
import com.example.chatapp.models.User
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatAdapter(
    private var chatList: List<Chat>,
    private val usersCache: Map<String, User>,
    private val onChatClick: (Chat) -> Unit,
    private val onUserClick: (String) -> Unit,
    private val onDeleteClick: (String) -> Unit
) : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    inner class ChatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivUserAvatar: ImageView = itemView.findViewById(R.id.ivUserAvatar)
        val tvChatName: TextView = itemView.findViewById(R.id.tvChatName)
        val tvLastMessage: TextView = itemView.findViewById(R.id.tvLastMessage)
        val tvMessageTime: TextView = itemView.findViewById(R.id.tvMessageTime)
        val vOnlineStatus: View = itemView.findViewById(R.id.vOnlineStatus)
    }

    // В класс ChatAdapter добавьте этот метод
    fun updateList(newList: List<Chat>) {
        this.chatList = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val chat = chatList[position]
        val user = usersCache[chat.creatorId]

        with(holder) {
            // Установка данных чата
            tvChatName.text = chat.name
            tvLastMessage.text = chat.lastMessage
            tvMessageTime.text = formatTime(chat.createdAt)

            // Установка аватарки


            // Индикатор онлайн
            vOnlineStatus.visibility = if (user?.isOnline == true) View.VISIBLE else View.GONE

            // Обработчики кликов
            itemView.setOnClickListener { onChatClick(chat) }  // Передаем весь объект
            ivUserAvatar.setOnClickListener { onUserClick(chat.creatorId) }
            itemView.setOnLongClickListener {
                onDeleteClick(chat.chatId)
                true
            }
        }
    }

    override fun getItemCount(): Int = chatList.size

    private fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}