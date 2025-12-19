package com.example.chatapp.zametki.ui.main

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.chatapp.databinding.ActivityAddNoteBinding
import com.example.chatapp.zametki.data.Note
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


@AndroidEntryPoint
class AddNoteActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddNoteBinding
    private val viewModel: AddNoteViewModel by viewModels()

    private var isEditMode = false
    private var noteId: Int = -1
    private var originalTitle = ""
    private var originalDescription = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityAddNoteBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isEditMode = intent.getBooleanExtra("editMode", false)
        noteId = intent.getIntExtra("noteId", -1)

        setupUI()
        setupListeners()

        if (isEditMode) {
            setupEditMode()
        }
    }

    private fun setupUI() {
        supportActionBar?.let {
            if (isEditMode) {
                it.title = "Редактировать заметку"
            } else {
                it.title = "Новая заметка"
            }
            it.setDisplayHomeAsUpEnabled(true)
        }
        binding.btnSave.isEnabled = false
    }

    private fun setupListeners() {
        binding.etTitle.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                validateFields()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        binding.etDescription.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                validateFields()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        binding.btnSave.setOnClickListener { saveNote() }
        binding.btnBack.setOnClickListener { onBackPressed() }
    }

    private fun validateFields(): Boolean {
        val title = binding.etTitle.text.toString().isNotEmpty()
        val description = binding.etDescription.text.toString().isNotEmpty()
        binding.btnSave.isEnabled = title && description
        return title && description
    }

    private fun saveNote() {
        val title = binding.etTitle.text.toString()
        val description = binding.etDescription.text.toString()
        val date = getCurrentDate()

        if (isEditMode) {
            val updatedNote = Note(id = noteId, title = title, description = description, date = date)
            viewModel.updateNote(updatedNote)
            Toast.makeText(this, "Заметка успешно обновлена", Toast.LENGTH_SHORT).show()
        } else {
            val newNote = Note(title = title, description = description, date = date)
            viewModel.addNote(newNote)
            Toast.makeText(this, "Заметка успешно создана", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun getCurrentDate(): String {
        val formatter = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        return formatter.format(Date())
    }

    private fun setupEditMode() {
        viewModel.getNoteById(noteId).observe(this) { note ->
            note?.let {
                binding.etTitle.setText(it.title)
                binding.etDescription.setText(it.description)
                originalTitle = it.title
                originalDescription = it.description
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}