package com.example.chatapp.zametki.domean.repository

import com.example.chatapp.zametki.data.Note
import com.example.chatapp.zametki.data.NoteDao
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton // Добавьте эту аннотацию

@Singleton // ← ВАЖНО: добавьте эту аннотацию
class NotesRepository @Inject constructor(
    private val noteDao: NoteDao
) {

    fun getAllNotes(): Flow<List<Note>> = noteDao.getAllNotes()

    suspend fun insertNote(note: Note) = noteDao.insertNote(note)

    suspend fun updateNote(note: Note) = noteDao.updateNote(note)

    suspend fun deleteNote(note: Note) = noteDao.deleteNote(note)

    fun getNoteById(id: Int): Flow<Note?> = noteDao.getNoteById(id)
}