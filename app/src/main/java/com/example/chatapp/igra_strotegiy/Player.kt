package com.example.chatapp.igra_strotegiy

data class Player(
    var resources: Resource = Resource(),
    var buildings: MutableList<Building> = mutableListOf(),
    var units: MutableList<GameUnit> = mutableListOf()
) {
    fun addBuilding(building: Building) {
        building.health = building.maxHealth
        buildings.add(building)
    }

    fun collectResources() {
        var income = Resource() // все значения = 0
        buildings.forEach { building ->
            if (building.isDestroyed()) return@forEach
            when (building) {
                is Building.Sawmill -> income.wood += 4 * building.level
                is Building.Farm -> income.food += 2 * building.level
                is Building.Well -> income.water += 3 * building.level
                is Building.Quarry -> income.stone += 3 * building.level
                is Building.GoldMine -> income.gold += 1 * building.level
                // TownHall, Barracks и другие — НЕ дают ресурсы
                else -> {}
            }
        }
        resources.add(income)
    }
}