package com.example.chatapp.igra_strotegiy

import com.google.gson.*
import java.lang.reflect.Type

object GameUnitTypeAdapter : JsonDeserializer<GameUnit>, JsonSerializer<GameUnit> {

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): GameUnit {
        val obj = json.asJsonObject
        val type = obj.get("type")?.asString

        return when (type) {
            "Soldier" -> context.deserialize(json, GameUnit.Soldier::class.java)
            "Archer" -> context.deserialize(json, GameUnit.Archer::class.java)
            "Tank" -> context.deserialize(json, GameUnit.Tank::class.java)
            null -> {
                // Fallback по имени
                when (val name = obj.get("name")?.asString) {
                    "Soldier" -> context.deserialize(json, GameUnit.Soldier::class.java)
                    "Archer" -> context.deserialize(json, GameUnit.Archer::class.java)
                    "Tank" -> context.deserialize(json, GameUnit.Tank::class.java)
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