// com.example.chatapp.igra_strotegiy.MultiplayerGameLogic.kt
package com.example.chatapp.igra_strotegiy

import com.example.chatapp.models.User
import com.google.firebase.database.DatabaseReference
import kotlinx.coroutines.tasks.await
import kotlin.math.abs

class MultiplayerGameLogic(private val database: DatabaseReference) {
    private val gamesRef = database.child("multiplayer_games")

    suspend fun createGame(hostUid: String, hostName: String, hostImageUrl: String?, maxPlayers: Int = 2): String {
        val gameId = gamesRef.push().key ?: throw Exception("Не удалось создать игру")
        val hostPlayer = GamePlayer(
            uid = hostUid,
            displayName = hostName,
            profileImageUrl = hostImageUrl,
            playerColor = getRandomColor(),
            isReady = true
        )
        val game = MultiplayerGame(
            gameId = gameId,
            hostUid = hostUid,
            players = mapOf(hostUid to hostPlayer),
            currentTurnUid = hostUid,
            gameState = GameState.WAITING_FOR_PLAYERS,
            maxPlayers = maxPlayers,
            minPlayers = 1
        )
        gamesRef.child(gameId).setValue(game).await()
        return gameId
    }

    suspend fun joinGame(gameId: String, user: User): Boolean {
        val gameSnapshot = gamesRef.child(gameId).get().await()
        val game = FirebaseGameMapper.safeGetMultiplayerGame(gameSnapshot) ?: return false
        if (game.getSafePlayers().size >= game.maxPlayers) return false
        if (game.gameState != GameState.WAITING_FOR_PLAYERS) return false
        if (game.getSafePlayers().containsKey(user.uid)) return true
        val newPlayer = GamePlayer(
            uid = user.uid,
            displayName = user.getFullName(),
            profileImageUrl = user.profileImageUrl,
            playerColor = getRandomColor(),
            isReady = false
        )
        gamesRef.child(gameId).child("players").child(user.uid).setValue(newPlayer).await()
        return true
    }

    suspend fun leaveGame(gameId: String, uid: String) {
        val gameSnapshot = gamesRef.child(gameId).get().await()
        val game = FirebaseGameMapper.safeGetMultiplayerGame(gameSnapshot) ?: return
        gamesRef.child(gameId).child("players").child(uid).removeValue().await()
        if (uid == game.hostUid && game.getSafePlayers().size > 1) {
            val newHostUid = game.getSafePlayers().keys.first { it != uid }
            gamesRef.child(gameId).child("hostUid").setValue(newHostUid).await()
        }
        if (game.getSafePlayers().size <= 1) {
            gamesRef.child(gameId).removeValue().await()
        }
    }

    suspend fun startGame(gameId: String, hostUid: String): Boolean {
        val gameSnapshot = gamesRef.child(gameId).get().await()
        val game = FirebaseGameMapper.safeGetMultiplayerGame(gameSnapshot) ?: return false
        if (game.hostUid != hostUid) return false
        if (!game.canStartGame()) return false
        if (game.getSafePlayers().size < 2) return false

        val sharedMap = GameMap()
        val playersList = game.getSafePlayers().entries.toList()
        val positions = listOf(
            Position(sharedMap.width / 2, sharedMap.height / 2),
            Position(1, 1),
            Position(sharedMap.width - 2, 1),
            Position(1, sharedMap.height - 2)
        )

        playersList.forEachIndexed { index, (uid, _) ->
            val logic = GameLogic()
            logic.player.buildings.clear()
            val townHall = Building.TownHall()
            logic.player.addBuilding(townHall)
            val position = positions.getOrNull(index) ?: positions[0]
            logic.player.townHallPosition = position
            gamesRef.child(gameId).child("players").child(uid).child("gameLogic").setValue(logic).await()
        }

        gamesRef.child(gameId).child("sharedMap").setValue(sharedMap).await()
        val updatedSharedMap = sharedMap.deepCopy()
        playersList.forEachIndexed { index, (uid, _) ->
            val position = positions.getOrNull(index) ?: positions[0]
            updatedSharedMap.setCellType(position.x, position.y, "town_hall")
            gamesRef.child(gameId).child("players").child(uid).child("gameLogic").child("player")
                .child("townHallPosition").setValue(position).await()
        }
        gamesRef.child(gameId).child("sharedMap").setValue(updatedSharedMap).await()

        val updates = mapOf<String, Any>(
            "gameState" to GameState.IN_PROGRESS.name,
            "currentTurnUid" to game.hostUid,
            "lastTurnTime" to System.currentTimeMillis()
        )
        gamesRef.child(gameId).updateChildren(updates).await()
        return true
    }

    suspend fun getGame(gameId: String): MultiplayerGame? {
        val snapshot = gamesRef.child(gameId).get().await()
        return FirebaseGameMapper.safeGetMultiplayerGame(snapshot)
    }

