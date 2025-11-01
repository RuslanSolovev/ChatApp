package com.example.chatapp.privetstvie_giga

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import android.content.Context

class GigaChatViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(GigaChatViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return GigaChatViewModel(context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}