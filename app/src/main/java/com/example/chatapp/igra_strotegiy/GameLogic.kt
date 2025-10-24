package com.example.chatapp.igra_strotegiy

import com.google.gson.Gson
import com.google.gson.GsonBuilder

class GameLogic {
    val player = Player()
    val enemies = mutableListOf<Enemy>()
    val gameMap = GameMap()
    private var turnCount = 0
    val enemyPositions = mutableMapOf<Int, Pair<Int, Int>>()
    var enemyBase: EnemyBase? = null
    var lastAttackMessage: String = ""

    fun initializeEnemiesIfNeeded() {
        if (enemies.isEmpty()) {
            // Враги
            enemies.add(Enemy(id = 1, name = "Гоблин", health = 40, attackPower = 8))
            enemies.add(Enemy(id = 2, name = "Орк", health = 60, attackPower = 12))
            enemyPositions[1] = Pair(0, 0)
            enemyPositions[2] = Pair(8, 0)
            enemyBase = EnemyBase(x = 0, y = 8)

            for ((_, pos) in enemyPositions) {
                gameMap.setCellType(pos.first, pos.second, "enemy")
            }
            gameMap.setCellType(enemyBase!!.x, enemyBase!!.y, "enemy_base")

            // 🔥 Ратуша в центре (вместо "базы")
            val centerX = gameMap.width / 2
            val centerY = gameMap.height / 2
            val townHall = Building.TownHall()
            player.addBuilding(townHall)
            gameMap.setCellType(centerX, centerY, townHall.type)
        }
    }

    fun buildBuildingOnMap(building: Building, x: Int, y: Int): Boolean {
        val cell = gameMap.getCell(x, y) ?: return false
        if (!cell.isBuildable()) return false
        if (enemyPositions.values.any { it.first == x && it.second == y }) return false
        if (enemyBase?.x == x && enemyBase?.y == y) return false
        if (!player.resources.hasEnough(building.buildCost)) return false

        if (building is Building.TownHall) {
            return false
        }

        player.resources.subtract(building.buildCost)
        player.addBuilding(building)
        gameMap.setCellType(x, y, building.type) // ← используем type
        return true
    }

    private fun getUnitCost(unit: GameUnit): Resource {
        return when (unit) {
            is GameUnit.Soldier -> Resource(wood = 10, food = 5)
            is GameUnit.Archer -> Resource(wood = 15, food = 5, stone = 5)
            is GameUnit.Tank -> Resource(wood = 20, food = 10, water = 5, stone = 15, gold = 5)
            else -> Resource()
        }
    }

    fun hireUnitFromBarracks(unit: GameUnit): Boolean {
        if (player.buildings.none { it is Building.Barracks && !it.isDestroyed() }) return false
        val cost = getUnitCost(unit)
        if (!player.resources.hasEnough(cost)) return false
        player.resources.subtract(cost)
        player.units.add(unit)
        return true
    }

    fun upgradeBuilding(building: Building): Boolean {
        if (building.level >= 10) return false
        val cost = building.upgradeCost()
        if (!player.resources.hasEnough(cost)) return false
        player.resources.subtract(cost)
        building.level++
        building.health = building.maxHealth
        return true
    }

    fun nextTurn() {
        turnCount++
        player.collectResources()
        lastAttackMessage = ""

        if (turnCount % 3 == 0) {
            val messages = mutableListOf<String>()
            var attackedBase = false

            enemies.filter { it.isAlive() }.forEach { enemy ->
                val result = enemy.attack(player)
                when (result) {
                    is AttackResult.UnitsAttacked -> {
                        messages.add("🛡️ ${enemy.name} атаковал юнитов!")
                    }
                    is AttackResult.UnitsKilled -> {
                        val killed = result.unitTypes.joinToString(", ")
                        messages.add("🩸 ${enemy.name} убил: $killed!")
                    }
                    is AttackResult.BaseAttacked -> {
                        attackedBase = true
                        messages.add("🔥 ${enemy.name} атаковал РАТУШУ! -${result.damage} HP")
                    }
                    AttackResult.NoTarget -> {
                        // Ничего не делаем
                    }
                }
            }

            if (messages.isNotEmpty()) {
                val title = if (attackedBase) "💥 НАПАДЕНИЕ НА БАЗУ!" else "⚔️ Враги атаковали"
                lastAttackMessage = "$title\n${messages.joinToString("\n")}"
            }
        }
    }

