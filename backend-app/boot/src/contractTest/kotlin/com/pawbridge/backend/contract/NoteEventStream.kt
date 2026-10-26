package com.pawbridge.backend.contract

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.assertj.core.api.Assertions.assertThat

/**
 * One server-sent event stream read over a real socket with `HttpClient` and a line handler.
 *
 * A reader thread splits the lines into events and only counts them and keeps their payloads. Tests wait for an
 * event count with a deadline instead of sleeping, so a late event fails the test and an early one never slows it.
 */
class NoteEventStream private constructor(
    private val client: HttpClient,
    val status: Int,
    private val headers: HttpHeaders,
    private val lines: java.util.stream.Stream<String>?,
) : AutoCloseable {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val payloads = HashMap<String, MutableList<String>>()
    private var finished = false
    private var broken = false
    private var closedByTest = false
    private var reader: Thread? = null

    fun header(name: String): String? = headers.firstValue(name).orElse(null)

    /** Number of events with this name received so far. */
    fun count(event: String): Int = lock.withLock { payloads[event]?.size ?: 0 }

    /** Payloads of the events with this name, parsed as JSON, in arrival order. */
    fun payloads(event: String): List<JsonNode> {
        return lock.withLock { payloads[event].orEmpty().map { MAPPER.readTree(it) } }
    }

    /** Waits until at least [expected] events with this name arrived, then returns the exact count. */
    fun await(event: String, expected: Int, timeout: Duration = Duration.ofSeconds(8)) {
        val deadline = System.nanoTime() + timeout.toNanos()
        lock.withLock {
            while (!broken && !finished && (payloads[event]?.size ?: 0) < expected) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) {
                    break
                }
                changed.awaitNanos(remaining)
            }
            assertThat(broken).`as`("The stream delivered a malformed line").isFalse()
            assertThat(payloads[event]?.size ?: 0)
                .`as`("Events named '$event' received before the deadline")
                .isGreaterThanOrEqualTo(expected)
        }
    }

    /**
     * Waits for [expected] events, then keeps watching for a short quiet period so that an event that must not exist
     * (a rolled-back write, another member's change) has had every chance to arrive before the count is compared.
     */
    fun awaitExactly(event: String, expected: Int, quiet: Duration = Duration.ofMillis(400)) {
        await(event, expected)
        TimeUnit.MILLISECONDS.sleep(quiet.toMillis())
        assertThat(count(event)).`as`("Events named '$event' received").isEqualTo(expected)
    }

    /** Waits for the server to end the stream cleanly. */
    fun awaitEnd(timeout: Duration = Duration.ofSeconds(10)) {
        val deadline = System.nanoTime() + timeout.toNanos()
        lock.withLock {
            while (!broken && !finished) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) {
                    break
                }
                changed.awaitNanos(remaining)
            }
            assertThat(broken).`as`("The stream delivered a malformed line").isFalse()
            assertThat(finished).`as`("The server ended the stream before the deadline").isTrue()
        }
    }

    private fun start() {
        val source = lines ?: return
        val thread = Thread({ read(source) }, "contract-note-stream-reader")
        thread.isDaemon = true
        reader = thread
        thread.start()
    }

    private fun read(source: java.util.stream.Stream<String>) {
        var event: String? = null
        val data = ArrayList<String>()
        try {
            source.forEach { line ->
                when {
                    line.isEmpty() -> {
                        if (event != null) {
                            val name = event!!
                            lock.withLock {
                                payloads.getOrPut(name) { ArrayList() }.add(data.joinToString("\n"))
                                changed.signalAll()
                            }
                        }
                        event = null
                        data.clear()
                    }
                    line.startsWith("event:") -> event = line.substring("event:".length).removePrefix(" ")
                    line.startsWith("data:") -> data.add(line.substring("data:".length).removePrefix(" "))
                    else -> throw IllegalStateException("Unexpected stream line")
                }
            }
            lock.withLock {
                finished = true
                changed.signalAll()
            }
        } catch (failure: RuntimeException) {
            lock.withLock {
                if (!closedByTest) {
                    broken = true
                }
                changed.signalAll()
            }
        }
    }

    override fun close() {
        lock.withLock { closedByTest = true }
        lines?.close()
        client.shutdownNow()
        reader?.join(2_000)
    }

    companion object {
        private val MAPPER = ObjectMapper()

        /**
         * Opens the stream. Only a 200 answer has event lines; refusals are asserted with an ordinary request.
         */
        fun open(uri: URI, token: String?, headers: Map<String, String> = emptyMap()): NoteEventStream {
            val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
            val request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "text/event-stream")
                // A forged expiry header must never extend a stream: the signed token decides.
                .header("X-Auth-Expires-At", (System.currentTimeMillis() / 1000 + 3600).toString())
                .header("X-User-Id", "1")
            if (token != null) {
                request.header("Authorization", "Bearer $token")
            }
            for ((name, value) in headers) {
                request.header(name, value)
            }
            val response: HttpResponse<java.util.stream.Stream<String>> = client.send(
                request.build(),
                HttpResponse.BodyHandlers.ofLines(),
            )
            val stream = NoteEventStream(client, response.statusCode(), response.headers(), response.body())
            if (response.statusCode() == 200) {
                stream.start()
            }
            return stream
        }
    }
}
