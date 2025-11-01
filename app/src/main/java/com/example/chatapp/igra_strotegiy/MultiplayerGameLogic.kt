package com.example.chatapp.igra_strotegiy

import com.example.chatapp.models.User
import com.google.firebase.database.DatabaseReference
import kotlinx.coroutines.tasks.await

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

        val updatedLogic = applyActions(player.gameLogic.deepCopy(), actions)
        gamesRef.child(gameId).child("players").child(playerUid).child("gameLogic").setValue(updatedLogic).await()

        // Обработка атаки на вражескую ратушу
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
                    // 🔥 ГАРАНТИРУЕМ обновление в списке
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

        // Завершение хода → сбор ресурсов
        if (actions.any { it is GameAction.NextTurn }) {
            val logicAfterTurn = updatedLogic.deepCopy()
            logicAfterTurn.nextTurn()
            gamesRef.child(gameId).child("players").child(playerUid).child("gameLogic").setValue(logicAfterTurn).await()
            val next = game.getNextPlayerUid()
            gamesRef.child(gameId).child("currentTurnUid").setValue(next).await()
            gamesRef.child(gameId).child("lastTurnTime").setValue(System.currentTimeMillis()).await()
        }

        checkWinConditions(gameId, updatedLogic, playerUid)
        return true
    }

    private fun applyActions(gameLogic: GameLogic, actions: List<GameAction>): GameLogic {
        val updated = gameLogic.deepCopy()
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
                else -> {}
            }
        }
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