    fun getPlayerInfo(): String {
        return "Ресурсы: ${player.resources}\n" +
                "Здания: ${player.buildings.size}, Юниты: ${player.units.size}"
    }

    fun attackTarget(x: Int, y: Int): String {
        val enemyEntry = enemyPositions.entries.find { (_, pos) -> pos.first == x && pos.second == y }
        if (enemyEntry != null) {
            val enemy = enemies.find { it.id == enemyEntry.key } ?: return "Ошибка врага"
            if (!enemy.isAlive()) return "Враг уже мёртв!"

            val damage = player.units.filter { it.health > 0 }.sumOf { it.attackPower }
            if (damage <= 0) return "Нет живых юнитов для атаки!"

            enemy.health -= damage
            val msg = StringBuilder("Атакован ${enemy.name}! Нанесено $damage урона. HP: ${enemy.health}")

            if (!enemy.isAlive()) {
                enemyPositions.remove(enemyEntry.key)
                clearEnemyFromMap(x, y)
                msg.append(" 💀 Враг убит!")
            }

            enemy.attack(player)
            return msg.toString()
        }

        if (enemyBase?.x == x && enemyBase?.y == y && !enemyBase!!.isDestroyed()) {
            val damage = player.units.filter { it.health > 0 }.sumOf { it.attackPower }
            if (damage <= 0) return "Нет живых юнитов для атаки!"

            enemyBase!!.takeDamage(damage)
            val msg = StringBuilder("Атакована вражеская база! Нанесено $damage урона. HP: ${enemyBase!!.health}/${enemyBase!!.maxHealth}")

            if (enemyBase!!.isDestroyed()) {
                gameMap.setCellType(x, y, "empty")
                msg.append(" 💥 База уничтожена! ПОБЕДА!")
            }

            return msg.toString()
        }

        return "Нет цели для атаки"
    }

    private fun clearEnemyFromMap(x: Int, y: Int) {
        val hasOtherEnemy = enemyPositions.values.any { it.first == x && it.second == y }
        if (!hasOtherEnemy) {
            gameMap.setCellType(x, y, "empty")
        }
    }

    // ✅ Исправлено: поражение только если Ратуша была и уничтожена
    fun isPlayerWon() = enemyBase?.isDestroyed() == true
    fun isPlayerDefeated(): Boolean {
        val townHalls = player.buildings.filterIsInstance<Building.TownHall>()
        return townHalls.isNotEmpty() && townHalls.all { it.isDestroyed() }
    }

    fun saveState(): String = getGson().toJson(this)

    fun loadState(json: String) {
        val saved = getGson().fromJson(json, GameLogic::class.java)
        player.resources = saved.player.resources
        player.buildings.clear()
        player.buildings.addAll(saved.player.buildings)
        player.units.clear()
        player.units.addAll(saved.player.units)
        gameMap.cells.clear()
        gameMap.cells.addAll(saved.gameMap.cells)
        enemies.clear()
        enemies.addAll(saved.enemies)
        enemyPositions.clear()
        enemyPositions.putAll(saved.enemyPositions)
        enemyBase = saved.enemyBase
        turnCount = saved.turnCount
        lastAttackMessage = saved.lastAttackMessage
    }

    companion object {
        private fun getGson(): Gson = GsonBuilder()
            .registerTypeAdapter(Building::class.java, BuildingTypeAdapter)
            .registerTypeAdapter(GameUnit::class.java, GameUnitTypeAdapter)
            .create()
    }
}