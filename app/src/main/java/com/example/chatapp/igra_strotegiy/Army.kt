package com.example.chatapp.igra_strotegiy

import com.google.firebase.database.IgnoreExtraProperties

@IgnoreExtraProperties
data class Army(
    var id: String = "",
    var units: MutableList<GameUnit> = mutableListOf(),
    var position: Position = Position(0, 0),
    var hasMovedThisTurn: Boolean = false
) {
    constructor() : this("", mutableListOf(), Position(0, 0), false)

    fun totalAttackPower(): Int = units.filter { it.health > 0 }.sumOf { it.attackPower }
    fun isAlive(): Boolean = units.any { it.health > 0 }
}