    suspend fun makeTurn(gameId: String, playerUid: String, actions: List<GameAction>): Boolean {
        val gameSnapshot = gamesRef.child(gameId).get().await()
        val game = FirebaseGameMapper.safeGetMultiplayerGame(gameSnapshot) ?: return false
        if (game.gameState != GameState.IN_PROGRESS) return false
        if (game.currentTurnUid != playerUid) return false
        val player = game.getPlayer(playerUid) ?: return false

        val updatedLogic = applyActions(game, player.gameLogic.deepCopy(), actions)
        gamesRef.child(gameId).child("players").child(playerUid).child("gameLogic").setValue(updatedLogic).await()

        // 🔥 НОВОЕ: Обработка перемещения армии и боя между армиями
        for (action in actions) {
            when (action) {
                is GameAction.MoveArmy -> {
                    resolveArmyCombat(gameId, playerUid, updatedLogic, action.armyId)
                    resolveArmyAttackOnTownHall(gameId, playerUid, updatedLogic, action)
                }

                else -> {}
            }
        }

        // Обработка атаки на вражескую ратушу (старый способ — оставляем для совместимости)
        val attackTownHallAction = actions.find { it is GameAction.AttackEnemyTownHall }
        if (attackTownHallAction is GameAction.AttackEnemyTownHall) {
            val targetUid = attackTownHallAction.targetPlayerUid
            val targetSnapshot = gamesRef.child(gameId).child("players").child(targetUid).child("gameLogic").get().await()
            val targetLogic = FirebaseGameMapper.parseGameLogic(targetSnapshot) ?: return false
            val townHall = targetLogic.player.buildings.find { it is Building.TownHall && !it.isDestroyed() }
            if (townHall != null) {
                val damage = updatedLogic.player.units.filter { it.health > 0 }.sumOf { it.attackPower }
                if (damage > 0) {
                    townHall.takeDamage(damage)
                    val index = targetLogic.player.buildings.indexOfFirst { it is Building.TownHall }
                    if (index != -1) {
                        targetLogic.player.buildings[index] = townHall
                    }
                    gamesRef.child(gameId).child("players").child(targetUid).child("gameLogic").setValue(targetLogic).await()
                    if (townHall.isDestroyed()) {
                        gamesRef.child(gameId).child("winnerUid").setValue(playerUid).await()
                        gamesRef.child(gameId).child("gameState").setValue(GameState.FINISHED).await()
                    }
                }
            }
        }

        // Завершение хода → сбор ресурсов + сброс флагов армий
        if (actions.any { it is GameAction.NextTurn }) {
            val logicAfterTurn = updatedLogic.deepCopy()
            logicAfterTurn.nextTurn()
            // Сбрасываем флаги перемещения у армий
            logicAfterTurn.armies.forEach { it.hasMovedThisTurn = false }
            gamesRef.child(gameId).child("players").child(playerUid).child("gameLogic").setValue(logicAfterTurn).await()
            val next = game.getNextPlayerUid()
            gamesRef.child(gameId).child("currentTurnUid").setValue(next).await()
            gamesRef.child(gameId).child("lastTurnTime").setValue(System.currentTimeMillis()).await()
        }

        checkWinConditions(gameId, updatedLogic, playerUid)
        return true
    }

    // 🔥 НОВОЕ: Бой между армиями
    private suspend fun resolveArmyCombat(gameId: String, attackerUid: String, logic: GameLogic, armyId: String) {
        val army = logic.armies.find { it.id == armyId } ?: return
        val allPlayersSnapshot = gamesRef.child(gameId).child("players").get().await()
        val allPlayers = mutableMapOf<String, GamePlayer>()

        // Загружаем всех игроков
        allPlayersSnapshot.children.forEach { playerSnapshot ->
            val uid = playerSnapshot.key ?: return@forEach
            val gameLogic = FirebaseGameMapper.parseGameLogic(playerSnapshot.child("gameLogic"))
            allPlayers[uid] = GamePlayer(gameLogic = gameLogic)
        }

        // Ищем вражеские армии на той же позиции
        for ((uid, player) in allPlayers) {
            if (uid == attackerUid) continue // пропускаем атакующего

            val enemyArmies = player.gameLogic.armies.filter {
                it.position == army.position && it.isAlive()
            }

            for (enemyArmy in enemyArmies) {
                // Бой между армиями: обмен уроном
                val attackPower = army.totalAttackPower()
                val defensePower = enemyArmy.totalAttackPower()

                // Урон по вражеской армии
                if (attackPower > 0 && enemyArmy.units.isNotEmpty()) {
                    val damagePerUnit = attackPower / enemyArmy.units.size.coerceAtLeast(1)
                    enemyArmy.units.forEach { u -> u.health -= damagePerUnit }
                }

                // Урон по атакующей армии
                if (defensePower > 0 && army.units.isNotEmpty()) {
                    val damagePerUnit = defensePower / army.units.size.coerceAtLeast(1)
                    army.units.forEach { u -> u.health -= damagePerUnit }
                }

                // Удаляем мёртвых юнитов
                enemyArmy.units.removeIf { it.health <= 0 }
                army.units.removeIf { it.health <= 0 }

                // Сохраняем изменения вражеской армии
                gamesRef.child(gameId).child("players").child(uid).child("gameLogic").setValue(player.gameLogic).await()
            }
        }

        // Сохраняем изменения атакующей армии
        gamesRef.child(gameId).child("players").child(attackerUid).child("gameLogic").setValue(logic).await()
    }

