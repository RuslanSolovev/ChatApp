package com.example.chatapp.igra_strotegiy

import kotlin.random.Random

data class GameMap(val width: Int = 9, val height: Int = 9) {
    val cells = mutableListOf<MapCell>()

    init {
        for (y in 0 until height) {
            for (x in 0 until width) {
                cells.add(MapCell("empty", x, y)) // ← всё пусто
            }
        }

        // Препятствия (~20%)
        val obstacles = listOf("mountain", "river")
        repeat((width * height) / 5) {
            var x: Int
            var y: Int
            do {
                x = Random.nextInt(width)
                y = Random.nextInt(height)
            } while (getCell(x, y)?.type != "empty")
            setCellType(x, y, obstacles.random())
        }
    }

    fun getCell(x: Int, y: Int): MapCell? {
        if (x !in 0 until width || y !in 0 until height) return null
        return cells[y * width + x]
    }

    fun setCellType(x: Int, y: Int, type: String) {
        val index = y * width + x
        if (index in cells.indices) {
            val old = cells[index]
            cells[index] = old.copy(type = type)
        }
    }
}