package com.example.chatapp.zametki.ui.main

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.chatapp.databinding.ActivityNotesBinding
import com.example.chatapp.zametki.adapter.NotesAdapter
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class NotesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotesBinding
    private val viewModel: NotesViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityNotesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        observeNotes()
        setupAddNoteButton()
        setupSwipeToDelete() // Добавляем свайп для удаления
    }

    private fun setupRecyclerView() {
        val adapter = NotesAdapter().apply {
            setOnItemClickListener { note ->
                val intent = Intent(this@NotesActivity, AddNoteActivity::class.java).apply {
                    putExtra("editMode", true)
                    putExtra("noteId", note.id)
                }
                startActivity(intent)
            }

            setOnDeleteClickListener { note ->
                showDeleteConfirmationDialog(note)
            }
        }

        binding.recyclerView.apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@NotesActivity)
            this.adapter = adapter
        }
    }

    private fun observeNotes() {
        lifecycleScope.launch {
            viewModel.allNotes.collect { notes ->
                (binding.recyclerView.adapter as NotesAdapter).submitList(notes)
            }
        }
    }

    private fun setupAddNoteButton() {
        binding.fabAddNote.setOnClickListener {
            startActivity(Intent(this, AddNoteActivity::class.java))
        }
    }

    private fun setupSwipeToDelete() {
        val itemTouchHelperCallback = object : ItemTouchHelper.SimpleCallback(
            0, // drag directions - не используем
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT // swipe directions
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                return false // Не поддерживаем перетаскивание
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                val adapter = binding.recyclerView.adapter as NotesAdapter
                val note = adapter.currentList[position]

                // Удаляем заметку
                viewModel.deleteNote(note)

                // Показываем Snackbar с возможностью отмены
                showUndoSnackbar(note)
            }
        }

        val itemTouchHelper = ItemTouchHelper(itemTouchHelperCallback)
        itemTouchHelper.attachToRecyclerView(binding.recyclerView)
    }

    private fun showUndoSnackbar(deletedNote: com.example.chatapp.zametki.data.Note) {
        Snackbar.make(
            binding.root,
            "Заметка удалена",
            Snackbar.LENGTH_LONG
        ).apply {
            setAction("Отменить") {
                // Восстанавливаем заметку (добавляем снова)
                // Вам нужно будет добавить метод addNote в NotesViewModel
                viewModel.addNote(deletedNote)
            }
            show()
        }
    }

    private fun showDeleteConfirmationDialog(note: com.example.chatapp.zametki.data.Note) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Удалить заметку")
            .setMessage("Вы уверены, что хотите удалить эту заметку?")
            .setPositiveButton("Удалить") { _, _ ->
                viewModel.deleteNote(note)
                Snackbar.make(binding.root, "Заметка удалена", Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}