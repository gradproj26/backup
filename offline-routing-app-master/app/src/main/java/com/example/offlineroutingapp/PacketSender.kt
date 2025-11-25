package com.example.offlineroutingapp

import java.io.OutputStream
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

object PacketSender {
    fun sendPacket(packet: RoutingPacket, hostAddress: String, port: Int = 8888) {
        Thread {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(hostAddress, port), 5000)

                val json = PacketUtils.toJson(packet)
                val output: OutputStream = socket.getOutputStream()
                val writer = PrintWriter(output, true)
                writer.println(json)

                socket.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }
}

