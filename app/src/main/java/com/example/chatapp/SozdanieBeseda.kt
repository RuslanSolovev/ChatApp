package com.example.chatapp

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.chatapp.databinding.ActivitySozdanieBesedaBinding
import com.example.chatapp.models.Discussion
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.database

class SozdanieBeseda : AppCompatActivity() {

    private lateinit var binding: ActivitySozdanieBesedaBinding
    private lateinit var auth: FirebaseAuth
    private val database: FirebaseDatabase = Firebase.database

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySozdanieBesedaBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()

        // Обработка нажатия кнопки
        binding.fabCreateChatBeseda2.setOnClickListener {
            createNewDiscussion()
        }

        // Фокус на поле ввода при открытии
        binding.etBesedaName.requestFocus()
    }

    private fun createNewDiscussion() {
        val discussionName = binding.etBesedaName.text.toString().trim()

        if (discussionName.isEmpty()) {
            binding.etBesedaName.error = "Введите название беседы"
            binding.etBesedaName.requestFocus()
            return
        }

        val currentUserId = auth.currentUser?.uid
        if (currentUserId == null) {
            Toast.makeText(this, "Ошибка аутентификации", Toast.LENGTH_SHORT).show()
            return
        }

        // Блокируем кнопку на время создания
        binding.fabCreateChatBeseda2.isEnabled = false

        val discussionId = database.reference.child("discussions").push().key
            ?: run {
                showError("Ошибка генерации ID беседы")
                binding.fabCreateChatBeseda2.isEnabled = true
                return
            }

        val discussion = Discussion(
            discussionId = discussionId,
            title = discussionName,
            creatorId = currentUserId,
            createdAt = System.currentTimeMillis(),
            participantCount = 1,
            messageCount = 0
        )

        // Создаем данные для записи
        val updates = hashMapOf<String, Any>(
            "discussions/$discussionId" to discussion,
            "users/$currentUserId/discussions/$discussionId" to true
        )

        // Записываем данные в Firebase
        database.reference.updateChildren(updates)
            .addOnSuccessListener {
                Toast.makeText(
                    this,
                    "Беседа '$discussionName' создана",
                    Toast.LENGTH_SHORT
                ).show()

                // Возвращаемся обратно
                finish()
            }
            .addOnFailureListener { e ->
                showError("Ошибка создания беседы: ${e.message}")
                binding.fabCreateChatBeseda2.isEnabled = true
            }
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}