package com.example.chatapp.igra_strotegiy

import com.google.gson.*
import java.lang.reflect.Type

object BuildingTypeAdapter : JsonDeserializer<Building>, JsonSerializer<Building> {
    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): Building {
        val jsonObject = json.asJsonObject
        // Проверяем наличие поля "type"
        val typeField = jsonObject.get("type")

        return if (typeField != null && !typeField.isJsonNull) {
            // Если поле "type" есть, используем его
            when (val type = typeField.asString) {
                "Barracks" -> context.deserialize<Building.Barracks>(json, Building.Barracks::class.java)
                "TownHall" -> context.deserialize<Building.TownHall>(json, Building.TownHall::class.java)
                "Mine" -> context.deserialize<Building.Mine>(json, Building.Mine::class.java)
                else -> throw JsonParseException("Unknown building type: $type")
            }
        } else {
            // Если поле "type" отсутствует, пытаемся определить по другим полям (например, name)
            val name = jsonObject.get("name")?.asString
            when (name) {
                "Barracks" -> context.deserialize<Building.Barracks>(json, Building.Barracks::class.java)
                "Town Hall" -> context.deserialize<Building.TownHall>(json, Building.TownHall::class.java)
                "Mine" -> context.deserialize<Building.Mine>(json, Building.Mine::class.java)
                else -> throw JsonParseException("Unknown building: $name")
            }
        }
    }

    override fun serialize(src: Building, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        val jsonObject = JsonObject()
        jsonObject.addProperty("type", src::class.simpleName) // Добавляем тип при сохранении
        when (src) {
            is Building.Barracks -> return context.serialize(src, Building.Barracks::class.java).asJsonObject
            is Building.TownHall -> return context.serialize(src, Building.TownHall::class.java).asJsonObject
            is Building.Mine -> return context.serialize(src, Building.Mine::class.java).asJsonObject
        }
    }
}