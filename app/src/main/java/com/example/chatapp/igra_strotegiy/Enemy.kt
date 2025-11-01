package com.example.chatapp.igra_strotegiy

data class Enemy(
    var id: Int = 0,
    var name: String = "Enemy",
    var health: Int = 50,
    var attackPower: Int = 10
) {
    // Конструктор без аргументов для Firebase
    constructor() : this(0, "Enemy", 50, 10)

    fun attack(player: Player): AttackResult {
        val aliveUnits = player.units.filter { it.health > 0 }

        if (aliveUnits.isNotEmpty()) {
            aliveUnits.forEach { unit ->
                unit.health -= attackPower
            }

            val deadUnits = player.units.filter { it.health <= 0 }
            val deadNames = deadUnits.map { it.name }.groupBy { it }.map { "${it.key} (${it.value.size})" }

            player.units.removeIf { it.health <= 0 }

            if (deadUnits.isNotEmpty()) {
                return AttackResult.UnitsKilled(deadNames)
            } else {
                return AttackResult.UnitsAttacked
            }
        }

        val townHall = player.buildings.find { it is Building.TownHall && !it.isDestroyed() }
        if (townHall != null) {
            val oldHealth = townHall.health
            townHall.takeDamage(attackPower)
            val damageDealt = oldHealth - townHall.health
            return AttackResult.BaseAttacked(damageDealt, townHall.health, townHall.maxHealth)
        }

        return AttackResult.NoTarget
    }

    fun isAlive(): Boolean = health > 0
}