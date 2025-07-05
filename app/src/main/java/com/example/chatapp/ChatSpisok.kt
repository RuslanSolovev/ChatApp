package com.example.chatapp

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.chatapp.activities.ChatDetailActivity
import com.example.chatapp.activities.CreateChatActivity
import com.example.chatapp.activities.UserProfileActivity
import com.example.chatapp.adapters.ChatAdapter
import com.example.chatapp.databinding.ChatSpisokBinding
import com.example.chatapp.models.Chat
import com.example.chatapp.models.User
import com.example.chatapp.utils.Constants
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import androidx.appcompat.widget.SearchView

class ChatSpisok : AppCompatActivity() {
    private lateinit var binding: ChatSpisokBinding
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var database: DatabaseReference
    private lateinit var auth: FirebaseAuth
    private val chatList = mutableListOf<Chat>()
    private val allChats = mutableListOf<Chat>()
    private val usersCache = hashMapOf<String, User>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ChatSpisokBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.title = "Мои чаты"
        binding.toolbar.setTitleTextColor(ContextCompat.getColor(this, android.R.color.white))
        binding.toolbar.navigationIcon = ContextCompat.getDrawable(this, R.drawable.ic_account_circle)

        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance().reference
        setupSearchBar()
        setupRecyclerView()
        setupEventListeners()
        fetchChats()
    }

    private fun setupSearchBar() {
        binding.toolbar.inflateMenu(R.menu.search_menu)
        val searchItem = binding.toolbar.menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView

        searchView.apply {
            queryHint = "Поиск чатов..."
            findViewById<View>(androidx.appcompat.R.id.search_plate)?.background = null

            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?) = false

                override fun onQueryTextChange(newText: String?): Boolean {
                    performSearch(newText ?: "")
                    return true
                }
            })

            searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
                override fun onMenuItemActionExpand(p0: MenuItem): Boolean = true

                override fun onMenuItemActionCollapse(p0: MenuItem): Boolean {
                    performSearch("")
                    return true
                }
            })
        }
    }

    private fun performSearch(query: String) {
        val filtered = if (query.isBlank()) allChats else allChats.filter { chat ->
            chat.name.contains(query, true) ||
                    chat.lastMessage.contains(query, true) ||
                    usersCache[chat.creatorId]?.name?.contains(query, true) == true
        }

        chatAdapter.updateList(filtered)
        binding.emptyState.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter(
            chatList,
            usersCache,
            { openChatDetail(it.chatId) },
            { openUserProfile(it) },
            { showDeleteConfirmation(it) }
        )

        with(binding.rvChats) {
            layoutManager = LinearLayoutManager(this@ChatSpisok)
            adapter = chatAdapter
            setHasFixedSize(true)
            addItemDecoration(androidx.recyclerview.widget.DividerItemDecoration(this@ChatSpisok, LinearLayoutManager.VERTICAL))
        }
    }

    private fun setupEventListeners() {
        binding.fabCreateChat.setOnClickListener {
            startActivity(Intent(this, CreateChatActivity::class.java))
            overridePendingTransition(R.anim.slide_in_up, R.anim.stay)
        }

        binding.toolbar.setNavigationOnClickListener {
            startActivity(Intent(this, UserProfileActivity::class.java))
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }
    }

    private fun fetchChats() {
        val userId = auth.currentUser?.uid ?: return
        binding.progressBar.visibility = View.VISIBLE
        binding.emptyState.visibility = View.GONE

        database.child("chats")
            .orderByChild("participants/$userId")
            .equalTo(true)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    chatList.clear()
                    allChats.clear()
                    usersCache.clear()

                    snapshot.children.forEach { ds ->  // Сохраняем DataSnapshot в ds
                        ds.getValue(Chat::class.java)?.let { chat ->
                            val updatedChat = if (chat.chatId.isEmpty()) {
                                chat.copy(chatId = ds.key ?: "")  // Используем ds.key
                            } else {
                                chat
                            }
                            chatList.add(updatedChat)
                            allChats.add(updatedChat)
                            loadUserData(updatedChat.creatorId)
                        }
                    }

                    chatList.sortByDescending { it.lastMessageTimestamp ?: it.createdAt }
                    allChats.sortByDescending { it.lastMessageTimestamp ?: it.createdAt }
                    chatAdapter.updateList(chatList)
                    binding.progressBar.visibility = View.GONE
                    binding.emptyState.visibility =
                        if (chatList.isEmpty()) View.VISIBLE else View.GONE
                }

                override fun onCancelled(error: DatabaseError) {
                    binding.progressBar.visibility = View.GONE
                    showError("Ошибка загрузки чатов: ${error.message}")
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
                    Log.e("ChatSpisok", "Ошибка загрузки пользователя", error.toException())
                }
            })
    }

    private fun openChatDetail(chatId: String) {
        if (chatId.isBlank()) {
            Log.e("openChatDetail", "chatId пустой")
            Toast.makeText(this, "ID чата не указан", Toast.LENGTH_SHORT).show()
            return
        }

        if (!isValidChatId(chatId)) {
            Log.e("openChatDetail", "Недопустимый chatId: $chatId")
            Toast.makeText(this, "Недопустимый ID чата", Toast.LENGTH_SHORT).show()
            return
        }

        Intent(this, ChatDetailActivity::class.java).apply {
            putExtra(Constants.CHAT_ID, chatId)
            startActivity(this)
        }
    }

    private fun isValidChatId(chatId: String): Boolean {
        // Разрешены буквы, цифры, подчеркивания и дефисы
        return Regex("^[a-zA-Z0-9_-]+\$").matches(chatId)
    }

    private fun openUserProfile(userId: String) {
        Intent(this, UserProfileActivity::class.java).apply {
            putExtra(Constants.USER_ID, userId)
            startActivity(this)
            overridePendingTransition(R.anim.slide_in_up, R.anim.stay)
        }
    }

    private fun showDeleteConfirmation(chatId: String) {
        Snackbar.make(binding.root, "Удалить этот чат?", Snackbar.LENGTH_LONG)
            .setAction("УДАЛИТЬ") { deleteChat(chatId) }
            .setActionTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
            .show()
    }

    private fun deleteChat(chatId: String) {
        database.child("chats").child(chatId).removeValue()
            .addOnSuccessListener { showMessage("Чат удалён") }
            .addOnFailureListener { showError("Ошибка удаления: ${it.message}") }
    }

    private fun showMessage(message: String) =
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()

    private fun showError(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
        Log.e("ChatSpisok", message)
    }

    override fun onResume() {
        super.onResume()
        binding.toolbar.menu.findItem(R.id.action_search)?.collapseActionView()
    }
}