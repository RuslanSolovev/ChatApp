package com.example.chatapp.igra_strotegiy

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Position(
    val x: Int = 0,
    val y: Int = 0
) : Parcelable {

    // Конструктор для совместимости с Pair
    constructor(pair: Pair<Int, Int>) : this(pair.first, pair.second)

    // Конвертация в Pair для обратной совместимости
    fun toPair(): Pair<Int, Int> = Pair(x, y)

    companion object {
        fun fromPair(pair: Pair<Int, Int>): Position = Position(pair.first, pair.second)
    }

    override fun toString(): String = "($x, $y)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Position
        return x == other.x && y == other.y
    }

    override fun hashCode(): Int = 31 * x + y
}