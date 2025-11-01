package com.example.chatapp.privetstvie_giga

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.chatapp.databinding.ItemUserMessageBinding
import com.example.chatapp.databinding.ItemBotMessageBinding

class GigaMessageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

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
                )
            )
            else -> BotViewHolder(
                ItemBotMessageBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
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

    fun updateMessages(newMessages: List<GigaMessage>) {
        messages.clear()
        messages.addAll(newMessages)
        notifyDataSetChanged()
    }

    // Метод для добавления нового сообщения
    fun addMessage(message: GigaMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1) // Уведомляем адаптер о новом элементе
    }

    class UserViewHolder(private val binding: ItemUserMessageBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(message: GigaMessage) {
            binding.textViewMessage.text = message.text
        }
    }

    class BotViewHolder(private val binding: ItemBotMessageBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(message: GigaMessage) {
            binding.textViewMessage.text = message.text
        }
    }
}