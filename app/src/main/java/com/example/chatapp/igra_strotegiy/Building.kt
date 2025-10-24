package com.example.chatapp.igra_strotegiy

sealed class Building {
    abstract val name: String
    abstract val type: String // ← НОВОЕ: уникальный англ. идентификатор
    abstract val buildCost: Resource
    var level: Int = 1
    var health: Int = maxHealth

    open val maxHealth: Int get() = 100 * level

    fun takeDamage(damage: Int) {
        health -= damage
        if (health < 0) health = 0
    }

    fun isDestroyed(): Boolean = health <= 0

    fun upgradeCost(): Resource {
        return Resource(
            wood = buildCost.wood * level * 2,
            food = buildCost.food * level * 2,
            water = buildCost.water * level * 2,
            stone = buildCost.stone * level * 2,
            gold = buildCost.gold * level * 2
        )
    }

    data class Sawmill(
        override val name: String = "Лесопилка",
        override val type: String = "sawmill",
        override val buildCost: Resource = Resource(wood = 10)
    ) : Building()

    data class Farm(
        override val name: String = "Ферма",
        override val type: String = "farm",
        override val buildCost: Resource = Resource(wood = 8, water = 2)
    ) : Building()

    data class Well(
        override val name: String = "Колодец",
        override val type: String = "well",
        override val buildCost: Resource = Resource(wood = 5, stone = 3)
    ) : Building()

    data class Quarry(
        override val name: String = "Каменоломня",
        override val type: String = "quarry",
        override val buildCost: Resource = Resource(water = 2, stone = 10)
    ) : Building()

    data class GoldMine(
        override val name: String = "Золотая шахта",
        override val type: String = "gold_mine",
        override val buildCost: Resource = Resource(wood = 10, food = 5, water = 5, stone = 10)
    ) : Building()

    data class Barracks(
        override val name: String = "Казармы",
        override val type: String = "barracks",
        override val buildCost: Resource = Resource(wood = 15, food = 5)
    ) : Building()

    data class TownHall(
        override val name: String = "Ратуша",
        override val type: String = "town_hall",
        override val buildCost: Resource = Resource(wood = 20, food = 10, water = 5, stone = 15)
    ) : Building() {
        override val maxHealth: Int get() = 200 * level
    }
}