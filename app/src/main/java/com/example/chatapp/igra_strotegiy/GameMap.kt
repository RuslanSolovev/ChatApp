package com.example.chatapp.igra_strotegiy

import com.google.firebase.database.Exclude
import com.google.firebase.database.IgnoreExtraProperties
import kotlin.random.Random

@IgnoreExtraProperties
data class GameMap(
    var width: Int = 9,
    var height: Int = 9,
    var cells: MutableList<MapCell> = mutableListOf()
) {
    // Конструктор без аргументов для Firebase
    constructor() : this(9, 9, mutableListOf())

    init {
        if (cells.isEmpty()) {
            initializeMap()
        }
    }

    private fun initializeMap() {
        cells.clear()
        for (y in 0 until height) {
            for (x in 0 until width) {
                cells.add(MapCell("empty", x, y))
            }
        }
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