package com.dar88.app

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.UUID
import kotlin.concurrent.thread

class UdpService(
    private val onPeer: (String, String) -> Unit,
    private val onPeerGone: (String) -> Unit,
    private val onMessage: (String, String, String) -> Unit
) {
    companion object {
        const val PORT = 8888
        const val MAGIC = "DAR88"
        const val HELLO_INTERVAL = 2000L
        const val PEER_TIMEOUT = 8000L
    }

    private val myId = UUID.randomUUID().toString().take(8)
    private var myName = ""
    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private val peers = mutableMapOf<String, PeerInfo>()

    data class PeerInfo(
        val id: String,
        var name: String,
        var address: InetAddress,
        var lastSeen: Long
    )

    fun start(name: String) {
        if (running) return
        myName = name
        running = true

        try {
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(PORT))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            running = false
            return
        }

        thread { listenLoop() }
        thread { broadcastLoop() }
        thread { cleanupLoop() }
    }

    private fun listenLoop() {
        val buf = ByteArray(65535)
        while (running) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket?.receive(packet)
                handlePacket(packet)
            } catch (e: Exception) {
                if (running) e.printStackTrace()
            }
        }
    }

    private fun broadcastLoop() {
        while (running) {
            try {
                val msg = "$MAGIC:HELLO:$myId:$myName"
                val bytes = msg.toByteArray()
                broadcastBytes(bytes)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            Thread.sleep(HELLO_INTERVAL)
        }
    }

    private fun broadcastBytes(bytes: ByteArray) {
        try {
            socket?.send(DatagramPacket(
                bytes, bytes.size,
                InetAddress.getByName("255.255.255.255"), PORT
            ))
        } catch (_: Exception) {}

        try {
            NetworkInterface.getNetworkInterfaces().toList().forEach { iface ->
                if (!iface.isUp || iface.isLoopback) return@forEach
                iface.interfaceAddresses.forEach { addr ->
                    val bcast = addr.broadcast
                    if (bcast != null) {
                        try {
                            socket?.send(DatagramPacket(bytes, bytes.size, bcast, PORT))
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {}
    }

    fun sendToAll(fromName: String, text: String) {
        val msg = "$MAGIC:MSG:$myId:$fromName:$text"
        broadcastBytes(msg.toByteArray())
    }

    private fun cleanupLoop() {
        while (running) {
            val now = System.currentTimeMillis()
            val gone = peers.filter { now - it.value.lastSeen > PEER_TIMEOUT }.keys.toList()
            gone.forEach {
                peers.remove(it)
                onPeerGone(it)
            }
            Thread.sleep(2000)
        }
    }

    private fun handlePacket(packet: DatagramPacket) {
        val msg = String(packet.data, 0, packet.length)
        val parts = msg.split(":", limit = 5)
        if (parts.size < 3 || parts[0] != MAGIC) return

        when (parts[1]) {
            "HELLO" -> {
                if (parts.size < 4) return
                val id = parts[2]
                val name = parts[3]
                if (id == myId) return
                val isNew = !peers.containsKey(id)
                peers[id] = PeerInfo(id, name, packet.address, System.currentTimeMillis())
                if (isNew) onPeer(id, name)
            }
            "MSG" -> {
                if (parts.size < 5) return
                val fromId = parts[2]
                val fromName = parts[3]
                val text = parts[4]
                peers[fromId]?.lastSeen = System.currentTimeMillis()
                onMessage(fromId, fromName, text)
            }
        }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        peers.clear()
    }
}
