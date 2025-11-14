package com.example.chatapp.igra_strotegiy

import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.Exclude
import com.google.firebase.database.IgnoreExtraProperties

@IgnoreExtraProperties
data class FirebaseMultiplayerGame(
    val gameId: String = "",
    val hostUid: String = "",
    val currentTurnUid: String = "",
    val gameState: String = GameState.WAITING_FOR_PLAYERS.name,
    val createdAt: Long = System.currentTimeMillis(),
    val maxPlayers: Int = 2,
    val minPlayers: Int = 1,
    val turnTimeLimit: Long = 24 * 60 * 60 * 1000,
    val lastTurnTime: Long = System.currentTimeMillis(),
    val winnerUid: String? = null
) {
    constructor() : this("", "", "", GameState.WAITING_FOR_PLAYERS.name,
        System.currentTimeMillis(), 2, 1, 24 * 60 * 60 * 1000, System.currentTimeMillis(), null)

    @Exclude
    fun toMultiplayerGame(players: Map<String, GamePlayer>): MultiplayerGame {
        return MultiplayerGame(
            gameId = gameId,
            hostUid = hostUid,
            players = players,
            currentTurnUid = currentTurnUid,
            gameState = GameState.valueOf(gameState),
            createdAt = createdAt,
            maxPlayers = maxPlayers,
            minPlayers = minPlayers,
            turnTimeLimit = turnTimeLimit,
            lastTurnTime = lastTurnTime,
            winnerUid = winnerUid
        )
    }
}

object FirebaseGameMapper {

    fun safeGetMultiplayerGame(snapshot: DataSnapshot): MultiplayerGame? {
        return try {
            Log.d("FirebaseGameMapper", "Parsing multiplayer game from snapshot: ${snapshot.key}")

            val firebaseGame = snapshot.getValue(FirebaseMultiplayerGame::class.java) ?: return null
            val players = parsePlayers(snapshot.child("players"))

            val game = firebaseGame.toMultiplayerGame(players).copy(
                gameId = if (firebaseGame.gameId.isEmpty()) snapshot.key ?: "" else firebaseGame.gameId
            )

            Log.d("FirebaseGameMapper", "Successfully parsed game: ${game.gameId} with ${players.size} players")
            game
        } catch (e: Exception) {
            Log.e("FirebaseGameMapper", "Error mapping game ${snapshot.key}: ${e.message}", e)
            null
        }
    }

    private fun parsePlayers(playersSnapshot: DataSnapshot): Map<String, GamePlayer> {
        val players = mutableMapOf<String, GamePlayer>()
        playersSnapshot.children.forEach { playerSnapshot ->
            try {
                val player = parsePlayer(playerSnapshot)
                player?.let {
                    players[playerSnapshot.key ?: ""] = it
                }
            } catch (e: Exception) {
                Log.e("FirebaseGameMapper", "Error parsing player ${playerSnapshot.key}: ${e.message}", e)
            }
        }
        Log.d("FirebaseGameMapper", "Parsed ${players.size} players")
        return players
    }

    private fun parsePlayer(playerSnapshot: DataSnapshot): GamePlayer? {
        return try {
            val uid = playerSnapshot.child("uid").getValue(String::class.java) ?: playerSnapshot.key ?: ""
            val displayName = playerSnapshot.child("displayName").getValue(String::class.java) ?: ""
            val profileImageUrl = playerSnapshot.child("profileImageUrl").getValue(String::class.java)
            val playerColor = playerSnapshot.child("playerColor").getValue(String::class.java) ?: "#FF0000"
            val isReady = playerSnapshot.child("isReady").getValue(Boolean::class.java) ?: false
            val joinedAt = playerSnapshot.child("joinedAt").getValue(Long::class.java) ?: System.currentTimeMillis()
            val lastActive = playerSnapshot.child("lastActive").getValue(Long::class.java) ?: System.currentTimeMillis()

            val gameLogic = parseGameLogic(playerSnapshot.child("gameLogic"))

            GamePlayer(
                uid = uid,
                displayName = displayName,
                profileImageUrl = profileImageUrl,
                playerColor = playerColor,
                gameLogic = gameLogic,
                isReady = isReady,
                joinedAt = joinedAt,
                lastActive = lastActive
            )
        } catch (e: Exception) {
            Log.e("FirebaseGameMapper", "Error parsing player data: ${e.message}", e)
            null
        }
    }


