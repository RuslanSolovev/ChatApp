package com.example.chatapp.chess

import com.example.chatapp.R
import kotlin.math.abs

object ChessGame {
    private var piecesBox = mutableSetOf<ChessPiece>()
    var currentPlayer: Player = Player.WHITE

    init {
        reset()
    }

    fun clear() {
        piecesBox.clear()
    }

    fun addPiece(piece: ChessPiece) {
        piecesBox.add(piece)
    }

    private fun canKnightMove(from: Square, to: Square): Boolean {
        return abs(from.col - to.col) == 2 && abs(from.row - to.row) == 1 ||
                abs(from.col - to.col) == 1 && abs(from.row - to.row) == 2
    }

    private fun canRookMove(from: Square, to: Square): Boolean {
        if (from.col == to.col && isClearVerticallyBetween(from, to) ||
            from.row == to.row && isClearHorizontallyBetween(from, to)) {
            return true
        }
        return false
    }

    private fun isClearVerticallyBetween(from: Square, to: Square): Boolean {
        if (from.col != to.col) return false
        val gap = abs(from.row - to.row) - 1
        if (gap == 0) return true
        for (i in 1..gap) {
            val nextRow = if (to.row > from.row) from.row + i else from.row - i
            if (pieceAt(Square(from.col, nextRow)) != null) {
                return false
            }
        }
        return true
    }

    private fun isClearHorizontallyBetween(from: Square, to: Square): Boolean {
        if (from.row != to.row) return false
        val gap = abs(from.col - to.col) - 1
        if (gap == 0) return true
        for (i in 1..gap) {
            val nextCol = if (to.col > from.col) from.col + i else from.col - i
            if (pieceAt(Square(nextCol, from.row)) != null) {
                return false
            }
        }
        return true
    }

    private fun isClearDiagonally(from: Square, to: Square): Boolean {
        if (abs(from.col - to.col) != abs(from.row - to.row)) return false
        val gap = abs(from.col - to.col) - 1
        for (i in 1..gap) {
            val nextCol = if (to.col > from.col) from.col + i else from.col - i
            val nextRow = if (to.row > from.row) from.row + i else from.row - i
            if (pieceAt(Square(nextCol, nextRow)) != null) {
                return false
            }
        }
        return true
    }

    private fun canBishopMove(from: Square, to: Square): Boolean {
        if (abs(from.col - to.col) == abs(from.row - to.row)) {
            return isClearDiagonally(from, to)
        }
        return false
    }

    private fun canQueenMove(from: Square, to: Square): Boolean {
        return canRookMove(from, to) || canBishopMove(from, to)
    }

    private fun canKingMove(from: Square, to: Square): Boolean {
        if (canQueenMove(from, to)) {
            val deltaCol = abs(from.col - to.col)
            val deltaRow = abs(from.row - to.row)
            return deltaCol == 1 && deltaRow == 1 || deltaCol + deltaRow == 1
        }
        return false
    }

    private fun canPawnMove(from: Square, to: Square): Boolean {
        val piece = pieceAt(from) ?: return false
        val direction = if (piece.player == Player.WHITE) 1 else -1
        val startRow = if (piece.player == Player.WHITE) 1 else 6

        // Движение вперед
        if (from.col == to.col) {
            // На одну клетку
            if (to.row == from.row + direction) {
                return pieceAt(to) == null
            }
            // На две клетки с начальной позиции
            if (from.row == startRow && to.row == from.row + 2 * direction) {
                return pieceAt(to) == null &&
                        pieceAt(Square(from.col, from.row + direction)) == null
            }
        }
        // Взятие
        else if (abs(from.col - to.col) == 1 && to.row == from.row + direction) {
            val targetPiece = pieceAt(to)
            return targetPiece != null && targetPiece.player != piece.player
        }
        return false
    }

    fun canMove(from: Square, to: Square): Boolean {
        if (from.col == to.col && from.row == to.row) {
            return false
        }
        val movingPiece = pieceAt(from) ?: return false
        return when (movingPiece.chessman) {
            Chessman.KNIGHT -> canKnightMove(from, to)
            Chessman.ROOK -> canRookMove(from, to)
            Chessman.BISHOP -> canBishopMove(from, to)
            Chessman.QUEEN -> canQueenMove(from, to)
            Chessman.KING -> canKingMove(from, to)
            Chessman.PAWN -> canPawnMove(from, to)
        }
    }

    fun kingsExist(): Boolean {
        var whiteKingExists = false
        var blackKingExists = false

        for (piece in piecesBox) {
            when {
                piece.chessman == Chessman.KING && piece.player == Player.WHITE -> whiteKingExists = true
                piece.chessman == Chessman.KING && piece.player == Player.BLACK -> blackKingExists = true
            }
            // Если оба короля на месте, можно прекратить проверку
            if (whiteKingExists && blackKingExists) return true
        }
        return whiteKingExists && blackKingExists
    }

