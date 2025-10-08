package com.example.chatapp.chess

data class ActiveGame(
    val gameId: String = "",
    val opponentId: String = "",
    val opponentName: String = "",
    val myColor: Player = Player.WHITE,
    val currentPlayer: Player = Player.WHITE,
    val lastMoveTime: Long = 0
) {
    constructor() : this("", "", "", Player.WHITE, Player.WHITE, 0)
}