    fun parseGameLogic(gameLogicSnapshot: DataSnapshot): GameLogic {
        return try {
            Log.d("FirebaseGameMapper", "Parsing GameLogic from snapshot")
            val gameLogic = GameLogic()

            if (gameLogicSnapshot.child("player").exists()) {
                parsePlayerData(gameLogicSnapshot.child("player"), gameLogic.player)
            }
            // Внутри parseGameLogic, после парсинга units:
            if (gameLogicSnapshot.child("armies").exists()) {
                gameLogicSnapshot.child("armies").children.forEach { armySnapshot ->
                    val army = parseArmy(armySnapshot)
                    army?.let { gameLogic.armies.add(it) }
                }
            }

            // Парсинг врагов (опционально)
            if (gameLogicSnapshot.child("enemies").exists()) {
                gameLogicSnapshot.child("enemies").children.forEach { enemySnapshot ->
                    val enemy = parseEnemy(enemySnapshot)
                    enemy?.let { gameLogic.enemies.add(it) }
                }
            }

            // Парсинг позиций врагов (опционально)
            if (gameLogicSnapshot.child("enemyPositions").exists()) {
                gameLogicSnapshot.child("enemyPositions").children.forEach { posSnapshot ->
                    val key = posSnapshot.key ?: ""
                    val x = posSnapshot.child("first").getValue(Int::class.java) ?: 0
                    val y = posSnapshot.child("second").getValue(Int::class.java) ?: 0
                    gameLogic.enemyPositions[key] = Pair(x, y)
                }
            }

            // Парсинг вражеской базы (опционально)
            if (gameLogicSnapshot.child("enemyBase").exists()) {
                gameLogicSnapshot.child("enemyBase").let { baseSnapshot ->
                    val x = baseSnapshot.child("x").getValue(Int::class.java) ?: 0
                    val y = baseSnapshot.child("y").getValue(Int::class.java) ?: 0
                    val health = baseSnapshot.child("health").getValue(Int::class.java) ?: 200
                    val maxHealth = baseSnapshot.child("maxHealth").getValue(Int::class.java) ?: 200
                    gameLogic.enemyBase = EnemyBase(x, y, health, maxHealth)
                }
            }

            // Парсинг карты (опционально)
            if (gameLogicSnapshot.child("gameMap").exists()) {
                parseGameMap(gameLogicSnapshot.child("gameMap"), gameLogic.gameMap)
            }

            // Дополнительные поля
            gameLogicSnapshot.child("lastAttackMessage").getValue(String::class.java)?.let {
                gameLogic.lastAttackMessage = it
            }

            gameLogicSnapshot.child("currentEvent").getValue(String::class.java)?.let {
                gameLogic.currentEvent = it
            }

            Log.d("FirebaseGameMapper", "GameLogic parsed successfully")
            gameLogic
        } catch (e: Exception) {
            Log.e("FirebaseGameMapper", "Error parsing game logic: ${e.message}", e)
            GameLogic() // Возвращаем пустой GameLogic вместо выброса исключения
        }
    }

    private fun parseArmy(armySnapshot: DataSnapshot): Army? {
        return try {
            val id = armySnapshot.child("id").getValue(String::class.java) ?: ""
            val hasMoved = armySnapshot.child("hasMovedThisTurn").getValue(Boolean::class.java) ?: false
            val posX = armySnapshot.child("position").child("x").getValue(Int::class.java) ?: 0
            val posY = armySnapshot.child("position").child("y").getValue(Int::class.java) ?: 0
            val position = Position(posX, posY)

            val units = mutableListOf<GameUnit>()
            if (armySnapshot.child("units").exists()) {
                armySnapshot.child("units").children.forEach { unitSnapshot ->
                    val unit = parseUnit(unitSnapshot)
                    unit?.let { units.add(it) }
                }
            }

            val army = Army(id = id, units = units, position = position, hasMovedThisTurn = hasMoved)

            // 🔥 ВАЖНО: Парсим перевозимую армию
            if (armySnapshot.child("carriedArmy").exists()) {
                val carriedArmy = parseArmy(armySnapshot.child("carriedArmy"))
                army.carriedArmy = carriedArmy
                Log.d("FirebaseGameMapper", "Parsed carried army for transport $id: ${carriedArmy?.units?.size ?: 0} units")
            }

            army
        } catch (e: Exception) {
            Log.e("FirebaseGameMapper", "Error parsing army", e)
            null
        }
    }

