package com.example.chatapp.igra_strotegiy

import com.google.firebase.database.Exclude
import com.google.firebase.database.IgnoreExtraProperties

@IgnoreExtraProperties
data class MapCell(
    var type: String = "empty",
    var x: Int = 0,
    var y: Int = 0
) {
    // Конструктор без аргументов для Firebase
    constructor() : this("empty", 0, 0)

    // Явно объявленное поле buildable для Firebase
    var buildable: Boolean = false
        @Exclude get
        @Exclude set

    fun copy(type: String = this.type): MapCell {
        val cell = MapCell(type, x, y)
        cell.buildable = isBuildable()
        return cell
    }

    fun isBuildable(): Boolean {
        buildable = type == "empty"
        return buildable
    }
}