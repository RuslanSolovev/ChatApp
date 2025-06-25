package com.example.chatapp.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatapp.models.GigaMessage
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

    // Метод для очистки всех сообщений
    fun clearAllMessages() {
        _messages.clear()
        viewModelScope.launch {
            MessageStorage.clearMessages(context)
        }
    }
}