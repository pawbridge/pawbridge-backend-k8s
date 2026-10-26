package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.apms.infrastructure.client.ApmsApiClient
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsAnimal
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsRootResponse
import com.pawbridge.backend.boot.javaIsBlank
import java.io.ByteArrayOutputStream
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

/**
 * APMS transport only. Snapshot retains its existing header checks and bounded recovery.
 * The primary constructor is the explicit ownership seam for offline close regression tests.
 */
internal class ApmsHttpClient(
    base: URI?,
    private val mapper: ObjectMapper,
    private val client: HttpClient,
    private val ownsClient: Boolean,
) : ApmsApiClient, AutoCloseable {
    private val base: URI = baseUri(base?.toString())
    private val responseType: JavaType =
        mapper.typeFactory.constructParametricType(ApmsRootResponse::class.java, ApmsAnimal::class.java)

    @Volatile
    private var closed = false

    constructor(base: URI?, mapper: ObjectMapper) : this(
        base,
        mapper,
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
        true,
    )

    /** Borrowed test seam: closing this adapter never closes the caller's HttpClient. */
    constructor(base: URI?, mapper: ObjectMapper, client: HttpClient) : this(base, mapper, client, false)

    override fun getAbandonmentAnimals(
        serviceKey: String?,
        pageNo: Int,
        numOfRows: Int,
        bgnde: String?,
        endde: String?,
        upkind: String?,
        state: String?,
        type: String?,
        updatedBeginDate: String?,
        updatedEndDate: String?,
    ): ApmsRootResponse<ApmsAnimal>? {
        var pending: CompletableFuture<HttpResponse<ByteArray>>? = null
        try {
            if (closed || serviceKey == null || javaIsBlank(serviceKey) ||
                serviceKey.any { Character.isISOControl(it) } ||
                pageNo < 1 || numOfRows < 1 || numOfRows > 1000
            ) {
                throw Unavailable()
            }
            val query = StringBuilder()
            parameter(query, "serviceKey", serviceKey)
            parameter(query, "pageNo", Integer.toString(pageNo))
            parameter(query, "numOfRows", Integer.toString(numOfRows))
            parameter(query, "bgnde", bgnde)
            parameter(query, "endde", endde)
            parameter(query, "upkind", upkind)
            parameter(query, "state", state)
            parameter(query, "_type", type ?: "json")
            parameter(query, "bgupd", updatedBeginDate)
            parameter(query, "enupd", updatedEndDate)
            var prefix = base.toASCIIString()
            while (prefix.endsWith("/")) {
                prefix = prefix.substring(0, prefix.length - 1)
            }
            val target = URI.create("$prefix/abandonmentPublic_v2?$query")
            val request = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .GET()
                .build()
            pending = client.sendAsync(request, HttpResponse.BodyHandler<ByteArray> { LimitedBodySubscriber() })
            val response = pending.get(6, TimeUnit.SECONDS)
            if (response.statusCode() != 200 || response.body() == null || response.body().size > MAX_BODY_BYTES) {
                throw Unavailable()
            }
            return mapper.readValue<ApmsRootResponse<ApmsAnimal>>(response.body(), responseType)
        } catch (interrupted: InterruptedException) {
            pending?.cancel(true)
            Thread.currentThread().interrupt()
            throw Unavailable()
        } catch (unavailable: Exception) {
            if (pending != null && !pending.isDone) {
                pending.cancel(true)
            }
            throw Unavailable()
        }
    }

    @Synchronized
    override fun close() {
        if (closed) {
            return
        }
        closed = true
        if (ownsClient) {
            try {
                client.close()
            } catch (failure: RuntimeException) {
                throw Unavailable()
            }
        }
    }

    class Unavailable : IllegalStateException("APMS request unavailable", null)

    /** Cancel before copying a buffer that would exceed the body limit. */
    class LimitedBodySubscriber : HttpResponse.BodySubscriber<ByteArray> {
        private val bytes = ByteArrayOutputStream()
        private val result = CompletableFuture<ByteArray>()
        private var subscription: Flow.Subscription? = null

        override fun getBody(): CompletionStage<ByteArray> {
            return result
        }

        override fun onSubscribe(incoming: Flow.Subscription) {
            if (subscription != null) {
                incoming.cancel()
                return
            }
            subscription = incoming
            incoming.request(1)
        }

        override fun onNext(buffers: MutableList<ByteBuffer>) {
            if (result.isDone) {
                return
            }
            for (buffer in buffers) {
                if (buffer.remaining() > MAX_BODY_BYTES - bytes.size()) {
                    subscription!!.cancel()
                    result.completeExceptionally(Unavailable())
                    return
                }
                val chunk = ByteArray(buffer.remaining())
                buffer.get(chunk)
                bytes.writeBytes(chunk)
            }
            subscription!!.request(1)
        }

        override fun onError(ignored: Throwable) {
            result.completeExceptionally(Unavailable())
        }

        override fun onComplete() {
            result.complete(bytes.toByteArray())
        }
    }

    companion object {
        const val MAX_BODY_BYTES: Int = 2 * 1024 * 1024

        @JvmStatic
        fun baseUri(configured: String?): URI {
            try {
                if (configured == null || javaIsBlank(configured)) {
                    throw IllegalArgumentException()
                }
                val uri = URI.create(configured)
                if ("https" != uri.scheme || uri.host == null || javaIsBlank(uri.host) ||
                    uri.rawAuthority == null || uri.rawUserInfo != null ||
                    uri.rawQuery != null || uri.rawFragment != null ||
                    uri.port == 0 || uri.port > 65535
                ) {
                    throw IllegalArgumentException()
                }
                return uri
            } catch (invalid: RuntimeException) {
                throw IllegalArgumentException("APMS base URL must be an explicit HTTPS provider URL")
            }
        }

        private fun parameter(query: StringBuilder, key: String, value: String?) {
            if (value == null) {
                return
            }
            if (query.isNotEmpty()) {
                query.append('&')
            }
            query.append(key).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8))
        }
    }
}
