package com.example.chatapp.activities

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.github.chrisbanes.photoview.PhotoView
import com.example.chatapp.R
import com.example.chatapp.adapters.MessageAdapter
import com.example.chatapp.api.RetrofitInstance
import com.example.chatapp.databinding.ActivityChatDetailBinding
import com.example.chatapp.databinding.DialogEditMessageBinding
import com.example.chatapp.models.Message
import com.example.chatapp.models.User
import com.example.chatapp.utils.Constants
import com.example.chatapp.utils.NotificationUtils
import com.google.android.gms.auth.api.signin.internal.Storage
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.File
import java.io.IOException
import java.lang.System.setProperty
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class ChatDetailActivity : AppCompatActivity() {

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { uploadImageToYandexCloud(it) }
    }

    private lateinit var binding: ActivityChatDetailBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var database: DatabaseReference
    private lateinit var storage: FirebaseStorage
    private lateinit var messageAdapter: MessageAdapter
    private val messageList = mutableListOf<Message>()
    private val usersCache = hashMapOf<String, User>()
    private var chatId: String = ""
    private var replyingToMessage: Message? = null
    private var otherUserId: String = "" // ID собеседника
    private var otherUserName: String = "" // Имя собеседника
    private var otherUserAvatar: String? = null // Аватар собеседника

    private val currentUserId: String
        get() = auth.currentUser?.uid ?: ""
    private val selectedMessageColor = Color.parseColor("#E3F2FD")

    companion object {
        private const val ACCESS_KEY_ID = "YCAJEIgiTghuX8JsxiQJUQIlM"
        private const val SECRET_ACCESS_KEY = "YCOVaI5JOrYqyLHWSiIYvV3Qa-N5T3lGmCMFwIvk"
        private const val TAG = "ChatDetailActivity"
        private const val MAX_RETRY_ATTEMPTS = 3
        const val CHAT_ID = "chat_id"

        // Константы для Yandex Object Storage
        private const val BUCKET_NAME = "chatskii"
        private const val REGION = "ru-central1"
        private const val SERVICE = "s3"
        private const val REQUEST_TYPE = "aws4_request"
        private const val ALGORITHM = "AWS4-HMAC-SHA256"
        private const val ENDPOINT = "https://storage.yandexcloud.net"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        try {
            initFirebase()
            setupRecyclerView()
            setupClickListeners()
            loadChatData()
            setupUserUpdatesListener()
            registerFcmToken()
            NotificationUtils.saveCurrentUserOneSignalIdToDatabase(this)
        } catch (e: Exception) {
            Log.e(TAG, "Initialization error", e)
            showErrorAndFinish("Initialization failed")
        }
    }

    private fun initFirebase() {
        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance().reference
        storage = FirebaseStorage.getInstance()
        chatId = intent.getStringExtra(Constants.CHAT_ID) ?: run {
            showErrorAndFinish("Chat ID is required")
            return
        }
    }

    private fun registerFcmToken() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w(TAG, "Failed to get FCM token", task.exception)
                retryFcmTokenRegistration()
                return@addOnCompleteListener
            }
            val token = task.result
            if (token.isNullOrEmpty()) {
                Log.w(TAG, "FCM token is empty")
                return@addOnCompleteListener
            }
            updateFcmTokenSafely(token)
        }
    }

    private fun updateFcmTokenSafely(token: String) {
        database.child("users").child(currentUserId).child("fcmToken")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val currentToken = snapshot.getValue(String::class.java)
                    if (currentToken == null || currentToken != token) {
                        database.child("users").child(currentUserId).child("fcmToken")
                            .setValue(token)
                            .addOnSuccessListener {
                                Log.d(TAG, "FCM token updated successfully")
                            }
                            .addOnFailureListener { e ->
                                Log.e(TAG, "Failed to update FCM token", e)
                                retryFcmTokenUpdate(token)
                            }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Failed to check current token", error.toException())
                    retryFcmTokenUpdate(token)
                }
            })
    }

    private fun retryFcmTokenUpdate(token: String, attempt: Int = 1) {
        if (attempt <= MAX_RETRY_ATTEMPTS) {
            binding.root.postDelayed({
                Log.d(TAG, "Retrying FCM token update (attempt $attempt)")
                updateFcmTokenSafely(token)
            }, 5000L * attempt)
        }
    }

    private fun retryFcmTokenRegistration(attempt: Int = 1) {
        if (attempt <= MAX_RETRY_ATTEMPTS) {
            binding.root.postDelayed({
                Log.d(TAG, "Retrying FCM token registration (attempt $attempt)")
                registerFcmToken()
            }, 5000L * attempt)
        }
    }

    private fun setupRecyclerView() {
        binding.rvMessages.apply {
            layoutManager = LinearLayoutManager(this@ChatDetailActivity).apply {
                stackFromEnd = true
            }
            messageAdapter = MessageAdapter(
                messages = messageList,
                usersCache = usersCache,
                currentUserId = currentUserId,
                onMessageClick = { message ->
                    // Обработка клика по сообщению
                },
                onUserClick = { userId ->
                    openUserProfile(userId)
                },
                onReplyClick = { message ->
                    replyingToMessage = message
                    showReplyingView(message)
                },
                onDeleteClick = { message ->
                    showDeleteDialog(message)
                },
                onEditClick = { message ->
                    showEditDialog(message)
                }
            )
            adapter = messageAdapter
        }
    }

    private fun openImagePicker() {
        try {
            imagePicker.launch("image/*")
        } catch (e: Exception) {
            Log.e(TAG, "Image picker error", e)
            showError("Failed to open gallery")
        }
    }

    private fun setupClickListeners() {
        binding.apply {
            btnSendMessage.setOnClickListener {
                val text = etMessage.text.toString().trim()
                if (text.isNotEmpty()) {
                    sendMessage(text)
                    etMessage.setText("")
                } else {
                    showToast("Please enter message")
                }
            }
            btnAttachImage.setOnClickListener { openImagePicker() }
            btnAddParticipant.setOnClickListener { openAddParticipants() }
            btnCancelReply.setOnClickListener { hideReplyingView() }
            replyContainer.setOnClickListener {
                replyingToMessage?.id?.let { messageId ->
                    scrollToMessage(messageId)
                }
            }

            // Обработчики кликов на заголовок чата
            ivProfileImage.setOnClickListener {
                if (otherUserId.isNotEmpty()) {
                    openUserProfile(otherUserId)
                }
            }

            tvUserName.setOnClickListener {
                if (otherUserId.isNotEmpty()) {
                    openUserProfile(otherUserId)
                }
            }
        }
    }

    private fun loadChatData() {
        if (chatId.isBlank()) {
            showErrorAndFinish("Invalid chat ID")
            return
        }

        database.child("chats").child(chatId).child("participants")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!snapshot.hasChild(currentUserId)) {
                        showErrorAndFinish("You don't have access to this chat")
                        return
                    }

                    // Находим ID собеседника (всех участников кроме текущего пользователя)
                    val participants = snapshot.children.mapNotNull { it.key }
                    val otherParticipants = participants.filter { it != currentUserId }

                    if (otherParticipants.isNotEmpty()) {
                        // Для диалога берем первого собеседника (в групповых чатах логика будет другой)
                        otherUserId = otherParticipants.first()
                        loadOtherUserData(otherUserId)
                    } else {
                        // Если нет других участников, это может быть ошибка
                        showError("No other participants found")
                        // Устанавливаем заголовок по умолчанию
                        binding.tvUserName.text = "Unknown"
                    }

                    loadParticipantsData(participants)
                    fetchMessages()
                }

                override fun onCancelled(error: DatabaseError) {
                    showError("Error checking access: ${error.message}")
                }
            })
    }

    private fun loadOtherUserData(userId: String) {
        database.child("users").child(userId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val user = snapshot.getValue(User::class.java)
                    user?.let {
                        otherUserName = it.name
                        otherUserAvatar = it.profileImageUrl
                        updateChatHeader() // Обновляем заголовок чата
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Error loading other user data", error.toException())
                    otherUserName = "Unknown"
                    updateChatHeader()
                }
            })
    }

    private fun updateChatHeader() {
        binding.apply {
            // Устанавливаем имя собеседника в заголовок
            tvUserName.text = otherUserName

            // Загружаем аватар собеседника
            if (!otherUserAvatar.isNullOrEmpty()) {
                loadUserAvatar(otherUserAvatar!!, ivProfileImage)
            } else {
                // Устанавливаем аватар по умолчанию
                ivProfileImage.setImageResource(R.drawable.ic_default_profile)
            }
        }
    }

    private fun loadUserAvatar(avatarUrl: String, imageView: ImageView) {
        try {
            Glide.with(this)
                .load(avatarUrl)
                .placeholder(R.drawable.ic_default_profile)
                .error(R.drawable.ic_default_profile)
                .circleCrop()
                .into(imageView)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading avatar", e)
            imageView.setImageResource(R.drawable.ic_default_profile)
        }
    }

    private fun setupUserUpdatesListener() {
        // Слушаем изменения данных текущего пользователя
        database.child("users").child(currentUserId)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    snapshot.getValue(User::class.java)?.let { user ->
                        usersCache[currentUserId] = user
                        messageAdapter.notifyDataSetChanged()
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Failed to listen to user updates", error.toException())
                }
            })

        // Слушаем изменения данных собеседника
        if (otherUserId.isNotEmpty()) {
            database.child("users").child(otherUserId)
                .addValueEventListener(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val user = snapshot.getValue(User::class.java)
                        user?.let {
                            otherUserName = it.name ?: "Unknown"
                            otherUserAvatar = it.profileImageUrl
                            updateChatHeader()
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Log.e(TAG, "Failed to listen to other user updates", error.toException())
                    }
                })
        }
    }

    private fun showDeleteDialog(message: Message) {
        AlertDialog.Builder(this)
            .setTitle("Удалить сообщение")
            .setMessage("Вы уверены?")
            .setPositiveButton("Удалить") { _, _ ->
                deleteMessage(message)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun deleteMessage(message: Message) {
        if (message.senderId != currentUserId) {
            showError("You can only delete your own messages")
            return
        }
        database.child("chats").child(chatId).child("messages").child(message.id)
            .removeValue()
            .addOnSuccessListener {
                val position = messageList.indexOfFirst { it.id == message.id }
                if (position != -1) {
                    messageList.removeAt(position)
                    messageAdapter.notifyItemRemoved(position)
                }
                showToast("Message deleted")
            }
            .addOnFailureListener { e ->
                showError("Failed to delete: ${e.message}")
            }
    }

    private fun showEditDialog(message: Message) {
        if (message.senderId != currentUserId) {
            showError("You can only edit your own messages")
            return
        }
        val dialogBinding = DialogEditMessageBinding.inflate(layoutInflater)
        dialogBinding.etEditMessage.setText(message.text)
        AlertDialog.Builder(this)
            .setTitle("Edit Message")
            .setView(dialogBinding.root)
            .setPositiveButton("Save") { _, _ ->
                val newText = dialogBinding.etEditMessage.text.toString()
                if (newText.isNotEmpty()) {
                    updateMessage(message, newText)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateMessage(message: Message, newText: String) {
        val updates = hashMapOf<String, Any>(
            "text" to newText,
            "isEdited" to true,
            "timestamp" to System.currentTimeMillis()
        )
        database.child("chats").child(chatId).child("messages").child(message.id)
            .updateChildren(updates)
            .addOnSuccessListener {
                val position = messageList.indexOfFirst { it.id == message.id }
                if (position != -1) {
                    messageList[position] = message.copy(
                        text = newText,
                        isEdited = true,
                        timestamp = System.currentTimeMillis()
                    )
                    messageAdapter.notifyItemChanged(position)
                }
                showToast("Message updated")
            }
            .addOnFailureListener { e ->
                showError("Failed to update: ${e.message}")
            }
    }

    private fun loadParticipantsData(userIds: List<String>) {
        userIds.forEach { userId ->
            if (userId.isNotBlank() && !usersCache.containsKey(userId)) {
                database.child("users").child(userId)
                    .addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            snapshot.getValue(User::class.java)?.let { user ->
                                usersCache[userId] = user
                                messageAdapter.notifyDataSetChanged()
                            }
                        }

                        override fun onCancelled(error: DatabaseError) {
                            Log.e(TAG, "Error loading user $userId", error.toException())
                        }
                    })
            }
        }
    }

    private fun fetchMessages() {
        database.child("chats").child(chatId).child("messages")
            .addChildEventListener(object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, prevKey: String?) {
                    snapshot.getValue(Message::class.java)?.let { message ->
                        messageList.add(message)
                        messageAdapter.notifyItemInserted(messageList.size - 1)
                        binding.rvMessages.smoothScrollToPosition(messageList.size - 1)
                        if (message.senderId.isNotBlank() && !usersCache.containsKey(message.senderId)) {
                            loadUserData(message.senderId)
                        }
                    }
                }

                override fun onChildChanged(snapshot: DataSnapshot, prevKey: String?) {
                    snapshot.getValue(Message::class.java)?.let { updatedMessage ->
                        val index = messageList.indexOfFirst { it.id == updatedMessage.id }
                        if (index != -1) {
                            messageList[index] = updatedMessage
                            messageAdapter.notifyItemChanged(index)
                        }
                    }
                }

                override fun onChildRemoved(snapshot: DataSnapshot) {
                    snapshot.getValue(Message::class.java)?.let { removedMessage ->
                        val index = messageList.indexOfFirst { it.id == removedMessage.id }
                        if (index != -1) {
                            messageList.removeAt(index)
                            messageAdapter.notifyItemRemoved(index)
                        }
                    }
                }

                override fun onChildMoved(snapshot: DataSnapshot, prevKey: String?) {}
                override fun onCancelled(error: DatabaseError) {
                    showError("Error loading messages: ${error.message}")
                }
            })
    }

    private fun loadUserData(userId: String) {
        database.child("users").child(userId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    snapshot.getValue(User::class.java)?.let { user ->
                        usersCache[userId] = user
                        messageAdapter.notifyDataSetChanged()
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Error loading user data: ${error.message}")
                }
            })
    }

    private fun showReplyingView(message: Message) {
        binding.apply {
            replyContainer.visibility = View.VISIBLE
            tvReplyingTo.text = message.senderName ?: "Unknown"
            tvReplyPreview.text = when {
                !message.text.isNullOrEmpty() ->
                    if (message.text.length > 50) "${message.text.take(47)}..." else message.text
                !message.imageUrl.isNullOrEmpty() -> "Image"
                else -> "Message"
            }
        }
    }

    private fun hideReplyingView() {
        binding.replyContainer.visibility = View.GONE
        replyingToMessage = null
    }

    private fun highlightMessage(messageId: String) {
        val position = messageList.indexOfFirst { it.id == messageId }
        if (position != -1) {
            binding.rvMessages.findViewHolderForAdapterPosition(position)?.itemView?.apply {
                setBackgroundColor(selectedMessageColor)
                postDelayed({ setBackgroundColor(Color.TRANSPARENT) }, 1000)
            }
        }
    }

    private fun scrollToMessage(messageId: String) {
        val position = messageList.indexOfFirst { it.id == messageId }
        if (position != -1) {
            binding.rvMessages.smoothScrollToPosition(position)
            highlightMessage(messageId)
        }
    }

    private fun sendMessage(text: String) {
        val messageId = database.child("chats").child(chatId).child("messages").push().key
            ?: run {
                showError("Failed to generate message ID")
                return
            }
        val replyData = replyingToMessage?.let { message ->
            mapOf(
                "replyToMessageId" to message.id,
                "replyToMessageText" to (message.text ?: "Image"),
                "replyToSenderName" to (message.senderName ?: "Unknown")
            )
        }
        val currentUser = usersCache[currentUserId]
        if (currentUser != null) {
            sendMessageToFirebase(messageId, text, currentUser.name, replyData)
        } else {
            loadUserNameAndSend(messageId, text, replyData)
        }
        hideReplyingView()
    }

    private fun loadUserNameAndSend(
        messageId: String,
        text: String,
        replyData: Map<String, String?>?
    ) {
        database.child("users").child(currentUserId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val currentUser = snapshot.getValue(User::class.java) ?: User()
                    val updatedUser = currentUser.copy(
                        name = snapshot.child("name").getValue(String::class.java) ?: "Unknown",
                        isActive = true
                    )
                    usersCache[currentUserId] = updatedUser
                    sendMessageToFirebase(messageId, text, updatedUser.name, replyData)
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Failed to load user data", error.toException())
                    sendMessageToFirebase(messageId, text, "Unknown", replyData)
                }
            })
    }

    private fun sendMessageToFirebase(
        messageId: String,
        text: String,
        senderName: String,
        replyData: Map<String, String?>?
    ) {
        // Для уведомлений используем имя текущего пользователя (отправителя)
        val currentUser = usersCache[currentUserId]
        val displayName = currentUser?.name ?: senderName

        val messageData = hashMapOf<String, Any>(
            "id" to messageId,
            "text" to text,
            "senderId" to currentUserId,
            "senderName" to displayName, // Сохраняем правильное имя отправителя
            "timestamp" to System.currentTimeMillis()
        )

        // Эти поля относятся к сообщению, на которое отвечаем (если есть)
        replyData?.get("replyToMessageId")?.let {
            messageData["replyToMessageId"] = it
        }
        replyData?.get("replyToMessageText")?.let {
            messageData["replyToMessageText"] = it
        }
        replyData?.get("replyToSenderName")?.let {
            messageData["replyToSenderName"] = it
        }

        // Подготавливаем обновления для узла чата
        val chatUpdates = hashMapOf<String, Any>(
            "lastMessageTimestamp" to System.currentTimeMillis(),
            "lastMessageSenderId" to currentUserId,
            "lastMessageSenderName" to displayName, // Используем правильное имя
            "lastMessageText" to text
        )

        // Выполняем обе операции одновременно
        val updates = hashMapOf<String, Any>()
        updates["chats/$chatId/messages/$messageId"] = messageData
        updates["chats/$chatId/lastMessageSenderId"] = chatUpdates["lastMessageSenderId"] as Any
        updates["chats/$chatId/lastMessageSenderName"] = chatUpdates["lastMessageSenderName"] as Any
        updates["chats/$chatId/lastMessageText"] = chatUpdates["lastMessageText"] as Any
        updates["chats/$chatId/lastMessageTimestamp"] = chatUpdates["lastMessageTimestamp"] as Any

        database.updateChildren(updates)
            .addOnSuccessListener {
                Log.d(TAG, "Message and chat info saved successfully")
                // Отправляем уведомления с правильным именем отправителя
                sendNotificationsToParticipants(text, displayName)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to save message or update chat", e)
                showError("Failed to send message")
            }
    }

    private fun sendNotificationsToParticipants(messageText: String, senderName: String) {
        database.child("chats").child(chatId).child("participants")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    snapshot.children.mapNotNull { it.key }
                        .filter { it != currentUserId }
                        .forEach { userId ->
                            // Отправляем уведомление от имени текущего пользователя
                            NotificationUtils.sendChatNotification(
                                this@ChatDetailActivity,
                                userId,
                                messageText,
                                senderName, // Имя отправителя (текущего пользователя)
                                chatId
                            )
                        }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Failed to get participants", error.toException())
                }
            })
    }

    private fun removeInvalidToken(userId: String) {
        database.child("users").child(userId).child("fcmToken")
            .removeValue()
            .addOnSuccessListener {
                Log.d(TAG, "Invalid FCM token removed for user $userId")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to remove token for user $userId", e)
            }
    }

    private fun uploadImageToYandexCloud(imageUri: Uri) {
        lifecycleScope.launch(Dispatchers.Main) {
            var tempFile: File? = null
            try {
                tempFile = withContext(Dispatchers.IO) { createTempFileFromUri(imageUri) }

                // Получаем размеры изображения перед загрузкой
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeFile(tempFile.path, options)
                val aspectRatio = options.outWidth.toFloat() / options.outHeight.toFloat()

                val fileName = generateSafeFileName()
                val imageUrl = withContext(Dispatchers.IO) {
                    uploadToYandexStorage(tempFile, fileName)
                }

                // Сохраняем aspect ratio в сообщении
                sendImageMessage(imageUrl, aspectRatio)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка загрузки", e)
                showError("Не удалось загрузить изображение: ${e.message}")
            } finally {
                tempFile?.delete()
            }
        }
    }

    private fun uploadToYandexStorage(file: File, fileName: String): String {
        val url = "$ENDPOINT/$BUCKET_NAME/$fileName"

        val now = System.currentTimeMillis()
        val date = formatDate(now, "yyyyMMdd")
        val time = formatDate(now, "yyyyMMdd'T'HHmmss'Z'")

        // 1. Подготавливаем заголовки
        val headers = mapOf(
            "Host" to "storage.yandexcloud.net",
            "X-Amz-Date" to time,
            "X-Amz-Content-Sha256" to "UNSIGNED-PAYLOAD",
            "x-amz-acl" to "public-read"
        )

        // 2. Создаем канонический запрос
        val signedHeaders = headers.keys.joinToString(";").lowercase()
        val canonicalHeaders = headers.entries.joinToString("\n") {
            "${it.key.lowercase()}:${it.value}"
        } + "\n"

        val canonicalRequest = """
            |PUT
            |/$BUCKET_NAME/$fileName
            |
            |$canonicalHeaders
            |$signedHeaders
            |UNSIGNED-PAYLOAD
        """.trimMargin()

        Log.d(TAG, "Canonical Request:\n$canonicalRequest")

        // 3. Создаем строку для подписи
        val credentialScope = "$date/$REGION/$SERVICE/$REQUEST_TYPE"
        val stringToSign = """
            |$ALGORITHM
            |$time
            |$credentialScope
            |${sha256(canonicalRequest)}
        """.trimMargin()

        Log.d(TAG, "String to Sign:\n$stringToSign")

        // 4. Вычисляем подпись
        val signature = calculateSignature(
            stringToSign,
            date,
            REGION,
            SERVICE
        )

        // 5. Формируем заголовок авторизации
        val authorizationHeader =
            "$ALGORITHM Credential=$ACCESS_KEY_ID/$credentialScope, " +
                    "SignedHeaders=$signedHeaders, Signature=$signature"

        Log.d(TAG, "Authorization: $authorizationHeader")

        // 6. Формируем запрос
        val client = OkHttpClient()
        val requestBody = file.asRequestBody("image/jpeg".toMediaTypeOrNull())
        val request = Request.Builder()
            .url(url)
            .put(requestBody)
            .header("Authorization", authorizationHeader)
            .header("x-amz-acl", "public-read")
            .header("x-amz-content-sha256", "UNSIGNED-PAYLOAD")
            .header("x-amz-date", time)
            .header("Host", "storage.yandexcloud.net")
            .build()

        // 7. Выполняем запрос
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Empty error body"
                Log.e(TAG, "HTTP Error ${response.code}: $errorBody")
                throw IOException("HTTP Error ${response.code}: $errorBody")
            }
            return url
        }
    }

    private fun formatDate(timestamp: Long, pattern: String): String {
        val formatter = SimpleDateFormat(pattern, Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date(timestamp))
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun calculateSignature(
        stringToSign: String,
        date: String,
        region: String,
        service: String
    ): String {
        val kSecret = ("AWS4$SECRET_ACCESS_KEY").toByteArray(Charsets.UTF_8)
        val kDate = hmacSha256(kSecret, date)
        val kRegion = hmacSha256(kDate, region)
        val kService = hmacSha256(kRegion, service)
        val kSigning = hmacSha256(kService, "aws4_request")
        return hmacSha256Hex(kSigning, stringToSign)
    }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val algorithm = "HmacSHA256"
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(key, algorithm))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun hmacSha256Hex(key: ByteArray, data: String): String {
        val bytes = hmacSha256(key, data)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun generateSafeFileName(): String {
        require(chatId.isNotBlank()) { "Chat ID не может быть пустым" }
        val timestamp = System.currentTimeMillis()
        return "chat_${chatId}_$timestamp.jpg"
            .replace(Regex("[^a-zA-Z0-9_.-]"), "_")
    }

    private fun createTempFileFromUri(uri: Uri): File {
        return try {
            File.createTempFile("upload_", ".jpg", externalCacheDir).apply {
                contentResolver.openInputStream(uri)?.use { input ->
                    outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: throw IOException("Ошибка чтения файла")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Ошибка создания временного файла", e)
            throw e
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun getRealPathFromUriQ(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(MediaStore.Images.Media._ID), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                        return@use fd.toString()
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting path for API 29+", e)
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun getRealPathFromURI(uri: Uri): String {
        val projection = arrayOf(MediaStore.Images.Media.DATA)
        var result = ""
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                result = cursor.getString(columnIndex)
            }
        }
        return result
    }

    private fun sendImageMessage(imageUrl: String, aspectRatio: Float = 1f) {
        val messageId = database.child("chats").child(chatId).child("messages").push().key
            ?: return showError("Failed to generate message ID")

        usersCache[currentUserId]?.let { user ->
            database.child("chats").child(chatId).child("messages").child(messageId)
                .setValue(createImageMessage(messageId, imageUrl, user.name, aspectRatio))
                .addOnSuccessListener {
                    sendNotificationsToParticipants("Image", user.name)
                }
        } ?: loadUserNameAndSendImage(messageId, imageUrl, aspectRatio)
    }

    private fun createImageMessage(
        messageId: String,
        imageUrl: String,
        senderName: String,
        aspectRatio: Float = 1f
    ) = Message(
        id = messageId,
        imageUrl = imageUrl,
        senderId = currentUserId,
        senderName = senderName,
        timestamp = System.currentTimeMillis(),
        replyToMessageId = replyingToMessage?.id,
        replyToMessageText = replyingToMessage?.text ?: replyingToMessage?.imageUrl?.let { "Image" },
        replyToSenderName = replyingToMessage?.senderName,
        messageType = Message.MessageType.IMAGE,
        aspectRatio = aspectRatio
    ).also { hideReplyingView() }

    private fun loadUserNameAndSendImage(messageId: String, imageUrl: String, aspectRatio: Float = 1f) {
        database.child("users").child(currentUserId).child("name")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val name = snapshot.getValue(String::class.java) ?: "Unknown"
                    database.child("chats").child(chatId).child("messages").child(messageId)
                        .setValue(createImageMessage(messageId, imageUrl, name, aspectRatio))
                        .addOnSuccessListener {
                            sendNotificationsToParticipants("Image", name)
                        }
                }

                override fun onCancelled(error: DatabaseError) {
                    database.child("chats").child(chatId).child("messages").child(messageId)
                        .setValue(createImageMessage(messageId, imageUrl, "Unknown", aspectRatio))
                }
            })
    }

    private fun openAddParticipants() {
        startActivity(Intent(this, SelectUsersActivity::class.java).apply {
            putExtra(Constants.CHAT_ID, chatId)
        })
    }

    private fun openUserProfile(userId: String) {
        if (userId.isNotBlank() && userId != currentUserId) {
            val intent = Intent(this, UserProfileActivity::class.java).apply {
                putExtra("USER_ID", userId)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showErrorAndFinish(message: String) {
        showError(message)
        finish()
    }

    override fun onStart() {
        super.onStart()
        if (auth.currentUser == null) {
            showErrorAndFinish("Authentication required")
        }
    }
}