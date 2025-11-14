package com.example.chatapp.igra_strotegiy

data class BattlePreview(
    val attackerArmy: Army,
    val defenderArmy: Army? = null,
    val defenderTownHall: Building.TownHall? = null,
    val defenderUid: String? = null,
    val defenderName: String? = null,
    val targetPosition: Position,
    val isTownHallAttack: Boolean = false
) {
    val attackerTotalPower: Int get() = attackerArmy.totalAttackPower()
    val defenderTotalPower: Int get() = defenderArmy?.totalAttackPower() ?: defenderTownHall?.health ?: 0

    fun calculateBattleResult(): BattleResult {
        val result = BattleResult()

        if (defenderArmy != null) {
            // 🔥 УЛУЧШЕННАЯ ЛОГИКА БОЯ АРМИЯ VS АРМИЯ
            result.attackerSurvivedUnits = simulateArmyCombat(attackerArmy, defenderArmy, true)
            result.defenderSurvivedUnits = simulateArmyCombat(defenderArmy, attackerArmy, false)

            result.attackerPowerRemaining = result.attackerSurvivedUnits.sumOf { it.totalAttackPower() }
            result.defenderPowerRemaining = result.defenderSurvivedUnits.sumOf { it.totalAttackPower() }

            // Победа, если у защитника не осталось армии
            result.victory = result.defenderPowerRemaining == 0 && result.attackerPowerRemaining > 0

        } else if (defenderTownHall != null) {
            // 🔥 УЛУЧШЕННАЯ ЛОГИКА БОЯ АРМИЯ VS РАТУША
            val armyPower = attackerTotalPower
            val townHallHealth = defenderTownHall.health

            if (armyPower >= townHallHealth) {
                // Ратуша уничтожена
                result.victory = true
                result.townHallDestroyed = true

                // Рассчитываем потери армии при штурме ратуши
                val lossesRatio = townHallHealth.toDouble() / armyPower
                val survivingArmy = calculateArmyLosses(attackerArmy, lossesRatio)
                result.attackerSurvivedUnits = if (survivingArmy.units.isNotEmpty()) listOf(survivingArmy) else emptyList()
                result.attackerPowerRemaining = survivingArmy.totalAttackPower()
            } else {
                // Ратуша выстояла, армия уничтожена
                result.victory = false
                result.townHallHealthRemaining = townHallHealth - armyPower
                result.attackerSurvivedUnits = emptyList()
                result.attackerPowerRemaining = 0
            }
        }

        return result
    }

    // 🔥 ИСПРАВЛЕННАЯ ЛОГИКА СИМУЛЯЦИИ БОЯ МЕЖДУ АРМИЯМИ
    private fun simulateArmyCombat(attackingArmy: Army, defendingArmy: Army, isAttacker: Boolean): List<Army> {
        val survivingUnits = mutableListOf<GameUnit>()
        val remainingAttackPower = attackingArmy.totalAttackPower()
        val remainingDefensePower = defendingArmy.totalAttackPower()

        // Рассчитываем эффективность атаки
        val attackEffectiveness = if (isAttacker) 1.0 else 0.8 // Защитники атакуют с 80% эффективностью

        // Общий урон, который может нанести эта армия
        val totalDamage = (remainingAttackPower * attackEffectiveness).toInt()

        // Если урон больше 0, распределяем его по юнитам защищающейся армии
        if (totalDamage > 0) {
            // Сортируем юнитов защищающейся армии по здоровью (сначала слабые)
            val sortedDefendingUnits = defendingArmy.units.sortedBy { it.health }
            var remainingDamage = totalDamage

            // Пошагово уничтожаем юнитов защищающейся армии
            for (unit in sortedDefendingUnits) {
                if (remainingDamage <= 0) break

                val damageToUnit = minOf(remainingDamage, unit.health)
                remainingDamage -= damageToUnit

                // Если юнит выжил после атаки, добавляем его в выжившие
                if (unit.health > damageToUnit) {
                    val survivingUnit = createUnitCopyWithHealth(unit, unit.health - damageToUnit)
                    survivingUnits.add(survivingUnit)
                }
                // Если юнит получает урон равный или больше его здоровья - он погибает
            }
        } else {
            // Если атакующая армия не может нанести урон, защищающаяся остается без потерь
            survivingUnits.addAll(defendingArmy.units.map { createUnitCopyWithHealth(it, it.health) })
        }

        // Возвращаем выжившую армию (если есть выжившие)
        return if (survivingUnits.isNotEmpty()) {
            listOf(Army(units = survivingUnits.toMutableList(), position = defendingArmy.position))
        } else {
            emptyList()
        }
    }

    // 🔥 ПОЛНОСТЬЮ ПЕРЕПИСАННЫЙ РАСЧЕТ ПОТЕРЬ АРМИИ - ТЕПЕРЬ МОЖЕТ ПОЛНОСТЬЮ УНИЧТОЖИТЬ
    private fun calculateArmyLosses(army: Army, lossesRatio: Double): Army {
        if (army.units.isEmpty()) return army

        // 🔥 ПРОСТАЯ И ЭФФЕКТИВНАЯ ЛОГИКА: удаляем процент юнитов на основе lossesRatio
        val totalUnits = army.units.size
        val unitsToSurvive = (totalUnits * (1 - lossesRatio)).toInt().coerceAtLeast(0)

        // Если нужно уничтожить всех юнитов - возвращаем пустую армию
        if (unitsToSurvive == 0) {
            return Army(
                id = army.id,
                units = mutableListOf(),
                position = army.position,
                hasMovedThisTurn = army.hasMovedThisTurn
            )
        }

        // Сортируем юнитов по здоровью (оставляем самых сильных)
        val sortedUnits = army.units.sortedByDescending { it.health }

        // Берем только выживших юнитов
        val survivingUnits = sortedUnits.take(unitsToSurvive).map {
            createUnitCopyWithHealth(it, it.health)
        }.toMutableList()

        return Army(
            id = army.id,
            units = survivingUnits,
            position = army.position,
            hasMovedThisTurn = army.hasMovedThisTurn
        )
    }

    private fun createUnitCopyWithHealth(unit: GameUnit, newHealth: Int): GameUnit {
        return when (unit) {
            is GameUnit.Caveman -> GameUnit.Caveman().apply { health = newHealth.coerceAtLeast(0) }
            is GameUnit.Hunter -> GameUnit.Hunter().apply { health = newHealth.coerceAtLeast(0) }
            is GameUnit.MammothRider -> GameUnit.MammothRider().apply { health = newHealth.coerceAtLeast(0) }
            is GameUnit.Swordsman -> GameUnit.Swordsman().apply { health = newHealth.coerceAtLeast(0) }
            is GameUnit.BronzeArcher -> GameUnit.BronzeArcher().apply { health = newHealth.coerceAtLeast(0) }
            is GameUnit.Chariot -> GameUnit.Chariot().apply { health = newHealth.coerceAtLeast(0) }
            is GameUnit.Knight -> GameUnit.Knight().apply { health = newHealth.coerceAtLeast(0) }
            is GameUnit.Crossbowman -> GameUnit.Crossbowman().apply { health = newHealth.coerceAtLeast(0) }
            is GameUnit.Ram -> GameUnit.Ram().apply { health = newHealth.coerceAtLeast(0) }
            is GameUnit.Soldier -> GameUnit.Soldier().apply { health = newHealth.coerceAtLeast(0) }
            is GameUnit.Artillery -> GameUnit.Artillery().apply { health = newHealth.coerceAtLeast(0) }
            is GameUnit.Tank -> GameUnit.Tank().apply { health = newHealth.coerceAtLeast(0) }
            is GameUnit.Drone -> GameUnit.Drone().apply { health = newHealth.coerceAtLeast(0) }
            is GameUnit.Mech -> GameUnit.Mech().apply { health = newHealth.coerceAtLeast(0) }
            is GameUnit.LaserCannon -> GameUnit.LaserCannon().apply { health = newHealth.coerceAtLeast(0) }
            is GameUnit.FishingBoat -> GameUnit.FishingBoat().apply { health = newHealth.coerceAtLeast(0) }
            is GameUnit.WarGalley -> GameUnit.WarGalley().apply { health = newHealth.coerceAtLeast(0) }
            is GameUnit.TransportBarge -> GameUnit.TransportBarge().apply { health = newHealth.coerceAtLeast(0) }
            else -> throw IllegalArgumentException("Неизвестный тип юнита: ${unit::class.java.simpleName}")
        }
    }
}

data class BattleResult(
    var victory: Boolean = false,
    var attackerSurvivedUnits: List<Army> = emptyList(),
    var defenderSurvivedUnits: List<Army> = emptyList(),
    var attackerPowerRemaining: Int = 0,
    var defenderPowerRemaining: Int = 0,
    var townHallDestroyed: Boolean = false,
    var townHallHealthRemaining: Int = 0
) {
    fun getResultMessage(): String {
        return if (victory) {
            if (townHallDestroyed) {
                "✅ ПОБЕДА! Вражеская ратуша уничтожена!"
            } else {
                "✅ ПОБЕДА! Вражеская армия разбита!"
            }
        } else {
            if (townHallDestroyed) {
                "❌ ПОРАЖЕНИЕ! Ваша армия уничтожена при штурме ратуши"
            } else {
                "❌ ПОРАЖЕНИЕ! Ваша армия разбита"
            }
        }
    }
}