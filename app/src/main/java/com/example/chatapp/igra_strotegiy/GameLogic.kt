package com.example.chatapp.igra_strotegiy

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken

class GameLogic {
    val player = Player()
    val enemy = Enemy()
    val gameMap = GameMap()

    fun buildBuildingOnMap(building: Building, x: Int, y: Int): Boolean {
        val cell = gameMap.getCell(x, y) ?: return false
        if (cell.type != "empty") return false

        if (player.resources.wood < building.cost) return false

        player.resources.wood -= building.cost
        player.addBuilding(building)
        gameMap.setCellType(x, y, building.name.lowercase().replace(" ", "_"))
        return true
    }

    fun hireUnit(unit: GameUnit): Boolean {
        return player.hireUnit(unit)
    }

    fun nextTurn() {
        player.collectResources()
    }

    fun getPlayerInfo(): String {
        return "Дерево: ${player.resources.wood}, Здания: ${player.buildings.size}, Юниты: ${player.units.size}"
    }

    fun attackEnemy(): String {
        var totalDamage = 0
        player.units.forEach { unit ->
            if (unit.health > 0) {
                totalDamage += unit.attackPower
            }
        }

        enemy.health -= totalDamage

        return if (enemy.health <= 0) {
            "Вы победили противника!"
        } else {
            val counterAttack = enemy.attack(player)
            if (counterAttack) {
                "Противник атакует! Осталось здоровья: ${enemy.health}"
            } else {
                "Ваши юниты уничтожены! Противник атакует!"
            }
        }
    }

    fun saveState(): String {
        val gson = getGsonInstance()
        return gson.toJson(this)
    }

    fun loadState(json: String) {
        val gson = getGsonInstance()
        val savedLogic = gson.fromJson<GameLogic>(json, object : TypeToken<GameLogic>() {}.type)
        player.resources = savedLogic.player.resources
        player.buildings.clear()
        player.buildings.addAll(savedLogic.player.buildings)
        player.units.clear()
        player.units.addAll(savedLogic.player.units)
        gameMap.cells.clear()
        gameMap.cells.addAll(savedLogic.gameMap.cells)
        enemy.health = savedLogic.enemy.health
    }

    companion object {
        private fun getGsonInstance(): Gson {
            return GsonBuilder()
                .registerTypeAdapter(Building::class.java, BuildingTypeAdapter)
                .registerTypeAdapter(GameUnit::class.java, GameUnitTypeAdapter)
                .create()
        }
    }
}