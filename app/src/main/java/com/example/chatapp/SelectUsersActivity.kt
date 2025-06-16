package com.example.chatapp.activities

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.chatapp.adapters.UserAdapter
import com.example.chatapp.databinding.ActivitySelectUsersBinding
import com.example.chatapp.models.User
import com.example.chatapp.utils.Constants
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

class SelectUsersActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySelectUsersBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var database: DatabaseReference
    private lateinit var userAdapter: UserAdapter
    private var chatId: String? = null
    private var currentUserId: String? = null

    private var participantsListener: ValueEventListener? = null
    private var usersListener: ValueEventListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySelectUsersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Инициализация Firebase
        auth = FirebaseAuth.getInstance()
        currentUserId = auth.currentUser?.uid
        if (currentUserId == null) {
            showErrorAndFinish("Требуется авторизация")
            return
        }

        database = FirebaseDatabase.getInstance().reference
        chatId = intent.getStringExtra(Constants.CHAT_ID)
        if (chatId == null) {
            showErrorAndFinish("Чат не найден")
            return
        }

        setupUI()
    }

    private fun setupUI() {
        // Настройка адаптера с обоими обязательными параметрами
        userAdapter = UserAdapter(
            onUserSelected = { user, isSelected ->
                // Логирование выбора пользователя
                Log.d("UserSelection", "User ${user.name} selected: $isSelected")
            },
            onUserProfileClick = { userId ->
                // Обработка перехода в профиль пользователя
                val intent = Intent(this@SelectUsersActivity, UserProfileActivity::class.java).apply {
                    putExtra("USER_ID", userId)
                }
                startActivity(intent)
            }
        )

        // Настройка RecyclerView
        binding.rvUsers.apply {
            layoutManager = LinearLayoutManager(this@SelectUsersActivity)
            adapter = userAdapter
            setHasFixedSize(true)
        }

        // Настройка поиска
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                userAdapter.filter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Обработчик кнопки добавления
        binding.btnAddUsers.setOnClickListener {
            addSelectedUsersToChat()
        }
    }

    override fun onResume() {
        super.onResume()
        loadAvailableUsers()
    }

    private fun loadAvailableUsers() {
        val chatId = chatId ?: return showError("Чат не найден")
        val currentUserId = currentUserId ?: return

        binding.progressBar.visibility = View.VISIBLE
        binding.tvEmptyState.visibility = View.GONE

        // Удаляем предыдущие слушатели
        participantsListener?.let { database.removeEventListener(it) }
        usersListener?.let { database.removeEventListener(it) }

        // 1. Получаем ID текущих участников чата
        participantsListener = object : ValueEventListener {
            override fun onDataChange(participantsSnapshot: DataSnapshot) {
                val participantIds = participantsSnapshot.children.mapNotNull { it.key }.toSet()

                // 2. Загружаем всех пользователей
                usersListener = object : ValueEventListener {
                    override fun onDataChange(usersSnapshot: DataSnapshot) {
                        val availableUsers = mutableListOf<User>()

                        usersSnapshot.children.forEach { userSnapshot ->
                            try {
                                val user = userSnapshot.getValue(User::class.java)?.apply {
                                    uid = userSnapshot.key ?: ""
                                }
                                user?.let {
                                    if (it.uid != currentUserId && !participantIds.contains(it.uid)) {
                                        availableUsers.add(it)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("LoadUsers", "Error parsing user ${userSnapshot.key}", e)
                            }
                        }

                        userAdapter.submitList(availableUsers)
                        binding.progressBar.visibility = View.GONE

                        if (availableUsers.isEmpty()) {
                            binding.tvEmptyState.visibility = View.VISIBLE
                            binding.tvEmptyState.text = "Нет доступных пользователей"
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        binding.progressBar.visibility = View.GONE
                        showError("Ошибка загрузки пользователей: ${error.message}")
                    }
                }.also { listener ->
                    database.child("users").addListenerForSingleValueEvent(listener)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                binding.progressBar.visibility = View.GONE
                showError("Ошибка загрузки участников: ${error.message}")
            }
        }.also { listener ->
            database.child("chats").child(chatId).child("participants")
                .addListenerForSingleValueEvent(listener)
        }
    }

    private fun addSelectedUsersToChat() {
        val selectedUsers = userAdapter.getSelectedUsers()
        if (selectedUsers.isEmpty()) {
            showError("Выберите хотя бы одного пользователя")
            return
        }

        val chatId = chatId ?: return showError("Чат не найден")
        binding.progressBar.visibility = View.VISIBLE
        binding.btnAddUsers.isEnabled = false

        val updates = hashMapOf<String, Any>()
        selectedUsers.forEach { user ->
            updates["chats/$chatId/participants/${user.uid}"] = true
        }

        database.updateChildren(updates)
            .addOnSuccessListener {
                binding.progressBar.visibility = View.GONE
                binding.btnAddUsers.isEnabled = true
                showSuccess("Пользователи добавлены в чат")
                setResult(RESULT_OK)
                finish()
            }
            .addOnFailureListener { e ->
                binding.progressBar.visibility = View.GONE
                binding.btnAddUsers.isEnabled = true
                Log.e("AddUsers", "Error adding users", e)
                showError("Ошибка: ${e.localizedMessage}")
            }
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showSuccess(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showErrorAndFinish(message: String) {
        showError(message)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Очищаем слушатели
        participantsListener?.let { database.removeEventListener(it) }
        usersListener?.let { database.removeEventListener(it) }
    }
}