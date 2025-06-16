package com.example.chatapp.adapters

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.chatapp.R
import com.example.chatapp.models.Message
import com.example.chatapp.models.User
import com.google.firebase.auth.FirebaseAuth
import java.text.SimpleDateFormat
import java.util.*

class MessageAdapter(
    private val messages: MutableList<Message>,
    private val usersCache: MutableMap<String, User>,
    private val currentUserId: String = FirebaseAuth.getInstance().currentUser?.uid ?: "",
    private val onMessageClick: (Message) -> Unit = {},
    private val onUserClick: (String) -> Unit = {},
    private val onReplyClick: (Message) -> Unit = {},
    private val onDeleteClick: (Message) -> Unit = {},
    private val onEditClick: (Message) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_MY_MESSAGE = 1
        private const val VIEW_TYPE_OTHER_MESSAGE = 2
        private const val MAX_REPLY_PREVIEW_LENGTH = 50
        private const val TAG = "MessageAdapter"
    }

    private lateinit var context: Context

    inner class MyMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvMessageText: TextView = itemView.findViewById(R.id.tvMessageText)
        val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        val ivMessageImage: ImageView = itemView.findViewById(R.id.ivMessageImage)
        val replyContainer: View = itemView.findViewById(R.id.replyContainer)
        val tvReplyText: TextView = itemView.findViewById(R.id.tvReplyText)
        val tvReplySender: TextView = itemView.findViewById(R.id.tvReplySender)
        val ivStatus: ImageView = itemView.findViewById(R.id.ivStatus)
        val tvEdited: TextView = itemView.findViewById(R.id.tvEdited)
        val messageContainer: View = itemView.findViewById(R.id.messageContainer)

        init {
            messageContainer.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onMessageClick(messages[pos])
                }
            }

            messageContainer.setOnLongClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    showMessageActionsDialog(messages[pos])
                }
                true
            }
        }
    }

    inner class OtherMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvMessageText: TextView = itemView.findViewById(R.id.tvMessageText)
        val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        val ivMessageImage: ImageView = itemView.findViewById(R.id.ivMessageImage)
        val replyContainer: View = itemView.findViewById(R.id.replyContainer)
        val tvReplyText: TextView = itemView.findViewById(R.id.tvReplyText)
        val tvReplySender: TextView = itemView.findViewById(R.id.tvReplySender)
        val ivUserAvatar: ImageView = itemView.findViewById(R.id.ivUserAvatar)
        val tvSenderName: TextView = itemView.findViewById(R.id.tvSenderName)
        val messageContainer: View = itemView.findViewById(R.id.messageContainer)

        init {
            ivUserAvatar.apply {
                isClickable = true
                isFocusable = false
                setOnClickListener { handleUserClick() }
            }

            tvSenderName.apply {
                isClickable = true
                isFocusable = false
                setOnClickListener { handleUserClick() }
            }

            messageContainer.setOnLongClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    showMessageActionsDialog(messages[pos])
                }
                true
            }
        }

        private fun handleUserClick() {
            val pos = adapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                val userId = messages[pos].senderId
                if (userId != null && userId != currentUserId) {
                    Log.d(TAG, "User profile click detected for: $userId")
                    onUserClick(userId)
                } else {
                    Log.w(TAG, "Invalid user click: $userId (current: $currentUserId)")
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        context = parent.context
        return when (viewType) {
            VIEW_TYPE_MY_MESSAGE -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_message_right, parent, false)
                MyMessageViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_message_left, parent, false)
                OtherMessageViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]

        when (holder) {
            is MyMessageViewHolder -> bindMyMessage(holder, message)
            is OtherMessageViewHolder -> {
                val user = usersCache[message.senderId]
                bindOtherMessage(holder, message, user)
            }
        }
    }

    private fun bindMyMessage(holder: MyMessageViewHolder, message: Message) {
        with(holder) {
            tvMessageText.text = message.text
            tvMessageText.visibility = if (message.text.isNotEmpty()) View.VISIBLE else View.GONE

            ivMessageImage.visibility = if (message.imageUrl.isNullOrEmpty()) View.GONE else View.VISIBLE
            message.imageUrl?.let { url ->
                Glide.with(context).load(url).into(ivMessageImage)
            }

            tvTime.text = SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(Date(message.timestamp))

            tvEdited.visibility = if (message.isEdited) View.VISIBLE else View.GONE
            if (message.isEdited) {
                tvEdited.text = context.getString(R.string.edited)
            }

            bindReplyContent(this, message)
            updateMessageStatus(this, message)
        }
    }

    private fun bindOtherMessage(holder: OtherMessageViewHolder, message: Message, user: User?) {
        with(holder) {
            tvMessageText.text = message.text
            tvMessageText.visibility = if (message.text.isNotEmpty()) View.VISIBLE else View.GONE

            ivMessageImage.visibility = if (message.imageUrl.isNullOrEmpty()) View.GONE else View.VISIBLE
            message.imageUrl?.let { url ->
                Glide.with(context).load(url).into(ivMessageImage)
            }

            tvSenderName.text = user?.name ?: message.senderName ?: "Unknown"
            tvTime.text = SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(Date(message.timestamp))

            Glide.with(context)
                .load(user?.profileImageUrl)
                .circleCrop()
                .placeholder(R.drawable.ic_default_profile)
                .into(ivUserAvatar)

            bindReplyContent(this, message)
        }
    }

    private fun bindReplyContent(holder: Any, message: Message) {
        val (replyContainer, tvReplyText, tvReplySender) = when (holder) {
            is MyMessageViewHolder -> Triple(holder.replyContainer, holder.tvReplyText, holder.tvReplySender)
            is OtherMessageViewHolder -> Triple(holder.replyContainer, holder.tvReplyText, holder.tvReplySender)
            else -> return
        }

        if (message.replyToMessageId != null) {
            replyContainer.visibility = View.VISIBLE
            tvReplySender.text = message.replyToSenderName ?: context.getString(R.string.message)
            tvReplyText.text = message.replyToMessageText?.let {
                if (it.length > MAX_REPLY_PREVIEW_LENGTH) "${it.take(MAX_REPLY_PREVIEW_LENGTH)}..." else it
            } ?: ""
        } else {
            replyContainer.visibility = View.GONE
        }
    }

    private fun updateMessageStatus(holder: MyMessageViewHolder, message: Message) {
        val statusIcon = when (message.status) {
            Message.MessageStatus.READ -> R.drawable.ic_read
            Message.MessageStatus.DELIVERED -> R.drawable.ic_delivered
            else -> R.drawable.ic_sent
        }
        holder.ivStatus.setImageResource(statusIcon)
    }

    private fun showMessageActionsDialog(message: Message) {
        val actions = mutableListOf(context.getString(R.string.reply))

        if (message.senderId == currentUserId) {
            actions.add(context.getString(R.string.edit))
            actions.add(context.getString(R.string.delete))
        }

        AlertDialog.Builder(context)
            .setItems(actions.toTypedArray()) { _, which ->
                when (which) {
                    0 -> onReplyClick(message)
                    1 -> if (actions[which] == context.getString(R.string.edit)) onEditClick(message)
                    2 -> if (which == 2) onDeleteClick(message)
                }
            }
            .show()
    }

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].senderId == currentUserId) {
            VIEW_TYPE_MY_MESSAGE
        } else {
            VIEW_TYPE_OTHER_MESSAGE
        }
    }

    override fun getItemCount(): Int = messages.size

    fun updateMessages(newMessages: List<Message>) {
        messages.clear()
        messages.addAll(newMessages)
        notifyDataSetChanged()
    }

    fun updateUserCache(newUsers: Map<String, User>) {
        usersCache.clear()
        usersCache.putAll(newUsers)
        notifyDataSetChanged()
    }
}