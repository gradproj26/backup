package com.example.offlineroutingapp

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket

object PacketReceiver {
    fun startListening(port: Int = 8888) {
        Thread {
            try {
                val serverSocket = ServerSocket(port)

                while (true) {
                    val client = serverSocket.accept()
                    val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                    val json = reader.readLine()

                    val packet = PacketUtils.fromJson(json)
                    println("Received Packet: $packet")

                    client.close()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }
}

