// adapters/ChatAdapter.kt
package com.example.chatapp.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.example.chatapp.R
import com.example.chatapp.models.Chat
import com.example.chatapp.models.User
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.text.SimpleDateFormat
import java.util.*

class ChatAdapter(
    private var chatList: List<Chat>,
    private val usersCache: MutableMap<String, User>,
    private val onChatClick: (Chat) -> Unit,
    private val onUserClick: (String) -> Unit,
    private val onDeleteClick: (String) -> Unit
) : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    private val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: ""
    private val database = FirebaseDatabase.getInstance().reference

    inner class ChatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivUserAvatar: ImageView = itemView.findViewById(R.id.ivUserAvatar)
        val tvChatName: TextView = itemView.findViewById(R.id.tvChatName)
        val tvLastMessage: TextView = itemView.findViewById(R.id.tvLastMessage)
        val tvMessageTime: TextView = itemView.findViewById(R.id.tvMessageTime)
        val vOnlineStatus: View = itemView.findViewById(R.id.vOnlineStatus)
    }

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

        // Находим ID собеседника
        val otherUserId = findOtherUserId(chat)

        with(holder) {
            if (otherUserId != null) {
                // Проверяем, есть ли данные собеседника в кеше
                val otherUser = usersCache[otherUserId]

                if (otherUser != null) {
                    // Данные есть в кеше - отображаем ИМЯ СОБЕСЕДНИКА
                    bindUserData(holder, chat, otherUser, otherUserId)
                } else {
                    // Данных нет в кеше - загружаем их
                    loadUserData(otherUserId) { user ->
                        if (user != null) {
                            usersCache[otherUserId] = user
                            // После загрузки обновляем имя на имя СОБЕСЕДНИКА
                            bindUserData(holder, chat, user, otherUserId)
                        } else {
                            // Если не удалось загрузить, используем fallback
                            bindFallbackData(holder, chat, otherUserId)
                        }
                    }
                    // Временно показываем fallback данные
                    bindFallbackData(holder, chat, otherUserId)
                }
            } else {
                // Не нашли собеседника - показываем fallback
                bindFallbackData(holder, chat, null)
            }
        }
    }

    /**
     * Отображает данные пользователя - ИМЯ СОБЕСЕДНИКА
     */
    private fun bindUserData(holder: ChatViewHolder, chat: Chat, otherUser: User, otherUserId: String) {
        with(holder) {
            // ВАЖНО: Всегда используем имя СОБЕСЕДНИКА, а не из chat.name
            tvChatName.text = otherUser.name ?: "Unknown"

            // Отображение последнего сообщения
            val senderName = chat.lastMessageSenderName ?: "Неизвестный"
            val messageText = chat.lastMessageText ?: "..."
            val formattedLastMessage = if (chat.lastMessageSenderId == currentUserId) {
                "Вы: $messageText"
            } else {
                "$senderName: $messageText"
            }
            tvLastMessage.text = formattedLastMessage

            // Установка времени сообщения
            val timestamp = chat.lastMessageTimestamp ?: chat.createdAt
            tvMessageTime.text = if (timestamp > 0) formatTime(timestamp) else ""

            // Индикатор онлайн собеседника
            vOnlineStatus.visibility = if (otherUser.online == true) View.VISIBLE else View.GONE

            // Загрузка аватара собеседника
            loadUserAvatar(otherUser.profileImageUrl, ivUserAvatar)

            // Обработчики кликов
            itemView.setOnClickListener { onChatClick(chat) }
            ivUserAvatar.setOnClickListener { onUserClick(otherUserId) }
            itemView.setOnLongClickListener {
                onDeleteClick(chat.chatId)
                true
            }
        }
    }

    /**
     * Fallback данные когда не удалось загрузить информацию о пользователе
     */
    private fun bindFallbackData(holder: ChatViewHolder, chat: Chat, otherUserId: String?) {
        with(holder) {
            // Временно используем имя из чата, но это должно быть имя СОБЕСЕДНИКА
            // Если в chat.name сохраняется неправильное имя, это временное решение
            tvChatName.text = chat.name

            // Отображение последнего сообщения
            val senderName = chat.lastMessageSenderName ?: "Неизвестный"
            val messageText = chat.lastMessageText ?: "..."
            val formattedLastMessage = if (chat.lastMessageSenderId == currentUserId) {
                "Вы: $messageText"
            } else {
                "$senderName: $messageText"
            }
            tvLastMessage.text = formattedLastMessage

            // Установка времени сообщения
            val timestamp = chat.lastMessageTimestamp ?: chat.createdAt
            tvMessageTime.text = if (timestamp > 0) formatTime(timestamp) else ""

            // Скрываем онлайн статус
            vOnlineStatus.visibility = View.GONE

            // Стандартный аватар
            ivUserAvatar.setImageResource(R.drawable.ic_default_profile)

            // Обработчики кликов
            itemView.setOnClickListener { onChatClick(chat) }
            ivUserAvatar.setOnClickListener {
                otherUserId?.let { onUserClick(it) }
            }
            itemView.setOnLongClickListener {
                onDeleteClick(chat.chatId)
                true
            }
        }
    }

    /**
     * Находит ID собеседника
     */
    private fun findOtherUserId(chat: Chat): String? {
        return chat.participants.keys.firstOrNull { it != currentUserId }
    }

    /**
     * Загружает данные пользователя из Firebase
     */
    private fun loadUserData(userId: String, callback: (User?) -> Unit) {
        database.child("users").child(userId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val user = snapshot.getValue(User::class.java)
                    callback(user)
                }

                override fun onCancelled(error: DatabaseError) {
                    callback(null)
                }
            })
    }

    /**
     * Загружает аватар пользователя
     */
    private fun loadUserAvatar(avatarUrl: String?, imageView: ImageView) {
        if (!avatarUrl.isNullOrEmpty()) {
            Glide.with(imageView.context)
                .load(avatarUrl)
                .placeholder(R.drawable.ic_default_profile)
                .error(R.drawable.ic_default_profile)
                .transform(CircleCrop())
                .into(imageView)
        } else {
            imageView.setImageResource(R.drawable.ic_default_profile)
        }
    }

    override fun getItemCount(): Int = chatList.size

    private fun formatTime(timestamp: Long?): String {
        if (timestamp == null || timestamp <= 0) return ""
        return try {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        } catch (e: Exception) {
            ""
        }
    }
}