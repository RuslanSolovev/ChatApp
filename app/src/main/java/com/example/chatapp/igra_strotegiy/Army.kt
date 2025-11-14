package com.example.chatapp.igra_strotegiy

import com.google.firebase.database.IgnoreExtraProperties

@IgnoreExtraProperties
data class Army(
    var id: String = "",
    var units: MutableList<GameUnit> = mutableListOf(),
    var position: Position = Position(0, 0),
    var hasMovedThisTurn: Boolean = false,
    var carriedArmy: Army? = null  // перевозимая армия
) {
    constructor() : this("", mutableListOf(), Position(0, 0), false)

    fun totalAttackPower(): Int = units.filter { it.health > 0 }.sumOf { it.attackPower }
    fun isAlive(): Boolean = units.any { it.health > 0 }
    fun totalHealth(): Int = units.sumOf { it.health }

    fun isCompletelyDestroyed(): Boolean = units.isEmpty() || units.all { it.health <= 0 }

    fun removeDeadUnits() {
        units.removeIf { it.health <= 0 }
    }

    // 🔥 ДОБАВИТЬ МЕТОД ДЛЯ ПРОВЕРКИ ЯВЛЯЕТСЯ ЛИ ТРАНСПОРТОМ
    fun isTransport(): Boolean = units.size == 1 && units[0] is GameUnit.TransportBarge

    // 🔥 ДОБАВИТЬ МЕТОД ДЛЯ ПРОВЕРКИ ЯВЛЯЕТСЯ ЛИ МОРСКОЙ
    fun isNaval(): Boolean {
        return units.isNotEmpty() && units.any { unit ->
            unit is GameUnit.FishingBoat ||
                    unit is GameUnit.WarGalley ||
                    unit is GameUnit.TransportBarge
        }
    }
}