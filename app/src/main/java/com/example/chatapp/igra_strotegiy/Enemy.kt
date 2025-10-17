package com.example.chatapp.igra_strotegiy

data class Enemy(
    val name: String = "Enemy",
    var health: Int = 50,
    val attackPower: Int = 10
) {
    fun attack(player: Player): Boolean {
        var attacked = false
        player.units.forEach { unit ->
            if (unit.health > 0) {
                unit.health -= attackPower
                attacked = true
                if (unit.health <= 0) {
                    player.units.remove(unit)
                }
            }
        }
        return attacked
    }
}