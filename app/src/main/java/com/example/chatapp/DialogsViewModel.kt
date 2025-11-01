// viewmodels/DialogsViewModel.kt
package com.example.chatapp.viewmodels

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.chatapp.SavedDialog
import com.example.chatapp.privetstvie_giga.GigaMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DialogsViewModel(application: Application) : AndroidViewModel(application) {

    private val _savedDialogs = MutableLiveData<List<SavedDialog>>()
    val savedDialogs: LiveData<List<SavedDialog>> = _savedDialogs

    private val sharedPreferences: SharedPreferences =
        application.getSharedPreferences("saved_dialogs", android.content.Context.MODE_PRIVATE)

    init {
        loadDialogs()
    }

    fun saveDialog(title: String, messages: List<GigaMessage>) {
        viewModelScope.launch {
            val newDialog = SavedDialog(
                title = title,
                messages = messages.toList()
            )

            val currentDialogs = _savedDialogs.value?.toMutableList() ?: mutableListOf()
            currentDialogs.add(0, newDialog)
            _savedDialogs.value = currentDialogs

            saveDialogs(currentDialogs)
        }
    }

    fun deleteDialog(dialogId: String) {
        viewModelScope.launch {
            val currentDialogs = _savedDialogs.value?.toMutableList() ?: mutableListOf()
            val updatedDialogs = currentDialogs.filter { it.id != dialogId }
            _savedDialogs.value = updatedDialogs
            saveDialogs(updatedDialogs)
        }
    }

    fun loadDialog(dialog: SavedDialog): List<GigaMessage> {
        return dialog.messages
    }

    private fun loadDialogs() {
        viewModelScope.launch {
            try {
                val dialogs = mutableListOf<SavedDialog>()
                val dialogCount = sharedPreferences.getInt("dialog_count", 0)

                for (i in 0 until dialogCount) {
                    val id = sharedPreferences.getString("dialog_${i}_id", "") ?: ""
                    val title = sharedPreferences.getString("dialog_${i}_title", "") ?: ""
                    val timestamp = sharedPreferences.getLong("dialog_${i}_timestamp", System.currentTimeMillis())
                    val messageCount = sharedPreferences.getInt("dialog_${i}_message_count", 0)

                    val messages = mutableListOf<GigaMessage>()
                    for (j in 0 until messageCount) {
                        val text = sharedPreferences.getString("dialog_${i}_message_${j}_text", "") ?: ""
                        val isUser = sharedPreferences.getBoolean("dialog_${i}_message_${j}_isUser", false)
                        messages.add(GigaMessage(text, isUser))
                    }

                    if (id.isNotEmpty() && title.isNotEmpty()) {
                        dialogs.add(SavedDialog(id, title, messages, timestamp))
                    }
                }

                _savedDialogs.value = dialogs
            } catch (e: Exception) {
                e.printStackTrace()
                _savedDialogs.value = emptyList()
            }
        }
    }

    private fun saveDialogs(dialogs: List<SavedDialog>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                with(sharedPreferences.edit()) {
                    putInt("dialog_count", dialogs.size)

                    dialogs.forEachIndexed { index, dialog ->
                        putString("dialog_${index}_id", dialog.id)
                        putString("dialog_${index}_title", dialog.title)
                        putLong("dialog_${index}_timestamp", dialog.timestamp)
                        putInt("dialog_${index}_message_count", dialog.messages.size)

                        dialog.messages.forEachIndexed { msgIndex, message ->
                            putString("dialog_${index}_message_${msgIndex}_text", message.text)
                            putBoolean("dialog_${index}_message_${msgIndex}_isUser", message.isUser)
                        }
                    }

                    apply()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}