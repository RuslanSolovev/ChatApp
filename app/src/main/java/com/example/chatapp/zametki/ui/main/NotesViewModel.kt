package com.example.chatapp.zametki.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatapp.zametki.data.Note
import com.example.chatapp.zametki.domean.repository.NotesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NotesViewModel @Inject constructor(
    private val repository: NotesRepository
) : ViewModel() {

    val allNotes: Flow<List<Note>> = repository.getAllNotes()

    fun deleteNote(note: Note) {
        viewModelScope.launch {
            repository.deleteNote(note)
        }
    }

    // Добавляем метод для восстановления заметки
    fun addNote(note: Note) {
        viewModelScope.launch {
            repository.insertNote(note)
        }
    }
}
