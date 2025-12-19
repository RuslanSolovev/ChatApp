package com.example.chatapp

import android.content.Context
import com.example.chatapp.zametki.data.AppDatabase
import com.example.chatapp.zametki.data.NoteDao
import com.example.chatapp.zametki.domean.repository.NotesRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.getDatabase(context)
    }

    @Provides
    @Singleton
    fun provideNoteDao(database: AppDatabase): NoteDao {
        return database.noteDao()
    }

    @Provides
    @Singleton
    fun provideNotesRepository(noteDao: NoteDao): NotesRepository {
        return NotesRepository(noteDao) // Убедитесь, что это работает
    }
}