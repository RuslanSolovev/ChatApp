package com.example.chatapp.privetstvie_giga

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.chatapp.databinding.ItemUserMessageBinding
import com.example.chatapp.databinding.ItemBotMessageBinding

class GigaMessageAdapter(
    private val onMessageClickListener: (GigaMessage) -> Unit = {},
    private val onMessageLongClickListener: (GigaMessage) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_BOT = 0
        private const val DOUBLE_CLICK_DELAY = 300L
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
                onMessageClickListener,
                onMessageLongClickListener
            )
            else -> BotViewHolder(
                ItemBotMessageBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                ),
                onMessageClickListener,
                onMessageLongClickListener
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

    // Метод для удаления сообщения
    fun removeMessage(message: GigaMessage) {
        val position = messages.indexOf(message)
        if (position != -1) {
            messages.removeAt(position)
            notifyItemRemoved(position)
        }
    }

    class UserViewHolder(
        private val binding: ItemUserMessageBinding,
        private val onMessageClickListener: (GigaMessage) -> Unit,
        private val onMessageLongClickListener: (GigaMessage) -> Unit = {}
    ) : RecyclerView.ViewHolder(binding.root) {

        private var lastClickTime = 0L
        private var isWaitingForDoubleClick = false
        private val doubleClickHandler = Handler(Looper.getMainLooper())
        private var doubleClickRunnable: Runnable? = null

        fun bind(message: GigaMessage) {
            binding.textViewMessage.text = message.text

            // Обработчик одинарного/двойного клика
            binding.root.setOnClickListener {
                val currentTime = System.currentTimeMillis()

                if (currentTime - lastClickTime < DOUBLE_CLICK_DELAY) {
                    // Двойной клик - отменяем ожидание одинарного клика
                    doubleClickRunnable?.let { doubleClickHandler.removeCallbacks(it) }
                    doubleClickRunnable = null
                    isWaitingForDoubleClick = false

                    // Вызываем обработчик двойного клика (повторная озвучка)
                    onMessageClickListener(message)
                } else {
                    // Одинарный клик - начинаем ожидание двойного
                    lastClickTime = currentTime
                    isWaitingForDoubleClick = true

                    doubleClickRunnable = Runnable {
                        // Если прошло время ожидания - это был одинарный клик
                        if (isWaitingForDoubleClick) {
                            // Ничего не делаем для одинарного клика
                            // Или можно вызвать другую функцию, если нужно
                            isWaitingForDoubleClick = false
                        }
                    }

                    doubleClickHandler.postDelayed(doubleClickRunnable!!, DOUBLE_CLICK_DELAY)
                }
            }

            // Обработчик долгого нажатия (только для контекстного меню)
            binding.root.setOnLongClickListener {
                // Отменяем ожидание двойного клика
                doubleClickRunnable?.let { doubleClickHandler.removeCallbacks(it) }
                doubleClickRunnable = null
                isWaitingForDoubleClick = false

                // Вызываем обработчик долгого нажатия
                onMessageLongClickListener(message)
                true
            }
        }

        // Очистка ресурсов
        fun clear() {
            doubleClickRunnable?.let { doubleClickHandler.removeCallbacks(it) }
            doubleClickRunnable = null
            isWaitingForDoubleClick = false
        }
    }

    class BotViewHolder(
        private val binding: ItemBotMessageBinding,
        private val onMessageClickListener: (GigaMessage) -> Unit,
        private val onMessageLongClickListener: (GigaMessage) -> Unit = {}
    ) : RecyclerView.ViewHolder(binding.root) {

        private var lastClickTime = 0L
        private var isWaitingForDoubleClick = false
        private val doubleClickHandler = Handler(Looper.getMainLooper())
        private var doubleClickRunnable: Runnable? = null

        fun bind(message: GigaMessage) {
            binding.textViewMessage.text = message.text

            // Обработчик одинарного/двойного клика
            binding.root.setOnClickListener {
                val currentTime = System.currentTimeMillis()

                if (currentTime - lastClickTime < DOUBLE_CLICK_DELAY) {
                    // Двойной клик - отменяем ожидание одинарного клика
                    doubleClickRunnable?.let { doubleClickHandler.removeCallbacks(it) }
                    doubleClickRunnable = null
                    isWaitingForDoubleClick = false

                    // Вызываем обработчик двойного клика (повторная озвучка)
                    onMessageClickListener(message)
                } else {
                    // Одинарный клик - начинаем ожидание двойного
                    lastClickTime = currentTime
                    isWaitingForDoubleClick = true

                    doubleClickRunnable = Runnable {
                        // Если прошло время ожидания - это был одинарный клик
                        if (isWaitingForDoubleClick) {
                            // Ничего не делаем для одинарного клика
                            isWaitingForDoubleClick = false
                        }
                    }

                    doubleClickHandler.postDelayed(doubleClickRunnable!!, DOUBLE_CLICK_DELAY)
                }
            }

            // Обработчик долгого нажатия (только для контекстного меню)
            binding.root.setOnLongClickListener {
                // Отменяем ожидание двойного клика
                doubleClickRunnable?.let { doubleClickHandler.removeCallbacks(it) }
                doubleClickRunnable = null
                isWaitingForDoubleClick = false

                // Вызываем обработчик долгого нажатия
                onMessageLongClickListener(message)
                true
            }
        }

        // Очистка ресурсов
        fun clear() {
            doubleClickRunnable?.let { doubleClickHandler.removeCallbacks(it) }
            doubleClickRunnable = null
            isWaitingForDoubleClick = false
        }
    }

    // Очистка всех ресурсов холдеров при уничтожении адаптера
    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        when (holder) {
            is UserViewHolder -> holder.clear()
            is BotViewHolder -> holder.clear()
        }
    }

    // Очистка при уничтожении адаптера
    fun release() {
        messages.clear()
        notifyDataSetChanged()
    }
}