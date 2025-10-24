package com.example.chatapp.igra_strotegiy

data class EnemyBase(
    val x: Int,
    val y: Int,
    var health: Int = 200,
    val maxHealth: Int = 200
) {
    fun isDestroyed() = health <= 0
    fun takeDamage(damage: Int) {
        health -= damage
        if (health < 0) health = 0
    }
}