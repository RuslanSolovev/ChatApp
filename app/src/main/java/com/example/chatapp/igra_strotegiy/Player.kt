// Player.kt
package com.example.chatapp.igra_strotegiy


data class Player(
    var era: Era = Era.STONE_AGE,
    var completedResearch: MutableList<String> = mutableListOf(),
    var resources: Resource = Resource(food = 1000, water = 1000, wood = 1000),
    var buildings: MutableList<Building> = mutableListOf(),
    var units: MutableList<GameUnit> = mutableListOf(),
    var townHallPosition: Position = Position(0, 0)
) {
    fun addBuilding(building: Building) {
        building.health = building.maxHealth
        buildings.add(building)
    }

    fun collectResources() {
        var income = Resource()

        if (buildings.isEmpty()) {
            return
        }

        buildings.forEach { building ->
            if (building.isDestroyed()) return@forEach

            val stoneBonus = if (completedResearch.contains("Каменные инструменты")) 1.2 else 1.0
            val goldBonus = if (completedResearch.contains("Денежная система")) 1.25 else 1.0
            val energyBonus = if (completedResearch.contains("Электричество")) 1.4 else 1.0

            when (building) {
                // Каменный век
                is Building.Hut -> income.food += (2 * building.level).toInt()
                is Building.Well -> income.water += (3 * building.level).toInt()
                is Building.Sawmill -> income.wood += (4 * building.level).toInt()
                is Building.FishingHut -> income.food += (3 * building.level).toInt()

                // Бронзовый век
                is Building.Farm -> income.food += (5 * building.level).toInt()
                is Building.Quarry -> income.stone += (4 * building.level * stoneBonus).toInt()
                is Building.GoldMine -> income.gold += (3 * building.level * goldBonus).toInt()
                is Building.Forge -> income.stone += (2 * building.level).toInt()

                // Средневековье
                is Building.IronMine -> income.iron += (4 * building.level).toInt()
                is Building.Castle -> income.stone += (3 * building.level).toInt()
                is Building.Blacksmith -> income.iron += (2 * building.level).toInt()

                // Индустриальная
                is Building.CoalMine -> income.coal += (5 * building.level).toInt()
                is Building.OilRig -> income.oil += (4 * building.level).toInt()
                is Building.Factory -> income.iron += (3 * building.level).toInt()
                is Building.PowerPlant -> income.energy += (6 * building.level * energyBonus).toInt()

                // Футуристическая
                is Building.SolarPlant -> income.energy += (8 * building.level).toInt()
                is Building.NuclearPlant -> income.energy += (12 * building.level).toInt()
                // УБИРАЕМ генерацию researchPoints из RoboticsLab

                // Общие
                is Building.ResearchCenter -> {
                    // Научный центр теперь дает бонус к производству ресурсов
                    income.food += (1 * building.level).toInt()
                    income.wood += (1 * building.level).toInt()
                    income.water += (1 * building.level).toInt()
                }
                is Building.TownHall -> {
                    income.food += 1
                    income.wood += 1
                    income.water += 1
                }
                else -> {}
            }
        }

        resources.add(income)
    }

    fun completeResearch(research: Research): Boolean {
        // Теперь проверяем ресурсы вместо researchPoints
        if (resources.hasEnough(research.cost, era) && era == research.era) {
            resources.subtract(research.cost)
            completedResearch.add(research.name)
            return true
        }
        return false
    }
}