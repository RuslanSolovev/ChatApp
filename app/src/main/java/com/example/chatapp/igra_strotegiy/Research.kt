package com.example.chatapp.igra_strotegiy

sealed class Research {
    abstract val name: String
    abstract val description: String
    abstract val cost: Resource // Меняем на ресурсы вместо очков
    abstract val era: Era

    // Каменный век
    object StoneTools : Research() {
        override val name = "Каменные инструменты"
        override val description = "+20% к добыче ресурсов"
        override val cost = Resource(food = 20, wood = 15, water = 10)
        override val era = Era.STONE_AGE
    }

    object BasicAgriculture : Research() {
        override val name = "Земледелие"
        override val description = "Открывает фермы"
        override val cost = Resource(food = 25, wood = 20, water = 15)
        override val era = Era.STONE_AGE
    }

    // Бронзовый век
    object BronzeWorking : Research() {
        override val name = "Обработка бронзы"
        override val description = "Открывает бронзовое оружие"
        override val cost = Resource(food = 40, stone = 30, gold = 20)
        override val era = Era.BRONZE_AGE
    }

    object Currency : Research() {
        override val name = "Денежная система"
        override val description = "+25% к добыче золота"
        override val cost = Resource(gold = 35, stone = 25, food = 20)
        override val era = Era.BRONZE_AGE
    }

    // Средневековье
    object IronWorking : Research() {
        override val name = "Кузнечное дело"
        override val description = "Открывает железное оружие"
        override val cost = Resource(iron = 50, gold = 30, stone = 40)
        override val era = Era.MIDDLE_AGES
    }

    object Engineering : Research() {
        override val name = "Инженерия"
        override val description = "+30% к прочности зданий"
        override val cost = Resource(iron = 45, stone = 35, gold = 25)
        override val era = Era.MIDDLE_AGES
    }

    // Индустриальная эра
    object SteamPower : Research() {
        override val name = "Паровой двигатель"
        override val description = "Открывает паровые технологии"
        override val cost = Resource(coal = 60, iron = 50, oil = 40)
        override val era = Era.INDUSTRIAL
    }

    object Electricity : Research() {
        override val name = "Электричество"
        override val description = "+40% к производству энергии"
        override val cost = Resource(energy = 70, coal = 50, iron = 45)
        override val era = Era.INDUSTRIAL
    }

    // Футуристическая эра
    object NuclearPower : Research() {
        override val name = "Ядерная энергия"
        override val description = "Открывает ядерные технологии"
        override val cost = Resource(energy = 120, oil = 80, gold = 60)
        override val era = Era.FUTURE
    }

    object ArtificialIntelligence : Research() {
        override val name = "Искусственный интеллект"
        override val description = "+50% ко всем исследованиям"
        override val cost = Resource(energy = 150, oil = 100, gold = 80)
        override val era = Era.FUTURE
    }

    // Resource.kt - добавить метод multiply
    fun Resource.multiply(factor: Int) {
        wood *= factor
        food *= factor
        water *= factor
        stone *= factor
        gold *= factor
        iron *= factor
        coal *= factor
        oil *= factor
        energy *= factor
    }
}