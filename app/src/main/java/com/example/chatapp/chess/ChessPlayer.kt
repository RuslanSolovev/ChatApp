package com.example.chatapp.chess

data class ChessPlayer(
    var uid: String = "",
    var name: String = "",
    var isOnline: Boolean = false,
    var isPlaying: Boolean = false,
    var lastActive: Long = 0
) {
    // Конструктор без аргументов для Firebase
    constructor() : this("", "", false, false, 0)

    // Для корректной работы copy с uid
    fun copy(uid: String = this.uid): ChessPlayer {
        return ChessPlayer(uid, name, isOnline, isPlaying, lastActive)
    }
}