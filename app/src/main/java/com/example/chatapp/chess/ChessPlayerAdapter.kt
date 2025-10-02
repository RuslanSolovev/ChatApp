package com.example.chatapp.chess

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.chatapp.R

class ChessPlayerAdapter(
    private val onPlayerClick: (ChessPlayer) -> Unit
) : ListAdapter<ChessPlayer, ChessPlayerAdapter.PlayerViewHolder>(PlayerDiffCallback()) {

    inner class PlayerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.playerName)
        private val emailTextView: TextView = itemView.findViewById(R.id.playerEmail)
        private val statusTextView: TextView = itemView.findViewById(R.id.playerStatus)
        private val avatarImageView: ImageView = itemView.findViewById(R.id.playerAvatar)

        fun bind(player: ChessPlayer) {
            // Устанавливаем имя игрока
            nameTextView.text = player.name ?: "Без имени"

            // Email убираем, так как его нет в модели
            emailTextView.visibility = View.GONE

            // Устанавливаем статус с разными цветами
            when {
                !player.isOnline -> {
                    statusTextView.text = "Оффлайн"
                    statusTextView.setTextColor(itemView.context.getColor(R.color.colorPrimaryLight))
                }
                player.isPlaying -> {
                    statusTextView.text = "В игре"
                    statusTextView.setTextColor(itemView.context.getColor(R.color.primary_color))
                }
                else -> {
                    statusTextView.text = "Онлайн"
                    statusTextView.setTextColor(itemView.context.getColor(R.color.gradientStart))
                }
            }

            // Заглушка для аватара
            avatarImageView.setImageResource(R.drawable.ic_default_profile)

            // Обработка нажатия на игрока
            itemView.setOnClickListener {
                onPlayerClick(player)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlayerViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chess_player, parent, false)
        return PlayerViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlayerViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class PlayerDiffCallback : DiffUtil.ItemCallback<ChessPlayer>() {
        override fun areItemsTheSame(oldItem: ChessPlayer, newItem: ChessPlayer): Boolean {
            return oldItem.uid == newItem.uid
        }

        override fun areContentsTheSame(oldItem: ChessPlayer, newItem: ChessPlayer): Boolean {
            return oldItem.name == newItem.name &&
                    oldItem.isOnline == newItem.isOnline &&
                    oldItem.isPlaying == newItem.isPlaying
        }
    }
}