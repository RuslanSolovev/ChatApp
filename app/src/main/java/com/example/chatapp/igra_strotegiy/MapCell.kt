package com.example.chatapp.igra_strotegiy

data class MapCell(
    val type: String = "empty", // "empty", "base", "barracks", "mine"
    val x: Int, // Координата X
    val y: Int  // Координата Y
) {
    fun copy(type: String = this.type): MapCell {
        return MapCell(type, x, y)
    }
}