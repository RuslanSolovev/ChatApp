package com.example.chatapp.igra_strotegiy

import com.google.firebase.database.Exclude
import com.google.firebase.database.IgnoreExtraProperties
import kotlin.random.Random

@IgnoreExtraProperties
data class GameMap(
    var width: Int = 13,
    var height: Int = 13,
    var cells: MutableList<MapCell> = mutableListOf()
) {
    // Конструктор без аргументов для Firebase
    constructor() : this(13, 13, mutableListOf())

    init {
        if (cells.isEmpty()) {
            initializeMap()
        }
    }

    private fun initializeMap() {
        cells.clear()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val type = when {
                    x <= 2 -> "empty"                // Левый остров
                    x >= width - 3 -> "empty"        // Правый остров
                    else -> "sea"                    // Всё остальное — море
                }
                cells.add(MapCell(type, x, y))
            }
        }

        // 🔥 Теперь ТОЛЬКО горы, и их в 2 раза меньше
        val obstacles = listOf("mountain") // ← реки убраны
        val obstacleCount = (width * height) / 12 // ← вместо /6
        repeat(obstacleCount) {
            var x: Int
            var y: Int
            do {
                x = Random.nextInt(width)
                y = Random.nextInt(height)
            } while (getCell(x, y)?.type != "empty") // только суша
            setCellType(x, y, obstacles.random())
        }
    }

    @Exclude
    fun isCoastal(x: Int, y: Int): Boolean {
        if (getCellType(x, y) != "empty") return false
        for (dx in -1..1) {
            for (dy in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (nx in 0 until width && ny in 0 until height) {
                    if (getCellType(nx, ny) == "sea") return true
                }
            }
        }
        return false
    }

    fun getCell(x: Int, y: Int): MapCell? {
        if (x !in 0 until width || y !in 0 until height) return null
        return cells[y * width + x]
    }

    fun setCellType(x: Int, y: Int, type: String) {
        val index = y * width + x
        if (index in cells.indices) {
            val old = cells[index]
            val newCell = old.copy(type = type)
            newCell.buildable = newCell.isBuildable() // Обновляем поле buildable
            cells[index] = newCell
        }
    }

    @Exclude
    fun getCellType(x: Int, y: Int): String {
        return getCell(x, y)?.type ?: "empty"
    }

    @Exclude
    fun isCellBuildable(x: Int, y: Int): Boolean {
        return getCell(x, y)?.isBuildable() ?: false
    }

    @Exclude
    fun getCellPosition(x: Int, y: Int): Int {
        return y * width + x
    }

    @Exclude
    fun isValidPosition(x: Int, y: Int): Boolean {
        return x in 0 until width && y in 0 until height
    }

    // Добавляем deepCopy для общей карты
    fun deepCopy(): GameMap {
        return GameMap(
            width = this.width,
            height = this.height,
            cells = this.cells.map { it.copy() }.toMutableList()
        )
    }
}