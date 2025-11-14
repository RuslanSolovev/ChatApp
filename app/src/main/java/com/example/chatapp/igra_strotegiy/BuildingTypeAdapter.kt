package com.example.chatapp.igra_strotegiy

import com.google.gson.*
import java.lang.reflect.Type

object BuildingTypeAdapter : JsonDeserializer<Building>, JsonSerializer<Building> {
    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): Building {
        val obj = json.asJsonObject
        val type = obj.get("type")?.asString
        return when (type) {
            "hut" -> context.deserialize(json, Building.Hut::class.java)
            "well" -> context.deserialize(json, Building.Well::class.java)
            "sawmill" -> context.deserialize(json, Building.Sawmill::class.java)
            "fishing_hut" -> context.deserialize(json, Building.FishingHut::class.java)
            "farm" -> context.deserialize(json, Building.Farm::class.java)
            "quarry" -> context.deserialize(json, Building.Quarry::class.java)
            "gold_mine" -> context.deserialize(json, Building.GoldMine::class.java)
            "forge" -> context.deserialize(json, Building.Forge::class.java)
            "iron_mine" -> context.deserialize(json, Building.IronMine::class.java)
            "castle" -> context.deserialize(json, Building.Castle::class.java)
            "blacksmith" -> context.deserialize(json, Building.Blacksmith::class.java)
            "coal_mine" -> context.deserialize(json, Building.CoalMine::class.java)
            "oil_rig" -> context.deserialize(json, Building.OilRig::class.java)
            "factory" -> context.deserialize(json, Building.Factory::class.java)
            "power_plant" -> context.deserialize(json, Building.PowerPlant::class.java)
            "solar_plant" -> context.deserialize(json, Building.SolarPlant::class.java)
            "nuclear_plant" -> context.deserialize(json, Building.NuclearPlant::class.java)
            "robotics_lab" -> context.deserialize(json, Building.RoboticsLab::class.java)
            "barracks" -> context.deserialize(json, Building.Barracks::class.java)
            "research_center" -> context.deserialize(json, Building.ResearchCenter::class.java)
            "town_hall" -> context.deserialize(json, Building.TownHall::class.java)
            "shipyard" -> context.deserialize(json, Building.Shipyard::class.java)
            else -> throw JsonParseException("Unknown building type: $type")
        }
    }

    override fun serialize(src: Building, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        val json = context.serialize(src).asJsonObject
        // 🔥 ВАЖНО: сохраняем type как src.type, а не как имя класса!
        json.addProperty("type", src.type)
        return json
    }
}