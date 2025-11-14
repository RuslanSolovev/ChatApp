package com.example.chatapp.igra_strotegiy

import com.google.firebase.database.Exclude
import com.google.firebase.database.IgnoreExtraProperties
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlin.math.abs

@IgnoreExtraProperties
data class GameLogic(
    val player: Player = Player(),
    val enemies: MutableList<Enemy> = mutableListOf(),
    var gameMap: GameMap = GameMap(),
    private var turnCount: Int = 0,
    // ИСПРАВЛЕНО: Используем String ключи вместо Int для Firebase
    val enemyPositions: MutableMap<String, Pair<Int, Int>> = mutableMapOf(),
    var enemyBase: EnemyBase? = null,
    var lastAttackMessage: String = "",

    // В GameLogic.kt, в тело класса
    var armies: MutableList<Army> = mutableListOf(),

    var currentEvent: String? = null,
    var townHallPosition: Position = Position(0, 0)
) {
    var _availableResearch: List<String> = emptyList()
        @Exclude get
        @Exclude set
    var _playerInfo: String = ""
        @Exclude get
        @Exclude set
    var _playerDefeated: Boolean = false
        @Exclude get
        @Exclude set
    var _playerWon: Boolean = false
        @Exclude get
        @Exclude set

    @IgnoreExtraProperties
    data class EraRequirement(
        val resources: Resource,
        val completedResearch: Int
    )

    fun initializeEnemiesIfNeeded() {
        if (enemies.isEmpty()) {
            enemies.add(Enemy(id = 1, name = "Гоблин", health = 40, attackPower = 8))
            enemies.add(Enemy(id = 2, name = "Орк", health = 60, attackPower = 12))
            // ИСПРАВЛЕНО: Используем строковые ключи
            enemyPositions["1"] = Pair(0, 0)
            enemyPositions["2"] = Pair(8, 0)
            enemyBase = EnemyBase(x = 0, y = 8)
            for ((_, pos) in enemyPositions) {
                gameMap.setCellType(pos.first, pos.second, "enemy")
            }
            gameMap.setCellType(enemyBase!!.x, enemyBase!!.y, "enemy_base")
            val centerX = gameMap.width / 2
            val centerY = gameMap.height / 2
            val townHall = Building.TownHall()
            player.addBuilding(townHall)
            gameMap.setCellType(centerX, centerY, townHall.type)
            updatePlayerInfo()
        }
    }

    // ИСПРАВЛЕННЫЙ метод attackTarget
    fun attackTarget(x: Int, y: Int): String {
        // Ищем врага по позиции
        val enemyEntry = enemyPositions.entries.find { (_, pos) -> pos.first == x && pos.second == y }
        if (enemyEntry != null) {
            // Конвертируем строковый ключ в Int для поиска врага
            val enemyId = enemyEntry.key.toIntOrNull()
            val enemy = enemies.find { it.id == enemyId } ?: return "Ошибка врага"
            if (!enemy.isAlive()) return "Враг уже мёртв!"
            val damage = player.units.filter { it.health > 0 }.sumOf { it.attackPower }
            if (damage <= 0) return "Нет живых юнитов для атаки!"
            enemy.health -= damage
            val msg = StringBuilder("Атакован ${enemy.name}! Нанесено $damage урона. HP: ${enemy.health}")
            if (!enemy.isAlive()) {
                // Удаляем по строковому ключу
                enemyPositions.remove(enemyEntry.key)
                clearEnemyFromMap(x, y)
                msg.append(" 💀 Враг убит!")
                player.resources.add(Resource(food = 15, wood = 10, water = 8))
                msg.append("\n🎁 Получено: 15 еды, 10 дерева, 8 воды")
            }
            val enemyAttackResult = enemy.attack(player)
            when (enemyAttackResult) {
                is AttackResult.UnitsKilled -> {
                    msg.append("\n${enemy.name} контратаковал: убил ${enemyAttackResult.unitTypes.joinToString(", ")}")
                }
                is AttackResult.BaseAttacked -> {
                    msg.append("\n${enemy.name} атаковал ратушу: -${enemyAttackResult.damage} HP")
                }
                else -> {}
            }
            updatePlayerInfo()
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
                val reward = when (player.era) {
                    Era.STONE_AGE -> Resource(food = 200, wood = 150, water = 100)
                    Era.BRONZE_AGE -> Resource(food = 300, wood = 200, water = 150, stone = 100, gold = 50)
                    Era.MIDDLE_AGES -> Resource(food = 400, wood = 250, water = 200, stone = 150, gold = 100, iron = 80)
                    Era.INDUSTRIAL -> Resource(food = 500, wood = 300, water = 250, stone = 200, gold = 150, iron = 120, coal = 100, oil = 80)
                    Era.FUTURE -> Resource(food = 600, wood = 350, water = 300, stone = 250, gold = 200, iron = 150, coal = 120, oil = 100, energy = 200)
                }
                player.resources.add(reward)
                msg.append("\n🏆 ПОБЕДА! Получена огромная награда!")
                _playerWon = true
            }
            updatePlayerInfo()
            return msg.toString()
        }
        return "Нет цели для атаки"
    }

    // ИНИЦИАЛИЗАЦИЯ ТОЛЬКО РАТУШИ ИГРОКА НА ОБЩЕЙ КАРТЕ (без очистки карты!)
    fun initializePlayerTownHallOnSharedMap(x: Int, y: Int) {
        val townHall = Building.TownHall()
        player.addBuilding(townHall)
        gameMap.setCellType(x, y, "town_hall")
    }

    fun loadArmyIntoTransport(transportArmyId: String, cargoArmyId: String): Boolean {
        val transport = armies.find { it.id == transportArmyId } ?: return false
        val cargo = armies.find { it.id == cargoArmyId } ?: return false

        // Проверка: транспорт — это TransportBarge
        if (transport.units.size != 1 || transport.units[0] !is GameUnit.TransportBarge) return false

        // Проверка: груз — сухопутная армия
        if (cargo.isNaval()) return false

        // 🔥 ИСПРАВЛЕНО: армия должна быть на СОСЕДНЕЙ клетке
        val dx = abs(transport.position.x - cargo.position.x)
        val dy = abs(transport.position.y - cargo.position.y)
        if (dx + dy != 1) return false

        // Транспорт не должен уже перевозить армию
        if (transport.carriedArmy != null) return false

        // Загружаем
        transport.carriedArmy = cargo
        armies.remove(cargo)
        return true
    }

    fun unloadArmyFromTransport(transportArmyId: String): Boolean {
        val transport = armies.find { it.id == transportArmyId } ?: return false
        val cargo = transport.carriedArmy ?: return false

        // Корабль должен быть на "море"
        if (gameMap.getCellType(transport.position.x, transport.position.y) != "sea") {
            return false // или разрешить на побережье?
        }

        // Ставим армию на ту же клетку
        cargo.position = transport.position
        armies.add(cargo)
        transport.carriedArmy = null
        return true
    }

    fun buildBuildingOnMap(building: Building, x: Int, y: Int): Boolean {
        if (building is Building.TownHall) return false
        val cell = gameMap.getCell(x, y) ?: return false
        if (!cell.isBuildable()) return false

        // ИСПРАВЛЕНО: Используем values() для Map со строковыми ключами
        if (enemyPositions.values.any { it.first == x && it.second == y }) return false
        if (enemyBase?.x == x && enemyBase?.y == y) return false
        if (!player.resources.hasEnough(building.buildCost, player.era)) return false

        // 🔥 ПРОВЕРКА: ВЕРФЬ ТОЛЬКО НА БЕРЕГУ
        if (building is Building.Shipyard) {
            if (!isCoastal(x, y)) {
                return false // Нельзя строить верфь не на берегу
            }
        }

        // 🔥 РАЗРЕШАЕМ СТРОИТЬ НЕСКОЛЬКО РЕСУРСНЫХ ЗДАНИЙ
        val isResourceBuilding = building is Building.Hut ||
                building is Building.Well ||
                building is Building.Sawmill ||
                building is Building.FishingHut ||
                building is Building.Farm ||
                building is Building.Quarry ||
                building is Building.GoldMine ||
                building is Building.IronMine ||
                building is Building.CoalMine ||
                building is Building.OilRig ||
                building is Building.SolarPlant ||
                building is Building.NuclearPlant

        if (building !is Building.Barracks &&
            building !is Building.ResearchCenter &&
            building !is Building.Shipyard && // ←_shipyard можно строить несколько
            !isResourceBuilding) {
            val existingBuilding = player.buildings.find { it::class == building::class }
            if (existingBuilding != null) {
                return false
            }
        }

        player.resources.subtract(building.buildCost)
        player.addBuilding(building)
        gameMap.setCellType(x, y, building.type)
        updatePlayerInfo()
        return true
    }

    @Exclude
    fun isCoastal(x: Int, y: Int): Boolean {
        if (gameMap.getCellType(x, y) != "empty") return false
        for (dx in -1..1) {
            for (dy in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (nx in 0 until gameMap.width && ny in 0 until gameMap.height) {
                    if (gameMap.getCellType(nx, ny) == "sea") return true
                }
            }
        }
        return false
    }

    fun GameUnit.isNaval(): Boolean = this is GameUnit.FishingBoat ||
            this is GameUnit.WarGalley ||
            this is GameUnit.TransportBarge



    private fun clearEnemyFromMap(x: Int, y: Int) {
        val hasOtherEnemy = enemyPositions.values.any { it.first == x && it.second == y }
        if (!hasOtherEnemy) {
            gameMap.setCellType(x, y, "empty")
        }
    }

    fun createArmy(unitCounts: Map<String, Int>): Army? {
        val availableUnits = player.units.filter { it.health > 0 }.toMutableList()
        val selectedUnits = mutableListOf<GameUnit>()

        for ((unitType, count) in unitCounts) {
            val found = availableUnits.filter { it.type == unitType }.take(count)
            if (found.size < count) return null
            selectedUnits.addAll(found)
            availableUnits.removeAll(found)
        }

        // Определяем, морская ли армия
        val isNaval = selectedUnits.all { it.isNaval() }

        // Находим стартовую позицию
        val startPosition = if (isNaval) {
            // Ищем ближайшую клетку "sea" рядом с ратушей или верфью
            findNearestSeaCell(player.townHallPosition) ?: player.townHallPosition
        } else {
            player.townHallPosition
        }

        player.units = availableUnits
        return Army(
            id = System.currentTimeMillis().toString(),
            units = selectedUnits,
            position = startPosition,
            hasMovedThisTurn = false
        )
    }

    private fun findNearestSeaCell(from: Position): Position? {
        // Сначала ищем в радиусе 2
        for (radius in 1..2) {
            for (dx in -radius..radius) {
                for (dy in -radius..radius) {
                    if (dx * dx + dy * dy > radius * radius) continue
                    val x = from.x + dx
                    val y = from.y + dy
                    if (x in 0 until gameMap.width && y in 0 until gameMap.height) {
                        if (gameMap.getCellType(x, y) == "sea") {
                            return Position(x, y)
                        }
                    }
                }
            }
        }
        // Если не нашли — ищем ЛЮБУЮ клетку моря (fallback)
        for (y in 0 until gameMap.height) {
            for (x in 0 until gameMap.width) {
                if (gameMap.getCellType(x, y) == "sea") {
                    return Position(x, y)
                }
            }
        }
        return null
    }

    fun returnArmyToTownHall(armyId: String): Boolean {
        val army = armies.find { it.id == armyId } ?: return false
        player.units.addAll(army.units.filter { it.health > 0 })
        armies.remove(army)
        return true
    }

    // Остальные методы без изменений...
    private fun getUnitCost(unit: GameUnit): Resource {
        return when (unit) {
            is GameUnit.Caveman -> Resource(food = 15, wood = 10)
            is GameUnit.Hunter -> Resource(food = 20, wood = 15, water = 5)
            is GameUnit.MammothRider -> Resource(food = 50, wood = 30, water = 15)
            is GameUnit.Swordsman -> Resource(food = 25, stone = 20, gold = 10)
            is GameUnit.BronzeArcher -> Resource(food = 20, stone = 15, gold = 8)
            is GameUnit.Chariot -> Resource(food = 60, stone = 40, gold = 25)
            is GameUnit.Knight -> Resource(food = 35, iron = 25, gold = 15)
            is GameUnit.Crossbowman -> Resource(food = 30, iron = 20, gold = 12)
            is GameUnit.Ram -> Resource(food = 40, iron = 50, wood = 30)
            is GameUnit.Soldier -> Resource(food = 25, iron = 15, coal = 10)
            is GameUnit.Artillery -> Resource(food = 35, iron = 30, coal = 20, oil = 10)
            is GameUnit.Tank -> Resource(food = 50, iron = 60, coal = 30, oil = 20)
            is GameUnit.Drone -> Resource(energy = 40, iron = 20, gold = 15)
            is GameUnit.Mech -> Resource(energy = 80, iron = 50, gold = 25)
            is GameUnit.LaserCannon -> Resource(energy = 120, iron = 30, gold = 40)
            is GameUnit.FishingBoat -> Resource(food = 30, wood = 20, stone = 10)
            is GameUnit.WarGalley -> Resource(food = 50, wood = 40, stone = 25, gold = 15)
            is GameUnit.TransportBarge -> Resource(food = 40, wood = 30, stone = 15, gold = 10)
            else -> Resource()
        }
    }


    fun upgradeBuilding(building: Building): Boolean {
        if (building.level >= 10) return false
        val cost = building.upgradeCost()
        if (!player.resources.hasEnough(cost, player.era)) return false
        player.resources.subtract(cost)
        building.level++
        building.health = building.maxHealth
        updatePlayerInfo()
        return true
    }

    fun completeResearch(research: Research): Boolean {
        val result = player.completeResearch(research)
        if (result) {
            updatePlayerInfo()
        }
        return result
    }

    fun getAvailableResearch(): List<Research> {
        return when (player.era) {
            Era.STONE_AGE -> listOf(Research.StoneTools, Research.BasicAgriculture)
            Era.BRONZE_AGE -> listOf(Research.BronzeWorking, Research.Currency)
            Era.MIDDLE_AGES -> listOf(Research.IronWorking, Research.Engineering)
            Era.INDUSTRIAL -> listOf(Research.SteamPower, Research.Electricity)
            Era.FUTURE -> listOf(Research.NuclearPower, Research.ArtificialIntelligence)
        }.filter { !player.completedResearch.contains(it.name) }
    }

    fun nextTurn() {
        turnCount++
        player.collectResources()

        // 🔥 СБОР ЕДЫ С РЫБОЛОВНЫХ КОРАБЛЕЙ — из армий!
        armies.filter { army ->
            army.units.size == 1 &&
                    army.units[0] is GameUnit.FishingBoat &&
                    army.isAlive()
        }.forEach { army ->
            if (gameMap.getCellType(army.position.x, army.position.y) == "sea") {
                player.resources.food += 3
            }
        }

        lastAttackMessage = ""
        currentEvent = null
        updatePlayerInfo()
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
                    AttackResult.NoTarget -> {}
                }
            }
            if (messages.isNotEmpty()) {
                val title = if (attackedBase) "💥 НАПАДЕНИЕ НА БАЗУ!" else "⚔️ Враги атаковали"
                lastAttackMessage = "$title\n${messages.joinToString("\n")}"
            }
        }
    }

    fun hireUnit(unit: GameUnit): Boolean {
        val isShip = unit is GameUnit.FishingBoat ||
                unit is GameUnit.WarGalley ||
                unit is GameUnit.TransportBarge

        if (isShip) {
            if (player.buildings.none { it is Building.Shipyard && !it.isDestroyed() }) {
                return false
            }
        } else {
            if (player.buildings.none { it is Building.Barracks && !it.isDestroyed() }) {
                return false
            }
        }

        val cost = getUnitCost(unit)
        if (!player.resources.hasEnough(cost, player.era)) return false

        player.resources.subtract(cost)
        player.units.add(unit)
        updatePlayerInfo()
        return true
    }

    fun updatePlayerInfo() {
        val eraName = when (player.era) {
            Era.STONE_AGE -> "Каменный век"
            Era.BRONZE_AGE -> "Бронзовый век"
            Era.MIDDLE_AGES -> "Средневековье"
            Era.INDUSTRIAL -> "Индустриальная эра"
            Era.FUTURE -> "Футуристическая эра"
        }
        _playerInfo = "Эпоха: $eraName\n" +
                "Завершено исследований: ${player.completedResearch.size}\n" +
                "Ресурсы: ${player.resources.getAvailableResources(player.era)}"
        _availableResearch = getAvailableResearch().map { it.name }
    }

    fun getPlayerInfo(): String {
        return _playerInfo
    }

    fun isPlayerWon(): Boolean {
        return enemyBase?.isDestroyed() == true || _playerWon
    }

    fun isPlayerDefeated(): Boolean {
        val townHalls = player.buildings.filterIsInstance<Building.TownHall>()
        val defeated = townHalls.isNotEmpty() && townHalls.all { it.isDestroyed() }
        _playerDefeated = defeated
        return defeated
    }

    fun canEvolveTo(targetEra: Era): Boolean {
        if (player.era.ordinal >= targetEra.ordinal) return false
        if (player.era.ordinal != targetEra.ordinal - 1) return false
        val req = ERA_REQUIREMENTS[targetEra] ?: return false
        return player.resources.hasEnough(req.resources, player.era) &&
                player.completedResearch.size >= req.completedResearch
    }

    fun evolveTo(targetEra: Era): Boolean {
        if (!canEvolveTo(targetEra)) return false
        val req = ERA_REQUIREMENTS[targetEra]!!
        player.resources.subtract(req.resources)
        player.era = targetEra
        when (targetEra) {
            Era.BRONZE_AGE -> {
                player.resources.stone += 50
                player.resources.gold += 25
            }
            Era.MIDDLE_AGES -> {
                player.resources.iron += 40
            }
            Era.INDUSTRIAL -> {
                player.resources.coal += 60
                player.resources.oil += 40
            }
            Era.FUTURE -> {
                player.resources.energy += 100
            }
            else -> {}
        }
        updatePlayerInfo()
        return true
    }

    fun saveState(): String = getGson().toJson(this)

    fun loadState(json: String) {
        val saved = getGson().fromJson(json, GameLogic::class.java)
        player.era = saved.player.era
        player.resources = saved.player.resources
        player.buildings.clear()
        player.buildings.addAll(saved.player.buildings)
        player.units.clear()
        player.units.addAll(saved.player.units)
        player.completedResearch.clear()
        player.completedResearch.addAll(saved.player.completedResearch)
        gameMap.cells.clear()
        gameMap.cells.addAll(saved.gameMap.cells)
        enemies.clear()
        enemies.addAll(saved.enemies)
        enemyPositions.clear()
        enemyPositions.putAll(saved.enemyPositions)
        enemyBase = saved.enemyBase
        turnCount = saved.turnCount
        lastAttackMessage = saved.lastAttackMessage
        currentEvent = saved.currentEvent
        _availableResearch = saved._availableResearch
        _playerInfo = saved._playerInfo
        _playerDefeated = saved._playerDefeated
        _playerWon = saved._playerWon
    }

    fun deepCopy(): GameLogic {
        val gson = getGson()
        val json = gson.toJson(this)
        return gson.fromJson(json, GameLogic::class.java)
    }

    companion object {
        val ERA_REQUIREMENTS = mapOf(
            Era.BRONZE_AGE to EraRequirement(
                Resource(food = 200, wood = 150, water = 100),
                1
            ),
            Era.MIDDLE_AGES to EraRequirement(
                Resource(food = 400, stone = 300, gold = 150, iron = 100),
                3
            ),
            Era.INDUSTRIAL to EraRequirement(
                Resource(iron = 500, coal = 300, oil = 200, gold = 250),
                5
            ),
            Era.FUTURE to EraRequirement(
                Resource(energy = 800, oil = 400, gold = 500, iron = 300),
                7
            )
        )

        private fun getGson(): Gson = GsonBuilder()
            .registerTypeAdapter(Building::class.java, BuildingTypeAdapter)
            .registerTypeAdapter(GameUnit::class.java, GameUnitTypeAdapter)
            .create()
    }
}