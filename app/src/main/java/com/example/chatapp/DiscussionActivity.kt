package com.example.chatapp

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.chatapp.adapters.DiscussionAdapter
import com.example.chatapp.models.Discussion
import com.example.chatapp.models.DiscussionMessage
import com.example.chatapp.models.User
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.database.ktx.getValue
import com.google.firebase.ktx.Firebase

class DiscussionActivity : AppCompatActivity() {

    private lateinit var discussionId: String
    private lateinit var discussion: Discussion
    private lateinit var adapter: DiscussionAdapter
    private lateinit var dbRef: DatabaseReference
    private lateinit var auth: FirebaseAuth

    private val messages = mutableListOf<DiscussionMessage>()
    private val usersCache = mutableMapOf<String, User>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.fragment_discussion)

        discussionId = intent.getStringExtra("discussionId") ?: run {
            showError("Не удалось загрузить беседу")
            finish()
            return
        }

        auth = FirebaseAuth.getInstance()
        dbRef = Firebase.database.reference

        initViews()
        loadDiscussionData()
        loadDiscussionMessages()
    }

    private fun initViews() {
        val recyclerView = findViewById<RecyclerView>(R.id.rvMessages)
        val etMessage = findViewById<EditText>(R.id.etMessage)
        val btnSend = findViewById<Button>(R.id.btnSend)
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)
        val tvDiscussionTitle = findViewById<TextView>(R.id.tvDiscussionTitle)
        val btnBack = findViewById<ImageButton>(R.id.btnBack)

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = DiscussionAdapter(
            messages = emptyList(),
            users = emptyMap(),
            currentUserId = auth.currentUser?.uid ?: "",
            onReplyClick = { message ->
                etMessage.hint = "Ответ ${getShortUserName(message.senderId)}..."
                etMessage.tag = message.messageId
            }
        )
        recyclerView.adapter = adapter

        btnBack.setOnClickListener { finish() }

        btnSend.setOnClickListener { sendMessage(etMessage) }

        etMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                btnSend.isEnabled = s?.isNotEmpty() == true
            }
        })
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.discussion_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val deleteItem = menu.findItem(R.id.action_delete)
        deleteItem.isVisible = auth.currentUser?.uid == discussion.creatorId
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_delete -> {
                confirmDeleteDiscussion()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun confirmDeleteDiscussion() {
        AlertDialog.Builder(this)
            .setTitle("Удаление беседы")
            .setMessage("Вы уверены, что хотите удалить эту беседу? Все сообщения будут удалены.")
            .setPositiveButton("Удалить") { _, _ -> deleteDiscussion() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun deleteDiscussion() {
        // Удаляем все сообщения этой беседы
        dbRef.child("discussionMessages")
            .orderByChild("discussionId")
            .equalTo(discussionId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val updates = hashMapOf<String, Any?>(
                        "discussions/$discussionId" to null
                    )

                    snapshot.children.forEach { messageSnapshot ->
                        val messageId = messageSnapshot.key
                        if (messageId != null) {
                            updates["discussionMessages/$messageId"] = null
                        }
                    }

                    dbRef.updateChildren(updates)
                        .addOnSuccessListener {
                            Toast.makeText(this@DiscussionActivity, "Беседа удалена", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                        .addOnFailureListener { e ->
                            showError("Ошибка удаления: ${e.message}")
                        }
                }

                override fun onCancelled(error: DatabaseError) {
                    showError("Ошибка загрузки сообщений для удаления")
                }
            })
    }

    private fun loadDiscussionData() {
        dbRef.child("discussions").child(discussionId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    discussion = snapshot.getValue<Discussion>() ?: run {
                        showError("Беседа не найдена")
                        finish()
                        return
                    }

                    findViewById<TextView>(R.id.tvDiscussionTitle).text = discussion.title
                    invalidateOptionsMenu()
                }

                override fun onCancelled(error: DatabaseError) {
                    showError("Ошибка загрузки данных беседы")
                }
            })
    }

    private fun loadDiscussionMessages() {
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)
        progressBar.visibility = View.VISIBLE

        val messagesListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val newMessages = mutableListOf<DiscussionMessage>()
                snapshot.children.forEach { messageSnapshot ->
                    messageSnapshot.getValue<DiscussionMessage>()?.let { newMessages.add(it) }
                }

                messages.clear()
                messages.addAll(newMessages)

                if (newMessages.isEmpty()) {
                    adapter.updateData(emptyList(), usersCache)
                    progressBar.visibility = View.GONE
                    return
                }

                val userIds = newMessages.map { it.senderId }.toSet()
                loadUsers(userIds, progressBar)
            }

            override fun onCancelled(error: DatabaseError) {
                progressBar.visibility = View.GONE
                showError("Ошибка загрузки сообщений")
            }
        }

        dbRef.child("discussionMessages")
            .orderByChild("discussionId")
            .equalTo(discussionId)
            .addValueEventListener(messagesListener)
    }

    private fun loadUsers(userIds: Set<String>, progressBar: ProgressBar) {
        if (userIds.all { usersCache.containsKey(it) }) {
            adapter.updateData(messages, usersCache)
            progressBar.visibility = View.GONE
            return
        }

        var loadedCount = 0
        val totalCount = userIds.size

        userIds.forEach { userId ->
            if (usersCache.containsKey(userId)) {
                loadedCount++
                if (loadedCount == totalCount) {
                    adapter.updateData(messages, usersCache)
                    progressBar.visibility = View.GONE
                }
                return@forEach
            }

            dbRef.child("users").child(userId)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val user = snapshot.getValue<User>() ?: User(uid = userId, name = "Аноним")
                        usersCache[userId] = user
                        loadedCount++

                        if (loadedCount == totalCount) {
                            adapter.updateData(messages, usersCache)
                            progressBar.visibility = View.GONE
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        usersCache[userId] = User(uid = userId, name = "Аноним")
                        loadedCount++

                        if (loadedCount == totalCount) {
                            adapter.updateData(messages, usersCache)
                            progressBar.visibility = View.GONE
                        }
                    }
                })
        }
    }

    private fun sendMessage(etMessage: EditText) {
        val text = etMessage.text.toString().trim()
        if (text.isEmpty()) return

        val userId = auth.currentUser?.uid ?: run {
            showError("Требуется авторизация")
            return
        }

        val replyToId = etMessage.tag as? String
        val messageId = dbRef.child("discussionMessages").push().key ?: return

        val message = DiscussionMessage(
            messageId = messageId,
            discussionId = discussionId,
            senderId = userId,
            text = text,
            timestamp = System.currentTimeMillis(),
            replyToMessageId = replyToId
        )

        dbRef.child("discussionMessages").child(messageId).setValue(message)
            .addOnSuccessListener {
                etMessage.text.clear()
                etMessage.tag = null
                etMessage.hint = "Ваше сообщение..."
                updateDiscussionStats(userId)
            }
            .addOnFailureListener { e ->
                showError("Ошибка отправки: ${e.message}")
            }
    }

    private fun updateDiscussionStats(userId: String) {
        dbRef.child("discussions").child(discussionId)
            .runTransaction(object : Transaction.Handler {
                override fun doTransaction(currentData: MutableData): Transaction.Result {
                    val discussion = currentData.getValue<Discussion>()
                        ?: return Transaction.success(currentData)

                    discussion.messageCount += 1

                    if (!usersCache.containsKey(userId)) {
                        discussion.participantCount += 1
                    }

                    discussion.lastMessageTimestamp = System.currentTimeMillis()
                    currentData.value = discussion
                    return Transaction.success(currentData)
                }

                override fun onComplete(
                    error: DatabaseError?,
                    committed: Boolean,
                    currentData: DataSnapshot?
                ) {
                    if (error != null) {
                        Log.e("DiscussionActivity", "Transaction failed: ${error.message}")
                    }
                }
            })
    }

    private fun getShortUserName(userId: String): String {
        return usersCache[userId]?.name?.take(8) ?: userId.take(5)
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }
}