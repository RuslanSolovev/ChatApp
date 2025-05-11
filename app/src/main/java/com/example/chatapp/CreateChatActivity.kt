package com.example.chatapp.activities

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.chatapp.databinding.ActivityCreateChatBinding
import com.example.chatapp.models.Chat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase

class CreateChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCreateChatBinding
    private lateinit var auth: FirebaseAuth
    private val database: FirebaseDatabase = Firebase.database
    private val currentUserId get() = auth.currentUser?.uid ?: ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreateChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()

        binding.btnCreateChat.setOnClickListener {
            createNewChat()
        }
    }

    private fun createNewChat() {
        val chatName = binding.etChatName.text.toString().trim()

        if (chatName.isEmpty()) {
            binding.etChatName.error = "Введите название чата"
            return
        }

        if (currentUserId.isEmpty()) {
            Toast.makeText(this, "Ошибка аутентификации", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnCreateChat.isEnabled = false


        val chatId = database.reference.child("chats").push().key
            ?: run {
                showError("Ошибка генерации ID чата")
                return
            }

        val chat = Chat(
            id = chatId,
            name = chatName,
            lastMessage = "Чат создан",
            participants = mapOf(currentUserId to true),
            createdAt = System.currentTimeMillis()
        )

        // Двойная запись для быстрого доступа к чатам пользователя
        val updates = hashMapOf<String, Any>(
            "chats/$chatId" to chat,
            "users/$currentUserId/chats/$chatId" to true
        )

        database.reference.updateChildren(updates)
            .addOnSuccessListener {
                Toast.makeText(this, "Чат $chatName создан", Toast.LENGTH_SHORT).show()
                navigateToChatDetail(chatId)
            }
            .addOnFailureListener { e ->
                showError("Ошибка создания чата: ${e.message}")
            }
            .addOnCompleteListener {
                binding.btnCreateChat.isEnabled = true

            }
    }

    private fun navigateToChatDetail(chatId: String) {
        Intent(this, ChatDetailActivity::class.java).apply {
            putExtra("chatId", chatId)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }.also { startActivity(it) }
        finish()
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}