    private fun parsePosition(snapshot: DataSnapshot): Position {
        return try {
            val map = snapshot.value as? Map<*, *>
            if (map != null) {
                // Пытаемся прочитать как Position объект
                val x = (map["x"] as? Long)?.toInt() ?: 0
                val y = (map["y"] as? Long)?.toInt() ?: 0
                Position(x, y)
            } else {
                // Пытаемся прочитать как Pair (для обратной совместимости)
                val list = snapshot.value as? List<*>
                if (list != null && list.size >= 2) {
                    val x = (list[0] as? Long)?.toInt() ?: 0
                    val y = (list[1] as? Long)?.toInt() ?: 0
                    Position(x, y)
                } else {
                    Position(0, 0)
                }
            }
        } catch (e: Exception) {
            Log.e("FirebaseGameMapper", "Error parsing position: ${e.message}")
            Position(0, 0)
        }
    }

    private fun parsePlayerData(playerSnapshot: DataSnapshot, player: Player) {
        try {
            Log.d("FirebaseGameMapper", "Parsing player data")

            // Эра
            playerSnapshot.child("era").getValue(String::class.java)?.let {
                player.era = Era.valueOf(it)
            }

            // Исследования
            playerSnapshot.child("completedResearch").children.forEach { researchSnapshot ->
                researchSnapshot.getValue(String::class.java)?.let {
                    player.completedResearch.add(it)
                }
            }

            // Ресурсы
            if (playerSnapshot.child("resources").exists()) {
                parseResources(playerSnapshot.child("resources"), player.resources)
            }

            // Здания
            if (playerSnapshot.child("buildings").exists()) {
                playerSnapshot.child("buildings").children.forEach { buildingSnapshot ->
                    val building = parseBuilding(buildingSnapshot)
                    building?.let {
                        player.buildings.add(it)
                        Log.d("FirebaseGameMapper", "Added building: ${building.name}")
                    }
                }
            }

            // Юниты
            if (playerSnapshot.child("units").exists()) {
                playerSnapshot.child("units").children.forEach { unitSnapshot ->
                    val unit = parseUnit(unitSnapshot)
                    unit?.let { player.units.add(it) }
                }
            }

            // Позиция ратуши
            if (playerSnapshot.child("townHallPosition").exists()) {
                player.townHallPosition = parsePosition(playerSnapshot.child("townHallPosition"))
            }

            Log.d("FirebaseGameMapper", "Player data parsed: ${player.buildings.size} buildings, ${player.units.size} units")
        } catch (e: Exception) {
            Log.e("FirebaseGameMapper", "Error parsing player data: ${e.message}", e)
        }
    }

    private fun parseResources(resourcesSnapshot: DataSnapshot, resources: Resource) {
        try {
            resourcesSnapshot.child("wood").getValue(Int::class.java)?.let { resources.wood = it }
            resourcesSnapshot.child("food").getValue(Int::class.java)?.let { resources.food = it }
            resourcesSnapshot.child("water").getValue(Int::class.java)?.let { resources.water = it }
            resourcesSnapshot.child("stone").getValue(Int::class.java)?.let { resources.stone = it }
            resourcesSnapshot.child("gold").getValue(Int::class.java)?.let { resources.gold = it }
            resourcesSnapshot.child("iron").getValue(Int::class.java)?.let { resources.iron = it }
            resourcesSnapshot.child("coal").getValue(Int::class.java)?.let { resources.coal = it }
            resourcesSnapshot.child("oil").getValue(Int::class.java)?.let { resources.oil = it }
            resourcesSnapshot.child("energy").getValue(Int::class.java)?.let { resources.energy = it }
        } catch (e: Exception) {
            Log.e("FirebaseGameMapper", "Error parsing resources: ${e.message}")
        }
    }

