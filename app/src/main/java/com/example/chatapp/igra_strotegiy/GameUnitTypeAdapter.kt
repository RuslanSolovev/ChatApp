package com.example.chatapp.igra_strotegiy

import com.google.gson.*
import java.lang.reflect.Type

object GameUnitTypeAdapter : JsonDeserializer<GameUnit>, JsonSerializer<GameUnit> {

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): GameUnit {
        val obj = json.asJsonObject
        val type = obj.get("type")?.asString

        return when (type) {
            // Каменный век
            "Caveman" -> context.deserialize(json, GameUnit.Caveman::class.java)
            "Hunter" -> context.deserialize(json, GameUnit.Hunter::class.java)
            "MammothRider" -> context.deserialize(json, GameUnit.MammothRider::class.java)

            // Бронзовый век
            "FishingBoat" -> context.deserialize(json, GameUnit.FishingBoat::class.java)
            "WarGalley" -> context.deserialize(json, GameUnit.WarGalley::class.java)
            "TransportBarge" -> context.deserialize(json, GameUnit.TransportBarge::class.java)
            "Swordsman" -> context.deserialize(json, GameUnit.Swordsman::class.java)
            "BronzeArcher" -> context.deserialize(json, GameUnit.BronzeArcher::class.java)
            "Chariot" -> context.deserialize(json, GameUnit.Chariot::class.java)

            // Средневековье
            "Knight" -> context.deserialize(json, GameUnit.Knight::class.java)
            "Crossbowman" -> context.deserialize(json, GameUnit.Crossbowman::class.java)
            "Ram" -> context.deserialize(json, GameUnit.Ram::class.java)

            // Индустриальная эра
            "Soldier" -> context.deserialize(json, GameUnit.Soldier::class.java)
            "Artillery" -> context.deserialize(json, GameUnit.Artillery::class.java)
            "Tank" -> context.deserialize(json, GameUnit.Tank::class.java)

            // Футуристическая эра
            "Drone" -> context.deserialize(json, GameUnit.Drone::class.java)
            "Mech" -> context.deserialize(json, GameUnit.Mech::class.java)
            "LaserCannon" -> context.deserialize(json, GameUnit.LaserCannon::class.java)

            null -> {
                // Fallback по имени
                when (val name = obj.get("name")?.asString) {
                    "Пещерный человек" -> context.deserialize(json, GameUnit.Caveman::class.java)
                    "Охотник" -> context.deserialize(json, GameUnit.Hunter::class.java)
                    "Всадник на мамонте" -> context.deserialize(json, GameUnit.MammothRider::class.java)
                    "Мечник" -> context.deserialize(json, GameUnit.Swordsman::class.java)
                    "Лучник" -> context.deserialize(json, GameUnit.BronzeArcher::class.java)
                    "Боевая колесница" -> context.deserialize(json, GameUnit.Chariot::class.java)
                    "Рыцарь" -> context.deserialize(json, GameUnit.Knight::class.java)
                    "Арбалетчик" -> context.deserialize(json, GameUnit.Crossbowman::class.java)
                    "Таран" -> context.deserialize(json, GameUnit.Ram::class.java)
                    "Солдат" -> context.deserialize(json, GameUnit.Soldier::class.java)
                    "Артиллерия" -> context.deserialize(json, GameUnit.Artillery::class.java)
                    "Танк" -> context.deserialize(json, GameUnit.Tank::class.java)
                    "Боевой дрон" -> context.deserialize(json, GameUnit.Drone::class.java)
                    "Боевой мех" -> context.deserialize(json, GameUnit.Mech::class.java)
                    "Лазерная пушка" -> context.deserialize(json, GameUnit.LaserCannon::class.java)
                    "Рыболовный корабль" -> context.deserialize(json, GameUnit.FishingBoat::class.java)
                    "Военный галеон" -> context.deserialize(json, GameUnit.WarGalley::class.java)
                    "Транспортный барж" -> context.deserialize(json, GameUnit.TransportBarge::class.java)
                    else -> throw JsonParseException("Unknown unit name: $name")
                }
            }
            else -> throw JsonParseException("Unknown unit type: $type")
        }
    }

    override fun serialize(src: GameUnit, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        val json = context.serialize(src).asJsonObject
        json.addProperty("type", src::class.simpleName)
        return json
    }
}