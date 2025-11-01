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
}