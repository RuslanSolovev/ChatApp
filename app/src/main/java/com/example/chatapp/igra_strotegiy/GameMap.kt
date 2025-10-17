package com.example.chatapp.igra_strotegiy

data class GameMap(val width: Int = 5, val height: Int = 5) {
    val cells = mutableListOf<MapCell>()

    init {
        for (x in 0 until width) {
            for (y in 0 until height) {
                cells.add(MapCell("empty", x, y))
            }
        }
        // База в центре
        val centerIndex = width * (height / 2) + (width / 2)
        cells[centerIndex] = cells[centerIndex].copy(type = "base")
    }

    fun getCell(x: Int, y: Int): MapCell? {
        if (x < 0 || x >= width || y < 0 || y >= height) return null
        return cells[y * width + x]
    }

    fun setCellType(x: Int, y: Int, type: String) {
        val cell = getCell(x, y)
        cell?.let { cells[cells.indexOf(it)] = it.copy(type = type) }
    }
}