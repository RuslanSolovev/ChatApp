package com.example.chatapp.igra_strotegiy

import com.google.firebase.database.IgnoreExtraProperties

@IgnoreExtraProperties
sealed class GameUnit {
    abstract val name: String
    abstract var health: Int
    abstract val attackPower: Int

    @Transient // ← ГЛАВНОЕ ИЗМЕНЕНИЕ
    open val type: String = this::class.simpleName.toString()

    // Конструктор без аргументов для Firebase
    constructor()

    // Каменный век
    @IgnoreExtraProperties
    data class Caveman(
        override val name: String = "Пещерный человек",
        override var health: Int = 20,
        override val attackPower: Int = 5
    ) : GameUnit() {
        override val type: String = "Caveman"
        constructor() : this("Пещерный человек", 20, 5)
    }

    @IgnoreExtraProperties
    data class Hunter(
        override val name: String = "Охотник",
        override var health: Int = 15,
        override val attackPower: Int = 8
    ) : GameUnit() {
        override val type: String = "Hunter"
        constructor() : this("Охотник", 15, 8)
    }

    @IgnoreExtraProperties
    data class MammothRider(
        override val name: String = "Всадник на мамонте",
        override var health: Int = 50,
        override val attackPower: Int = 12
    ) : GameUnit() {
        override val type: String = "MammothRider"
        constructor() : this("Всадник на мамонте", 50, 12)
    }

    // Бронзовый век
    @IgnoreExtraProperties
    data class Swordsman(
        override val name: String = "Мечник",
        override var health: Int = 30,
        override val attackPower: Int = 10
    ) : GameUnit() {
        override val type: String = "Swordsman"
        constructor() : this("Мечник", 30, 10)
    }

    @IgnoreExtraProperties
    data class BronzeArcher(
        override val name: String = "Лучник",
        override var health: Int = 25,
        override val attackPower: Int = 12
    ) : GameUnit() {
        override val type: String = "BronzeArcher"
        constructor() : this("Лучник", 25, 12)
    }

    @IgnoreExtraProperties
    data class Chariot(
        override val name: String = "Боевая колесница",
        override var health: Int = 60,
        override val attackPower: Int = 15
    ) : GameUnit() {
        override val type: String = "Chariot"
        constructor() : this("Боевая колесница", 60, 15)
    }

    // Средневековье
    @IgnoreExtraProperties
    data class Knight(
        override val name: String = "Рыцарь",
        override var health: Int = 40,
        override val attackPower: Int = 15
    ) : GameUnit() {
        override val type: String = "Knight"
        constructor() : this("Рыцарь", 40, 15)
    }

    @IgnoreExtraProperties
    data class Crossbowman(
        override val name: String = "Арбалетчик",
        override var health: Int = 35,
        override val attackPower: Int = 18
    ) : GameUnit() {
        override val type: String = "Crossbowman"
        constructor() : this("Арбалетчик", 35, 18)
    }

    @IgnoreExtraProperties
    data class Ram(
        override val name: String = "Таран",
        override var health: Int = 80,
        override val attackPower: Int = 8
    ) : GameUnit() {
        override val type: String = "Ram"
        constructor() : this("Таран", 80, 8)
    }

    // Индустриальная эра
    @IgnoreExtraProperties
    data class Soldier(
        override val name: String = "Солдат",
        override var health: Int = 45,
        override val attackPower: Int = 20
    ) : GameUnit() {
        override val type: String = "Soldier"
        constructor() : this("Солдат", 45, 20)
    }

    @IgnoreExtraProperties
    data class Artillery(
        override val name: String = "Артиллерия",
        override var health: Int = 60,
        override val attackPower: Int = 25
    ) : GameUnit() {
        override val type: String = "Artillery"
        constructor() : this("Артиллерия", 60, 25)
    }


    @IgnoreExtraProperties
    data class Tank(
        override val name: String = "Танк",
        override var health: Int = 100,
        override val attackPower: Int = 30
    ) : GameUnit() {
        override val type: String = "Tank"
        constructor() : this("Танк", 100, 30)
    }

    // Футуристическая эра
    @IgnoreExtraProperties
    data class Drone(
        override val name: String = "Боевой дрон",
        override var health: Int = 30,
        override val attackPower: Int = 22
    ) : GameUnit() {
        override val type: String = "Drone"
        constructor() : this("Боевой дрон", 30, 22)
    }

    @IgnoreExtraProperties
    data class Mech(
        override val name: String = "Боевой мех",
        override var health: Int = 120,
        override val attackPower: Int = 35
    ) : GameUnit() {
        override val type: String = "Mech"
        constructor() : this("Боевой мех", 120, 35)
    }

    @IgnoreExtraProperties
    data class LaserCannon(
        override val name: String = "Лазерная пушка",
        override var health: Int = 70,
        override val attackPower: Int = 40
    ) : GameUnit() {
        override val type: String = "LaserCannon"
        constructor() : this("Лазерная пушка", 70, 40)
    }
}