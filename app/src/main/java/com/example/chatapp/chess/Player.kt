package com.example.chatapp.chess

enum class Player {
    WHITE,
    BLACK;

    fun opposite(): Player {
        return if (this == WHITE) BLACK else WHITE
    }
}