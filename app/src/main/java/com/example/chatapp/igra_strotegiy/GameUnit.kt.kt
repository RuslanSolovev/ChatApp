package com.example.chatapp.igra_strotegiy

sealed class GameUnit {
    abstract val name: String
    abstract var health: Int
    abstract val attackPower: Int

    data class Soldier(
        override val name: String = "Soldier",
        override var health: Int = 20,
        override val attackPower: Int = 5
    ) : GameUnit()

    data class Archer(
        override val name: String = "Archer",
        override var health: Int = 15,
        override val attackPower: Int = 8
    ) : GameUnit()

    data class Tank(
        override val name: String = "Tank",
        override var health: Int = 30,
        override val attackPower: Int = 3
    ) : GameUnit()
}