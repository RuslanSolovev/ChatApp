package com.example.chatapp.igra_strotegiy

import com.example.chatapp.models.User
import com.google.firebase.database.Exclude
import com.google.firebase.database.IgnoreExtraProperties
import java.util.*

@IgnoreExtraProperties
data class MultiplayerGame(
    val gameId: String = "",
    val hostUid: String = "",
    val players: Map<String, GamePlayer> = emptyMap(),
    val currentTurnUid: String = "",
    val gameState: GameState = GameState.WAITING_FOR_PLAYERS,
    val createdAt: Long = System.currentTimeMillis(),
    val maxPlayers: Int = 2,
    val minPlayers: Int = 1,
    val turnTimeLimit: Long = 24 * 60 * 60 * 1000,
    val lastTurnTime: Long = System.currentTimeMillis(),
    val winnerUid: String? = null
) {
    // Конструктор без аргументов для Firebase
    constructor() : this("", "", emptyMap(), "", GameState.WAITING_FOR_PLAYERS,
        System.currentTimeMillis(), 2, 1, 24 * 60 * 60 * 1000, System.currentTimeMillis(), null)

    @Exclude
    fun isPlayerTurn(uid: String): Boolean = currentTurnUid == uid

    @Exclude
    fun isPlayerInGame(uid: String): Boolean = getSafePlayers().containsKey(uid)

    @Exclude
    fun getPlayer(uid: String): GamePlayer? = getSafePlayers()[uid]

    @Exclude
    fun canStartGame(): Boolean {
        return getSafePlayers().size >= minPlayers && gameState == GameState.WAITING_FOR_PLAYERS
    }

    @Exclude
    fun getNextPlayerUid(): String {
        val playerList = getSafePlayers().keys.toList()
        if (playerList.isEmpty()) return hostUid

        val currentIndex = playerList.indexOf(currentTurnUid)
        val nextIndex = if (currentIndex == -1 || currentIndex == playerList.size - 1) {
            0
        } else {
            currentIndex + 1
        }
        return playerList[nextIndex]
    }

    @Exclude
    fun getSafePlayers(): Map<String, GamePlayer> {
        return try {
            when (players) {
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    players as Map<String, GamePlayer>
                }
                else -> emptyMap()
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    @Exclude
    fun getFixedGameId(snapshotKey: String): String {
        return if (gameId.isEmpty()) snapshotKey else gameId
    }
}

@IgnoreExtraProperties
data class GamePlayer(
    val uid: String = "",
    val displayName: String = "",
    val profileImageUrl: String? = null,
    val playerColor: String = "#FF0000",
    val gameLogic: GameLogic = GameLogic(),
    val isReady: Boolean = false,
    val joinedAt: Long = System.currentTimeMillis(),
    val lastActive: Long = System.currentTimeMillis()
) {
    constructor() : this("", "", null, "#FF0000", GameLogic(), false,
        System.currentTimeMillis(), System.currentTimeMillis())

    @Exclude
    fun toUser(): User {
        return User(
            uid = uid,
            name = displayName,
            profileImageUrl = profileImageUrl,
            online = true
        )
    }
}

enum class GameState {
    WAITING_FOR_PLAYERS,
    IN_PROGRESS,
    FINISHED,
    ABANDONED
}