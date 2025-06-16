package com.example.chatapp.activities

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.chatapp.GuessNumberMenuActivity
import com.example.chatapp.IgraActivity
import com.example.chatapp.LocationActivity
import com.example.chatapp.R
import com.example.chatapp.StepCounterActivity
import com.example.chatapp.adapters.ChatAdapter
import com.example.chatapp.databinding.ActivityMainBinding
import com.example.chatapp.models.Chat
import com.example.chatapp.models.User
import com.example.chatapp.utils.Constants
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var database: DatabaseReference
    private lateinit var chatAdapter: ChatAdapter
    private val chatList = mutableListOf<Chat>()
    private val usersCache = hashMapOf<String, User>()
    private lateinit var igra: Button
    private lateinit var hagi: Button
    private lateinit var btnLocation: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Инициализация ViewBinding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Инициализация кнопок
        igra = findViewById(R.id.igra)
        hagi = findViewById(R.id.hagi)
        btnLocation = findViewById(R.id.btnLocation)

        // Инициализация FirebaseAuth
        auth = FirebaseAuth.getInstance()

        // Проверка аутентификации
        if (auth.currentUser == null) {
            startAuthActivity()
            return
        }

        // Инициализация базы данных Firebase
        database = FirebaseDatabase.getInstance().reference

        // Настройка RecyclerView и адаптера
        setupRecyclerView()

        // Загрузка чатов из базы
        fetchChats()

        // Настройка обработчиков кликов для кнопок
        setupClickListeners()

        // Обработчик для кнопки перехода в LocationActivity
        btnLocation.setOnClickListener {
            startActivity(Intent(this, LocationActivity::class.java))
        }
    }

    private fun startAuthActivity() {
        startActivity(Intent(this, AuthActivity::class.java))
        finish()
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter(
            chatList = chatList,
            usersCache = usersCache,
            onChatClick = { chatId -> openChatDetail(chatId) },
            onUserClick = { userId -> openUserProfile(userId) },
            onDeleteClick = { chatId -> deleteChat(chatId) }
        )

        binding.rvChats.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = chatAdapter
            setHasFixedSize(true)
        }
    }

    private fun setupClickListeners() {
        binding.fabCreateChat.setOnClickListener {
            startActivity(Intent(this, CreateChatActivity::class.java))
        }

        binding.igra.setOnClickListener {
            val intent = Intent(this, IgraActivity::class.java)
            startActivity(intent)
        }

        binding.btnLogout.setOnClickListener {
            auth.signOut()
            startAuthActivity()
        }

        binding.btnMyProfile.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }

        binding.hagi.setOnClickListener {
            startActivity(Intent(this, StepCounterActivity::class.java))
        }
    }

    private fun openChatDetail(chatId: String) {
        Intent(this, ChatDetailActivity::class.java).apply {
            putExtra(Constants.CHAT_ID, chatId)
            startActivity(this)
        }
    }

    private fun openUserProfile(userId: String) {
        Intent(this, UserProfileActivity::class.java).apply {
            putExtra(Constants.USER_ID, userId)
            startActivity(this)
        }
    }

    private fun fetchChats() {
        val userId = auth.currentUser?.uid ?: return

        database.child("chats")
            .orderByChild("participants/$userId")
            .equalTo(true)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    chatList.clear()
                    snapshot.children.forEach { ds ->
                        ds.getValue(Chat::class.java)?.let { chat ->
                            chatList.add(chat)
                            loadUserData(chat.creatorId)
                        }
                    }
                    chatAdapter.notifyDataSetChanged()

                    if (chatList.isEmpty()) {
                        Toast.makeText(
                            this@MainActivity,
                            "У вас пока нет чатов",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Toast.makeText(
                        this@MainActivity,
                        "Ошибка загрузки чатов: ${error.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                    Log.e("MainActivity", "DatabaseError: ${error.message}")
                }
            })
    }

    private fun loadUserData(userId: String) {
        if (usersCache.containsKey(userId)) return

        database.child("users").child(userId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    snapshot.getValue(User::class.java)?.let { user ->
                        usersCache[userId] = user
                        chatAdapter.notifyDataSetChanged()
                    }
                }

                override fun onCancelled(error: DatabaseError) {

                    Log.e("MainActivity", "Ошибка загрузки пользователя", error.toException())
                }
            })
    }

    private fun deleteChat(chatId: String) {
        database.child("chats").child(chatId).removeValue()
            .addOnSuccessListener {
                Toast.makeText(this, "Чат удалён", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Ошибка удаления: ${e.message}", Toast.LENGTH_SHORT).show()
                Log.e("MainActivity", "Ошибка удаления чата", e)
            }
    }

    override fun onStart() {
        super.onStart()
        if (auth.currentUser == null) {
            startAuthActivity()
        }
    }
}
