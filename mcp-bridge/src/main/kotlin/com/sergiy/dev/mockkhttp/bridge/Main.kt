package com.sergiy.dev.mockkhttp.bridge

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Entry point of the MockkHttp stdio MCP bridge.
 *
 * The single most important property of this process is that **it must never die on its own**. An
 * MCP server that exits during start-up does not fail gracefully: the client marks the whole server
 * as broken and every `mockkhttp_*` tool disappears from the session, usually with a message the
 * user cannot act on. So there is no configuration to validate here, nothing to connect to at
 * start-up and no failure path that reaches `exitProcess`. If the IDE is not running, if the
 * instance file is missing, corrupt or stale, if the token was rotated — `tools/list` still answers
 * and every `tools/call` comes back as a normal result with `isError: true` explaining what to do.
 *
 * Discovery deliberately happens on the FIRST TOOL CALL and is re-read after a connection failure,
 * never once at boot, so an IDE restarted (new port, new token) mid-session heals by itself.
 */
fun main(args: Array<String>) {
    // The real stdout is the JSON-RPC channel and nothing else may touch it: one stray println
    // corrupts the stream and the client drops the connection. Grab the file descriptor first, then
    // point System.out at stderr so even third-party noise lands somewhere harmless.
    val protocolOut = PrintStream(BufferedOutputStream(FileOutputStream(FileDescriptor.out), 1 shl 16), false, StandardCharsets.UTF_8)
    System.setOut(System.err)

    if (args.any { it == "--version" || it == "-v" }) {
        protocolOut.print("$BRIDGE_NAME-mcp $BRIDGE_VERSION\n")
        protocolOut.flush()
        return
    }

    try {
        StdioServer(protocolOut).run()
    } catch (t: Throwable) {
        // Reaching here means the read loop itself blew up. Report it on stderr (which the client
        // surfaces in its MCP logs) instead of a stack trace on stdout that would look like a frame.
        System.err.println("[mockkhttp-mcp] fatal: ${t.javaClass.name}: ${t.message}")
    }
}

/**
 * Newline-delimited JSON-RPC 2.0 over stdin/stdout.
 *
 * Requests are dispatched to a small worker pool rather than handled inline: `mockkhttp_await_flow`
 * blocks for up to 25 s server-side, and a serial loop would make the client wait that long for an
 * unrelated `ping` or `tools/list`. JSON-RPC correlates by id, so answering out of order is legal.
 */
private class StdioServer(private val out: PrintStream) {

    private val protocol = McpProtocol()
    private val writeLock = Any()

    private val workers: ExecutorService = Executors.newFixedThreadPool(4) { runnable ->
        // Daemon threads: when the client closes stdin we exit even with a long poll in flight.
        Thread(runnable, "mockkhttp-mcp-worker").apply { isDaemon = true }
    }

    fun run() {
        val reader = BufferedReader(InputStreamReader(System.`in`, StandardCharsets.UTF_8))
        while (true) {
            val line = try {
                reader.readLine()
            } catch (t: Throwable) {
                System.err.println("[mockkhttp-mcp] stdin read failed: ${t.javaClass.simpleName}: ${t.message}")
                null
            } ?: break // EOF: the client is gone, this is the ONE legitimate way to stop.
            if (line.isBlank()) continue
            dispatch(line)
        }
        // EOF means no further request can arrive, but answers to the ones already in flight still
        // have to be written — a client that writes its last call and closes stdin (and every piped
        // test) would otherwise get nothing at all. Every HTTP call is timeout-bounded, so the wait
        // is finite; shutdownNow is only the backstop.
        workers.shutdown()
        try {
            if (!workers.awaitTermination(60, TimeUnit.SECONDS)) workers.shutdownNow()
        } catch (_: InterruptedException) {
            workers.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    private fun dispatch(line: String) {
        try {
            workers.execute { handle(line) }
        } catch (_: RejectedExecutionException) {
            // Pool already shutting down: answer on this thread rather than drop the request.
            handle(line)
        }
    }

    private fun handle(line: String) {
        try {
            val parsed = try {
                JsonParser.parseString(line)
            } catch (t: Throwable) {
                write(parseErrorResponse("${t.javaClass.simpleName}: ${t.message}"))
                return
            }
            when {
                parsed.isJsonObject -> protocol.handle(parsed.asJsonObject)?.let { write(it) }
                // Batches only exist in MCP revisions up to 2025-03-26; still cheap to honour.
                parsed.isJsonArray -> {
                    val answers = JsonArray()
                    for (element in parsed.asJsonArray) {
                        if (element.isJsonObject) protocol.handle(element.asJsonObject)?.let { answers.add(it) }
                    }
                    if (answers.size() > 0) write(answers)
                }
                else -> write(parseErrorResponse("a JSON-RPC message must be an object or a batch array"))
            }
        } catch (t: Throwable) {
            // Last line of defence. Swallowing beats dying: the session keeps its other tools.
            System.err.println("[mockkhttp-mcp] dropped a message: ${t.javaClass.name}: ${t.message}")
        }
    }

    private fun write(payload: JsonElement) {
        // Compact, single line: WIRE never emits a raw newline inside a string.
        val text = WIRE.toJson(payload)
        synchronized(writeLock) {
            out.print(text)
            out.print('\n')
            out.flush()
        }
    }
}
