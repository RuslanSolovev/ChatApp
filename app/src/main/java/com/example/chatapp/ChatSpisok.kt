package com.example.chatapp

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View

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
import com.google.firebase.database.DataSnapshot
import androidx.appcompat.widget.SearchView;  // Правильная версия
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class ChatSpisok : AppCompatActivity() {

    private lateinit var binding: ChatSpisokBinding
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var database: DatabaseReference
    private lateinit var auth: FirebaseAuth
    private val chatList = mutableListOf<Chat>() // текущий список чатов (для отображения)
    private val allChats = mutableListOf<Chat>() // полный список чатов (для поиска)
    private val usersCache = hashMapOf<String, User>()
    private lateinit var searchView: SearchView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ChatSpisokBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Убрать setSupportActionBar
        // setSupportActionBar(binding.toolbar)

        // Настроить Toolbar вручную
        binding.toolbar.title = "Мои чаты"
        binding.toolbar.setTitleTextColor(ContextCompat.getColor(this, android.R.color.white))
        binding.toolbar.navigationIcon = ContextCompat.getDrawable(this, R.drawable.ic_account_circle)

        // Остальной код
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

        searchView = searchItem.actionView as SearchView
        searchView.queryHint = "Поиск чатов..."

        // Убрать подчеркивание
        val searchPlate = searchView.findViewById<View>(androidx.appcompat.R.id.search_plate)
        searchPlate?.background = null

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                query?.let { performSearch(it) }
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                newText?.let { performSearch(it) }
                return true
            }
        })

        // Добавьте обработчик закрытия поиска
        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                performSearch("")
                return true
            }
        })
    }

    private fun performSearch(query: String) {
        val filteredList = if (query.isBlank()) {
            allChats.toList()
        } else {
            allChats.filter { chat ->
                chat.name.contains(query, ignoreCase = true) ||
                        chat.lastMessage.contains(query, ignoreCase = true) ||
                        (usersCache[chat.creatorId]?.name?.contains(
                            query,
                            ignoreCase = true
                        ) == true)
            }
        }

        // Обновляем адаптер с отфильтрованным списком
        chatAdapter.updateList(filteredList)
        binding.emptyState.visibility = if (filteredList.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter(
            chatList = chatList,
            usersCache = usersCache,
            onChatClick = { chat -> openChatDetail(chat.chatId) },
            onUserClick = { userId -> openUserProfile(userId) },
            onDeleteClick = { chatId -> showDeleteConfirmation(chatId) }
        )

        with(binding.rvChats) {
            layoutManager = LinearLayoutManager(this@ChatSpisok)
            adapter = chatAdapter
            setHasFixedSize(true)
            addItemDecoration(
                androidx.recyclerview.widget.DividerItemDecoration(
                    this@ChatSpisok,
                    LinearLayoutManager.VERTICAL
                )
            )
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

                    snapshot.children.forEach { ds ->
                        ds.getValue(Chat::class.java)?.let { chat ->
                            val updatedChat = if (chat.chatId.isEmpty()) {
                                chat.copy(chatId = ds.key ?: "")
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
        Intent(this, ChatDetailActivity::class.java).apply {
            putExtra(Constants.CHAT_ID, chatId)
            startActivity(this)
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }
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
            .setActionTextColor(resources.getColor(android.R.color.holo_red_light, theme))
            .show()
    }

    private fun deleteChat(chatId: String) {
        database.child("chats").child(chatId).removeValue()
            .addOnSuccessListener {
                showMessage("Чат удалён")
            }
            .addOnFailureListener { e ->
                showError("Ошибка удаления: ${e.message}")
            }
    }

    private fun showMessage(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    private fun showError(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
        Log.e("ChatSpisok", message)
    }

    override fun onResume() {
        super.onResume()
        performSearch("")

        // Закрыть поиск если открыт
        val searchItem = binding.toolbar.menu.findItem(R.id.action_search)
        if (searchItem.isActionViewExpanded) {
            searchItem.collapseActionView()
        }
        searchView.setQuery("", false)
        searchView.clearFocus()
    }
}