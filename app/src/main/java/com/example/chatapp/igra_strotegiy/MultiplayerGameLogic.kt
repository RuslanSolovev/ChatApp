package com.example.chatapp.igra_strotegiy

import android.util.Log
import com.example.chatapp.igra_strotegiy.Research.ArtificialIntelligence.multiply
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

        // 🔥 ОБРАБОТКА ПОДТВЕРЖДЁННОГО БОЯ
        for (action in actions) {
            if (action is GameAction.ConfirmArmyCombat) {
                val army = player.gameLogic.armies.find { it.id == action.attackerArmyId } ?: continue

                // Проверяем, что армия находится на соседней клетке
                val dx = abs(army.position.x - action.targetX)
                val dy = abs(army.position.y - action.targetY)
                if (dx + dy != 1) {
                    continue // Атака только с соседней клетки
                }

                if (action.isTownHallAttack) {
                    // Атака на ратушу
                    action.defenderUid?.let { defenderUid ->
                        resolveArmyAttackOnTownHall(gameId, playerUid, army, defenderUid)
                    }
                } else {
                    // Атака на армию
                    action.defenderArmyId?.let { defenderArmyId ->
                        action.defenderUid?.let { defenderUid ->
                            resolveArmyCombat(gameId, playerUid, army, defenderUid, defenderArmyId)
                        }
                    }
                }

                // Помечаем армию как атаковавшую
                army.hasMovedThisTurn = true
            }
        }

        // 🔥 ПЕРЕДАЁМ game в applyActions
        val updatedLogic = applyActions(game, player.gameLogic.deepCopy(), actions)
        gamesRef.child(gameId).child("players").child(playerUid).child("gameLogic").setValue(updatedLogic).await()

        // 🔥 ОБРАБОТКА MoveArmy ТОЛЬКО ДЛЯ ПЕРЕМЕЩЕНИЯ (без боя)
        for (action in actions) {
            if (action is GameAction.MoveArmy) {
                val army = updatedLogic.armies.find { it.id == action.armyId } ?: continue
                val targetX = action.targetX
                val targetY = action.targetY

                // Проверяем, что клетка пуста
                var isCellEmpty = true
                for ((otherUid, otherPlayer) in game.players) {
                    if (otherUid == playerUid) continue
                    // Проверяем вражеские армии
                    if (otherPlayer.gameLogic.armies.any {
                            it.position.x == targetX && it.position.y == targetY && it.isAlive()
                        }) {
                        isCellEmpty = false
                        break
                    }
                    // Проверяем вражеские ратуши
                    val pos = otherPlayer.gameLogic.player.townHallPosition
                    if (pos.x == targetX && pos.y == targetY) {
                        isCellEmpty = false
                        break
                    }
                }

                if (!isCellEmpty) {
                    // Отменяем перемещение - возвращаем армию на исходную позицию
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

        // Устаревшая логика — оставляем для совместимости
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

        // Завершение ходa → сбор ресурсов + сброс флагов армий
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
        return true
    }

    // 🔥 ПОЛНОСТЬЮ ОБНОВЛЁННЫЙ applyActions С ПОДДЕРЖКОЙ КОЛИЧЕСТВА ЮНИТОВ И НОВЫХ ДЕЙСТВИЙ
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
                    val totalCost = Resource().apply {
                        // Умножаем стоимость на количество
                        add(cost.copy().apply {
                            multiply(action.quantity)
                        })
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

                    // 🔥 ПРОВЕРКА НА ЗАНЯТОСТЬ КЛЕТКИ - ИСПРАВЛЕННАЯ
                    var isCellEmpty = true
                    for ((otherUid, otherPlayer) in game.players) {
                        // Пропускаем текущего игрока - ищем по UID из game, а не из gameLogic
                        val currentPlayerUid = game.currentTurnUid
                        if (otherUid == currentPlayerUid) continue

                        // Проверяем вражеские армии
                        if (otherPlayer.gameLogic.armies.any {
                                it.position.x == action.targetX && it.position.y == action.targetY && it.isAlive()
                            }) {
                            isCellEmpty = false
                            break
                        }

                        // Проверяем вражеские ратуши
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

                    // Проверяем, что есть цель для атаки - ИСПРАВЛЕННАЯ
                    var hasTarget = false
                    for ((otherUid, otherPlayer) in game.players) {
                        // Пропускаем текущего игрока
                        val currentPlayerUid = game.currentTurnUid
                        if (otherUid == currentPlayerUid) continue

                        // Вражеская армия
                        if (otherPlayer.gameLogic.armies.any {
                                it.position.x == action.targetX && it.position.y == action.targetY && it.isAlive()
                            }) {
                            hasTarget = true
                            break
                        }

                        // Вражеская ратуша
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
                    // Валидация для подтвержденного боя
                    val army = updated.armies.find { it.id == action.attackerArmyId } ?: throw Exception("Армия не найдена")
                    val dx = abs(army.position.x - action.targetX)
                    val dy = abs(army.position.y - action.targetY)
                    if (dx + dy != 1) throw Exception("Для атаки армия должна быть на соседней клетке")

                    // Проверяем, что есть цель для атаки
                    var hasTarget = false
                    for ((otherUid, otherPlayer) in game.players) {
                        if (otherUid == game.currentTurnUid) continue

                        if (action.isTownHallAttack) {
                            // Проверяем ратушу
                            val pos = otherPlayer.gameLogic.player.townHallPosition
                            if (pos.x == action.targetX && pos.y == action.targetY) {
                                hasTarget = true
                                break
                            }
                        } else {
                            // Проверяем армию
                            if (otherPlayer.gameLogic.armies.any {
                                    it.id == action.defenderArmyId && it.position.x == action.targetX && it.position.y == action.targetY && it.isAlive()
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

        // === Применение ===
        for (action in actions) {
            when (action) {
                is GameAction.BuildBuilding -> updated.buildBuildingOnMap(action.building, action.x, action.y)
                is GameAction.HireUnit -> {
                    // Нанимаем указанное количество юнитов
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
                    // Атака обрабатывается в makeTurn, здесь только помечаем армию
                    val army = updated.armies.find { it.id == action.armyId }
                    if (army != null) {
                        army.hasMovedThisTurn = true
                    }
                }
                is GameAction.ConfirmArmyCombat -> {
                    // Подтвержденный бой обрабатывается в makeTurn, здесь только помечаем армию
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
        return updated
    }

    // 🔥 ИСПРАВЛЕННЫЙ БОЙ МЕЖДУ АРМИЯМИ С ПОЛНЫМ УНИЧТОЖЕНИЕМ
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

        // 🔥 УЛУЧШЕННЫЙ РАСЧЕТ БОЯ
        val attackerPower = attackerArmy.totalAttackPower()
        val defenderPower = defenderArmy.totalAttackPower()

        // Расчет эффективности атаки с учетом случайного фактора
        val attackerEffectiveness = 0.8 + Math.random() * 0.4 // 0.8-1.2
        val defenderEffectiveness = 0.8 + Math.random() * 0.4 // 0.8-1.2

        val effectiveAttackerPower = (attackerPower * attackerEffectiveness).toInt()
        val effectiveDefenderPower = (defenderPower * defenderEffectiveness).toInt()

        // Расчет потерь на основе соотношения сил
        val totalPower = effectiveAttackerPower + effectiveDefenderPower
        val attackerLossRatio = if (totalPower > 0) effectiveDefenderPower.toDouble() / totalPower else 0.5
        val defenderLossRatio = if (totalPower > 0) effectiveAttackerPower.toDouble() / totalPower else 0.5

        // 🔥 ПРИМЕНЯЕМ ПОТЕРИ К АРМИЯМ (МОЖЕТ ПОЛНОСТЬЮ УНИЧТОЖИТЬ)
        applyDamageToArmy(attackerArmy, attackerLossRatio)
        applyDamageToArmy(defenderArmy, defenderLossRatio)

        // 🔥 УДАЛЯЕМ ПОЛНОСТЬЮ УНИЧТОЖЕННЫЕ АРМИИ ИЗ БАЗЫ ДАННЫХ
        if (attackerArmy.units.isEmpty()) {
            // Удаляем атакующую армию из базы данных
            gamesRef.child(gameId).child("players").child(attackerUid)
                .child("gameLogic").child("armies").child(attackerArmy.id).removeValue().await()
            Log.d("BATTLE", "Армия атакующего полностью уничтожена и удалена")
        } else {
            // Сохраняем обновленную атакующую армию
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
            // Удаляем защищающуюся армию из базы данных
            gamesRef.child(gameId).child("players").child(defenderUid)
                .child("gameLogic").child("armies").child(defenderArmyId).removeValue().await()
            Log.d("BATTLE", "Армия защитника полностью уничтожена и удалена")
        } else {
            // Сохраняем обновленную защищающуюся армию
            val updatedDefenderArmy = defenderLogic.armies.find { it.id == defenderArmyId }
            if (updatedDefenderArmy != null) {
                updatedDefenderArmy.units.clear()
                updatedDefenderArmy.units.addAll(defenderArmy.units)
            }
            gamesRef.child(gameId).child("players").child(defenderUid).child("gameLogic").setValue(defenderLogic).await()
        }

        // 🔥 ПРОВЕРЯЕМ УСЛОВИЯ ПОБЕДЫ
        if (defenderArmy.units.isEmpty() && attackerArmy.units.isNotEmpty()) {
            Log.d("BATTLE", "Армия $attackerUid победила армию $defenderUid")
        } else if (attackerArmy.units.isEmpty() && defenderArmy.units.isNotEmpty()) {
            Log.d("BATTLE", "Армия $defenderUid победила армию $attackerUid")
        } else if (attackerArmy.units.isEmpty() && defenderArmy.units.isEmpty()) {
            Log.d("BATTLE", "Ничья - обе армии уничтожены")
        }
    }

    // 🔥 ПОЛНОСТЬЮ ПЕРЕПИСАННЫЙ МЕТОД ДЛЯ ПРИМЕНЕНИЯ УРОНА - ТЕПЕРЬ УНИЧТОЖАЕТ АРМИЮ ПОЛНОСТЬЮ
    private fun applyDamageToArmy(army: Army, lossRatio: Double) {
        if (army.units.isEmpty()) return

        // 🔥 РАСЧЕТ ОБЩЕГО УРОНА БЕЗ ОГРАНИЧЕНИЙ
        val totalDamage = (army.totalHealth() * lossRatio).toInt()
        var remainingDamage = totalDamage

        // Сортируем юниты по здоровью (сначала слабые)
        val sortedUnits = army.units.sortedBy { it.health }

        for (unit in sortedUnits) {
            if (remainingDamage <= 0) break

            val damageToUnit = minOf(remainingDamage, unit.health)
            unit.health -= damageToUnit
            remainingDamage -= damageToUnit
        }

        // 🔥 УДАЛЯЕМ ВСЕХ МЕРТВЫХ ЮНИТОВ БЕЗ ИСКЛЮЧЕНИЙ
        army.units.removeIf { it.health <= 0 }

        // 🔥 ЛОГИРУЕМ РЕЗУЛЬТАТ
        Log.d("BATTLE", "Армия после боя: ${army.units.size} выживших юнитов")
    }

    // 🔥 ИСПРАВЛЕННАЯ АТАКА АРМИИ НА РАТУШУ
    private suspend fun resolveArmyAttackOnTownHall(
        gameId: String,
        attackerUid: String,
        attackerArmy: Army,
        defenderUid: String
    ) {
        val defenderSnapshot = gamesRef.child(gameId).child("players").child(defenderUid).child("gameLogic").get().await()
        val defenderLogic = FirebaseGameMapper.parseGameLogic(defenderSnapshot) ?: return
        val townHall = defenderLogic.player.buildings.find { it is Building.TownHall && !it.isDestroyed() } as? Building.TownHall ?: return

        // 🔥 УЛУЧШЕННЫЙ РАСЧЕТ АТАКИ НА РАТУШУ
        val armyPower = attackerArmy.totalAttackPower()

        // Сначала атакуем защитных юнитов (если есть)
        val defendingUnits = defenderLogic.player.units.filter { it.health > 0 }
        if (defendingUnits.isNotEmpty()) {
            val unitsCombatResult = resolveUnitsCombat(attackerArmy, defendingUnits)

            // Обновляем состояние защитных юнитов
            defenderLogic.player.units.clear()
            defenderLogic.player.units.addAll(unitsCombatResult.defenderSurvivedUnits)

            // Если армия выжила после боя с защитниками, атакуем ратушу
            if (unitsCombatResult.attackerSurvivedUnits.isNotEmpty()) {
                val remainingArmyPower = unitsCombatResult.attackerSurvivedUnits.sumOf { it.totalAttackPower() }
                if (remainingArmyPower > 0) {
                    townHall.takeDamage(remainingArmyPower)
                }
            }
        } else {
            // Если защитников нет — бьём по ратуше
            townHall.takeDamage(armyPower)
        }

        // Обновляем состояние ратуши
        val townHallIndex = defenderLogic.player.buildings.indexOfFirst { it is Building.TownHall }
        if (townHallIndex != -1) {
            defenderLogic.player.buildings[townHallIndex] = townHall
        }

        // Сохраняем изменения защитника
        gamesRef.child(gameId).child("players").child(defenderUid).child("gameLogic").setValue(defenderLogic).await()

        // 🔥 ОБНОВЛЯЕМ ИЛИ УДАЛЯЕМ АТАКУЮЩУЮ АРМИЮ
        if (attackerArmy.isCompletelyDestroyed() || attackerArmy.units.isEmpty()) {
            // Удаляем полностью уничтоженную армию
            gamesRef.child(gameId).child("players").child(attackerUid)
                .child("gameLogic").child("armies").child(attackerArmy.id).removeValue().await()
            Log.d("BATTLE", "Атакующая армия полностью уничтожена при штурме ратуши")
        } else {
            // Обновляем атакующую армию
            val attackerSnapshot = gamesRef.child(gameId).child("players").child(attackerUid).child("gameLogic").get().await()
            val attackerLogic = FirebaseGameMapper.parseGameLogic(attackerSnapshot) ?: return
            val updatedAttackerArmy = attackerLogic.armies.find { it.id == attackerArmy.id }
            if (updatedAttackerArmy != null) {
                updatedAttackerArmy.units.clear()
                updatedAttackerArmy.units.addAll(attackerArmy.units)
                gamesRef.child(gameId).child("players").child(attackerUid).child("gameLogic").setValue(attackerLogic).await()
            }
        }

        // Проверяем уничтожение ратуши
        if (townHall.isDestroyed()) {
            gamesRef.child(gameId).child("winnerUid").setValue(attackerUid).await()
            gamesRef.child(gameId).child("gameState").setValue(GameState.FINISHED).await()
            Log.d("BATTLE", "Ратуша игрока $defenderUid уничтожена игроком $attackerUid")
        }
    }

    // 🔥 ВСПОМОГАТЕЛЬНЫЙ МЕТОД ДЛЯ БОЯ АРМИИ С ЗАЩИТНЫМИ ЮНИТАМИ
    private fun resolveUnitsCombat(attackerArmy: Army, defendingUnits: List<GameUnit>): CombatResult {
        val result = CombatResult()

        val attackerPower = attackerArmy.totalAttackPower()
        val defenderPower = defendingUnits.sumOf { it.attackPower }

        // Расчет эффективности
        val attackerEffectiveness = 0.7 + Math.random() * 0.6 // 0.7-1.3
        val defenderEffectiveness = 0.7 + Math.random() * 0.6 // 0.7-1.3

        val effectiveAttackerPower = (attackerPower * attackerEffectiveness).toInt()
        val effectiveDefenderPower = (defenderPower * defenderEffectiveness).toInt()

        // Применяем урон
        applyDamageToUnits(attackerArmy.units, effectiveDefenderPower)
        applyDamageToUnits(defendingUnits.toMutableList(), effectiveAttackerPower)

        // Собираем выживших
        result.attackerSurvivedUnits.addAll(attackerArmy.units.filter { it.health > 0 }.map {
            Army(units = mutableListOf(it), position = attackerArmy.position)
        })
        result.defenderSurvivedUnits.addAll(defendingUnits.filter { it.health > 0 })

        return result
    }

    // 🔥 ПРИМЕНЕНИЕ УРОНА К СПИСКУ ЮНИТОВ
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

        // Удаляем мертвых юнитов
        units.removeIf { it.health <= 0 }
    }

    // 🔥 ВСПОМОГАТЕЛЬНЫЙ КЛАСС ДЛЯ РЕЗУЛЬТАТОВ БОЯ
    private data class CombatResult(
        val attackerSurvivedUnits: MutableList<Army> = mutableListOf(),
        val defenderSurvivedUnits: MutableList<GameUnit> = mutableListOf()
    )

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