    private fun parseBuilding(buildingSnapshot: DataSnapshot): Building? {
        return try {
            val type = buildingSnapshot.child("type").getValue(String::class.java) ?: return null
            val level = buildingSnapshot.child("level").getValue(Int::class.java) ?: 1
            val health = buildingSnapshot.child("health").getValue(Int::class.java) ?: -1

            Log.d("FirebaseGameMapper", "Parsing building type: $type, level: $level, health: $health")

            val building = when (type) {
                "hut" -> Building.Hut()
                "well" -> Building.Well()
                "sawmill" -> Building.Sawmill()
                "fishing_hut" -> Building.FishingHut()
                "farm" -> Building.Farm()
                "quarry" -> Building.Quarry()
                "gold_mine" -> Building.GoldMine()
                "forge" -> Building.Forge()
                "iron_mine" -> Building.IronMine()
                "castle" -> Building.Castle()
                "blacksmith" -> Building.Blacksmith()
                "coal_mine" -> Building.CoalMine()
                "oil_rig" -> Building.OilRig()
                "factory" -> Building.Factory()
                "power_plant" -> Building.PowerPlant()
                "solar_plant" -> Building.SolarPlant()
                "nuclear_plant" -> Building.NuclearPlant()
                "robotics_lab" -> Building.RoboticsLab()
                "barracks" -> Building.Barracks()
                "research_center" -> Building.ResearchCenter()
                "town_hall" -> Building.TownHall()
                "shipyard" -> Building.Shipyard()  // ← ЭТО ДОБАВЬ!
                else -> {
                    Log.w("FirebaseGameMapper", "Unknown building type: $type")
                    return null
                }
            }

            building.level = level
            building.health = if (health == -1) building.maxHealth else health

            Log.d("FirebaseGameMapper", "Successfully parsed building: ${building.name}, level: ${building.level}, health: ${building.health}")
            return building
        } catch (e: Exception) {
            Log.e("FirebaseGameMapper", "Error parsing building: ${e.message}", e)
            return null
        }
    }

    private fun parseUnit(unitSnapshot: DataSnapshot): GameUnit? {
        return try {
            val type = unitSnapshot.child("type").getValue(String::class.java) ?: return null
            when (type) {
                "Caveman" -> unitSnapshot.getValue(GameUnit.Caveman::class.java)
                "Hunter" -> unitSnapshot.getValue(GameUnit.Hunter::class.java)
                "MammothRider" -> unitSnapshot.getValue(GameUnit.MammothRider::class.java)
                "Swordsman" -> unitSnapshot.getValue(GameUnit.Swordsman::class.java)
                "BronzeArcher" -> unitSnapshot.getValue(GameUnit.BronzeArcher::class.java)
                "Chariot" -> unitSnapshot.getValue(GameUnit.Chariot::class.java)
                "Knight" -> unitSnapshot.getValue(GameUnit.Knight::class.java)
                "Crossbowman" -> unitSnapshot.getValue(GameUnit.Crossbowman::class.java)
                "Ram" -> unitSnapshot.getValue(GameUnit.Ram::class.java)
                "Soldier" -> unitSnapshot.getValue(GameUnit.Soldier::class.java)
                "Artillery" -> unitSnapshot.getValue(GameUnit.Artillery::class.java)
                "Tank" -> unitSnapshot.getValue(GameUnit.Tank::class.java)
                "Drone" -> unitSnapshot.getValue(GameUnit.Drone::class.java)
                "Mech" -> unitSnapshot.getValue(GameUnit.Mech::class.java)
                "LaserCannon" -> unitSnapshot.getValue(GameUnit.LaserCannon::class.java)
                // 🔥 ДОБАВИТЬ КОРАБЛИ:
                "FishingBoat" -> unitSnapshot.getValue(GameUnit.FishingBoat::class.java)
                "WarGalley" -> unitSnapshot.getValue(GameUnit.WarGalley::class.java)
                "TransportBarge" -> unitSnapshot.getValue(GameUnit.TransportBarge::class.java)
                else -> null
            }
        } catch (e: Exception) {
            Log.e("FirebaseGameMapper", "Error parsing unit: ${e.message}")
            null
        }
    }

    fun armyToMap(army: Army): Map<String, Any?> {
        return mapOf(
            "id" to army.id,
            "hasMovedThisTurn" to army.hasMovedThisTurn,
            "position" to mapOf(
                "x" to army.position.x,
                "y" to army.position.y
            ),
            "units" to army.units.map { unitToMap(it) },
            // 🔥 ВАЖНО: Сохраняем carriedArmy рекурсивно
            "carriedArmy" to army.carriedArmy?.let { armyToMap(it) }
        )
    }