    // 🔥 НОВОЕ: Атака армией на ратушу
    private suspend fun resolveArmyAttackOnTownHall(
        gameId: String,
        attackerUid: String,
        logic: GameLogic,
        moveAction: GameAction.MoveArmy
    ) {
        val army = logic.armies.find { it.id == moveAction.armyId } ?: return
        val targetPos = Position(moveAction.targetX, moveAction.targetY)

        // Ищем игрока, чья ратуша на этой позиции
        val allPlayersSnapshot = gamesRef.child(gameId).child("players").get().await()
        for (playerSnapshot in allPlayersSnapshot.children) {
            val targetUid = playerSnapshot.key ?: continue
            if (targetUid == attackerUid) continue // нельзя атаковать себя

            val targetLogic = FirebaseGameMapper.parseGameLogic(playerSnapshot.child("gameLogic"))
            val townHallPos = targetLogic.player.townHallPosition
            if (townHallPos != targetPos) continue

            val townHall = targetLogic.player.buildings.find { it is Building.TownHall && !it.isDestroyed() } ?: continue
            val defendingUnits = targetLogic.player.units.filter { it.health > 0 }

            if (defendingUnits.isNotEmpty()) {
                // Бой с защитниками
                val armyPower = army.totalAttackPower()
                defendingUnits.forEach { u -> u.health -= armyPower / defendingUnits.size }
                army.units.forEach { u ->
                    u.health -= defendingUnits.sumOf { it.attackPower } / army.units.size.coerceAtLeast(1)
                }
                targetLogic.player.units.removeIf { it.health <= 0 }
                army.units.removeIf { it.health <= 0 }
            } else {
                // Атака ратуши
                val damage = army.totalAttackPower()
                if (damage > 0) {
                    townHall.takeDamage(damage)
                    val idx = targetLogic.player.buildings.indexOfFirst { it is Building.TownHall }
                    if (idx != -1) {
                        targetLogic.player.buildings[idx] = townHall
                    }
                    if (townHall.isDestroyed()) {
                        gamesRef.child(gameId).child("winnerUid").setValue(attackerUid).await()
                        gamesRef.child(gameId).child("gameState").setValue(GameState.FINISHED).await()
                    }
                }
            }

            // Сохраняем цель
            gamesRef.child(gameId).child("players").child(targetUid).child("gameLogic").setValue(targetLogic).await()
            // Сохраняем атакующего
            gamesRef.child(gameId).child("players").child(attackerUid).child("gameLogic").setValue(logic).await()
            return
        }
    }

