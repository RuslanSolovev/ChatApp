package com.example.chatapp.activities

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.chatapp.R
import com.example.chatapp.adapters.MessageAdapter
import com.example.chatapp.databinding.ActivityChatDetailBinding
import com.example.chatapp.databinding.DialogEditMessageBinding
import com.example.chatapp.models.Message
import com.example.chatapp.models.User
import com.example.chatapp.utils.Constants
import com.example.chatapp.utils.NotificationSender
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.storage.FirebaseStorage
import java.util.concurrent.TimeUnit

class ChatDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatDetailBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var database: DatabaseReference
    private lateinit var storage: FirebaseStorage
    private lateinit var messageAdapter: MessageAdapter

    private val messageList = mutableListOf<Message>()
    private val usersCache = hashMapOf<String, User>()
    private var chatId: String = ""
    private var replyingToMessage: Message? = null

    private val currentUserId: String
        get() = auth.currentUser?.uid ?: ""

    private val selectedMessageColor = Color.parseColor("#E3F2FD")

    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { uploadImageToFirebase(it) }
        }

    companion object {
        private const val TAG = "ChatDetailActivity"
        private const val MAX_RETRY_ATTEMPTS = 3
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
            registerFcmToken()
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
                    snapshot.children.mapNotNull { it.key }.let { userIds ->
                        loadParticipantsData(userIds)
                        fetchMessages()
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    showError("Error checking access: ${error.message}")
                }
            })
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
        val messageData = hashMapOf<String, Any>(
            "id" to messageId,
            "text" to text,
            "senderId" to currentUserId,
            "senderName" to senderName,
            "timestamp" to System.currentTimeMillis()
        )

        replyData?.get("replyToMessageId")?.let {
            messageData["replyToMessageId"] = it
        }
        replyData?.get("replyToMessageText")?.let {
            messageData["replyToMessageText"] = it
        }
        replyData?.get("replyToSenderName")?.let {
            messageData["replyToSenderName"] = it
        }

        database.child("chats").child(chatId).child("messages").child(messageId)
            .updateChildren(messageData)
            .addOnSuccessListener {
                Log.d(TAG, "Message saved successfully")
                sendNotificationsToParticipants(text, senderName)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to save message", e)
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
                            sendNotificationToUser(userId, messageText, senderName)
                        }
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Failed to get participants", error.toException())
                }
            })
    }

    private fun sendNotificationToUser(userId: String, messageText: String, senderName: String) {
        database.child("users").child(userId).child("fcmToken")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val token = snapshot.getValue(String::class.java)
                    if (!token.isNullOrBlank()) {
                        NotificationSender.sendNotification(
                            fcmToken = token,
                            title = "Новое сообщение от $senderName",
                            body = messageText,
                            senderName = senderName,
                            callback = { isSuccess, errorMessage ->
                                if (!isSuccess) {
                                    Log.e(TAG, "Failed to send notification: $errorMessage")
                                    if (errorMessage?.contains("Invalid token") == true) {
                                        removeInvalidToken(userId)
                                    }
                                }
                            }
                        )
                    } else {
                        Log.w(TAG, "Empty FCM token for user $userId")
                    }
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Failed to get token for $userId", error.toException())
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

    private fun openImagePicker() {
        try {
            imagePicker.launch("image/*")
        } catch (e: Exception) {
            Log.e(TAG, "Image picker error", e)
            showError("Failed to open gallery")
        }
    }

    private fun uploadImageToFirebase(imageUri: Uri) {
        val imageName = "chat_${chatId}_${System.currentTimeMillis()}.jpg"
        val imageRef = storage.reference.child("chat_images/$imageName")

        imageRef.putFile(imageUri)
            .addOnSuccessListener { task ->
                task.storage.downloadUrl.addOnSuccessListener { uri ->
                    sendImageMessage(uri.toString())
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Image upload error", e)
                showError("Failed to upload image")
            }
    }

    private fun sendImageMessage(imageUrl: String) {
        val messageId = database.child("chats").child(chatId).child("messages").push().key
            ?: return showError("Failed to generate message ID")

        usersCache[currentUserId]?.let { user ->
            database.child("chats").child(chatId).child("messages").child(messageId)
                .setValue(createImageMessage(messageId, imageUrl, user.name))
                .addOnSuccessListener {
                    sendNotificationsToParticipants("Image", user.name)
                }
        } ?: loadUserNameAndSendImage(messageId, imageUrl)
    }

    private fun createImageMessage(
        messageId: String,
        imageUrl: String,
        senderName: String
    ) = Message(
        id = messageId,
        imageUrl = imageUrl,
        senderId = currentUserId,
        senderName = senderName,
        timestamp = System.currentTimeMillis(),
        replyToMessageId = replyingToMessage?.id,
        replyToMessageText = replyingToMessage?.text ?: replyingToMessage?.imageUrl?.let { "Image" },
        replyToSenderName = replyingToMessage?.senderName
    ).also { hideReplyingView() }

    private fun loadUserNameAndSendImage(messageId: String, imageUrl: String) {
        database.child("users").child(currentUserId).child("name")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val name = snapshot.getValue(String::class.java) ?: "Unknown"
                    database.child("chats").child(chatId).child("messages").child(messageId)
                        .setValue(createImageMessage(messageId, imageUrl, name))
                        .addOnSuccessListener {
                            sendNotificationsToParticipants("Image", name)
                        }
                }
                override fun onCancelled(error: DatabaseError) {
                    database.child("chats").child(chatId).child("messages").child(messageId)
                        .setValue(createImageMessage(messageId, imageUrl, "Unknown"))
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