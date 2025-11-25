package com.example.offlineroutingapp
import com.google.gson.Gson

object PacketUtils {
    private val gson = Gson()

    fun toJson(packet: RoutingPacket): String {
        return gson.toJson(packet)
    }

    fun fromJson(json: String): RoutingPacket {
        return gson.fromJson(json, RoutingPacket::class.java)
    }
}

data class RoutingPacket(
    val src: String,
    val dst: String,
    val via: String,
    var hop: Int,
    val maxHop: Int = 5,
    val msg: String
)