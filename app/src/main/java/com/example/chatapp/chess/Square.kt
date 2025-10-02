package com.example.chatapp.chess

data class Square(
    var col: Int = 0,
    var row: Int = 0
) {
    // Конструктор без аргументов для Firebase
    constructor() : this(0, 0)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Square

        if (col != other.col) return false
        if (row != other.row) return false

        return true
    }

    override fun hashCode(): Int {
        var result = col
        result = 31 * result + row
        return result
    }
}