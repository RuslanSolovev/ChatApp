package com.example.chatapp.igra_strotegiy

data class Player(
    var resources: Resource = Resource(wood = 100),
    var buildings: MutableList<Building> = mutableListOf(),
    var units: MutableList<GameUnit> = mutableListOf() // Замените здесь
) {
    fun addBuilding(building: Building) {
        buildings.add(building)
    }

    fun collectResources() {
        val mineCount = buildings.count { it is Building.Mine }
        resources.wood += mineCount * 5
    }

    fun hireUnit(unit: GameUnit): Boolean { // Замените здесь
        return when (unit) {
            is GameUnit.Soldier -> {
                if (resources.wood >= 10) {
                    resources.wood -= 10
                    units.add(unit)
                    true
                } else false
            }
            is GameUnit.Archer -> {
                if (resources.wood >= 15) {
                    resources.wood -= 15
                    units.add(unit)
                    true
                } else false
            }
            is GameUnit.Tank -> {
                if (resources.wood >= 25) {
                    resources.wood -= 25
                    units.add(unit)
                    true
                } else false
            }
        }
    }
}