    private fun applyActions(game: MultiplayerGame, gameLogic: GameLogic, actions: List<GameAction>): GameLogic {
        val updated = gameLogic.deepCopy()

        // === Валидация ===
        for (action in actions) {
            when (action) {
                is GameAction.BuildBuilding -> {
                    if (!updated.player.resources.hasEnough(action.building.buildCost, updated.player.era)) {
                        throw Exception("Недостаточно ресурсов")
                    }
                }
                is GameAction.HireUnit -> {
                    val cost = getUnitCost(action.unit)
                    if (!updated.player.resources.hasEnough(cost, updated.player.era)) {
                        throw Exception("Недостаточно ресурсов")
                    }
                }
                is GameAction.UpgradeBuilding -> {
                    val cost = action.building.upgradeCost()
                    if (!updated.player.resources.hasEnough(cost, updated.player.era)) {
                        throw Exception("Недостаточно ресурсов")
                    }
                }
                is GameAction.CompleteResearch -> {
                    if (!updated.player.resources.hasEnough(action.research.cost, updated.player.era)) {
                        throw Exception("Недостаточно ресурсов")
                    }
                }
                is GameAction.EvolveToEra -> {
                    val req = GameLogic.ERA_REQUIREMENTS[action.targetEra]
                    if (req == null || !updated.player.resources.hasEnough(req.resources, updated.player.era)) {
                        throw Exception("Недостаточно ресурсов для эволюции")
                    }
                    if (updated.player.completedResearch.size < req.completedResearch) {
                        throw Exception("Недостаточно завершённых исследований")
                    }
                    if (updated.player.era.ordinal != action.targetEra.ordinal - 1) {
                        throw Exception("Можно эволюционировать только в следующую эру")
                    }
                }
                is GameAction.CreateArmy -> {
                    val availableUnits = updated.player.units.filter { it.health > 0 }
                    val grouped = availableUnits.groupBy { it.type }
                    for ((unitType, count) in action.unitCounts) {
                        val availableCount = grouped[unitType]?.size ?: 0
                        if (availableCount < count) {
                            throw Exception("Недостаточно юнитов типа $unitType")
                        }
                    }
                }
                is GameAction.MoveArmy -> {
                    val army = updated.armies.find { it.id == action.armyId } ?: throw Exception("Армия не найдена")
                    if (army.hasMovedThisTurn) throw Exception("Армия уже перемещалась")
                    val dx = abs(army.position.x - action.targetX)
                    val dy = abs(army.position.y - action.targetY)
                    if (dx + dy > 2) throw Exception("Армия может двигаться не более чем на 2 клетки")
                    // 🔥 Запрет заходить на занятые клетки
                    for (otherPlayer in game.players.values) {
                        // Чужая или своя ратуша
                        if (otherPlayer.gameLogic.player.townHallPosition.x == action.targetX &&
                            otherPlayer.gameLogic.player.townHallPosition.y == action.targetY) {
                            throw Exception("Нельзя заходить на ратушу")
                        }
                        // Любая армия
                        if (otherPlayer.gameLogic.armies.any {
                                it.position.x == action.targetX && it.position.y == action.targetY && it.isAlive()
                            }) {
                            throw Exception("Нельзя заходить на клетку с армией")
                        }
                    }
                }
                is GameAction.ReturnArmyToTownHall -> {
                    val army = updated.armies.find { it.id == action.armyId } ?: throw Exception("Армия не найдена")
                    val dist = abs(army.position.x - updated.player.townHallPosition.x) +
                            abs(army.position.y - updated.player.townHallPosition.y)
                    if (dist > 1) throw Exception("Армия слишком далеко от ратуши")
                }
                else -> {}
            }
        }

        // === Применение ===
        for (action in actions) {
            when (action) {
                is GameAction.BuildBuilding -> updated.buildBuildingOnMap(action.building, action.x, action.y)
                is GameAction.HireUnit -> updated.hireUnit(action.unit)
                is GameAction.UpgradeBuilding -> updated.upgradeBuilding(action.building)
                is GameAction.CompleteResearch -> updated.completeResearch(action.research)
                is GameAction.AttackTarget -> updated.attackTarget(action.x, action.y)
                is GameAction.NextTurn -> {}
                is GameAction.AttackEnemyTownHall -> {}
                is GameAction.EvolveToEra -> updated.evolveTo(action.targetEra)
                is GameAction.CreateArmy -> {
                    val army = updated.createArmy(action.unitCounts)
                    if (army != null) updated.armies.add(army)
                    else throw Exception("Не удалось создать армию")
                }
                is GameAction.MoveArmy -> {
                    val army = updated.armies.find { it.id == action.armyId }
                    if (army != null) {
                        army.position = Position(action.targetX, action.targetY)
                        army.hasMovedThisTurn = true
                    }
                }
                is GameAction.ReturnArmyToTownHall -> {
                    updated.returnArmyToTownHall(action.armyId)
                }
                else -> {}
            }
        }
        return updated
    }

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
            else -> Resource()
        }
    }

    private suspend fun checkWinConditions(gameId: String, gameLogic: GameLogic, playerUid: String) {
        if (gameLogic.isPlayerDefeated()) {
            val game = getGame(gameId) ?: return
            val otherUid = game.getSafePlayers().keys.firstOrNull { it != playerUid }
            if (otherUid != null) {
                gamesRef.child(gameId).child("winnerUid").setValue(otherUid).await()
                gamesRef.child(gameId).child("gameState").setValue(GameState.FINISHED).await()
            }
        }
    }

    suspend fun setPlayerReady(gameId: String, playerUid: String, isReady: Boolean): Boolean {
        val game = FirebaseGameMapper.safeGetMultiplayerGame(gamesRef.child(gameId).get().await()) ?: return false
        if (!game.getSafePlayers().containsKey(playerUid)) return false
        gamesRef.child(gameId).child("players").child(playerUid).child("isReady").setValue(isReady).await()
        return true
    }

    private fun getRandomColor(): String {
        val colors = listOf("#FF0000", "#00FF00", "#0000FF", "#FFFF00", "#FF00FF", "#00FFFF", "#FFA500", "#800080")
        return colors.random()
    }
}