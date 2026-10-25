package com.pawbridge.backend.community.internal.homevideo.infrastructure

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement.Metadata
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
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

class YouTubeVideoClient(
    private val mapper: ObjectMapper,
    private val apiKey: String,
    private val http: HttpClient,
    private val endpoint: URI,
) {

    fun lookup(ids: List<String>): Map<String, Metadata> {
        if (isBlank(apiKey)) {
            throw unavailable()
        }
        if (ids.isEmpty() || ids.size > 50 || ids.any { id -> !VIDEO_ID.matches(id) }) {
            throw IllegalArgumentException("Invalid bounded video ID batch")
        }
        val fields = URLEncoder.encode(
            "items(id,snippet(title,channelTitle,thumbnails,liveBroadcastContent)," +
                "contentDetails(duration,regionRestriction,contentRating)," +
                "status(privacyStatus,uploadStatus,embeddable))",
            StandardCharsets.UTF_8,
        )
        val query = "?part=snippet,contentDetails,status&id=" + ids.joinToString(",") +
            "&fields=" + fields +
            "&key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
        var pending: CompletableFuture<HttpResponse<ByteArray>>? = null
        try {
            val request = HttpRequest.newBuilder(URI.create(endpoint.toString() + query))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build()
            val sent = http.sendAsync(request, HttpResponse.BodyHandler<ByteArray> { BoundedBody() })
            pending = sent
            val response = sent.get(10, TimeUnit.SECONDS)
            if (response.statusCode() != 200) {
                throw unavailable()
            }
            val root = mapper.readTree(response.body())
            if (!root.path("items").isArray) {
                throw unavailable()
            }
            val result = HashMap<String, Metadata>()
            for (item in root.path("items")) {
                val id = item.path("id").asText()
                if (!ids.contains(id)) {
                    continue
                }
                val snippet = item.path("snippet")
                val details = item.path("contentDetails")
                val status = item.path("status")
                val title = snippet.path("title").asText()
                val channel = snippet.path("channelTitle").asText()
                var thumbnail = snippet.path("thumbnails").path("high").path("url").asText()
                if (isBlank(thumbnail)) {
                    thumbnail = snippet.path("thumbnails").path("medium").path("url").asText()
                }
                if (isBlank(thumbnail)) {
                    thumbnail = snippet.path("thumbnails").path("default").path("url").asText()
                }
                val duration = Duration.parse(details.path("duration").asText()).toSeconds()
                val playable = "public" == status.path("privacyStatus").asText() &&
                    "processed" == status.path("uploadStatus").asText() &&
                    status.path("embeddable").asBoolean(false) &&
                    "none" == snippet.path("liveBroadcastContent").asText() &&
                    details.path("contentRating").path("ytRating").isMissingNode &&
                    allowsKorea(details.path("regionRestriction")) &&
                    duration > 0 &&
                    !isBlank(title) &&
                    title.length <= 500 &&
                    !isBlank(channel) &&
                    channel.length <= 300 &&
                    safeThumbnail(thumbnail)
                result[id] = Metadata(id, title, channel, thumbnail, duration, playable)
            }
            return java.util.Map.copyOf(result)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw unavailable()
        } catch (failure: Exception) {
            // HTTP exceptions can contain the request URI and key. Never retain their cause or text.
            throw unavailable()
        } finally {
            if (pending != null && !pending.isDone) {
                pending.cancel(true)
            }
        }
    }

    /** Bound both buffered bytes and the full-response deadline, including a stalled body. */
    private class BoundedBody : HttpResponse.BodySubscriber<ByteArray> {
        private val delegate: HttpResponse.BodySubscriber<ByteArray> = HttpResponse.BodySubscribers.ofByteArray()
        private lateinit var subscription: Flow.Subscription
        private var received = 0L

        override fun getBody(): CompletionStage<ByteArray> = delegate.body

        override fun onSubscribe(value: Flow.Subscription) {
            subscription = value
            delegate.onSubscribe(value)
        }

        override fun onNext(buffers: MutableList<ByteBuffer>) {
            for (buffer in buffers) {
                received += buffer.remaining()
            }
            if (received > MAX_BODY_BYTES) {
                subscription.cancel()
                delegate.onError(IllegalStateException("Provider response limit exceeded"))
            } else {
                delegate.onNext(buffers)
            }
        }

        override fun onError(failure: Throwable) {
            delegate.onError(failure)
        }

        override fun onComplete() {
            delegate.onComplete()
        }
    }

    private companion object {
        private const val MAX_BODY_BYTES = 524_288
        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

        // Java String.isBlank semantics (Character.isWhitespace); Kotlin's isBlank also treats NBSP as blank.
        private fun isBlank(value: String): Boolean = value.all { Character.isWhitespace(it) }

        private fun safeThumbnail(value: String): Boolean {
            try {
                val uri = URI.create(value)
                return "https" == uri.scheme &&
                    "i.ytimg.com" == uri.host &&
                    uri.userInfo == null &&
                    uri.port == -1 &&
                    value.length <= 2048
            } catch (failure: IllegalArgumentException) {
                return false
            }
        }

        private fun allowsKorea(region: JsonNode): Boolean {
            if (region.path("blocked").isArray) {
                for (code in region.path("blocked")) {
                    if ("KR" == code.asText()) {
                        return false
                    }
                }
            }
            if (region.path("allowed").isArray) {
                for (code in region.path("allowed")) {
                    if ("KR" == code.asText()) {
                        return true
                    }
                }
                return false
            }
            return true
        }

        private fun unavailable(): ResponseStatusException =
            ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "YouTube 정보를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.",
            )
    }
}
