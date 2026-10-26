package com.pawbridge.backend.contract

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * The object storage every contract test in the JVM talks to: a loopback JDK `HttpServer` that stands in for S3.
 *
 * It speaks the path-style PUT, DELETE, GET and HEAD that the application's SDK client sends, keeps the bytes in
 * memory and records every request, so a test can assert what the real SDK put on the wire (bytes, MIME type, key
 * order) and that nothing was sent at all. It accepts only keys below the configured post media prefix and only
 * requests signed with the synthetic access key, so a wrong bucket, prefix or credential shows up as a 403.
 *
 * One server serves the whole JVM, like [ContractPostgres]. Tests call [reset] before they start and never run in
 * parallel with each other.
 */
object ContractFakeS3 {
    const val BUCKET = "contract-test-bucket"
    const val OBJECT_PREFIX = "contract-test/"
    const val PUBLIC_BASE_URL = "https://storage.contract.invalid"
    const val ACCESS_KEY = "contract-access"
    const val SECRET_KEY = "contract-secret"

    private val mediaKey = Regex(
        "${Regex.escape(OBJECT_PREFIX)}posts/(?:images|videos)/" +
            "[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\\.[^/\\\\]*)?",
    )

    private val lock = Any()
    private val objects = LinkedHashMap<String, StoredObject>()
    private val requests = ArrayList<Request>()
    private var putFailureAfter = 0
    private var putFailures = 0
    private var deleteFailureAfter = 0
    private var deleteFailures = 0

    private val server: HttpServer by lazy { start() }

    /** `http://127.0.0.1:port` without a path: the application validates this shape. */
    val endpoint: String get() = "http://127.0.0.1:${server.address.port}"

    /** Forgets every object, request and failure instruction. */
    fun reset() {
        synchronized(lock) {
            objects.clear()
            requests.clear()
            putFailureAfter = 0
            putFailures = 0
            deleteFailureAfter = 0
            deleteFailures = 0
        }
    }

    /** Makes [count] PUT requests fail with HTTP 400 after [after] more succeeded. */
    fun failNextPut(after: Int, count: Int) {
        synchronized(lock) {
            putFailureAfter = after
            putFailures = count
        }
    }

    /** Makes [count] DELETE requests fail with HTTP 400 after [after] more succeeded. */
    fun failNextDelete(after: Int, count: Int) {
        synchronized(lock) {
            deleteFailureAfter = after
            deleteFailures = count
        }
    }

    /** Every request the server received since [reset], in arrival order. */
    fun requests(): List<Request> = synchronized(lock) { ArrayList(requests) }

    fun requestCount(method: String): Int = requests().count { it.method == method }

    /** The stored objects in the order they were first stored. */
    fun objects(): Map<String, StoredObject> = synchronized(lock) { LinkedHashMap(objects) }

    /** The key a public URL of the post media points to, or null when the URL is not below the public base. */
    fun keyOf(publicUrl: String?): String? = publicUrl?.removePrefix("$PUBLIC_BASE_URL/")?.takeIf { publicUrl.startsWith("$PUBLIC_BASE_URL/") }

    fun publicUrl(key: String): String = "$PUBLIC_BASE_URL/$key"

    fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    class StoredObject(val contentType: String?, val body: ByteArray) {
        val sha256: String get() = sha256(body)
    }

    class Request(val method: String, val key: String?, val status: Int, val contentType: String?, val size: Int?)

    private fun start(): HttpServer {
        val created = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        created.createContext("/") { exchange -> exchange.use { handle(it) } }
        created.executor = Executors.newCachedThreadPool(
            ThreadFactory { runnable ->
                val thread = Thread(runnable, "contract-fake-s3")
                thread.isDaemon = true
                thread
            },
        )
        created.start()
        return created
    }

    private fun handle(exchange: HttpExchange) {
        val key = keyOf(exchange)
        val signed = exchange.requestHeaders.getFirst("Authorization")?.contains("Credential=$ACCESS_KEY/") == true
        when (exchange.requestMethod) {
            "PUT" -> put(exchange, key, signed)
            "DELETE" -> delete(exchange, key, signed)
            "GET", "HEAD" -> read(exchange, key)
            else -> respond(exchange, 405, key)
        }
    }

    private fun put(exchange: HttpExchange, key: String?, signed: Boolean) {
        if (key == null || !signed) {
            record(exchange, key, 403, null)
            respond(exchange, 403, key, record = false)
            return
        }
        val declaredLength = exchange.requestHeaders.getFirst("Content-Length")?.toLong()
        val body = if (declaredLength != null) {
            exchange.requestBody.readNBytes(Math.toIntExact(declaredLength))
        } else {
            exchange.requestBody.readAllBytes()
        }
        val contentType = exchange.requestHeaders.getFirst("Content-Type")
        synchronized(lock) {
            if (putFailures > 0 && putFailureAfter == 0) {
                putFailures--
                requests.add(Request("PUT", key, 400, contentType, body.size))
                respond(exchange, 400, key, record = false)
                return
            }
            if (putFailures > 0) {
                putFailureAfter--
            }
            objects[key] = StoredObject(contentType, body)
            requests.add(Request("PUT", key, 200, contentType, body.size))
        }
        // The SDK compares the returned ETag with the MD5 of what it sent.
        exchange.responseHeaders.add(
            "ETag",
            "\"" + HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(body)) + "\"",
        )
        respond(exchange, 200, key, record = false)
    }

    private fun delete(exchange: HttpExchange, key: String?, signed: Boolean) {
        synchronized(lock) {
            if (key == null || !signed) {
                requests.add(Request("DELETE", key, 403, null, null))
                respond(exchange, 403, key, record = false)
                return
            }
            if (deleteFailures > 0 && deleteFailureAfter == 0) {
                deleteFailures--
                requests.add(Request("DELETE", key, 400, null, null))
                respond(exchange, 400, key, record = false)
                return
            }
            if (deleteFailures > 0) {
                deleteFailureAfter--
            }
            objects.remove(key)
            requests.add(Request("DELETE", key, 204, null, null))
        }
        respond(exchange, 204, key, record = false)
    }

    private fun read(exchange: HttpExchange, key: String?) {
        val stored = synchronized(lock) { key?.let { objects[it] } }
        if (key == null) {
            respond(exchange, 403, key)
        } else if (stored == null) {
            respond(exchange, 404, key)
        } else {
            record(exchange, key, 200, null)
            exchange.responseHeaders.add("Content-Type", stored.contentType ?: "application/octet-stream")
            if (exchange.requestMethod == "HEAD") {
                exchange.sendResponseHeaders(200, -1)
            } else {
                exchange.sendResponseHeaders(200, stored.body.size.toLong())
                exchange.responseBody.write(stored.body)
            }
        }
    }

    private fun record(exchange: HttpExchange, key: String?, status: Int, size: Int?) {
        synchronized(lock) {
            requests.add(Request(exchange.requestMethod, key, status, exchange.requestHeaders.getFirst("Content-Type"), size))
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, key: String?, record: Boolean = true) {
        if (record) {
            record(exchange, key, status, null)
        }
        exchange.sendResponseHeaders(status, -1)
    }

    private fun keyOf(exchange: HttpExchange): String? {
        val path = exchange.requestURI.path
        val prefix = "/$BUCKET/"
        if (!path.startsWith(prefix)) {
            return null
        }
        val key = path.removePrefix(prefix)
        return key.takeIf { mediaKey.matches(it) }
    }
}
