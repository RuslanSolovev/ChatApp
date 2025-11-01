package com.example.chatapp.igra_strotegiy

data class EnemyBase(
    var x: Int = 0,
    var y: Int = 0,
    var health: Int = 200,
    var maxHealth: Int = 200
) {
    // Конструктор без аргументов для Firebase
    constructor() : this(0, 0, 200, 200)

    fun isDestroyed() = health <= 0
    fun takeDamage(damage: Int) {
        health -= damage
        if (health < 0) health = 0
    }
}