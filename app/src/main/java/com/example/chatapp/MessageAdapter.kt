package com.example.chatapp.adapters

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.example.chatapp.R
import com.example.chatapp.activities.FullScreenImageActivity
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
        private const val VIEW_TYPE_MY_TEXT = 1
        private const val VIEW_TYPE_MY_IMAGE = 2
        private const val VIEW_TYPE_OTHER_TEXT = 3
        private const val VIEW_TYPE_OTHER_IMAGE = 4
        private const val MAX_REPLY_PREVIEW_LENGTH = 50
        private const val TAG = "MessageAdapter"
    }

    private lateinit var context: Context

    abstract inner class BaseMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        abstract val replyContainer: View
        abstract val tvReplyText: TextView
        abstract val tvReplySender: TextView
    }

    inner class MyTextViewHolder(itemView: View) : BaseMessageViewHolder(itemView) {
        val tvMessageText: TextView = itemView.findViewById(R.id.tvMessageText)
        val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        override val replyContainer: View = itemView.findViewById(R.id.replyContainer)
        override val tvReplyText: TextView = itemView.findViewById(R.id.tvReplyText)
        override val tvReplySender: TextView = itemView.findViewById(R.id.tvReplySender)
        val ivStatus: ImageView = itemView.findViewById(R.id.ivStatus)
        val tvEdited: TextView = itemView.findViewById(R.id.tvEdited)
        val messageContainer: View = itemView.findViewById(R.id.messageContainer)
        val bottomPanel: LinearLayout = itemView.findViewById(R.id.bottomPanel)

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

    inner class MyImageViewHolder(itemView: View) : BaseMessageViewHolder(itemView) {
        val ivMessageImage: ImageView = itemView.findViewById(R.id.ivMessageImage)
        val tvImageTime: TextView = itemView.findViewById(R.id.tvImageTime)
        val ivImageStatus: ImageView = itemView.findViewById(R.id.ivImageStatus)
        override val replyContainer: View = itemView.findViewById(R.id.replyContainer)
        override val tvReplyText: TextView = itemView.findViewById(R.id.tvReplyText)
        override val tvReplySender: TextView = itemView.findViewById(R.id.tvReplySender)
        val imageContainer: FrameLayout = itemView.findViewById(R.id.imageContainer)

        init {
            ivMessageImage.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val message = messages[pos]
                    message.imageUrl?.let { url ->
                        val intent = Intent(context, FullScreenImageActivity::class.java).apply {
                            putExtra("image_url", url)
                        }
                        context.startActivity(intent)
                    }
                }
            }

            imageContainer.setOnLongClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    showMessageActionsDialog(messages[pos])
                }
                true
            }
        }
    }

    inner class OtherTextViewHolder(itemView: View) : BaseMessageViewHolder(itemView) {
        val tvMessageText: TextView = itemView.findViewById(R.id.tvMessageText)
        val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        override val replyContainer: View = itemView.findViewById(R.id.replyContainer)
        override val tvReplyText: TextView = itemView.findViewById(R.id.tvReplyText)
        override val tvReplySender: TextView = itemView.findViewById(R.id.tvReplySender)
        val ivUserAvatar: ImageView = itemView.findViewById(R.id.ivUserAvatar)
        val tvSenderName: TextView = itemView.findViewById(R.id.tvSenderName)
        val messageContainer: View = itemView.findViewById(R.id.messageContainer)

        init {
            ivUserAvatar.apply {
                setOnClickListener { handleUserClick() }
            }
            tvSenderName.setOnClickListener { handleUserClick() }
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
                    onUserClick(userId)
                }
            }
        }
    }

    inner class OtherImageViewHolder(itemView: View) : BaseMessageViewHolder(itemView) {
        val ivMessageImage: ImageView = itemView.findViewById(R.id.ivMessageImage)
        val tvImageTime: TextView = itemView.findViewById(R.id.tvImageTime)
        val ivUserAvatar: ImageView = itemView.findViewById(R.id.ivUserAvatar)
        val tvSenderName: TextView = itemView.findViewById(R.id.tvSenderName)
        override val replyContainer: View = itemView.findViewById(R.id.replyContainer)
        override val tvReplyText: TextView = itemView.findViewById(R.id.tvReplyText)
        override val tvReplySender: TextView = itemView.findViewById(R.id.tvReplySender)
        val imageContainer: FrameLayout = itemView.findViewById(R.id.imageContainer)

        init {
            ivUserAvatar.apply {
                setOnClickListener { handleUserClick() }
            }
            tvSenderName.setOnClickListener { handleUserClick() }

            ivMessageImage.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val message = messages[pos]
                    message.imageUrl?.let { url ->
                        val intent = Intent(context, FullScreenImageActivity::class.java).apply {
                            putExtra("image_url", url)
                        }
                        context.startActivity(intent)
                    }
                }
            }

            imageContainer.setOnLongClickListener {
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
                    onUserClick(userId)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        context = parent.context
        return when (viewType) {
            VIEW_TYPE_MY_TEXT -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_message_right, parent, false)
                MyTextViewHolder(view)
            }
            VIEW_TYPE_MY_IMAGE -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_image_right, parent, false)
                MyImageViewHolder(view)
            }
            VIEW_TYPE_OTHER_TEXT -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_message_left, parent, false)
                OtherTextViewHolder(view)
            }
            VIEW_TYPE_OTHER_IMAGE -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_image_left, parent, false)
                OtherImageViewHolder(view)
            }
            else -> throw IllegalArgumentException("Invalid view type")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        when (holder) {
            is MyTextViewHolder -> bindMyText(holder, message)
            is MyImageViewHolder -> bindMyImage(holder, message)
            is OtherTextViewHolder -> bindOtherText(holder, message, usersCache[message.senderId])
            is OtherImageViewHolder -> bindOtherImage(holder, message, usersCache[message.senderId])
        }
    }

    private fun bindMyText(holder: MyTextViewHolder, message: Message) {
        with(holder) {
            tvMessageText.text = message.text
            tvTime.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp))
            tvEdited.visibility = if (message.isEdited) View.VISIBLE else View.GONE
            tvEdited.text = context.getString(R.string.edited)

            bindReplyContent(holder, message)

            ivStatus.setImageResource(
                when (message.status) {
                    Message.MessageStatus.READ -> R.drawable.ic_read
                    Message.MessageStatus.DELIVERED -> R.drawable.ic_delivered
                    else -> R.drawable.ic_sent
                }
            )
        }
    }

    private fun bindMyImage(holder: MyImageViewHolder, message: Message) {
        with(holder) {
            message.imageUrl?.let { url ->
                Glide.with(context)
                    .load(url)
                    .placeholder(R.drawable.ic_plus)
                    .error(R.drawable.ic_walk)
                    .listener(object : RequestListener<Drawable> {
                        override fun onLoadFailed(
                            e: GlideException?,
                            model: Any?,
                            target: Target<Drawable>,
                            isFirstResource: Boolean
                        ): Boolean {
                            Log.e(TAG, "Image load failed", e)
                            return false
                        }

                        override fun onResourceReady(
                            resource: Drawable,
                            model: Any,
                            target: Target<Drawable>?,
                            dataSource: DataSource,
                            isFirstResource: Boolean
                        ): Boolean {
                            tvImageTime.text = SimpleDateFormat("HH:mm", Locale.getDefault())
                                .format(Date(message.timestamp))
                            tvImageTime.visibility = View.VISIBLE
                            return false
                        }
                    })
                    .into(ivMessageImage)
            }

            tvImageTime.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp))
            ivImageStatus.setImageResource(
                when (message.status) {
                    Message.MessageStatus.READ -> R.drawable.ic_read
                    Message.MessageStatus.DELIVERED -> R.drawable.ic_delivered
                    else -> R.drawable.ic_sent
                }
            )

            bindReplyContent(holder, message)
        }
    }

    private fun bindOtherText(holder: OtherTextViewHolder, message: Message, user: User?) {
        with(holder) {
            tvMessageText.text = message.text
            tvTime.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp))

            // Настройка отправителя
            tvSenderName.text = user?.name ?: message.senderName ?: "Unknown"

            // Аватар отправителя
            Glide.with(context)
                .load(user?.profileImageUrl)
                .circleCrop()
                .placeholder(R.drawable.ic_default_profile)
                .error(R.drawable.ic_default_profile)
                .into(ivUserAvatar)

            bindReplyContent(holder, message)
        }
    }

    private fun bindOtherImage(holder: OtherImageViewHolder, message: Message, user: User?) {
        with(holder) {
            message.imageUrl?.let { url ->
                Glide.with(context)
                    .load(url)
                    .placeholder(R.drawable.ic_plus)
                    .error(R.drawable.ic_walk)
                    .listener(object : RequestListener<Drawable> {
                        override fun onLoadFailed(
                            e: GlideException?,
                            model: Any?,
                            target: Target<Drawable>,
                            isFirstResource: Boolean
                        ): Boolean {
                            Log.e(TAG, "Image load failed", e)
                            return false
                        }

                        override fun onResourceReady(
                            resource: Drawable,
                            model: Any,
                            target: Target<Drawable>?,
                            dataSource: DataSource,
                            isFirstResource: Boolean
                        ): Boolean {
                            tvImageTime.text = SimpleDateFormat("HH:mm", Locale.getDefault())
                                .format(Date(message.timestamp))
                            tvImageTime.visibility = View.VISIBLE
                            return false
                        }
                    })
                    .into(ivMessageImage)
            }

            tvImageTime.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp))

            // Настройка отправителя
            tvSenderName.text = user?.name ?: message.senderName ?: "Unknown"

            // Аватар отправителя
            Glide.with(context)
                .load(user?.profileImageUrl)
                .circleCrop()
                .placeholder(R.drawable.ic_default_profile)
                .error(R.drawable.ic_default_profile)
                .into(ivUserAvatar)

            bindReplyContent(holder, message)
        }
    }

    private fun bindReplyContent(holder: BaseMessageViewHolder, message: Message) {
        if (message.replyToMessageId != null) {
            holder.replyContainer.visibility = View.VISIBLE
            holder.tvReplySender.text = message.replyToSenderName ?: "Unknown"

            holder.tvReplyText.text = when {
                !message.replyToMessageText.isNullOrEmpty() ->
                    message.replyToMessageText.take(MAX_REPLY_PREVIEW_LENGTH)
                message.messageType == Message.MessageType.IMAGE -> context.getString(R.string.image)
                else -> context.getString(R.string.message)
            }
        } else {
            holder.replyContainer.visibility = View.GONE
        }
    }

    private fun showMessageActionsDialog(message: Message) {
        val actions = mutableListOf(context.getString(R.string.reply))
        if (message.senderId == currentUserId) {
            actions.add(context.getString(R.string.delete))
            if (message.messageType != Message.MessageType.IMAGE) {
                actions.add(context.getString(R.string.edit))
            }
        }
        AlertDialog.Builder(context)
            .setItems(actions.toTypedArray()) { _, which ->
                when (which) {
                    0 -> onReplyClick(message)
                    1 -> {
                        if (actions.size == 2) {
                            onDeleteClick(message)
                        } else {
                            when {
                                message.senderId == currentUserId && message.messageType == Message.MessageType.IMAGE ->
                                    onDeleteClick(message)
                                message.senderId == currentUserId ->
                                    onEditClick(message)
                            }
                        }
                    }
                    2 -> onDeleteClick(message)
                }
            }
            .show()
    }

    override fun getItemViewType(position: Int): Int {
        val message = messages[position]
        return if (message.senderId == currentUserId) {
            if (message.messageType == Message.MessageType.IMAGE) VIEW_TYPE_MY_IMAGE else VIEW_TYPE_MY_TEXT
        } else {
            if (message.messageType == Message.MessageType.IMAGE) VIEW_TYPE_OTHER_IMAGE else VIEW_TYPE_OTHER_TEXT
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

    fun addMessage(message: Message) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun updateMessage(updatedMessage: Message) {
        val index = messages.indexOfFirst { it.id == updatedMessage.id }
        if (index != -1) {
            messages[index] = updatedMessage
            notifyItemChanged(index)
        }
    }

    fun removeMessage(messageId: String) {
        val index = messages.indexOfFirst { it.id == messageId }
        if (index != -1) {
            messages.removeAt(index)
            notifyItemRemoved(index)
        }
    }
}