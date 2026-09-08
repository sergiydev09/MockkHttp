package com.sergiy.dev.mockkhttp.interceptor

import com.google.gson.JsonParser
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Audit round four, finding W, on the native side: the 500 ms deduplication window dropped a
 * genuine second request to the same URL inside the app — two screens asking for the same
 * forecast 12 ms apart, an immediate retry — with no trace anywhere. What the window existed for
 * is ONE request seen by TWO copies of the interceptor, which is identity, not time.
 *
 * Runs on the JVM against a fake plugin on an ephemeral port and a MockWebServer origin; nothing
 * here needs a device or the real plugin.
 */
class CaptureIdentityTest {

    private lateinit var plugin: FakePlugin
    private lateinit var origin: MockWebServer

    @Before
    fun setUp() {
        MockkHttpInterceptor.isEnabled = true
        MockkHttpInterceptor.enableDeduplication = true
        MockkHttpInterceptor.resetConnectionStateForTests()
        MockkHttpInterceptor.resetStatsForTests()
        plugin = FakePlugin.start()
        origin = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        MockkHttpInterceptor.enableDeduplication = true
        origin.shutdown()
        plugin.close()
    }

    @Test
    fun `the interceptor added twice captures a request once`() {
        origin.enqueue(MockResponse().setBody("{}"))
        val client = client(interceptor(), interceptor())

        client.newCall(get("/data/2.5/weather?q=Madrid")).execute().close()

        plugin.awaitFlows(1)
        assertEquals("one request, one flow — whatever the number of copies in the chain", 1, plugin.flowCount)

        // And the numbers say so: one flow sent, one pass yielded by the second copy.
        val stats = plugin.lastClientStats ?: throw AssertionError("the flow must carry client.stats")
        assertEquals(1L, stats["flows_sent"])
        assertEquals(1L, stats["passes_yielded"])
        assertEquals("android-okhttp", plugin.lastClientLibrary)
    }

    @Test
    fun `two identical requests 12 ms apart are two flows`() {
        origin.enqueue(MockResponse().setBody("{}"))
        origin.enqueue(MockResponse().setBody("{}"))
        val client = client(interceptor())

        client.newCall(get("/data/2.5/forecast?q=Madrid")).execute().close()
        Thread.sleep(12)
        client.newCall(get("/data/2.5/forecast?q=Madrid")).execute().close()

        plugin.awaitFlows(2)
        assertEquals("the app made two requests; a log that keeps one is not a source of truth", 2, plugin.flowCount)
    }

    @Test
    fun `with coordination off every copy captures, which is what off means`() {
        MockkHttpInterceptor.enableDeduplication = false
        origin.enqueue(MockResponse().setBody("{}"))
        val client = client(interceptor(), interceptor())

        client.newCall(get("/data/2.5/weather")).execute().close()

        plugin.awaitFlows(2)
        assertEquals(2, plugin.flowCount)
    }

    @Test
    fun `install adds the interceptor once, and not again to a builder derived from the client`() {
        val builder = OkHttpClient.Builder()
        MockkHttpInterceptor.install(builder)
        MockkHttpInterceptor.install(builder)
        assertEquals(1, builder.interceptors().count { it is MockkHttpInterceptor })

        // newBuilder() copies the interceptors of the client it comes from; the transform calls
        // install() in front of that builder's build() too.
        val derived = builder.build().newBuilder()
        MockkHttpInterceptor.install(derived)
        assertEquals(1, derived.interceptors().count { it is MockkHttpInterceptor })
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────────

    private fun interceptor() = MockkHttpInterceptor(null, "127.0.0.1", plugin.port)

    private fun client(vararg interceptors: Interceptor): OkHttpClient =
        OkHttpClient.Builder().apply { interceptors.forEach { addInterceptor(it) } }.build()

    private fun get(path: String): Request = Request.Builder().url(origin.url(path)).build()

    /**
     * Stands in for the IntelliJ plugin on port 9876: answers PING with PONG, CHECK_MOCK with
     * "no mock, RECORDING", and counts every flow that arrives.
     */
    private class FakePlugin private constructor(private val server: ServerSocket) {

        val port: Int get() = server.localPort
        private val flows = AtomicInteger(0)
        val flowCount: Int get() = flows.get()

        /** The `client` block of the last flow, as the plugin would read it. */
        @Volatile var lastClientStats: Map<String, Long>? = null
        @Volatile var lastClientLibrary: String? = null

        private val acceptor = thread(name = "fake-plugin", isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { serve(socket) }
            }
        }

        private fun serve(socket: Socket) = socket.use { s ->
            try {
                val line = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8)).readLine() ?: return
                val out = s.getOutputStream()
                if (line.startsWith("PING")) {
                    out.write("PONG\n".toByteArray()); out.flush(); return
                }
                val message = JsonParser.parseString(line).asJsonObject
                val type = message.get("type")?.asString ?: "FLOW"
                if (type == "CHECK_MOCK") {
                    out.write("{\"hasMock\":false,\"mode\":\"RECORDING\"}\n".toByteArray())
                } else {
                    flows.incrementAndGet()
                    message.getAsJsonObject("client")?.let { client ->
                        lastClientLibrary = client.get("library")?.asString
                        lastClientStats = client.getAsJsonObject("stats")?.entrySet()?.associate { it.key to it.value.asLong }
                    }
                    out.write("{}\n".toByteArray())
                }
                out.flush()
            } catch (_: Exception) {
                // A client that hangs up mid-message is not a test failure; the counts decide.
            }
        }

        /** Wait until [expected] flows arrived, then a little longer so an unwanted extra one can show up. */
        fun awaitFlows(expected: Int) {
            val deadline = System.currentTimeMillis() + 3_000
            while (flows.get() < expected && System.currentTimeMillis() < deadline) Thread.sleep(20)
            Thread.sleep(300)
        }

        fun close() {
            server.close()
            acceptor.join(1_000)
        }

        companion object {
            fun start(): FakePlugin = FakePlugin(ServerSocket(0, 50, InetAddress.getLoopbackAddress()))
        }
    }
}
