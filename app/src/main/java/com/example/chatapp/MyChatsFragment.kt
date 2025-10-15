package com.example.chatapp.fragments

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView // Добавлено, если используется в layout
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
import com.example.chatapp.utils.Constants // Убедитесь, что Constants содержит CHAT_ID и USER_ID
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
    // private val allChats = mutableListOf<Chat>() // Не используется в адаптере, возможно, не нужна
    private val usersCache = hashMapOf<String, User>()
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyState: View
    private lateinit var rvChats: RecyclerView
    private lateinit var fabCreateChat: FloatingActionButton

    // --- Хранение слушателя ---
    private var chatsValueEventListener: ValueEventListener? = null
    private var currentUserUid: String? = null // Кэшируем UID при инициализации

    companion object {
        private const val TAG = "MyChatsFragment"
    }

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
        currentUserUid = auth.currentUser?.uid // Кэшируем UID

        // Инициализация элементов UI
        progressBar = binding.findViewById(R.id.progressBar)
        emptyState = binding.findViewById(R.id.emptyState)
        rvChats = binding.findViewById(R.id.rvChats)
        fabCreateChat = binding.findViewById(R.id.fabCreateChat)

        setupRecyclerView()
        setupFab()
        // fetchChats() вызывается в onStart, а не здесь
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

    // --- Управление слушателем в onStart/onStop ---
    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart: Fragment started, attaching listener")
        attachChatsListener()
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop: Fragment stopped, detaching listener")
        detachChatsListener()
    }

    private fun attachChatsListener() {
        val userId = currentUserUid
        if (userId.isNullOrEmpty()) {
            Log.e(TAG, "attachChatsListener: User not authenticated")
            // Можно обновить UI для неавторизованного пользователя
            return
        }

        // Убедитесь, что предыдущий слушатель отсоединен
        detachChatsListener()

        progressBar.visibility = View.VISIBLE
        emptyState.visibility = View.GONE

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                Log.d(TAG, "onDataChange: Received ${snapshot.children.count()} chats")
                chatList.clear()
                // allChats.clear() // Не используется в адаптере
                // usersCache.clear() // Очищать кэш пользователей не обязательно, это может быть неэффективно

                snapshot.children.forEach { ds ->
                    ds.getValue(Chat::class.java)?.let { chat ->
                        val updatedChat = if (chat.chatId.isEmpty()) {
                            chat.copy(chatId = ds.key ?: "")
                        } else {
                            chat
                        }
                        chatList.add(updatedChat)
                        // allChats.add(updatedChat) // Не используется в адаптере
                        // Загружаем данные только для других пользователей в чате
                        val otherUserId = findOtherUserId(updatedChat, userId)
                        if (otherUserId != null && !usersCache.containsKey(otherUserId)) {
                            loadUserData(otherUserId)
                        }
                    }
                }

                chatList.sortByDescending { it.lastMessageTimestamp ?: it.createdAt }
                // allChats.sortByDescending { it.lastMessageTimestamp ?: it.createdAt } // Не используется в адаптере
                chatAdapter.notifyDataSetChanged() // Используем notifyDataSetChanged, так как список перезаписывается
                progressBar.visibility = View.GONE
                emptyState.visibility = if (chatList.isEmpty()) View.VISIBLE else View.GONE
            }

            override fun onCancelled(error: DatabaseError) {
                progressBar.visibility = View.GONE
                Log.e(TAG, "onCancelled: ${error.message}", error.toException())
                showError("Ошибка загрузки чатов: ${error.message}")
            }
        }

        database.child("chats")
            .orderByChild("participants/$userId")
            .equalTo(true)
            .addValueEventListener(listener)

        // Сохраняем ссылку на новый слушатель
        chatsValueEventListener = listener
        Log.d(TAG, "attachChatsListener: ValueEventListener attached for user $userId")
    }

    private fun detachChatsListener() {
        chatsValueEventListener?.let { listener ->
            val userId = currentUserUid
            if (!userId.isNullOrEmpty()) {
                database.child("chats")
                    .orderByChild("participants/$userId")
                    .equalTo(true)
                    .removeEventListener(listener)
                Log.d(TAG, "detachChatsListener: ValueEventListener detached for user $userId")
            } else {
                Log.w(TAG, "detachChatsListener: User UID was null, listener might not be removed correctly")
            }
            chatsValueEventListener = null
        }
    }

    // Вспомогательная функция для нахождения другого участника
    private fun findOtherUserId(chat: Chat, currentUserId: String): String? {
        return chat.participants.keys.firstOrNull { it != currentUserId }
    }


    private fun loadUserData(userId: String) {
        if (usersCache.containsKey(userId)) return // Уже загружено

        database.child("users").child(userId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    snapshot.getValue(User::class.java)?.let { user ->
                        usersCache[userId] = user
                        // Уведомление адаптера нужно только если отображение зависит от данных из кеша, которые могут измениться позже
                        // chatAdapter.notifyDataSetChanged() // Обычно не нужно после загрузки одного пользователя, если адаптер не ждет его
                        // Лучше обновлять конкретный элемент, если это возможно, или полагаться на то, что onBindViewHolder обработает это при следующем отображении
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Ошибка загрузки пользователя $userId", error.toException())
                }
            })
    }


    private fun openChatDetail(chatId: String) {
        if (chatId.isBlank()) {
            Log.e(TAG, "openChatDetail: chatId пустой")
            return
        }

        if (!isValidChatId(chatId)) {
            Log.e(TAG, "openChatDetail: Недопустимый chatId: $chatId")
            return
        }

        Intent(requireContext(), ChatDetailActivity::class.java).apply {
            putExtra(Constants.CHAT_ID, chatId)
            startActivity(this)
        }
    }

    private fun isValidChatId(chatId: String): Boolean {
        // Упрощенная проверка, можно улучшить
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
        // Используем View, привязанную к RecyclerView, для Snackbar
        Snackbar.make(rvChats, "Удалить этот чат?", Snackbar.LENGTH_LONG)
            .setAction("УДАЛИТЬ") { deleteChat(chatId) }
            .setActionTextColor(requireContext().getColor(android.R.color.holo_red_light))
            .show()
    }

    private fun deleteChat(chatId: String) {
        database.child("chats").child(chatId).removeValue()
            .addOnSuccessListener {
                showMessage("Чат удалён")
                // Уведомление адаптера может быть автоматическим через ValueEventListener, но на всякий случай:
                // fetchChats() // Не вызываем fetchChats снова, слушатель сам обновит список
            }
            .addOnFailureListener { showError("Ошибка удаления: ${it.message}") }
    }

    private fun showMessage(message: String) {
        Snackbar.make(rvChats, message, Snackbar.LENGTH_SHORT).show() // Используем rvChats
    }

    private fun showError(message: String) {
        Snackbar.make(rvChats, message, Snackbar.LENGTH_LONG).show() // Используем rvChats
        Log.e(TAG, message)
    }

}