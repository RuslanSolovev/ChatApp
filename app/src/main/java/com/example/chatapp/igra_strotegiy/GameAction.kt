package com.example.chatapp.igra_strotegiy

sealed class GameAction {
    data class BuildBuilding(val building: Building, val x: Int, val y: Int) : GameAction()
    data class HireUnit(val unit: GameUnit) : GameAction()
    data class UpgradeBuilding(val building: Building) : GameAction()
    data class CompleteResearch(val research: Research) : GameAction()
    data class AttackTarget(val x: Int, val y: Int) : GameAction()
    data class AttackEnemyTownHall(val targetPlayerUid: String) : GameAction()
    data class EvolveToEra(val targetEra: Era) : GameAction() // ← ДОБАВЛЕНО
    object NextTurn : GameAction()
    data class CreateArmy(val unitCounts: Map<String, Int>) : GameAction()
    data class MoveArmy(val armyId: String, val targetX: Int, val targetY: Int) : GameAction()
    data class ReturnArmyToTownHall(val armyId: String) : GameAction()
    data class AttackWithArmy(val armyId: String, val targetX: Int, val targetY: Int) : GameAction()
}