package com.example.chatapp.chess

data class ChessInvitation(
    var from: String = "",
    var fromName: String = "",
    var gameId: String = "",
    var status: String = "pending",
    var timestamp: Long = 0
) {
    constructor() : this("", "", "", "pending", 0)
}