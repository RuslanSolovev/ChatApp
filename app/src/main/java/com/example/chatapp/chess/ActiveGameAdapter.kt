package com.example.chatapp.chess

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.chatapp.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ActiveGameAdapter(
    private val onGameClick: (ActiveGame) -> Unit
) : ListAdapter<ActiveGame, ActiveGameAdapter.ActiveGameViewHolder>(ActiveGameDiffCallback()) {

    inner class ActiveGameViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val opponentNameTextView: TextView = itemView.findViewById(R.id.opponentName)
        private val gameInfoTextView: TextView = itemView.findViewById(R.id.gameInfo)
        private val lastMoveTextView: TextView = itemView.findViewById(R.id.lastMoveTime)
        private val resumeButton: Button = itemView.findViewById(R.id.resumeButton)

        fun bind(game: ActiveGame) {
            opponentNameTextView.text = "Противник: ${game.opponentName}"
            gameInfoTextView.text = "Ваш цвет: ${if (game.myColor == Player.WHITE) "Белые" else "Черные"}"

            val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
            lastMoveTextView.text = "Последний ход: ${dateFormat.format(Date(game.lastMoveTime))}"

            resumeButton.setOnClickListener {
                onGameClick(game)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ActiveGameViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_active_game, parent, false)
        return ActiveGameViewHolder(view)
    }

    override fun onBindViewHolder(holder: ActiveGameViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ActiveGameDiffCallback : DiffUtil.ItemCallback<ActiveGame>() {
        override fun areItemsTheSame(oldItem: ActiveGame, newItem: ActiveGame): Boolean {
            return oldItem.gameId == newItem.gameId
        }

        override fun areContentsTheSame(oldItem: ActiveGame, newItem: ActiveGame): Boolean {
            return oldItem == newItem
        }
    }
}