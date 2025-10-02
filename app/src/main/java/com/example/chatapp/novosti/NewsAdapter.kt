package com.example.chatapp.novosti

import android.app.AlertDialog
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import com.example.chatapp.R
import com.example.chatapp.activities.MainActivity
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class NewsAdapter(
    private val onNewsClick: (NewsItem) -> Unit = {},
    private val onNewsLongClick: (NewsItem) -> Unit = { _ -> }
) : ListAdapter<NewsItem, RecyclerView.ViewHolder>(NewsItemDiffCallback()) {

    companion object {
        private const val TAG = "NewsAdapter"
        private const val TYPE_TEXT = 1
        private const val TYPE_IMAGE = 2
        private const val TYPE_EXTERNAL = 3
        private const val DEFAULT_SOURCE_NAME = "Lenta.ru"
        private val DEFAULT_SOURCE_LOGO = R.drawable.id_lenta
        private const val AVATAR_RADIUS_DP = 24
        private const val IMAGE_RADIUS_DP = 8
    }

    private val currentList = mutableListOf<NewsItem>()
    private val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: ""

    private val requestOptionsAvatar = RequestOptions()
        .placeholder(R.drawable.ic_default_profile)
        .error(R.drawable.ic_default_profile)
        .transform(CenterCrop(), RoundedCorners(AVATAR_RADIUS_DP))
        .diskCacheStrategy(DiskCacheStrategy.ALL)

    private val requestOptionsImage = RequestOptions()
        .placeholder(R.drawable.search_view_background)
        .error(R.drawable.search_view_background)
        .transform(CenterCrop(), RoundedCorners(IMAGE_RADIUS_DP))
        .diskCacheStrategy(DiskCacheStrategy.ALL)

    private var rootView: View? = null

    override fun getItemViewType(position: Int): Int {
        val item = getItem(position)
        return when {
            item.isExternal -> TYPE_EXTERNAL
            item.imageUrl != null -> TYPE_IMAGE
            else -> TYPE_TEXT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_TEXT -> {
                val view = layoutInflater.inflate(R.layout.item_news_text, parent, false)
                TextViewHolder(view, onNewsClick, onNewsLongClick, requestOptionsAvatar)
            }
            TYPE_IMAGE -> {
                val view = layoutInflater.inflate(R.layout.item_news_image, parent, false)
                ImageViewHolder(view, onNewsClick, onNewsLongClick, requestOptionsAvatar, requestOptionsImage)
            }
            TYPE_EXTERNAL -> {
                val view = layoutInflater.inflate(R.layout.item_news_external, parent, false)
                ExternalViewHolder(view, onNewsClick, onNewsLongClick, requestOptionsAvatar, requestOptionsImage)
            }
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val newsItem = getItem(position)
        when (holder) {
            is TextViewHolder -> holder.bind(newsItem, currentUserId)
            is ImageViewHolder -> holder.bind(newsItem, currentUserId)
            is ExternalViewHolder -> holder.bind(newsItem)
        }

        if (rootView == null) {
            rootView = holder.itemView
        }
    }

    override fun submitList(list: MutableList<NewsItem>?) {
        val newList = list?.distinctBy { it.id }?.toMutableList() ?: mutableListOf()
        currentList.clear()
        currentList.addAll(newList)
        super.submitList(ArrayList(currentList))
    }

    fun addNewsToTop(newNews: List<NewsItem>) {
        val uniqueNewNews = newNews.distinctBy { it.id }
            .filter { newItem ->
                currentList.none { existingItem ->
                    existingItem.id == newItem.id ||
                            (newItem.isExternal && existingItem.externalLink == newItem.externalLink)
                }
            }

        if (uniqueNewNews.isNotEmpty()) {
            val newList = mutableListOf<NewsItem>()
            newList.addAll(uniqueNewNews)
            newList.addAll(currentList)
            submitList(newList)
            Log.d(TAG, "Added ${uniqueNewNews.size} news to top. Total: ${currentList.size}")
        }
    }

    fun addNewsItem(item: NewsItem) {
        if (currentList.none { it.id == item.id }) {
            val newList = mutableListOf<NewsItem>()
            newList.add(item)
            newList.addAll(currentList)
            submitList(newList)
        }
    }

    fun clearAll() {
        submitList(mutableListOf())
    }

    fun getItemsCount(): Int {
        return currentList.size
    }

    fun getItemAt(position: Int): NewsItem? {
        return if (position in 0 until currentList.size) currentList[position] else null
    }

    abstract inner class BaseInternalViewHolder(
        itemView: View,
        private val onNewsClick: (NewsItem) -> Unit,
        private val onNewsLongClick: (NewsItem) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        protected fun setupClickListeners(news: NewsItem) {
            itemView.setOnClickListener { onNewsClick(news) }
            itemView.setOnLongClickListener {
                onNewsLongClick(news)
                true
            }
        }

        protected fun showDeleteConfirmation(news: NewsItem, onDeleteConfirmed: () -> Unit) {
            val context = itemView.context
            AlertDialog.Builder(context)
                .setTitle("Подтверждение удаления")
                .setMessage("Вы действительно хотите удалить эту новость?")
                .setPositiveButton("Удалить") { _, _ ->
                    onDeleteConfirmed()
                }
                .setNegativeButton("Отмена", null)
                .create()
                .show()
        }

        protected fun setupEditDeleteButtons(
            news: NewsItem,
            ibEdit: ImageButton,
            ibDelete: ImageButton,
            currentUserId: String
        ) {
            val isMyPost = news.authorId == currentUserId && !news.isExternal
            ibEdit.visibility = if (isMyPost) View.VISIBLE else View.GONE
            ibDelete.visibility = if (isMyPost) View.VISIBLE else View.GONE

            ibEdit.setOnClickListener {
                (itemView.context as? MainActivity)?.openEditNewsFragment(news)
            }

            ibDelete.setOnClickListener {
                showDeleteConfirmation(news) {
                    onNewsClick.invoke(news.copy(id = "DELETE_${news.id}"))
                }
            }
        }

        protected fun bindAuthorInfo(
            news: NewsItem,
            tvAuthor: TextView,
            ivAvatar: ImageView,
            avatarOptions: RequestOptions
        ) {
            if (news.isExternal) {
                tvAuthor.text = news.source ?: DEFAULT_SOURCE_NAME
                Glide.with(itemView.context)
                    .load(news.sourceLogoUrl ?: DEFAULT_SOURCE_LOGO)
                    .apply(avatarOptions)
                    .into(ivAvatar)
            } else {
                tvAuthor.text = news.authorName ?: "Неизвестный"
                Glide.with(itemView.context)
                    .load(news.authorAvatarUrl)
                    .apply(avatarOptions)
                    .into(ivAvatar)
            }
        }

        protected fun bindContentAndTime(news: NewsItem, tvContent: TextView, tvTime: TextView) {
            tvContent.text = news.content
            tvTime.text = formatTime(news.timestamp)

            news.backgroundColor?.takeIf { it.isNotBlank() }?.let { color ->
                try {
                    itemView.setBackgroundColor(android.graphics.Color.parseColor(color))
                } catch (e: Exception) {
                    Log.w(TAG, "Invalid background color: $color", e)
                }
            }
        }
    }

    inner class TextViewHolder(
        itemView: View,
        onNewsClick: (NewsItem) -> Unit,
        onNewsLongClick: (NewsItem) -> Unit,
        private val avatarOptions: RequestOptions
    ) : BaseInternalViewHolder(itemView, onNewsClick, onNewsLongClick) {

        private val ibEdit: ImageButton = itemView.findViewById(R.id.ibEdit)
        private val ibDelete: ImageButton = itemView.findViewById(R.id.ibDelete)
        private val tvAuthor: TextView = itemView.findViewById(R.id.tvAuthor)
        private val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        private val tvContent: TextView = itemView.findViewById(R.id.tvContent)
        private val ivAvatar: ImageView = itemView.findViewById(R.id.ivAuthorAvatar)

        fun bind(news: NewsItem, currentUserId: String) {
            setupClickListeners(news)
            setupEditDeleteButtons(news, ibEdit, ibDelete, currentUserId)
            bindAuthorInfo(news, tvAuthor, ivAvatar, avatarOptions)
            bindContentAndTime(news, tvContent, tvTime)
        }
    }

    inner class ImageViewHolder(
        itemView: View,
        onNewsClick: (NewsItem) -> Unit,
        onNewsLongClick: (NewsItem) -> Unit,
        private val avatarOptions: RequestOptions,
        private val imageOptions: RequestOptions
    ) : BaseInternalViewHolder(itemView, onNewsClick, onNewsLongClick) {

        private val ibEdit: ImageButton = itemView.findViewById(R.id.ibEdit)
        private val ibDelete: ImageButton = itemView.findViewById(R.id.ibDelete)
        private val tvAuthor: TextView = itemView.findViewById(R.id.tvAuthor)
        private val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        private val tvContent: TextView = itemView.findViewById(R.id.tvContent)
        private val ivImage: ImageView = itemView.findViewById(R.id.ivNewsImage)
        private val ivAvatar: ImageView = itemView.findViewById(R.id.ivAuthorAvatar)

        fun bind(news: NewsItem, currentUserId: String) {
            setupClickListeners(news)
            setupEditDeleteButtons(news, ibEdit, ibDelete, currentUserId)
            bindAuthorInfo(news, tvAuthor, ivAvatar, avatarOptions)
            bindContentAndTime(news, tvContent, tvTime)
            bindNewsImage(news)
        }

        private fun bindNewsImage(news: NewsItem) {
            news.imageUrl?.let { url ->
                ivImage.visibility = View.VISIBLE
                Glide.with(itemView.context)
                    .load(url)
                    .apply(imageOptions)
                    .into(ivImage)

                ivImage.setOnClickListener {
                    if (news.imageUrl != null) {
                        onNewsClick(news)
                    }
                }
            } ?: run {
                ivImage.visibility = View.GONE
            }
        }
    }

    inner class ExternalViewHolder(
        itemView: View,
        private val onNewsClick: (NewsItem) -> Unit,
        private val onNewsLongClick: (NewsItem) -> Unit,
        private val avatarOptions: RequestOptions,
        private val imageOptions: RequestOptions
    ) : RecyclerView.ViewHolder(itemView) {

        private val tvSource: TextView = itemView.findViewById(R.id.tvSource)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        private val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        private val tvSummary: TextView? = itemView.findViewById(R.id.tvSummary)
        private val ivLogo: ImageView = itemView.findViewById(R.id.ivSourceLogo)
        private val ivNewsImage: ImageView = itemView.findViewById(R.id.ivNewsImage)

        init {
            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val news = getItem(position)
                    onNewsClick(news)
                }
            }

            itemView.setOnLongClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val news = getItem(position)
                    onNewsLongClick(news)
                    true
                } else {
                    false
                }
            }
        }

        fun bind(news: NewsItem) {
            tvSource.text = news.source ?: DEFAULT_SOURCE_NAME
            Glide.with(itemView.context)
                .load(news.sourceLogoUrl ?: DEFAULT_SOURCE_LOGO)
                .apply(avatarOptions)
                .into(ivLogo)

            tvTitle.text = news.content
            tvTime.text = formatTime(news.timestamp)

            tvSummary?.text = news.summary ?: ""

            news.backgroundColor?.takeIf { it.isNotBlank() }?.let { color ->
                try {
                    itemView.setBackgroundColor(android.graphics.Color.parseColor(color))
                } catch (e: Exception) {
                    Log.w(TAG, "Invalid background color: $color", e)
                }
            }

            news.imageUrl?.let { imageUrl ->
                ivNewsImage.visibility = View.VISIBLE
                Glide.with(itemView.context)
                    .load(imageUrl)
                    .apply(imageOptions)
                    .into(ivNewsImage)
            } ?: run {
                ivNewsImage.visibility = View.GONE
            }
        }
    }

    fun clearGlideCache() {
        val context = rootView?.context ?: return
        Glide.get(context).clearMemory()

        GlobalScope.launch(Dispatchers.IO) {
            try {
                Glide.get(context).clearDiskCache()
                Log.d(TAG, "Glide disk cache cleared successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing Glide disk cache", e)
            }
        }
    }

    private fun formatTime(timestamp: Long): String {
        return try {
            val currentTime = System.currentTimeMillis()
            val diff = currentTime - timestamp
            val days = diff / (24 * 60 * 60 * 1000)

            when {
                days == 0L -> {
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
                }
                days == 1L -> "Вчера"
                days < 7L -> "$days дня назад"
                else -> SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(timestamp))
            }
        } catch (e: Exception) {
            Log.w(TAG, "formatTime: Error formatting timestamp $timestamp", e)
            "Неизвестно"
        }
    }
}

class NewsItemDiffCallback : DiffUtil.ItemCallback<NewsItem>() {
    override fun areItemsTheSame(oldItem: NewsItem, newItem: NewsItem): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: NewsItem, newItem: NewsItem): Boolean {
        return oldItem.content == newItem.content &&
                oldItem.timestamp == newItem.timestamp &&
                oldItem.authorName == newItem.authorName &&
                oldItem.authorAvatarUrl == newItem.authorAvatarUrl &&
                oldItem.imageUrl == newItem.imageUrl &&
                oldItem.isExternal == newItem.isExternal &&
                oldItem.source == newItem.source &&
                oldItem.sourceLogoUrl == newItem.sourceLogoUrl &&
                oldItem.backgroundColor == newItem.backgroundColor &&
                oldItem.summary == newItem.summary
    }

    override fun getChangePayload(oldItem: NewsItem, newItem: NewsItem): Any? {
        return null // Упрощаем - всегда полное обновление
    }
}