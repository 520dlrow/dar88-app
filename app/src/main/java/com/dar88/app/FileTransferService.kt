package com.dar88.app

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class FileTransferService(
    private val onFileReceived: (fromId: String, fromName: String, type: String, data: String) -> Unit
) {
    companion object {
        const val PORT = 9999
        const val MAGIC = "DAR88FILE"
    }

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (running) return
        running = true
        thread { listenLoop() }
    }

    private fun listenLoop() {
        try {
            serverSocket = ServerSocket(PORT)
            while (running) {
                val client = serverSocket!!.accept()
                thread { handleClient(client) }
            }
        } catch (e: Exception) {
            if (running) e.printStackTrace()
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 20000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val header = reader.readLine() ?: return
            val parts = header.split("|")
            if (parts.size < 5 || parts[0] != MAGIC) return
            val fromId = parts[1]
            val fromName = parts[2]
            val type = parts[3]
            val length = parts[4].toIntOrNull() ?: return
            if (length <= 0 || length > 3_000_000) return

            val buffer = CharArray(length)
            var read = 0
            while (read < length) {
                val r = reader.read(buffer, read, length - read)
                if (r <= 0) break
                read += r
            }
            val data = String(buffer, 0, read)
            onFileReceived(fromId, fromName, type, data)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    fun sendFile(address: String, fromId: String, fromName: String, type: String, data: String): Boolean {
        return try {
            val socket = Socket(address, PORT)
            socket.soTimeout = 20000
            val writer = socket.getOutputStream()
            val header = "$MAGIC|$fromId|$fromName|$type|${data.length}\n"
            writer.write(header.toByteArray(Charsets.UTF_8))
            writer.write(data.toByteArray(Charsets.UTF_8))
            writer.flush()
            socket.close()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }
}
