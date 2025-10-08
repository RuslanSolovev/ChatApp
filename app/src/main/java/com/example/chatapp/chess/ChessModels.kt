package com.example.chatapp.chess

data class ChessGameData(
    var playerWhite: String = "",
    var playerBlack: String = "",
    var currentPlayer: String = Player.WHITE.name,
    var lastMove: ChessMove? = null,
    var winner: String? = null,
    var lastMoveTimestamp: Long = 0,
    var moveHistory: List<ChessMove> = emptyList() // Добавляем историю ходов
) {
    // Конструктор без аргументов для Firebase
    constructor() : this("", "", Player.WHITE.name, null, null, 0)
}




data class ChessMove(
    var from: Square = Square(0, 0),
    var to: Square = Square(0, 0),
    var player: Player = Player.WHITE
) {
    // Конструктор без аргументов для Firebase
    constructor() : this(Square(0, 0), Square(0, 0), Player.WHITE)
}