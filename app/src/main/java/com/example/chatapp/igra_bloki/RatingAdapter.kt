package com.example.chatapp.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.chatapp.R
import com.example.chatapp.models.User

class RatingAdapter : ListAdapter<User, RatingAdapter.ViewHolder>(UserDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_rating, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val user = getItem(position)
        holder.bind(user)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvPosition: TextView = itemView.findViewById(R.id.tvPosition)
        private val tvName: TextView = itemView.findViewById(R.id.tvUserName)
        private val tvUserRating: TextView = itemView.findViewById(R.id.tvUserRating)
        private val tvLevel: TextView = itemView.findViewById(R.id.tvLevel)
        private val tvUserStats: TextView = itemView.findViewById(R.id.tvUserStats)
        private val ivAvatar: ImageView = itemView.findViewById(R.id.ivUserAvatar)

        fun bind(user: User) {
            // Позиция с эмодзи для топ-3
            val positionText = when (user.position) {
                1 -> "🥇"
                2 -> "🥈"
                3 -> "🥉"
                else -> "${user.position}"
            }
            tvPosition.text = positionText

            // Цвет для топ-3
            val positionColor = when (user.position) {
                1 -> ContextCompat.getColor(itemView.context, R.color.gold)
                2 -> ContextCompat.getColor(itemView.context, R.color.silver)
                3 -> ContextCompat.getColor(itemView.context, R.color.bronze)
                else -> ContextCompat.getColor(itemView.context, R.color.text_secondary)
            }
            tvPosition.setTextColor(positionColor)

            // Имя пользователя
            tvName.text = user.name.ifEmpty { user.email.substringBefore("@") }

            // Рейтинг (единый для всех)
            tvUserRating.text = "🏆 ${user.rating}"

            // Уровень игрока на основе единого рейтинга
            tvLevel.text = user.getLevel()

            // Статистика - упрощенная без указания сложности
            val stats = buildString {
                if (user.gamesPlayed > 0) {
                    append("Игр: ${user.gamesPlayed}")
                    append(" | Побед: ${user.gamesWon}")

                    val winRate = user.getWinRate()
                    if (winRate > 0) {
                        append(" | Винрейт: ${String.format("%.1f", winRate)}%")
                    }

                    // Лучший результат
                    if (user.bestScore > 0) {
                        append("\nЛучший: ${user.bestScore} очков")
                        append(" (ур. ${user.bestLevel})")
                    }

                    // Средний счет
                    val avgScore = user.getAverageScore()
                    if (avgScore > 0) {
                        append(" | Средний: $avgScore")
                    }

                    // Информация о последней игре
                    if (user.lastGameDate > 0) {
                        append("\nПоследняя: ${user.getLastGameDateFormatted()}")
                    }
                } else {
                    append("Еще не играл")
                }
            }
            tvUserStats.text = stats

            // Аватарка с безопасной загрузкой
            try {
                user.profileImageUrl?.let { url ->
                    if (url.isNotEmpty()) {
                        Glide.with(itemView.context)
                            .load(url)
                            .circleCrop()
                            .placeholder(R.drawable.ic_default_profile)
                            .error(R.drawable.ic_default_profile)
                            .into(ivAvatar)
                    } else {
                        ivAvatar.setImageResource(R.drawable.ic_default_profile)
                    }
                } ?: ivAvatar.setImageResource(R.drawable.ic_default_profile)
            } catch (e: Exception) {
                ivAvatar.setImageResource(R.drawable.ic_default_profile)
            }
        }
    }

    class UserDiffCallback : DiffUtil.ItemCallback<User>() {
        override fun areItemsTheSame(oldItem: User, newItem: User): Boolean {
            return oldItem.uid == newItem.uid
        }

        override fun areContentsTheSame(oldItem: User, newItem: User): Boolean {
            return oldItem.rating == newItem.rating &&
                    oldItem.position == newItem.position &&
                    oldItem.gamesPlayed == newItem.gamesPlayed &&
                    oldItem.gamesWon == newItem.gamesWon &&
                    oldItem.totalScore == newItem.totalScore &&
                    oldItem.bestScore == newItem.bestScore &&
                    oldItem.bestLevel == newItem.bestLevel &&
                    oldItem.lastGameScore == newItem.lastGameScore &&
                    oldItem.lastGameDate == newItem.lastGameDate
        }
    }
}