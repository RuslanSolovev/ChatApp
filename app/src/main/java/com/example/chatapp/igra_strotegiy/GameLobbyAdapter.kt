package com.example.chatapp.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.chatapp.R
import com.example.chatapp.igra_strotegiy.GameState
import com.example.chatapp.igra_strotegiy.MultiplayerGame

class GameLobbyAdapter(
    private val onGameJoin: (MultiplayerGame) -> Unit,
    private val onGameSpectate: (MultiplayerGame) -> Unit
) : RecyclerView.Adapter<GameLobbyAdapter.ViewHolder>() {

    private var games = listOf<MultiplayerGame>()

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvHost: TextView = itemView.findViewById(R.id.tvGameHost)
        private val tvPlayers: TextView = itemView.findViewById(R.id.tvGamePlayers)
        private val btnJoin: Button = itemView.findViewById(R.id.btnJoinGame)
        private val btnSpectate: Button = itemView.findViewById(R.id.btnSpectateGame)

        fun bind(game: MultiplayerGame) {
            val safePlayers = game.getSafePlayers()
            val hostName = safePlayers[game.hostUid]?.displayName ?: "Неизвестно"
            tvHost.text = "Хост: $hostName"
            tvPlayers.text = "Игроков: ${safePlayers.size}/${game.maxPlayers} - ${getGameStatus(game)}"

            btnJoin.setOnClickListener { onGameJoin(game) }
            btnSpectate.setOnClickListener { onGameSpectate(game) }

            val canJoin = safePlayers.size < game.maxPlayers && game.gameState == GameState.WAITING_FOR_PLAYERS
            btnJoin.visibility = if (canJoin) View.VISIBLE else View.GONE
            btnJoin.isEnabled = canJoin
            btnJoin.text = if (canJoin) "Присоединиться" else "Мест нет"
        }

        private fun getGameStatus(game: MultiplayerGame): String {
            return when (game.gameState) {
                GameState.WAITING_FOR_PLAYERS -> "Ожидание игроков"
                GameState.IN_PROGRESS -> "Идет игра"
                GameState.FINISHED -> "Завершена"
                GameState.ABANDONED -> "Прервана"
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_game_lobby, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(games[position])
    }

    override fun getItemCount(): Int = games.size

    fun submitList(newGames: List<MultiplayerGame>) {
        games = newGames
        notifyDataSetChanged()
    }
}