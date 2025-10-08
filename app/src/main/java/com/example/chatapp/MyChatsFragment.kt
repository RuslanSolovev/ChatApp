package com.example.chatapp.fragments

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.chatapp.R
import com.example.chatapp.activities.ChatDetailActivity
import com.example.chatapp.activities.CreateChatActivity
import com.example.chatapp.activities.UserProfileActivity
import com.example.chatapp.adapters.ChatAdapter
import com.example.chatapp.models.Chat
import com.example.chatapp.models.User
import com.example.chatapp.utils.Constants
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

class MyChatsFragment : Fragment() {

    private lateinit var binding: View
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var database: DatabaseReference
    private lateinit var auth: FirebaseAuth
    private val chatList = mutableListOf<Chat>()
    private val allChats = mutableListOf<Chat>()
    private val usersCache = hashMapOf<String, User>()
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyState: View
    private lateinit var rvChats: RecyclerView
    private lateinit var fabCreateChat: FloatingActionButton

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = inflater.inflate(R.layout.fragment_my_chats, container, false)
        return binding
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance().reference

        // Инициализация элементов UI
        progressBar = binding.findViewById(R.id.progressBar)
        emptyState = binding.findViewById(R.id.emptyState)
        rvChats = binding.findViewById(R.id.rvChats)
        fabCreateChat = binding.findViewById(R.id.fabCreateChat)

        setupRecyclerView()
        setupFab()
        fetchChats()
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter(
            chatList,
            usersCache,
            { openChatDetail(it.chatId) },
            { openUserProfile(it) },
            { showDeleteConfirmation(it) }
        )

        with(rvChats) {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = chatAdapter
            setHasFixedSize(true)
        }
    }

    private fun setupFab() {
        fabCreateChat.setOnClickListener {
            startActivity(Intent(requireContext(), CreateChatActivity::class.java))
            requireActivity().overridePendingTransition(R.anim.slide_in_up, R.anim.stay)
        }
    }

    private fun fetchChats() {
        val userId = auth.currentUser?.uid ?: return
        progressBar.visibility = View.VISIBLE
        emptyState.visibility = View.GONE

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
                    progressBar.visibility = View.GONE
                    emptyState.visibility = if (chatList.isEmpty()) View.VISIBLE else View.GONE
                }

                override fun onCancelled(error: DatabaseError) {
                    progressBar.visibility = View.GONE
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
                    Log.e("MyChatsFragment", "Ошибка загрузки пользователя", error.toException())
                }
            })
    }

    private fun openChatDetail(chatId: String) {
        if (chatId.isBlank()) {
            Log.e("openChatDetail", "chatId пустой")
            return
        }

        if (!isValidChatId(chatId)) {
            Log.e("openChatDetail", "Недопустимый chatId: $chatId")
            return
        }

        Intent(requireContext(), ChatDetailActivity::class.java).apply {
            putExtra(Constants.CHAT_ID, chatId)
            startActivity(this)
        }
    }

    private fun isValidChatId(chatId: String): Boolean {
        return Regex("^[a-zA-Z0-9_-]+\$").matches(chatId)
    }

    private fun openUserProfile(userId: String) {
        Intent(requireContext(), UserProfileActivity::class.java).apply {
            putExtra(Constants.USER_ID, userId)
            startActivity(this)
            requireActivity().overridePendingTransition(R.anim.slide_in_up, R.anim.stay)
        }
    }

    private fun showDeleteConfirmation(chatId: String) {
        Snackbar.make(binding, "Удалить этот чат?", Snackbar.LENGTH_LONG)
            .setAction("УДАЛИТЬ") { deleteChat(chatId) }
            .setActionTextColor(requireContext().getColor(android.R.color.holo_red_light))
            .show()
    }

    private fun deleteChat(chatId: String) {
        database.child("chats").child(chatId).removeValue()
            .addOnSuccessListener { showMessage("Чат удалён") }
            .addOnFailureListener { showError("Ошибка удаления: ${it.message}") }
    }

    private fun showMessage(message: String) {
        Snackbar.make(binding, message, Snackbar.LENGTH_SHORT).show()
    }

    private fun showError(message: String) {
        Snackbar.make(binding, message, Snackbar.LENGTH_LONG).show()
        Log.e("MyChatsFragment", message)
    }
}