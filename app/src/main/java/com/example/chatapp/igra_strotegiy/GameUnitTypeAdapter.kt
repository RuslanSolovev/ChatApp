package com.example.chatapp.igra_strotegiy

import com.google.gson.*
import java.lang.reflect.Type

object GameUnitTypeAdapter : JsonDeserializer<GameUnit>, JsonSerializer<GameUnit> {
    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): GameUnit {
        val jsonObject = json.asJsonObject
        val type = jsonObject.get("type").asString

        return when (type) {
            "Soldier" -> context.deserialize<GameUnit.Soldier>(json, GameUnit.Soldier::class.java)
            "Archer" -> context.deserialize<GameUnit.Archer>(json, GameUnit.Archer::class.java)
            "Tank" -> context.deserialize<GameUnit.Tank>(json, GameUnit.Tank::class.java)
            else -> throw JsonParseException("Unknown unit type: $type")
        }
    }

    override fun serialize(src: GameUnit, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        val jsonObject = JsonObject()
        jsonObject.addProperty("type", src::class.simpleName)
        // Добавляем остальные поля
        when (src) {
            is GameUnit.Soldier -> return context.serialize(src, GameUnit.Soldier::class.java).asJsonObject
            is GameUnit.Archer -> return context.serialize(src, GameUnit.Archer::class.java).asJsonObject
            is GameUnit.Tank -> return context.serialize(src, GameUnit.Tank::class.java).asJsonObject
        }
    }
}