package com.example.chatapp.igra_strotegiy

import com.google.gson.*
import java.lang.reflect.Type

object BuildingTypeAdapter : JsonDeserializer<Building>, JsonSerializer<Building> {

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): Building {
        val obj = json.asJsonObject
        val type = obj.get("type")?.asString

        return when (type) {
            "Barracks" -> context.deserialize(json, Building.Barracks::class.java)
            "TownHall" -> context.deserialize(json, Building.TownHall::class.java)
            "Mine" -> context.deserialize(json, Building.GoldMine::class.java)
            null -> {
                // Fallback: определяем по имени
                when (val name = obj.get("name")?.asString) {
                    "Barracks" -> context.deserialize(json, Building.Barracks::class.java)
                    "Town Hall" -> context.deserialize(json, Building.TownHall::class.java)
                    "Mine" -> context.deserialize(json, Building.GoldMine::class.java)
                    else -> throw JsonParseException("Unknown building name: $name")
                }
            }
            else -> throw JsonParseException("Unknown building type: $type")
        }
    }

    override fun serialize(src: Building, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        val json = context.serialize(src).asJsonObject
        json.addProperty("type", src::class.simpleName)
        return json
    }
}