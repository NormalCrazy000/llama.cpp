package com.example.llama

import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

/**
 * Minimal HTTP server that exposes the local chat.
 *
 * `POST /chat` with body `{"message":"hello"}` (or plain text) writes the message into the chat like a manual input, waits for the generation to end, then answers `{"response":"..."}`.
 *
 * [onMessage] blocks until the assistant answer is complete.
 */
class ChatHttpServer(
    private val port: Int,
    private val onMessage: (String) -> String
) {
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (serverSocket != null) return

        // Reuse the address so that toggling the endpoint off and on again binds right away
        ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) }.also { socket ->
            serverSocket = socket
            thread(name = "chat-http-accept") {
                Log.i(TAG, "Listening on port $port")
                while (!socket.isClosed) {
                    try {
                        socket.accept().let { client ->
                            thread(name = "chat-http-client") { handle(client) }
                        }
                    } catch (e: Exception) {
                        if (!socket.isClosed) Log.e(TAG, "Accept failed", e)
                    }
                }
                Log.i(TAG, "Stopped listening")
            }
        }
    }

    fun stop() {
        serverSocket?.close()
        serverSocket = null
    }

    private fun handle(client: Socket) = client.use { socket ->
        try {
            val input = socket.getInputStream()
            val requestLine = readLine(input).split(" ")
            val method = requestLine.getOrElse(0) { "" }
            val path = requestLine.getOrElse(1) { "" }.substringBefore('?')

            var contentLength = 0
            while (true) {
                val header = readLine(input)
                if (header.isEmpty()) break
                header.indexOf(':').let { separator ->
                    if (separator > 0 && header.take(separator).equals(HEADER_CONTENT_LENGTH, true)) {
                        contentLength = header.substring(separator + 1).trim().toIntOrNull() ?: 0
                    }
                }
            }

            val body = ByteArray(contentLength).also { DataInputStream(input).readFully(it) }
                .toString(StandardCharsets.UTF_8)

            if (method != "POST" || path != PATH_CHAT) {
                respond(socket, 404, JSONObject().put("error", "use POST $PATH_CHAT"))
                return@use
            }

            val message = parseMessage(body)
            if (message.isBlank()) {
                respond(socket, 400, JSONObject().put("error", "empty message"))
                return@use
            }

            Log.i(TAG, "Received message: $message")
            try {
                respond(socket, 200, JSONObject().put("response", onMessage(message)))
            } catch (e: Exception) {
                Log.e(TAG, "Generation failed", e)
                respond(socket, 503, JSONObject().put("error", e.message ?: "generation failed"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle request", e)
        }
    }

    /**
     * Take the `message` field of a JSON body, or the whole body if it is not JSON
     */
    private fun parseMessage(body: String) = try {
        JSONObject(body).optString("message")
    } catch (e: Exception) {
        body.trim()
    }

    private fun respond(socket: Socket, status: Int, json: JSONObject) {
        val payload = json.toString().toByteArray(StandardCharsets.UTF_8)
        socket.getOutputStream().let { output ->
            output.write(
                ("HTTP/1.1 $status ${statusText(status)}\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\n" +
                    "Content-Length: ${payload.size}\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(StandardCharsets.UTF_8)
            )
            output.write(payload)
            output.flush()
        }
    }

    private fun statusText(status: Int) = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        else -> "Service Unavailable"
    }

    /**
     * Read one CRLF terminated line, byte per byte to keep the body bytes untouched
     */
    private fun readLine(input: InputStream): String {
        val line = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte == -1 || byte == '\n'.code) break
            if (byte != '\r'.code) line.write(byte)
        }
        return line.toString(StandardCharsets.UTF_8.name())
    }

    companion object {
        private val TAG = ChatHttpServer::class.java.simpleName

        private const val HEADER_CONTENT_LENGTH = "Content-Length"
        private const val PATH_CHAT = "/chat"
    }
}
