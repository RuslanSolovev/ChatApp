package com.example.chatapp.location

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.chatapp.R
import com.example.chatapp.activities.ChatDetailActivity
import com.example.chatapp.activities.UserProfileActivity
import com.example.chatapp.models.User
import com.example.chatapp.utils.Constants
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

class FriendsListActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: FriendsAdapter
    private val database = FirebaseDatabase.getInstance().reference
    private val auth = FirebaseAuth.getInstance()
    private val isOnlineMap = mutableMapOf<String, Boolean>()
    private val friendsList = mutableListOf<String>()
    private val friendNames = mutableMapOf<String, String>()
    private val friendAvatars = mutableMapOf<String, String>() // Новая карта для аватаров

    companion object {
        private const val TAG = "FriendsListActivity" // Добавим тег для логов
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_friends_list)

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        loadFriends()
        observeOnlineStatus()
    }

    private fun loadFriends() {
        val userId = auth.currentUser?.uid ?: return

        database.child("users").child(userId).child("friends")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    friendsList.clear()
                    for (child in snapshot.children) {
                        val friendId = child.key ?: continue
                        friendsList.add(friendId)
                        loadFriendInfo(friendId) // Загружаем имя и аватар
                    }
                    updateAdapter()
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Ошибка загрузки друзей: ${error.message}", error.toException())
                    Toast.makeText(this@FriendsListActivity, "Ошибка загрузки друзей", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun loadFriendInfo(friendId: String) {
        database.child("users").child(friendId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val user = snapshot.getValue(User::class.java)
                    val name = user?.getFullName() ?: friendId
                    val avatarUrl = user?.profileImageUrl ?: "https://storage.yandexcloud.net/chatskii/profile_$friendId.jpg" // Заглушка
                    friendNames[friendId] = name
                    friendAvatars[friendId] = avatarUrl
                    updateAdapter()
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Ошибка загрузки информации о друге $friendId: ${error.message}", error.toException())
                    friendNames[friendId] = friendId
                    friendAvatars[friendId] = "" // Пустая строка для заглушки
                    updateAdapter()
                }
            })
    }

    private fun observeOnlineStatus() {
        database.child("user_locations")
            .addChildEventListener(object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                    val userId = snapshot.key
                    userId?.let { isOnlineMap[it] = true }
                    updateAdapter()
                }

                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                    val userId = snapshot.key
                    userId?.let { isOnlineMap[it] = true }
                    updateAdapter()
                }

                override fun onChildRemoved(snapshot: DataSnapshot) {
                    val userId = snapshot.key
                    userId?.let { isOnlineMap[it] = false }
                    updateAdapter()
                }

                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Ошибка наблюдения за статусом друзей: ${error.message}", error.toException())
                }
            })
    }

    private fun updateAdapter() {
        val namesList = friendsList.map { friendNames[it] ?: it }
        val avatarUrlList = friendsList.associateWith { friendAvatars[it] ?: "" } // Создаём карту ID -> URL
        adapter = FriendsAdapter(
            friends = namesList,
            friendIds = friendsList,
            onChatClick = { position -> openChat(friendsList[position]) }, // Передаём friendId
            onProfileClick = { position -> openProfile(friendsList[position]) }, // Передаём friendId
            onDeleteClick = { position -> removeFriend(friendsList[position]) },
            isOnlineMap = isOnlineMap,
            avatarUrls = avatarUrlList // Передаём карту аватаров
        )
        recyclerView.adapter = adapter
    }

    // --- Изменённая функция openChat ---
    private fun openChat(friendId: String) {
        Log.d(TAG, "openChat: Открытие чата с $friendId")
        val currentUserId = auth.currentUser?.uid ?: run {
            Log.w(TAG, "openChat: Пользователь не авторизован")
            Toast.makeText(this@FriendsListActivity, "Необходимо войти в аккаунт", Toast.LENGTH_SHORT).show()
            return
        }

        // Проверка на отправку сообщения себе
        if (friendId == currentUserId) {
            Log.w(TAG, "openChat: Попытка отправить сообщение самому себе")
            Toast.makeText(this@FriendsListActivity, "Нельзя отправить сообщение самому себе", Toast.LENGTH_SHORT).show()
            return
        }

        val chatId = if (currentUserId < friendId) "$currentUserId-$friendId" else "$friendId-$currentUserId"
        val chatRef = database.child("chats").child(chatId)

        // Проверяем, существует ли чат
        chatRef.get().addOnSuccessListener { chatSnapshot ->
            if (chatSnapshot.exists()) {
                Log.d(TAG, "openChat: Чат с $friendId уже существует, открываем.")
                // Чат уже есть, сразу открываем
                openChatActivity(chatId) // Передаём только chatId
            } else {
                Log.d(TAG, "openChat: Создание нового чата с $friendId")
                // Загружаем данные другого пользователя
                database.child("users").child(friendId).get().addOnSuccessListener { userSnapshot ->
                    val otherUser = userSnapshot.getValue(User::class.java)
                    if (otherUser != null) {
                        val otherUserName = otherUser.getFullName() ?: "Пользователь $friendId"
                        val otherUserAvatarUrl = otherUser.profileImageUrl
                        // Загружаем данные текущего пользователя
                        database.child("users").child(currentUserId).addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(currentUserSnapshot: DataSnapshot) {
                                val currentUser = currentUserSnapshot.getValue(User::class.java)
                                if (currentUser != null) {
                                    val currentUserName = currentUser.getFullName() ?: "Я"
                                    val currentUserAvatarUrl = currentUser.profileImageUrl
                                    // Создаём объект Chat с правильной структурой для ChatDetailActivity
                                    val newChat = mapOf(
                                        "creatorId" to currentUserId,
                                        "creatorName" to currentUserName,
                                        "imageUrl" to currentUserAvatarUrl,
                                        "createdAt" to System.currentTimeMillis(),
                                        "participants" to mapOf(currentUserId to true, friendId to true),
                                        "name" to otherUserName, // Имя другого пользователя для отображения
                                        "avatarUrl" to otherUserAvatarUrl, // Аватар другого пользователя для отображения
                                        "lastMessage" to "", // Или null
                                        "lastMessageTime" to 0L // Или System.currentTimeMillis() если это время создания
                                    )
                                    // Сохраняем чат
                                    val updates = mutableMapOf<String, Any>()
                                    updates["chats/$chatId"] = newChat
                                    database.updateChildren(updates)
                                        .addOnSuccessListener {
                                            Log.d(TAG, "openChat: Чат с $friendId успешно создан в базе данных.")
                                            // После успешного создания открываем чат
                                            openChatActivity(chatId) // Передаём только chatId
                                        }
                                        .addOnFailureListener { e ->
                                            Log.e(TAG, "openChat: Ошибка сохранения чата в базе данных", e)
                                            Toast.makeText(this@FriendsListActivity, "Ошибка сохранения чата: ${e.message}", Toast.LENGTH_LONG).show()
                                        }
                                } else {
                                    Log.w(TAG, "openChat: Данные текущего пользователя не найдены")
                                    Toast.makeText(this@FriendsListActivity, "Ошибка: данные текущего пользователя", Toast.LENGTH_SHORT).show()
                                }
                            }
                            override fun onCancelled(databaseError: DatabaseError) {
                                Log.e(TAG, "openChat: Ошибка загрузки данных текущего пользователя", databaseError.toException())
                                Toast.makeText(this@FriendsListActivity, "Ошибка загрузки данных текущего пользователя", Toast.LENGTH_SHORT).show()
                            }
                        })
                    } else {
                        Log.w(TAG, "openChat: Пользователь с ID $friendId не найден в базе данных")
                        Toast.makeText(this@FriendsListActivity, "Пользователь не найден", Toast.LENGTH_SHORT).show()
                    }
                }.addOnFailureListener { e ->
                    Log.e(TAG, "openChat: Ошибка загрузки данных пользователя $friendId", e)
                    Toast.makeText(this@FriendsListActivity, "Ошибка загрузки данных пользователя: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.addOnFailureListener { e ->
            Log.e(TAG, "openChat: Ошибка проверки существования чата", e)
            Toast.makeText(this@FriendsListActivity, "Ошибка проверки чата: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // --- Новая функция openChatActivity ---
    private fun openChatActivity(chatId: String) {
        Log.d(TAG, "openChatActivity: Открытие чата $chatId")
        val intent = Intent(this@FriendsListActivity, ChatDetailActivity::class.java).apply {
            putExtra(Constants.CHAT_ID, chatId) // Используем Constants.CHAT_ID
            // Убираем putExtra для FRIEND_NAME и FRIEND_AVATAR_URL
        }
        startActivity(intent)
        // Дополнительно: можно добавить анимацию перехода
        // overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }


    // --- Изменённая функция openProfile ---
    private fun openProfile(friendId: String) {
        Log.d(TAG, "openProfile: Открытие профиля $friendId")
        val intent = Intent(this@FriendsListActivity, UserProfileActivity::class.java).apply {
            putExtra("USER_ID", friendId) // Передаём USER_ID
        }
        startActivity(intent)
        // Дополнительно: можно добавить анимацию перехода
        // overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }


    private fun removeFriend(friendId: String) {
        val userId = auth.currentUser?.uid ?: return
        database.child("users").child(userId).child("friends").child(friendId).removeValue()
        friendsList.remove(friendId)
        friendNames.remove(friendId)
        friendAvatars.remove(friendId)
        updateAdapter()
        Toast.makeText(this, "Удалён из друзей: $friendId", Toast.LENGTH_SHORT).show()
    }
}