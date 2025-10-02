package com.example.chatapp.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.chatapp.R
import com.example.chatapp.models.DiscussionMessage
import com.example.chatapp.models.User
import java.text.SimpleDateFormat
import java.util.*

class DiscussionAdapter(
    private var messages: List<DiscussionMessage>,
    private var users: Map<String, User>,
    private val currentUserId: String,
    private val onReplyClick: (DiscussionMessage) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_MY_MESSAGE = 1
        private const val VIEW_TYPE_THEIR_MESSAGE = 2
        private const val MAX_NESTING_LEVEL = 5
    }

    private var messageTree: Map<String?, List<DiscussionMessage>> = messages.groupBy { it.replyToMessageId }
    private var rootMessages: List<DiscussionMessage> = messageTree[null] ?: emptyList()

    init {
        rebuildMessageTree()
    }

    override fun getItemViewType(position: Int): Int {
        return if (rootMessages[position].senderId == currentUserId) {
            VIEW_TYPE_MY_MESSAGE
        } else {
            VIEW_TYPE_THEIR_MESSAGE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_MY_MESSAGE -> MyMessageViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_my_message, parent, false)
            )
            else -> TheirMessageViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_their_message, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = rootMessages[position]
        when (holder) {
            is MyMessageViewHolder -> holder.bind(message)
            is TheirMessageViewHolder -> holder.bind(message)
        }
    }

    override fun getItemCount(): Int = rootMessages.size

    fun updateData(newMessages: List<DiscussionMessage>, newUsers: Map<String, User>) {
        messages = newMessages
        users = newUsers
        rebuildMessageTree()
        notifyDataSetChanged()
    }

    private fun rebuildMessageTree() {
        messageTree = messages.groupBy { it.replyToMessageId }
        rootMessages = messageTree[null] ?: emptyList()
    }

    abstract inner class BaseViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        abstract fun bind(message: DiscussionMessage, nestingLevel: Int = 0)
        protected val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())

        protected fun bindCommonViews(
            message: DiscussionMessage,
            container: ViewGroup,
            tvMessage: TextView,
            tvTime: TextView,
            btnReply: TextView,
            tvReplyAuthor: TextView? = null,
            tvReplyText: TextView? = null,
            replyPreview: View? = null,
            dividerTop: View? = null,
            nestingLevel: Int = 0
        ) {
            // Установка разделителя для новых цепочек
            dividerTop?.visibility = if (message.replyToMessageId == null && nestingLevel == 0) {
                View.VISIBLE
            } else {
                View.GONE
            }

            // Настройка превью ответа
            message.replyToMessageId?.let { replyId ->
                messages.find { it.messageId == replyId }?.let { reply ->
                    replyPreview?.visibility = View.VISIBLE
                    tvReplyAuthor?.text = users[reply.senderId]?.name ?: "Аноним"
                    tvReplyText?.text = reply.text
                } ?: run {
                    replyPreview?.visibility = View.GONE
                }
            } ?: run {
                replyPreview?.visibility = View.GONE
            }

            tvMessage.text = message.text
            tvTime.text = sdf.format(Date(message.timestamp))

            btnReply.setOnClickListener {
                onReplyClick(message)
            }

            // Обработка вложенных ответов
            container.removeAllViews()
            if (nestingLevel < MAX_NESTING_LEVEL) {
                messageTree[message.messageId]?.forEach { reply ->
                    addReplyView(container, reply, nestingLevel + 1)
                }
            }
        }

        protected fun addReplyView(container: ViewGroup, message: DiscussionMessage, nestingLevel: Int) {
            val viewType = if (message.senderId == currentUserId) {
                VIEW_TYPE_MY_MESSAGE
            } else {
                VIEW_TYPE_THEIR_MESSAGE
            }

            val replyView = LayoutInflater.from(container.context)
                .inflate(
                    when (viewType) {
                        VIEW_TYPE_MY_MESSAGE -> R.layout.item_my_message
                        else -> R.layout.item_their_message
                    },
                    container,
                    false
                )

            when (viewType) {
                VIEW_TYPE_MY_MESSAGE -> MyMessageViewHolder(replyView).bind(message, nestingLevel)
                VIEW_TYPE_THEIR_MESSAGE -> TheirMessageViewHolder(replyView).bind(message, nestingLevel)
            }

            container.addView(replyView)
        }
    }

    inner class MyMessageViewHolder(view: View) : BaseViewHolder(view) {
        private val tvMessage: TextView = view.findViewById(R.id.tvMessage)
        private val tvTime: TextView = view.findViewById(R.id.tvTime)
        private val btnReply: TextView = view.findViewById(R.id.btnReply)
        private val repliesContainer: ViewGroup = view.findViewById(R.id.repliesContainer)
        private val tvReplyAuthor: TextView = view.findViewById(R.id.tvReplyAuthor)
        private val tvReplyText: TextView = view.findViewById(R.id.tvReplyText)
        private val replyPreview: View = view.findViewById(R.id.replyPreview)
        private val dividerTop: View = view.findViewById(R.id.dividerTop)

        override fun bind(message: DiscussionMessage, nestingLevel: Int) {
            bindCommonViews(
                message = message,
                container = repliesContainer,
                tvMessage = tvMessage,
                tvTime = tvTime,
                btnReply = btnReply,
                tvReplyAuthor = tvReplyAuthor,
                tvReplyText = tvReplyText,
                replyPreview = replyPreview,
                dividerTop = dividerTop,
                nestingLevel = nestingLevel
            )
        }
    }

    inner class TheirMessageViewHolder(view: View) : BaseViewHolder(view) {
        private val tvUserName: TextView = view.findViewById(R.id.tvUserName)
        private val tvMessage: TextView = view.findViewById(R.id.tvMessage)
        private val tvTime: TextView = view.findViewById(R.id.tvTime)
        private val btnReply: TextView = view.findViewById(R.id.btnReply)
        private val repliesContainer: ViewGroup = view.findViewById(R.id.repliesContainer)
        private val tvReplyAuthor: TextView = view.findViewById(R.id.tvReplyAuthor)
        private val tvReplyText: TextView = view.findViewById(R.id.tvReplyText)
        private val replyPreview: View = view.findViewById(R.id.replyPreview)
        private val dividerTop: View = view.findViewById(R.id.dividerTop)

        override fun bind(message: DiscussionMessage, nestingLevel: Int) {
            tvUserName.text = users[message.senderId]?.name ?: "Аноним"
            bindCommonViews(
                message = message,
                container = repliesContainer,
                tvMessage = tvMessage,
                tvTime = tvTime,
                btnReply = btnReply,
                tvReplyAuthor = tvReplyAuthor,
                tvReplyText = tvReplyText,
                replyPreview = replyPreview,
                dividerTop = dividerTop,
                nestingLevel = nestingLevel
            )
        }
    }
}