package com.example.chatapp.utils

import android.content.Context
import com.example.chatapp.models.GigaMessage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object MessageStorage {

    private const val PREFS_NAME = "ChatPrefs"
    private const val MESSAGES_KEY = "messages"

    // Сохранение списка сообщений
    fun saveMessages(context: Context, messages: List<GigaMessage>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val gson = Gson()
        val json = gson.toJson(messages)
        editor.putString(MESSAGES_KEY, json)
        editor.apply()
    }

    // Загрузка списка сообщений
    fun loadMessages(context: Context): List<GigaMessage> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(MESSAGES_KEY, null)
        return if (json != null) {
            val gson = Gson()
            val type = object : TypeToken<List<GigaMessage>>() {}.type
            gson.fromJson(json, type)
        } else {
            emptyList()
        }
    }

    // Очистка всех сообщений
    fun clearMessages(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.remove(MESSAGES_KEY)
        editor.apply()
    }
}