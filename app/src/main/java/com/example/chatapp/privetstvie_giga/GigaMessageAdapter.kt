package com.example.chatapp.privetstvie_giga

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.chatapp.databinding.ItemUserMessageBinding
import com.example.chatapp.databinding.ItemBotMessageBinding

class GigaMessageAdapter(
    private val onMessageClickListener: (GigaMessage) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_BOT = 0
    }

    // Используем изменяемый список для хранения сообщений
    private val messages = mutableListOf<GigaMessage>()

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isUser) VIEW_TYPE_USER else VIEW_TYPE_BOT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_USER -> UserViewHolder(
                ItemUserMessageBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                ),
                onMessageClickListener
            )
            else -> BotViewHolder(
                ItemBotMessageBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                ),
                onMessageClickListener
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is UserViewHolder -> holder.bind(messages[position])
            is BotViewHolder -> holder.bind(messages[position])
        }
    }

    override fun getItemCount(): Int = messages.size

    // Метод для получения всех сообщений
    fun getMessages(): List<GigaMessage> = messages.toList()

    // Метод для получения сообщения по позиции
    fun getMessage(position: Int): GigaMessage? = messages.getOrNull(position)

    fun updateMessages(newMessages: List<GigaMessage>) {
        messages.clear()
        messages.addAll(newMessages)
        notifyDataSetChanged()
    }

    // Метод для добавления нового сообщения
    fun addMessage(message: GigaMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    // Метод для очистки всех сообщений
    fun clearMessages() {
        messages.clear()
        notifyDataSetChanged()
    }

    // Метод для получения последнего сообщения
    fun getLastMessage(): GigaMessage? {
        return messages.lastOrNull()
    }

    class UserViewHolder(
        private val binding: ItemUserMessageBinding,
        private val onMessageClickListener: (GigaMessage) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private var lastClickTime = 0L

        fun bind(message: GigaMessage) {
            binding.textViewMessage.text = message.text

            // Устанавливаем обработчик двойного клика
            binding.root.setOnClickListener {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastClickTime < 300) {
                    // Двойной клик - повторная озвучка
                    onMessageClickListener(message)
                    lastClickTime = 0
                } else {
                    lastClickTime = currentTime
                }
            }

            // Устанавливаем обработчик долгого нажатия
            binding.root.setOnLongClickListener {
                onMessageClickListener(message)
                true
            }
        }
    }

    class BotViewHolder(
        private val binding: ItemBotMessageBinding,
        private val onMessageClickListener: (GigaMessage) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private var lastClickTime = 0L

        fun bind(message: GigaMessage) {
            binding.textViewMessage.text = message.text

            // Устанавливаем обработчик двойного клика
            binding.root.setOnClickListener {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastClickTime < 300) {
                    // Двойной клик - повторная озвучка
                    onMessageClickListener(message)
                    lastClickTime = 0
                } else {
                    lastClickTime = currentTime
                }
            }

            // Устанавливаем обработчик долгого нажатия
            binding.root.setOnLongClickListener {
                onMessageClickListener(message)
                true
            }
        }
    }
}