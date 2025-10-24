package com.example.chatapp.igra_strotegiy

data class Resource(
    var wood: Int = 0,
    var food: Int = 0,
    var water: Int = 0,
    var stone: Int = 0,
    var gold: Int = 0
) {
    fun hasEnough(cost: Resource): Boolean {
        return wood >= cost.wood &&
                food >= cost.food &&
                water >= cost.water &&
                stone >= cost.stone &&
                gold >= cost.gold
    }

    fun subtract(cost: Resource) {
        wood -= cost.wood
        food -= cost.food
        water -= cost.water
        stone -= cost.stone
        gold -= cost.gold
    }

    fun add(other: Resource) {
        wood += other.wood
        food += other.food
        water += other.water
        stone += other.stone
        gold += other.gold
    }

    override fun toString(): String {
        return "Дерево: $wood, Еда: $food, Вода: $water, Камень: $stone, Золото: $gold"
    }
}