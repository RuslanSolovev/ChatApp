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
    fun totalHealth(): Int = units.sumOf { it.health }

    // 🔥 НОВЫЙ МЕТОД ДЛЯ ПРОВЕРКИ ПОЛНОГО УНИЧТОЖЕНИЯ
    fun isCompletelyDestroyed(): Boolean = units.isEmpty() || units.all { it.health <= 0 }

    // 🔥 МЕТОД ДЛЯ ОЧИСТКИ МЕРТВЫХ ЮНИТОВ
    fun removeDeadUnits() {
        units.removeIf { it.health <= 0 }
    }
}