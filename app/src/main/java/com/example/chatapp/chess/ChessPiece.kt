package com.example.chatapp.chess

data class ChessPiece(
    var col: Int = 0,
    var row: Int = 0,
    var player: Player = Player.WHITE,
    var chessman: Chessman = Chessman.PAWN,
    var imageResId: Int = 0
) {
    // Конструктор без аргументов для Firebase
    constructor() : this(0, 0, Player.WHITE, Chessman.PAWN, 0)

    fun copy(col: Int = this.col, row: Int = this.row): ChessPiece {
        return ChessPiece(col, row, player, chessman, imageResId)
    }
}

