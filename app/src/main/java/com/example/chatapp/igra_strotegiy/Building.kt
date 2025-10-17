package com.example.chatapp.igra_strotegiy


sealed class Building {
    abstract val name: String
    abstract val cost: Int

    data class Barracks(
        override val name: String = "Barracks",
        override val cost: Int = 10
    ) : Building()

    data class TownHall(
        override val name: String = "Town Hall",
        override val cost: Int = 15
    ) : Building()

    data class Mine(
        override val name: String = "Mine",
        override val cost: Int = 20
    ) : Building()
}