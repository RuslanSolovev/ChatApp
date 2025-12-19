package com.example.chatapp.privetstvie_giga

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatapp.utils.MessageStorage
import kotlinx.coroutines.launch

class GigaChatViewModel(private val context: Context) : ViewModel() {

    private val _messages = mutableListOf<GigaMessage>()

    // Загрузка сообщений из SharedPreferences
    init {
        _messages.addAll(MessageStorage.loadMessages(context))
    }

    // Получение текущего списка сообщений
    val messages: List<GigaMessage>
        get() = _messages

    // Метод для добавления нового сообщения
    fun addMessage(text: String, isUser: Boolean) {
        val newMessage = GigaMessage(text, isUser)
        _messages.add(newMessage)
        viewModelScope.launch {
            MessageStorage.saveMessages(context, _messages)
        }
    }

    // Метод для удаления сообщения с сохранением в хранилище
    fun removeMessage(message: GigaMessage) {
        val index = _messages.indexOfFirst {
            it.text == message.text &&
                    it.isUser == message.isUser &&
                    it.timestamp == message.timestamp
        }

        if (index != -1) {
            _messages.removeAt(index)
            viewModelScope.launch {
                MessageStorage.saveMessages(context, _messages)
            }
        }
    }

    // Метод для удаления сообщения по индексу
    fun removeMessageAt(position: Int) {
        if (position in 0 until _messages.size) {
            _messages.removeAt(position)
            viewModelScope.launch {
                MessageStorage.saveMessages(context, _messages)
            }
        }
    }

    // Метод для удаления сообщения по тексту и типу
    fun removeMessage(text: String, isUser: Boolean) {
        val iterator = _messages.iterator()
        while (iterator.hasNext()) {
            val message = iterator.next()
            if (message.text == text && message.isUser == isUser) {
                iterator.remove()
                viewModelScope.launch {
                    MessageStorage.saveMessages(context, _messages)
                }
                break
            }
        }
    }

    // Метод для очистки всех сообщений
    fun clearAllMessages() {
        _messages.clear()
        viewModelScope.launch {
            MessageStorage.clearMessages(context)
        }
    }
}