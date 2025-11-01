// Resource.kt
package com.example.chatapp.igra_strotegiy

data class Resource(
    var wood: Int = 0,      // Дерево (доступно с Каменного века)
    var food: Int = 0,      // Еда (доступно с Каменного века)
    var water: Int = 0,     // Вода (доступно с Каменного века)
    var stone: Int = 0,     // Камень (доступно с Бронзового века)
    var gold: Int = 0,      // Золото (доступно с Бронзового века)
    var iron: Int = 0,      // Железо (доступно со Средневековья)
    var coal: Int = 0,      // Уголь (доступно с Индустриальной эры)
    var oil: Int = 0,       // Нефть (доступно с Индустриальной эры)
    var energy: Int = 0     // Энергия (доступно с Футуристической эры)
) {
    fun hasEnough(cost: Resource, era: Era = Era.STONE_AGE): Boolean {
        // Проверяем только те ресурсы, которые доступны в текущей эпохе
        if (era.ordinal < Era.BRONZE_AGE.ordinal && (cost.stone > 0 || cost.gold > 0 || cost.iron > 0 || cost.coal > 0 || cost.oil > 0 || cost.energy > 0))
            return false

        if (era.ordinal < Era.MIDDLE_AGES.ordinal && (cost.iron > 0 || cost.coal > 0 || cost.oil > 0 || cost.energy > 0))
            return false

        if (era.ordinal < Era.INDUSTRIAL.ordinal && (cost.coal > 0 || cost.oil > 0 || cost.energy > 0))
            return false

        if (era.ordinal < Era.FUTURE.ordinal && cost.energy > 0)
            return false

        return wood >= cost.wood && food >= cost.food && water >= cost.water &&
                stone >= cost.stone && gold >= cost.gold && iron >= cost.iron &&
                coal >= cost.coal && oil >= cost.oil && energy >= cost.energy
    }

    fun subtract(cost: Resource) {
        wood -= cost.wood
        food -= cost.food
        water -= cost.water
        stone -= cost.stone
        gold -= cost.gold
        iron -= cost.iron
        coal -= cost.coal
        oil -= cost.oil
        energy -= cost.energy

        // Гарантируем, что ресурсы не уйдут в минус
        if (wood < 0) wood = 0
        if (food < 0) food = 0
        if (water < 0) water = 0
        if (stone < 0) stone = 0
        if (gold < 0) gold = 0
        if (iron < 0) iron = 0
        if (coal < 0) coal = 0
        if (oil < 0) oil = 0
        if (energy < 0) energy = 0
    }

    fun add(other: Resource) {
        wood += other.wood
        food += other.food
        water += other.water
        stone += other.stone
        gold += other.gold
        iron += other.iron
        coal += other.coal
        oil += other.oil
        energy += other.energy
    }

    fun getAvailableResources(era: Era): String {
        val parts = mutableListOf<String>()
        if (wood > 0) parts.add("Дерево: $wood")
        if (food > 0) parts.add("Еда: $food")
        if (water > 0) parts.add("Вода: $water")

        if (era.ordinal >= Era.BRONZE_AGE.ordinal) {
            if (stone > 0) parts.add("Камень: $stone")
            if (gold > 0) parts.add("Золото: $gold")
        }

        if (era.ordinal >= Era.MIDDLE_AGES.ordinal) {
            if (iron > 0) parts.add("Железо: $iron")
        }

        if (era.ordinal >= Era.INDUSTRIAL.ordinal) {
            if (coal > 0) parts.add("Уголь: $coal")
            if (oil > 0) parts.add("Нефть: $oil")
        }

        if (era.ordinal >= Era.FUTURE.ordinal) {
            if (energy > 0) parts.add("Энергия: $energy")
        }

        return if (parts.isEmpty()) "Ресурсы отсутствуют" else parts.joinToString(", ")
    }

    override fun toString(): String {
        return getAvailableResources(Era.FUTURE) // Показываем все ресурсы
    }

    // Метод для копирования
    fun copy(): Resource {
        return Resource(
            wood = this.wood,
            food = this.food,
            water = this.water,
            stone = this.stone,
            gold = this.gold,
            iron = this.iron,
            coal = this.coal,
            oil = this.oil,
            energy = this.energy
        )
    }
}