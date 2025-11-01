package com.example.chatapp.igra_strotegiy

sealed class AttackResult {
    object UnitsAttacked : AttackResult()
    data class UnitsKilled(val unitTypes: List<String>) : AttackResult()
    data class BaseAttacked(val damage: Int, val currentHp: Int, val maxHp: Int) : AttackResult()
    object NoTarget : AttackResult()
}