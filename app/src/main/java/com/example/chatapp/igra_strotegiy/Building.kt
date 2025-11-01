package com.example.chatapp.igra_strotegiy

import com.google.firebase.database.IgnoreExtraProperties

@IgnoreExtraProperties
sealed class Building {
    abstract val name: String
    abstract val type: String
    abstract val buildCost: Resource
    var level: Int = 1
    var health: Int = 0
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
            gold = buildCost.gold * level * 2,
            iron = buildCost.iron * level * 2,
            coal = buildCost.coal * level * 2,
            oil = buildCost.oil * level * 2,
            energy = buildCost.energy * level * 2
        )
    }

    // === ЗДАНИЯ ===

    @IgnoreExtraProperties
    class Hut : Building() {
        override val name: String = "Хижина"
        override val type: String = "hut"
        override val buildCost: Resource = Resource(wood = 30, food = 20)
    }

    @IgnoreExtraProperties
    class Well : Building() {
        override val name: String = "Колодец"
        override val type: String = "well"
        override val buildCost: Resource = Resource(wood = 25, water = 15)
    }

    @IgnoreExtraProperties
    class Sawmill : Building() {
        override val name: String = "Лесопилка"
        override val type: String = "sawmill"
        override val buildCost: Resource = Resource(wood = 40)
    }

    @IgnoreExtraProperties
    class FishingHut : Building() {
        override val name: String = "Рыболовная хижина"
        override val type: String = "fishing_hut"
        override val buildCost: Resource = Resource(wood = 35, water = 15)
    }

    @IgnoreExtraProperties
    class Farm : Building() {
        override val name: String = "Ферма"
        override val type: String = "farm"
        override val buildCost: Resource = Resource(wood = 40, stone = 20, food = 15)
    }

    @IgnoreExtraProperties
    class Quarry : Building() {
        override val name: String = "Каменоломня"
        override val type: String = "quarry"
        override val buildCost: Resource = Resource(stone = 30, food = 20)
    }

    @IgnoreExtraProperties
    class GoldMine : Building() {
        override val name: String = "Золотой рудник"
        override val type: String = "gold_mine"
        override val buildCost: Resource = Resource(stone = 40, wood = 30, food = 25)
    }

    @IgnoreExtraProperties
    class Forge : Building() {
        override val name: String = "Кузница"
        override val type: String = "forge"
        override val buildCost: Resource = Resource(stone = 50, gold = 25, wood = 20)
    }

    @IgnoreExtraProperties
    class IronMine : Building() {
        override val name: String = "Железный рудник"
        override val type: String = "iron_mine"
        override val buildCost: Resource = Resource(stone = 60, gold = 30, iron = 40)
    }

    @IgnoreExtraProperties
    class Castle : Building() {
        override val name: String = "Замок"
        override val type: String = "castle"
        override val buildCost: Resource = Resource(stone = 100, iron = 60, gold = 40)
    }

    @IgnoreExtraProperties
    class Blacksmith : Building() {
        override val name: String = "Оружейная"
        override val type: String = "blacksmith"
        override val buildCost: Resource = Resource(iron = 50, wood = 40, stone = 30)
    }

    @IgnoreExtraProperties
    class CoalMine : Building() {
        override val name: String = "Угольная шахта"
        override val type: String = "coal_mine"
        override val buildCost: Resource = Resource(iron = 60, stone = 50, coal = 80)
    }

    @IgnoreExtraProperties
    class OilRig : Building() {
        override val name: String = "Нефтяная вышка"
        override val type: String = "oil_rig"
        override val buildCost: Resource = Resource(iron = 80, coal = 60, oil = 100)
    }

    @IgnoreExtraProperties
    class Factory : Building() {
        override val name: String = "Фабрика"
        override val type: String = "factory"
        override val buildCost: Resource = Resource(coal = 70, iron = 90, oil = 50)
    }

    @IgnoreExtraProperties
    class PowerPlant : Building() {
        override val name: String = "Электростанция"
        override val type: String = "power_plant"
        override val buildCost: Resource = Resource(coal = 120, oil = 80, iron = 60)
    }

    @IgnoreExtraProperties
    class SolarPlant : Building() {
        override val name: String = "Солнечная станция"
        override val type: String = "solar_plant"
        override val buildCost: Resource = Resource(energy = 150, iron = 60, oil = 40)
    }

    @IgnoreExtraProperties
    class NuclearPlant : Building() {
        override val name: String = "Ядерный реактор"
        override val type: String = "nuclear_plant"
        override val buildCost: Resource = Resource(energy = 300, oil = 100, iron = 80)
    }

    @IgnoreExtraProperties
    class RoboticsLab : Building() {
        override val name: String = "Робо-лаборатория"
        override val type: String = "robotics_lab"
        override val buildCost: Resource = Resource(energy = 400, gold = 100, iron = 70)
    }

    @IgnoreExtraProperties
    class Barracks : Building() {
        override val name: String = "Казармы"
        override val type: String = "barracks"
        override val buildCost: Resource = Resource(wood = 40, food = 20)
    }

    @IgnoreExtraProperties
    class ResearchCenter : Building() {
        override val name: String = "Научный центр"
        override val type: String = "research_center"
        override val buildCost: Resource = Resource(wood = 50, food = 25)
    }

    @IgnoreExtraProperties
    class TownHall : Building() {
        override val name: String = "Ратуша"
        override val type: String = "town_hall"
        override val buildCost: Resource = Resource(wood = 60, food = 30, water = 20)
        override val maxHealth: Int get() = 200
    }
}