    fun tryMovePiece(from: Square, to: Square): Boolean {
        val piece = pieceAt(from) ?: return false
        if (piece.player != currentPlayer) return false

        if (!canMove(from, to)) return false

        movePieceInternal(from, to)
        return true
    }

    fun applyMove(from: Square, to: Square) {
        movePieceInternal(from, to)
    }

    private fun movePieceInternal(from: Square, to: Square) {
        if (from.col == to.col && from.row == to.row) return
        val movingPiece = pieceAt(from) ?: return

        pieceAt(to)?.let {
            if (it.player == movingPiece.player) {
                return
            }
            piecesBox.remove(it)
        }

        piecesBox.remove(movingPiece)
        addPiece(movingPiece.copy(col = to.col, row = to.row))
    }

    fun reset() {
        clear()
        currentPlayer = Player.WHITE

        // Белые фигуры
        // Ладьи
        addPiece(ChessPiece(0, 0, Player.WHITE, Chessman.ROOK, R.drawable.rook_white))
        addPiece(ChessPiece(7, 0, Player.WHITE, Chessman.ROOK, R.drawable.rook_white))

        // Кони
        addPiece(ChessPiece(1, 0, Player.WHITE, Chessman.KNIGHT, R.drawable.knight_white))
        addPiece(ChessPiece(6, 0, Player.WHITE, Chessman.KNIGHT, R.drawable.knight_white))

        // Слоны
        addPiece(ChessPiece(2, 0, Player.WHITE, Chessman.BISHOP, R.drawable.bishop_white))
        addPiece(ChessPiece(5, 0, Player.WHITE, Chessman.BISHOP, R.drawable.bishop_white))

        // Ферзь и король
        addPiece(ChessPiece(3, 0, Player.WHITE, Chessman.QUEEN, R.drawable.queen_white))
        addPiece(ChessPiece(4, 0, Player.WHITE, Chessman.KING, R.drawable.king_white))

        // Пешки
        for (i in 0 until 8) {
            addPiece(ChessPiece(i, 1, Player.WHITE, Chessman.PAWN, R.drawable.pawn_white))
        }

        // Черные фигуры
        // Ладьи
        addPiece(ChessPiece(0, 7, Player.BLACK, Chessman.ROOK, R.drawable.rook_black))
        addPiece(ChessPiece(7, 7, Player.BLACK, Chessman.ROOK, R.drawable.rook_black))

        // Кони
        addPiece(ChessPiece(1, 7, Player.BLACK, Chessman.KNIGHT, R.drawable.knight_black))
        addPiece(ChessPiece(6, 7, Player.BLACK, Chessman.KNIGHT, R.drawable.knight_black))

        // Слоны
        addPiece(ChessPiece(2, 7, Player.BLACK, Chessman.BISHOP, R.drawable.bishop_black))
        addPiece(ChessPiece(5, 7, Player.BLACK, Chessman.BISHOP, R.drawable.bishop_black))

        // Ферзь и король
        addPiece(ChessPiece(3, 7, Player.BLACK, Chessman.QUEEN, R.drawable.queen_black))
        addPiece(ChessPiece(4, 7, Player.BLACK, Chessman.KING, R.drawable.king_black))

        // Пешки
        for (i in 0 until 8) {
            addPiece(ChessPiece(i, 6, Player.BLACK, Chessman.PAWN, R.drawable.pawn_black))
        }
    }

    fun pieceAt(square: Square): ChessPiece? {
        return pieceAt(square.col, square.row)
    }

    private fun pieceAt(col: Int, row: Int): ChessPiece? {
        for (piece in piecesBox) {
            if (col == piece.col && row == piece.row) {
                return piece
            }
        }
        return null
    }

    fun pgnBoard(): String {
        var desc = " \n"
        desc += "  a b c d e f g h\n"
        for (row in 7 downTo 0) {
            desc += "${row + 1}"
            desc += boardRow(row)
            desc += " ${row + 1}"
            desc += "\n"
        }
        desc += "  a b c d e f g h"

        return desc
    }

    override fun toString(): String {
        var desc = " \n"
        for (row in 7 downTo 0) {
            desc += "$row"
            desc += boardRow(row)
            desc += "\n"
        }
        desc += "  0 1 2 3 4 5 6 7"

        return desc
    }

    private fun boardRow(row: Int): String {
        var desc = ""
        for (col in 0 until 8) {
            desc += " "
            desc += pieceAt(col, row)?.let {
                val white = it.player == Player.WHITE
                when (it.chessman) {
                    Chessman.KING -> if (white) "K" else "k"
                    Chessman.QUEEN -> if (white) "Q" else "q"
                    Chessman.BISHOP -> if (white) "B" else "b"
                    Chessman.ROOK -> if (white) "R" else "r"
                    Chessman.KNIGHT -> if (white) "N" else "n"
                    Chessman.PAWN -> if (white) "P" else "p"
                }
            } ?: "."
        }
        return desc
    }
}