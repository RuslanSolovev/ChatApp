package com.example.chatapp.igra_strotegiy

import android.util.Log
import com.example.chatapp.models.User
import com.google.firebase.database.DatabaseReference
import kotlinx.coroutines.tasks.await
import kotlin.math.abs
import kotlin.math.min

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
            Position(1, 1),
            Position(sharedMap.width - 2, 1),
            Position(1, sharedMap.height - 2),
            Position(sharedMap.width - 2, sharedMap.height - 2)
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
        Log.d("MULTIPLAYER_LOGIC", "=== MAKE TURN STARTED ===")
        Log.d("MULTIPLAYER_LOGIC", "Game: $gameId, Player: $playerUid, Actions: ${actions.size}")

        val gameSnapshot = gamesRef.child(gameId).get().await()
        val game = FirebaseGameMapper.safeGetMultiplayerGame(gameSnapshot) ?: return false
        if (game.gameState != GameState.IN_PROGRESS) {
            Log.d("MULTIPLAYER_LOGIC", "Game not in progress")
            return false
        }
        if (game.currentTurnUid != playerUid) {
            Log.d("MULTIPLAYER_LOGIC", "Not player's turn")
            return false
        }
        val player = game.getPlayer(playerUid) ?: return false

        Log.d("MULTIPLAYER_LOGIC", "Processing ${actions.size} actions")

        // 🔥 Обработка выгрузки армии из транспорта - ПЕРВОЙ!
        for (action in actions) {
            if (action is GameAction.UnloadArmyFromTransport) {
                Log.d("TRANSPORT", "=== PROCESSING UNLOAD ACTION ===")
                Log.d("TRANSPORT", "Transport: ${action.transportArmyId}, Target: (${action.targetX}, ${action.targetY})")

                val transport = player.gameLogic.armies.find { it.id == action.transportArmyId }
                if (transport != null && transport.carriedArmy != null) {
                    Log.d("TRANSPORT", "Found transport with cargo, applying unload...")
                    val updatedLogic = applyActions(game, player.gameLogic.deepCopy(), listOf(action))
                    gamesRef.child(gameId).child("players").child(playerUid).child("gameLogic").setValue(updatedLogic).await()
                    Log.d("TRANSPORT", "Unload action applied and saved to database")

                    return true
                } else {
                    Log.d("TRANSPORT", "Transport not found or no cargo")
                }
            }
        }

        // 🔥 Обработка боевых действий
        for (action in actions) {
            if (action is GameAction.ConfirmArmyCombat) {
                val army = player.gameLogic.armies.find { it.id == action.attackerArmyId } ?: continue
                val dx = abs(army.position.x - action.targetX)
                val dy = abs(army.position.y - action.targetY)
                if (dx + dy != 1) continue

                if (action.isTownHallAttack) {
                    action.defenderUid?.let { defenderUid ->
                        resolveArmyAttackOnTownHall(gameId, playerUid, army, defenderUid)
                    }
                } else {
                    action.defenderArmyId?.let { defenderArmyId ->
                        action.defenderUid?.let { defenderUid ->
                            resolveArmyCombat(gameId, playerUid, army, defenderUid, defenderArmyId)
                        }
                    }
                }
                army.hasMovedThisTurn = true
            }
        }

        val updatedLogic = applyActions(game, player.gameLogic.deepCopy(), actions)
        gamesRef.child(gameId).child("players").child(playerUid).child("gameLogic").setValue(updatedLogic).await()

        // 🔥 Обработка перемещений — откат, если клетка занята
        for (action in actions) {
            if (action is GameAction.MoveArmy) {
                val army = updatedLogic.armies.find { it.id == action.armyId } ?: continue
                val targetX = action.targetX
                val targetY = action.targetY
                var isCellEmpty = true
                for ((otherUid, otherPlayer) in game.players) {
                    if (otherUid == playerUid) continue
                    if (otherPlayer.gameLogic.armies.any {
                            it.position.x == targetX && it.position.y == targetY && it.isAlive()
                        }) {
                        isCellEmpty = false
                        break
                    }
                    val pos = otherPlayer.gameLogic.player.townHallPosition
                    if (pos.x == targetX && pos.y == targetY) {
                        isCellEmpty = false
                        break
                    }
                }
                if (!isCellEmpty) {
                    val originalArmy = player.gameLogic.armies.find { it.id == action.armyId }
                    if (originalArmy != null) {
                        army.position = originalArmy.position
                        gamesRef.child(gameId).child("players").child(playerUid).child("gameLogic")
                            .child("armies").child(action.armyId).child("position")
                            .setValue(originalArmy.position).await()
                    }
                }
            }
        }

        // Старый код — совместимость с одиночным режимом (можно удалить при полном переходе на новый бой)
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

        if (actions.any { it is GameAction.NextTurn }) {
            val logicAfterTurn = updatedLogic.deepCopy()
            logicAfterTurn.nextTurn()
            logicAfterTurn.armies.forEach { it.hasMovedThisTurn = false }
            gamesRef.child(gameId).child("players").child(playerUid).child("gameLogic").setValue(logicAfterTurn).await()
            val next = game.getNextPlayerUid()
            gamesRef.child(gameId).child("currentTurnUid").setValue(next).await()
            gamesRef.child(gameId).child("lastTurnTime").setValue(System.currentTimeMillis()).await()
        }

        checkWinConditions(gameId, updatedLogic, playerUid)

        Log.d("MULTIPLAYER_LOGIC", "=== MAKE TURN COMPLETED ===")
        return true
    }

    // 🔥 НОВЫЕ МЕТОДЫ ЗАГРУЗКИ И ВЫГРУЗКИ
    fun loadArmyIntoTransport(transportArmyId: String, cargoArmyId: String, gameLogic: GameLogic): Boolean {
        Log.d("TRANSPORT", "Loading army $cargoArmyId into transport $transportArmyId")

        val transport = gameLogic.armies.find { it.id == transportArmyId } ?: return false
        val cargo = gameLogic.armies.find { it.id == cargoArmyId } ?: return false

        Log.d("TRANSPORT", "Transport: ${transport.id} at (${transport.position.x}, ${transport.position.y})")
        Log.d("TRANSPORT", "Cargo: ${cargo.id} at (${cargo.position.x}, ${cargo.position.y})")

        if (transport.units.size != 1 || transport.units.firstOrNull() !is GameUnit.TransportBarge) {
            Log.d("TRANSPORT", "Transport is not a valid transport barge")
            return false
        }
        if (cargo.isNaval()) {
            Log.d("TRANSPORT", "Cannot load naval units into transport")
            return false
        }

        // Проверяем, что армии на соседних клетках
        val dx = abs(transport.position.x - cargo.position.x)
        val dy = abs(transport.position.y - cargo.position.y)
        if (dx + dy != 1) {
            Log.d("TRANSPORT", "Armies are not adjacent: dx=$dx, dy=$dy")
            return false
        }

        if (transport.carriedArmy != null) {
            Log.d("TRANSPORT", "Transport already has cargo")
            return false
        }

        transport.carriedArmy = cargo
        gameLogic.armies.remove(cargo)

        Log.d("TRANSPORT", "Army loaded successfully")
        return true
    }

    fun unloadArmyFromTransport(transportArmyId: String, targetX: Int, targetY: Int, gameLogic: GameLogic): Boolean {
        Log.d("TRANSPORT", "=== UNLOAD ARMY FROM TRANSPORT ===")
        Log.d("TRANSPORT", "Transport: $transportArmyId, Target: ($targetX, $targetY)")

        val transport = gameLogic.armies.find { it.id == transportArmyId } ?: return false
        val cargo = transport.carriedArmy ?: return false

        Log.d("TRANSPORT", "Transport at (${transport.position.x}, ${transport.position.y})")
        Log.d("TRANSPORT", "Cargo army: ${cargo.units.size} units")
        Log.d("TRANSPORT", "Cargo units: ${cargo.units.joinToString { it.name }}")

        // 🔥 РАСШИРЕННЫЙ РАДИУС - до 3 клеток
        val dx = abs(transport.position.x - targetX)
        val dy = abs(transport.position.y - targetY)
        val distance = dx + dy

        Log.d("TRANSPORT", "Distance to target: $distance")

        if (distance > 3) {
            Log.d("TRANSPORT", "Target cell is too far: distance=$distance")
            return false
        }

        // Проверяем, что целевая клетка - суша
        val targetCellType = gameLogic.gameMap.getCellType(targetX, targetY)
        Log.d("TRANSPORT", "Target cell type: $targetCellType")

        if (targetCellType != "empty" && targetCellType != "town_hall") {
            Log.d("TRANSPORT", "Target cell is not land: $targetCellType")
            return false
        }

        // Проверяем, что клетка не занята другими армиями
        var isOccupied = false
        var occupationDetails = ""

        for (army in gameLogic.armies) {
            if (army.id != cargo.id &&
                army.position.x == targetX &&
                army.position.y == targetY &&
                army.isAlive()) {
                isOccupied = true
                occupationDetails = "army ${army.id} with ${army.units.size} units"
                Log.d("TRANSPORT", "Target cell occupied by $occupationDetails")
                break
            }
        }

        if (isOccupied) {
            Log.d("TRANSPORT", "Target cell is occupied: $occupationDetails")
            return false
        }

        // 🔥 ВЫГРУЖАЕМ АРМИЮ
        Log.d("TRANSPORT", "Before unload - Total armies: ${gameLogic.armies.size}")
        Log.d("TRANSPORT", "Cargo position before: (${cargo.position.x}, ${cargo.position.y})")

        // Обновляем позицию выгружаемой армии
        cargo.position = Position(targetX, targetY)

        // Добавляем армию обратно в список армий
        gameLogic.armies.add(cargo)

        // Очищаем перевозимую армию у транспорта
        transport.carriedArmy = null

        Log.d("TRANSPORT", "After unload - Total armies: ${gameLogic.armies.size}")
        Log.d("TRANSPORT", "Cargo position after: (${cargo.position.x}, ${cargo.position.y})")
        Log.d("TRANSPORT", "Transport carried army after: ${transport.carriedArmy}")
        Log.d("TRANSPORT", "Unload completed successfully!")

        return true
    }

    private fun applyActions(game: MultiplayerGame, gameLogic: GameLogic, actions: List<GameAction>): GameLogic {
        val updated = gameLogic.deepCopy()

        Log.d("APPLY_ACTIONS", "Applying ${actions.size} actions")

        // === Валидация действий ===
        for (action in actions) {
            when (action) {
                is GameAction.LoadArmyIntoTransport -> {
                    Log.d("TRANSPORT", "Validating LoadArmyIntoTransport action")
                    val transport = updated.armies.find { it.id == action.transportArmyId }
                        ?: throw Exception("Транспорт не найден")
                    val cargo = updated.armies.find { it.id == action.cargoArmyId }
                        ?: throw Exception("Армия для загрузки не найдена")

                    // 🔥 Проверка: армии на соседних клетках
                    val dx = abs(transport.position.x - cargo.position.x)
                    val dy = abs(transport.position.y - cargo.position.y)
                    if (dx + dy != 1) throw Exception("Армия должна быть на соседней клетке от транспорта")

                    if (cargo.isNaval()) throw Exception("Нельзя грузить корабли в корабли")
                    if (transport.carriedArmy != null) throw Exception("Транспорт уже занят")
                    if (transport.units.size != 1 || transport.units[0] !is GameUnit.TransportBarge) {
                        throw Exception("Только транспортный барж может перевозить армии")
                    }
                    val transportCellType = updated.gameMap.getCellType(transport.position.x, transport.position.y)
                    if (transportCellType != "sea") {
                        throw Exception("Транспорт должен быть в море")
                    }
                }
                is GameAction.UnloadArmyFromTransport -> {
                    Log.d("TRANSPORT", "=== VALIDATING UNLOAD ACTION ===")
                    Log.d("TRANSPORT", "Transport: ${action.transportArmyId}, Target: (${action.targetX}, ${action.targetY})")

                    val transport = updated.armies.find { it.id == action.transportArmyId }
                        ?: throw Exception("Транспорт не найден: ${action.transportArmyId}")
                    val cargo = transport.carriedArmy
                        ?: throw Exception("Нет армии для выгрузки в транспорте ${action.transportArmyId}")

                    Log.d("TRANSPORT", "Found transport at (${transport.position.x}, ${transport.position.y}) with cargo: ${cargo.units.size} units")

                    // 🔥 РАСШИРЕННЫЙ РАДИУС - до 3 клеток
                    val dx = abs(transport.position.x - action.targetX)
                    val dy = abs(transport.position.y - action.targetY)
                    val distance = dx + dy
                    if (distance > 3) {
                        Log.d("TRANSPORT", "Target cell is too far: distance=$distance")
                        throw Exception("Можно выгружать только в радиусе 3 клеток")
                    }

                    // Целевая клетка должна быть сушей
                    val targetCellType = updated.gameMap.getCellType(action.targetX, action.targetY)
                    if (targetCellType != "empty" && targetCellType != "town_hall") {
                        Log.d("TRANSPORT", "Target cell is not land: $targetCellType")
                        throw Exception("Можно выгружать только на сушу, а не на $targetCellType")
                    }

                    // Проверяем, что клетка не занята другими армиями
                    var isOccupied = false
                    for ((otherUid, otherPlayer) in game.players) {
                        // Проверяем все армии всех игроков (кроме той, что в транспорте)
                        if (otherPlayer.gameLogic.armies.any {
                                it.id != cargo.id && // кроме выгружаемой армии
                                        it.position.x == action.targetX &&
                                        it.position.y == action.targetY &&
                                        it.isAlive()
                            }) {
                            isOccupied = true
                            Log.d("TRANSPORT", "Cell occupied by other army from player $otherUid")
                            break
                        }

                        // Проверяем ратуши (можно выгружать только на свою ратушу)
                        val pos = otherPlayer.gameLogic.player.townHallPosition
                        if (pos.x == action.targetX && pos.y == action.targetY) {
                            if (otherUid != game.currentTurnUid) {
                                isOccupied = true
                                Log.d("TRANSPORT", "Cell is enemy town hall from player $otherUid")
                                break
                            }
                            // Своя ратуша - можно выгружать
                            Log.d("TRANSPORT", "Cell is own town hall - valid for unloading")
                        }
                    }

                    if (isOccupied) {
                        throw Exception("Целевая клетка занята")
                    }
                }
                is GameAction.BuildBuilding -> {
                    if (!updated.player.resources.hasEnough(action.building.buildCost, updated.player.era)) {
                        throw Exception("Недостаточно ресурсов")
                    }
                }
                is GameAction.HireUnit -> {
                    val cost = getUnitCost(action.unit)
                    val totalCost = Resource().apply {
                        add(cost.copy().apply { multiply(action.quantity) })
                    }
                    if (!updated.player.resources.hasEnough(totalCost, updated.player.era)) {
                        throw Exception("Недостаточно ресурсов для найма ${action.quantity} ${action.unit.name}")
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
                    val targetCellType = updated.gameMap.getCellType(action.targetX, action.targetY)
                    val isNaval = army.isNaval()
                    val canMove = if (isNaval) {
                        targetCellType == "sea"
                    } else {
                        targetCellType == "empty"
                    }
                    if (!canMove) {
                        throw Exception("Нельзя переместиться в эту клетку: ${if (isNaval) "требуется море" else "требуется суша"}")
                    }
                    var isCellEmpty = true
                    for ((otherUid, otherPlayer) in game.players) {
                        if (otherUid == game.currentTurnUid) continue
                        if (otherPlayer.gameLogic.armies.any {
                                it.position.x == action.targetX && it.position.y == action.targetY && it.isAlive()
                            }) {
                            isCellEmpty = false
                            break
                        }
                        val pos = otherPlayer.gameLogic.player.townHallPosition
                        if (pos.x == action.targetX && pos.y == action.targetY) {
                            isCellEmpty = false
                            break
                        }
                    }
                    if (!isCellEmpty) {
                        throw Exception("Клетка занята вражеской армией или ратушей! Используйте атаку.")
                    }
                }
                is GameAction.AttackWithArmy -> {
                    val army = updated.armies.find { it.id == action.armyId } ?: throw Exception("Армия не найдена")
                    val dx = abs(army.position.x - action.targetX)
                    val dy = abs(army.position.y - action.targetY)
                    if (dx + dy != 1) throw Exception("Для атаки армия должна быть на соседней клетке")
                    var hasTarget = false
                    for ((otherUid, otherPlayer) in game.players) {
                        if (otherUid == game.currentTurnUid) continue
                        if (otherPlayer.gameLogic.armies.any {
                                it.position.x == action.targetX && it.position.y == action.targetY && it.isAlive()
                            }) {
                            hasTarget = true
                            break
                        }
                        val pos = otherPlayer.gameLogic.player.townHallPosition
                        if (pos.x == action.targetX && pos.y == action.targetY) {
                            hasTarget = true
                            break
                        }
                    }
                    if (!hasTarget) {
                        throw Exception("Нет цели для атаки на этой клетке")
                    }
                }
                is GameAction.ConfirmArmyCombat -> {
                    val army = updated.armies.find { it.id == action.attackerArmyId } ?: throw Exception("Армия не найдена")
                    val dx = abs(army.position.x - action.targetX)
                    val dy = abs(army.position.y - action.targetY)
                    if (dx + dy != 1) throw Exception("Для атаки армия должна быть на соседней клетке")
                    var hasTarget = false
                    for ((otherUid, otherPlayer) in game.players) {
                        if (otherUid == game.currentTurnUid) continue
                        if (action.isTownHallAttack) {
                            val pos = otherPlayer.gameLogic.player.townHallPosition
                            if (pos.x == action.targetX && pos.y == action.targetY) {
                                hasTarget = true
                                break
                            }
                        } else {
                            if (otherPlayer.gameLogic.armies.any {
                                    it.id == action.defenderArmyId &&
                                            it.position.x == action.targetX &&
                                            it.position.y == action.targetY &&
                                            it.isAlive()
                                }) {
                                hasTarget = true
                                break
                            }
                        }
                    }
                    if (!hasTarget) {
                        throw Exception("Цель для атаки не найдена")
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

        // === Применение действий ===
        for (action in actions) {
            when (action) {
                is GameAction.LoadArmyIntoTransport -> {
                    Log.d("TRANSPORT", "Applying LoadArmyIntoTransport action")
                    val transport = updated.armies.find { it.id == action.transportArmyId }!!
                    val cargo = updated.armies.find { it.id == action.cargoArmyId }!!
                    transport.carriedArmy = cargo
                    updated.armies.remove(cargo)
                    Log.d("TRANSPORT", "Army loaded into transport. Total armies: ${updated.armies.size}")
                }
                is GameAction.UnloadArmyFromTransport -> {
                    Log.d("TRANSPORT", "=== APPLYING UNLOAD ACTION ===")
                    Log.d("TRANSPORT", "Transport: ${action.transportArmyId}, Target: (${action.targetX}, ${action.targetY})")

                    val transport = updated.armies.find { it.id == action.transportArmyId }!!
                    val cargo = transport.carriedArmy!!

                    Log.d("TRANSPORT", "Before unload - Total armies: ${updated.armies.size}")
                    Log.d("TRANSPORT", "Cargo position before: (${cargo.position.x}, ${cargo.position.y})")

                    // ВЫПОЛНЯЕМ ВЫГРУЗКУ
                    cargo.position = Position(action.targetX, action.targetY)
                    updated.armies.add(cargo)
                    transport.carriedArmy = null

                    Log.d("TRANSPORT", "After unload - Total armies: ${updated.armies.size}")
                    Log.d("TRANSPORT", "Cargo position after: (${cargo.position.x}, ${cargo.position.y})")
                    Log.d("TRANSPORT", "Unload completed successfully. Cargo army added to armies list")
                }
                is GameAction.BuildBuilding -> updated.buildBuildingOnMap(action.building, action.x, action.y)
                is GameAction.HireUnit -> {
                    repeat(action.quantity) {
                        updated.hireUnit(action.unit)
                    }
                }
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
                is GameAction.AttackWithArmy -> {
                    val army = updated.armies.find { it.id == action.armyId }
                    if (army != null) {
                        army.hasMovedThisTurn = true
                    }
                }
                is GameAction.ConfirmArmyCombat -> {
                    val army = updated.armies.find { it.id == action.attackerArmyId }
                    if (army != null) {
                        army.hasMovedThisTurn = true
                    }
                }
                is GameAction.ReturnArmyToTownHall -> {
                    updated.returnArmyToTownHall(action.armyId)
                }
                else -> {}
            }
        }

        Log.d("APPLY_ACTIONS", "Actions applied successfully")
        return updated
    }


    // 🔥 Боевая логика (без изменений из вашего кода)
    private suspend fun resolveArmyCombat(
        gameId: String,
        attackerUid: String,
        attackerArmy: Army,
        defenderUid: String,
        defenderArmyId: String
    ) {
        val defenderSnapshot = gamesRef.child(gameId).child("players").child(defenderUid).child("gameLogic").get().await()
        val defenderLogic = FirebaseGameMapper.parseGameLogic(defenderSnapshot) ?: return
        val defenderArmy = defenderLogic.armies.find { it.id == defenderArmyId } ?: return

        val attackerPower = attackerArmy.totalAttackPower()
        val defenderPower = defenderArmy.totalAttackPower()
        val attackerEffectiveness = 0.8 + Math.random() * 0.4
        val defenderEffectiveness = 0.8 + Math.random() * 0.4
        val effectiveAttackerPower = (attackerPower * attackerEffectiveness).toInt()
        val effectiveDefenderPower = (defenderPower * defenderEffectiveness).toInt()
        val totalPower = effectiveAttackerPower + effectiveDefenderPower
        val attackerLossRatio = if (totalPower > 0) effectiveDefenderPower.toDouble() / totalPower else 0.5
        val defenderLossRatio = if (totalPower > 0) effectiveAttackerPower.toDouble() / totalPower else 0.5

        applyDamageToArmy(attackerArmy, attackerLossRatio)
        applyDamageToArmy(defenderArmy, defenderLossRatio)

        if (attackerArmy.units.isEmpty()) {
            gamesRef.child(gameId).child("players").child(attackerUid)
                .child("gameLogic").child("armies").child(attackerArmy.id).removeValue().await()
            Log.d("BATTLE", "Армия атакующего полностью уничтожена и удалена")
        } else {
            val attackerSnapshot = gamesRef.child(gameId).child("players").child(attackerUid).child("gameLogic").get().await()
            val attackerLogic = FirebaseGameMapper.parseGameLogic(attackerSnapshot) ?: return
            val updatedAttackerArmy = attackerLogic.armies.find { it.id == attackerArmy.id }
            if (updatedAttackerArmy != null) {
                updatedAttackerArmy.units.clear()
                updatedAttackerArmy.units.addAll(attackerArmy.units)
                gamesRef.child(gameId).child("players").child(attackerUid).child("gameLogic").setValue(attackerLogic).await()
            }
        }

        if (defenderArmy.units.isEmpty()) {
            gamesRef.child(gameId).child("players").child(defenderUid)
                .child("gameLogic").child("armies").child(defenderArmyId).removeValue().await()
            Log.d("BATTLE", "Армия защитника полностью уничтожена и удалена")
        } else {
            val updatedDefenderArmy = defenderLogic.armies.find { it.id == defenderArmyId }
            if (updatedDefenderArmy != null) {
                updatedDefenderArmy.units.clear()
                updatedDefenderArmy.units.addAll(defenderArmy.units)
            }
            gamesRef.child(gameId).child("players").child(defenderUid).child("gameLogic").setValue(defenderLogic).await()
        }
    }

    private fun applyDamageToArmy(army: Army, lossRatio: Double) {
        if (army.units.isEmpty()) return
        val totalDamage = (army.totalHealth() * lossRatio).toInt()
        var remainingDamage = totalDamage
        val sortedUnits = army.units.sortedBy { it.health }
        for (unit in sortedUnits) {
            if (remainingDamage <= 0) break
            val damageToUnit = minOf(remainingDamage, unit.health)
            unit.health -= damageToUnit
            remainingDamage -= damageToUnit
        }
        army.units.removeIf { it.health <= 0 }
        Log.d("BATTLE", "Армия после боя: ${army.units.size} выживших юнитов")
    }

    private suspend fun resolveArmyAttackOnTownHall(
        gameId: String,
        attackerUid: String,
        attackerArmy: Army,
        defenderUid: String
    ) {
        val defenderSnapshot = gamesRef.child(gameId).child("players").child(defenderUid).child("gameLogic").get().await()
        val defenderLogic = FirebaseGameMapper.parseGameLogic(defenderSnapshot) ?: return
        val townHall = defenderLogic.player.buildings.find { it is Building.TownHall && !it.isDestroyed() } as? Building.TownHall ?: return

        val armyPower = attackerArmy.totalAttackPower()
        val defendingUnits = defenderLogic.player.units.filter { it.health > 0 }
        if (defendingUnits.isNotEmpty()) {
            val unitsCombatResult = resolveUnitsCombat(attackerArmy, defendingUnits)
            defenderLogic.player.units.clear()
            defenderLogic.player.units.addAll(unitsCombatResult.defenderSurvivedUnits)
            if (unitsCombatResult.attackerSurvivedUnits.isNotEmpty()) {
                val remainingArmyPower = unitsCombatResult.attackerSurvivedUnits.sumOf { it.totalAttackPower() }
                if (remainingArmyPower > 0) {
                    townHall.takeDamage(remainingArmyPower)
                }
            }
        } else {
            townHall.takeDamage(armyPower)
        }

        val townHallIndex = defenderLogic.player.buildings.indexOfFirst { it is Building.TownHall }
        if (townHallIndex != -1) {
            defenderLogic.player.buildings[townHallIndex] = townHall
        }
        gamesRef.child(gameId).child("players").child(defenderUid).child("gameLogic").setValue(defenderLogic).await()

        if (attackerArmy.isCompletelyDestroyed() || attackerArmy.units.isEmpty()) {
            gamesRef.child(gameId).child("players").child(attackerUid)
                .child("gameLogic").child("armies").child(attackerArmy.id).removeValue().await()
            Log.d("BATTLE", "Атакующая армия полностью уничтожена при штурме ратуши")
        } else {
            val attackerSnapshot = gamesRef.child(gameId).child("players").child(attackerUid).child("gameLogic").get().await()
            val attackerLogic = FirebaseGameMapper.parseGameLogic(attackerSnapshot) ?: return
            val updatedAttackerArmy = attackerLogic.armies.find { it.id == attackerArmy.id }
            if (updatedAttackerArmy != null) {
                updatedAttackerArmy.units.clear()
                updatedAttackerArmy.units.addAll(attackerArmy.units)
                gamesRef.child(gameId).child("players").child(attackerUid).child("gameLogic").setValue(attackerLogic).await()
            }
        }

        if (townHall.isDestroyed()) {
            gamesRef.child(gameId).child("winnerUid").setValue(attackerUid).await()
            gamesRef.child(gameId).child("gameState").setValue(GameState.FINISHED).await()
            Log.d("BATTLE", "Ратуша игрока $defenderUid уничтожена игроком $attackerUid")
        }
    }

    private data class CombatResult(
        val attackerSurvivedUnits: MutableList<Army> = mutableListOf(),
        val defenderSurvivedUnits: MutableList<GameUnit> = mutableListOf()
    )

    private fun resolveUnitsCombat(attackerArmy: Army, defendingUnits: List<GameUnit>): CombatResult {
        val result = CombatResult()
        val attackerPower = attackerArmy.totalAttackPower()
        val defenderPower = defendingUnits.sumOf { it.attackPower }
        val attackerEffectiveness = 0.7 + Math.random() * 0.6
        val defenderEffectiveness = 0.7 + Math.random() * 0.6
        val effectiveAttackerPower = (attackerPower * attackerEffectiveness).toInt()
        val effectiveDefenderPower = (defenderPower * defenderEffectiveness).toInt()
        applyDamageToUnits(attackerArmy.units, effectiveDefenderPower)
        applyDamageToUnits(defendingUnits.toMutableList(), effectiveAttackerPower)
        result.attackerSurvivedUnits.addAll(attackerArmy.units.filter { it.health > 0 }.map {
            Army(units = mutableListOf(it), position = attackerArmy.position)
        })
        result.defenderSurvivedUnits.addAll(defendingUnits.filter { it.health > 0 })
        return result
    }

    private fun applyDamageToUnits(units: MutableList<GameUnit>, totalDamage: Int) {
        if (units.isEmpty() || totalDamage <= 0) return
        var remainingDamage = totalDamage
        val sortedUnits = units.sortedBy { it.health }
        for (unit in sortedUnits) {
            if (remainingDamage <= 0) break
            val damageToUnit = minOf(remainingDamage, unit.health)
            unit.health -= damageToUnit
            remainingDamage -= damageToUnit
        }
        units.removeIf { it.health <= 0 }
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
            is GameUnit.FishingBoat -> Resource(food = 30, wood = 20, stone = 10)
            is GameUnit.WarGalley -> Resource(food = 50, wood = 40, stone = 25, gold = 15)
            is GameUnit.TransportBarge -> Resource(food = 40, wood = 30, stone = 15, gold = 10)
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