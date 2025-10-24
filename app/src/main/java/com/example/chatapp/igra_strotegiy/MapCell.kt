package com.example.chatapp.igra_strotegiy

data class MapCell(
    val type: String = "empty", // "empty", "base", "barracks", "mine", "mountain", "river"
    val x: Int,
    val y: Int
) {
    fun copy(type: String = this.type): MapCell {
        return MapCell(type, x, y)
    }

    // Проверка, можно ли строить на клетке
    fun isBuildable(): Boolean {
        return type == "empty"
    }
}