    private fun unitToMap(unit: GameUnit): Map<String, Any?> {
        return when (unit) {
            is GameUnit.Caveman -> mapOf(
                "type" to "Caveman",
                "health" to unit.health,
                "attackPower" to unit.attackPower
            )
            is GameUnit.Hunter -> mapOf(
                "type" to "Hunter",
                "health" to unit.health,
                "attackPower" to unit.attackPower
            )
            is GameUnit.MammothRider -> mapOf(
                "type" to "MammothRider",
                "health" to unit.health,
                "attackPower" to unit.attackPower
            )
            is GameUnit.Swordsman -> mapOf(
                "type" to "Swordsman",
                "health" to unit.health,
                "attackPower" to unit.attackPower
            )
            is GameUnit.BronzeArcher -> mapOf(
                "type" to "BronzeArcher",
                "health" to unit.health,
                "attackPower" to unit.attackPower
            )
            is GameUnit.Chariot -> mapOf(
                "type" to "Chariot",
                "health" to unit.health,
                "attackPower" to unit.attackPower
            )
            is GameUnit.Knight -> mapOf(
                "type" to "Knight",
                "health" to unit.health,
                "attackPower" to unit.attackPower
            )
            is GameUnit.Crossbowman -> mapOf(
                "type" to "Crossbowman",
                "health" to unit.health,
                "attackPower" to unit.attackPower
            )
            is GameUnit.Ram -> mapOf(
                "type" to "Ram",
                "health" to unit.health,
                "attackPower" to unit.attackPower
            )
            is GameUnit.Soldier -> mapOf(
                "type" to "Soldier",
                "health" to unit.health,
                "attackPower" to unit.attackPower
            )
            is GameUnit.Artillery -> mapOf(
                "type" to "Artillery",
                "health" to unit.health,
                "attackPower" to unit.attackPower
            )
            is GameUnit.Tank -> mapOf(
                "type" to "Tank",
                "health" to unit.health,
                "attackPower" to unit.attackPower
            )
            is GameUnit.Drone -> mapOf(
                "type" to "Drone",
                "health" to unit.health,
                "attackPower" to unit.attackPower
            )
            is GameUnit.Mech -> mapOf(
                "type" to "Mech",
                "health" to unit.health,
                "attackPower" to unit.attackPower
            )
            is GameUnit.LaserCannon -> mapOf(
                "type" to "LaserCannon",
                "health" to unit.health,
                "attackPower" to unit.attackPower
            )
            // 🔥 КОРАБЛИ
            is GameUnit.FishingBoat -> mapOf(
                "type" to "FishingBoat",
                "health" to unit.health,
                "attackPower" to unit.attackPower
            )
            is GameUnit.WarGalley -> mapOf(
                "type" to "WarGalley",
                "health" to unit.health,
                "attackPower" to unit.attackPower
            )
            is GameUnit.TransportBarge -> mapOf(
                "type" to "TransportBarge",
                "health" to unit.health,
                "attackPower" to unit.attackPower
            )
            else -> emptyMap()
        }
    }

    private fun parseEnemy(enemySnapshot: DataSnapshot): Enemy? {
        return try {
            Enemy(
                id = enemySnapshot.child("id").getValue(Int::class.java) ?: 0,
                name = enemySnapshot.child("name").getValue(String::class.java) ?: "Enemy",
                health = enemySnapshot.child("health").getValue(Int::class.java) ?: 50,
                attackPower = enemySnapshot.child("attackPower").getValue(Int::class.java) ?: 10
            )
        } catch (e: Exception) {
            Log.e("FirebaseGameMapper", "Error parsing enemy: ${e.message}")
            null
        }
    }

    private fun parseGameMap(mapSnapshot: DataSnapshot, gameMap: GameMap) {
        try {
            mapSnapshot.child("width").getValue(Int::class.java)?.let { gameMap.width = it }
            mapSnapshot.child("height").getValue(Int::class.java)?.let { gameMap.height = it }
            mapSnapshot.child("cells").children.forEach { cellSnapshot ->
                val index = cellSnapshot.key?.toIntOrNull() ?: return@forEach
                if (index < gameMap.cells.size) {
                    val type = cellSnapshot.child("type").getValue(String::class.java) ?: "empty"
                    val x = cellSnapshot.child("x").getValue(Int::class.java) ?: 0
                    val y = cellSnapshot.child("y").getValue(Int::class.java) ?: 0
                    gameMap.cells[index] = MapCell(type, x, y)
                }
            }
        } catch (e: Exception) {
            Log.e("FirebaseGameMapper", "Error parsing game map: ${e.message}")
        }
    }
}