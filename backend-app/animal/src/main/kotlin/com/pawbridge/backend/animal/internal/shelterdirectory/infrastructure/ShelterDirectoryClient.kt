package com.pawbridge.backend.animal.internal.shelterdirectory.infrastructure

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit

/** Original bounded, read-only APMS shelter collection. Never retain credential-bearing HTTP exceptions. */
class ShelterDirectoryClient(
    private val key: String,
    private val mapper: ObjectMapper,
) {
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    fun collect(): List<Map<String, String>> {
        if (isBlank(key)) {
            throw unavailable()
        }
        val collected = LinkedHashMap<String?, Map<String, String>>()
        // totalCount includes source duplicates. An explicit empty page terminates traversal.
        for (page in 1..30) {
            val rows = fetch(page)
            if (rows.isEmpty()) {
                if (collected.isEmpty()) {
                    throw unavailable()
                }
                return collected.values.toList()
            }
            val before = collected.size
            for (row in rows) {
                collected.merge(row["careRegNo"], row) { first, next ->
                    if (first != next) {
                        throw unavailable()
                    }
                    first
                }
            }
            if (before == collected.size) {
                throw unavailable()
            }
        }
        throw unavailable()
    }

    // Public (package-private in Java) because the Java tests spy on it.
    fun fetch(page: Int): List<Map<String, String>> {
        var pending: CompletableFuture<HttpResponse<ByteArray>>? = null
        try {
            val query = "?serviceKey=" + URLEncoder.encode(javaTrim(key), StandardCharsets.UTF_8) +
                "&_type=json&numOfRows=1000&pageNo=" + page
            val request = HttpRequest.newBuilder(
                URI.create("https://apis.data.go.kr/1543061/animalShelterSrvc_v2/shelterInfo_v2" + query),
            )
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build()
            val started = http.sendAsync(request, HttpResponse.BodyHandler { LimitedBody() })
            pending = started
            val response = started.get(12, TimeUnit.SECONDS)
            if (response.statusCode() != 200) {
                throw unavailable()
            }
            return parse(response.body())
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw unavailable()
        } catch (exception: Exception) {
            throw unavailable()
        } finally {
            if (pending != null && !pending.isDone) {
                pending.cancel(true)
            }
        }
    }

    // Public (package-private in Java) because the Java tests call it directly.
    fun parse(bytes: ByteArray): List<Map<String, String>> {
        try {
            var root: JsonNode = mapper.readTree(bytes)
            if (root.has("response")) {
                root = root.get("response")
            }
            if ("00" != root.path("header").path("resultCode").asText()) {
                throw unavailable()
            }
            if (!root.path("body").isObject) {
                throw unavailable()
            }
            val items = root.path("body").path("items")
            if (items.isTextual && isBlank(items.asText())) {
                return emptyList()
            }
            if (items.isObject && items.isEmpty) {
                return emptyList()
            }
            if (!items.isObject || !items.has("item")) {
                throw unavailable()
            }
            val node = items.get("item")
            if (!node.isArray && !node.isObject) {
                throw unavailable()
            }
            val nodes = ArrayList<JsonNode>()
            if (node.isObject) {
                nodes.add(node)
            } else {
                node.forEach { element -> nodes.add(element) }
            }
            if (nodes.size > 1000) {
                throw unavailable()
            }
            val rows = ArrayList<Map<String, String>>()
            for (value in nodes) {
                val row = LinkedHashMap<String, String>()
                for (field in FIELDS) {
                    val fieldValue = value.path(field)
                    if (fieldValue.isValueNode && !fieldValue.isNull && !isBlank(fieldValue.asText())) {
                        if (fieldValue.asText().length > 500) {
                            throw unavailable()
                        }
                        row[field] = javaTrim(fieldValue.asText())
                    }
                }
                if (!CARE_REG_NO.matches(row.getOrDefault("careRegNo", "")) ||
                    !row.containsKey("careNm") ||
                    row["careNm"]!!.length > 200
                ) {
                    throw unavailable()
                }
                rows.add(java.util.Map.copyOf(row))
            }
            return java.util.List.copyOf(rows)
        } catch (exception: Exception) {
            throw unavailable()
        }
    }

    // Java String.isBlank(): empty or only Character.isWhitespace characters (Kotlin isBlank also accepts NBSP).
    private fun isBlank(value: String): Boolean = value.all { Character.isWhitespace(it) }

    // Java String.trim() removes every character up to U+0020; Kotlin trim() uses Character.isWhitespace.
    private fun javaTrim(value: String): String = value.trim { it <= ' ' }

    internal class LimitedBody : HttpResponse.BodySubscriber<ByteArray> {
        private val delegate: HttpResponse.BodySubscriber<ByteArray> = HttpResponse.BodySubscribers.ofByteArray()
        private var subscription: Flow.Subscription? = null
        private var received = 0
        private var failed = false

        override fun getBody(): CompletionStage<ByteArray> = delegate.body

        override fun onSubscribe(subscription: Flow.Subscription) {
            this.subscription = subscription
            delegate.onSubscribe(subscription)
        }

        override fun onNext(buffers: List<ByteBuffer>) {
            if (failed) {
                return
            }
            val incoming = buffers.sumOf { buffer -> buffer.remaining().toLong() }
            if (received + incoming > MAX_BODY_BYTES) {
                failed = true
                subscription!!.cancel()
                delegate.onError(unavailable())
                return
            }
            received += incoming.toInt()
            delegate.onNext(buffers)
        }

        override fun onError(throwable: Throwable) {
            delegate.onError(unavailable())
        }

        override fun onComplete() {
            if (!failed) {
                delegate.onComplete()
            }
        }
    }

    companion object {
        private const val MAX_BODY_BYTES = 4 * 1024 * 1024
        private val CARE_REG_NO = Regex("[0-9]{15}")
        private val FIELDS = setOf(
            "careRegNo", "careNm", "careAddr", "careTel", "orgNm",
            "lat", "lng", "weekOprStime", "weekOprEtime", "weekendOprStime", "weekendOprEtime", "closeDay", "dataStdDt",
        )

        // Public (package-private in Java) because the Java tests compare against it.
        @JvmStatic
        fun unavailable(): IllegalStateException = IllegalStateException("SHELTER_DIRECTORY_UNAVAILABLE")
    }
}
