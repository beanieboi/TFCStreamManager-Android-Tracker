package com.tfcleipzig.tracker.debug

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * A tiny HTTP server for the debug page, so the phone can be watched and driven from another
 * phone at the table while it hangs out of reach: `http://<phone ip>:8081/`.
 *
 * GET and POST only, parameters in the query string, one request per connection. Anyone on the
 * network can use it, like adb on these phones; it's a debug tool for our own network.
 */
class DebugServer(
    private val port: Int = PORT,
    private val handle: (Request) -> Response,
) {
    data class Request(
        val method: String,
        val path: String,
        val params: Map<String, String>,
    )

    class Response(
        val status: Int,
        val type: String,
        val body: ByteArray,
    ) {
        companion object {
            fun text(
                body: String,
                type: String = "text/plain; charset=utf-8",
                status: Int = 200,
            ) = Response(status, type, body.toByteArray())

            fun json(body: String) = text(body, "application/json")

            val notFound = text("not found", status = 404)
        }
    }

    private val pool = Executors.newFixedThreadPool(THREADS)
    private var socket: ServerSocket? = null

    fun start() {
        if (socket != null) return
        val server = ServerSocket(port).also { socket = it }
        Thread({
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                pool.execute { serve(client) }
            }
        }, "debug-server").apply { isDaemon = true }.start()
        Log.i(TAG, "debug page on port $port")
    }

    fun stop() {
        socket?.close()
        socket = null
    }

    private fun serve(client: Socket) {
        client.use { s ->
            runCatching {
                s.soTimeout = TIMEOUT_MS
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val requestLine = reader.readLine() ?: return
                while (!reader.readLine().isNullOrEmpty()) Unit // headers; bodies aren't used
                val (method, target) = requestLine.split(" ").let { it[0] to it.getOrElse(1) { "/" } }
                val path = target.substringBefore('?')
                val params =
                    target
                        .substringAfter('?', "")
                        .split('&')
                        .filter { it.isNotEmpty() }
                        .associate { p ->
                            URLDecoder.decode(p.substringBefore('='), "UTF-8") to URLDecoder.decode(p.substringAfter('=', ""), "UTF-8")
                        }
                val response =
                    runCatching {
                        handle(
                            Request(method, path, params),
                        )
                    }.getOrElse { Response.text("error: ${it.message}", status = 500) }
                val out = s.getOutputStream()
                out.write(
                    (
                        "HTTP/1.1 ${response.status} ${if (response.status < 400) "OK" else "Error"}\r\n" +
                            "Content-Type: ${response.type}\r\nContent-Length: ${response.body.size}\r\n" +
                            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
                    ).toByteArray(),
                )
                out.write(response.body)
                out.flush()
            }.onFailure { Log.w(TAG, "request failed", it) }
        }
    }

    companion object {
        const val PORT = 8081
        private const val THREADS = 4
        private const val TIMEOUT_MS = 5000
        private const val TAG = "DebugServer"
    }
}
