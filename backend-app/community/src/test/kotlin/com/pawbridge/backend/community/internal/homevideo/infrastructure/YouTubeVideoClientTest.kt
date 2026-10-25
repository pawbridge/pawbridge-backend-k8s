package com.pawbridge.backend.community.internal.homevideo.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.web.server.ResponseStatusException
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets

class YouTubeVideoClientTest {
    private lateinit var server: HttpServer
    private lateinit var client: YouTubeVideoClient
    private lateinit var payload: String
    private var status = 200
    private var query: String? = null
    private lateinit var http: HttpClient

    @BeforeEach
    fun setup() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/videos") { exchange ->
            query = exchange.requestURI.rawQuery
            val body = payload.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.write(body)
            exchange.close()
        }
        server.start()
        http = HttpClient.newHttpClient()
        client = YouTubeVideoClient(
            ObjectMapper(),
            "synthetic-key",
            http,
            URI.create("http://127.0.0.1:" + server.address.port + "/videos"),
        )
        payload = json("\"privacyStatus\":\"public\",\"uploadStatus\":\"processed\",\"embeddable\":true", "")
    }

    @AfterEach
    fun stop() {
        server.stop(0)
        http.close()
    }

    @Test
    fun givenPublicEmbeddableVideo_whenLookup_thenMetadataAndSingleIdsRequest() {
        val result = client.lookup(listOf(ID, "OtherVideo1"))

        assertThat(result[ID]!!.available).isTrue()
        assertThat(result[ID]!!.durationSeconds).isEqualTo(204)
        assertThat(query).contains("part=snippet,contentDetails,status", "id=AbCdEfGhI_1,OtherVideo1")
    }

    @ParameterizedTest
    @ValueSource(strings = ["private", "unlisted"])
    fun givenNonPublicVideo_whenLookup_thenNotPlayable(privacy: String) {
        payload = json("\"privacyStatus\":\"" + privacy + "\",\"uploadStatus\":\"processed\",\"embeddable\":true", "")

        assertThat(client.lookup(listOf(ID))[ID]!!.available).isFalse()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            ",\"regionRestriction\":{\"blocked\":[\"KR\"]}",
            ",\"regionRestriction\":{\"allowed\":[\"US\"]}",
            ",\"contentRating\":{\"ytRating\":\"ytAgeRestricted\"}",
        ],
    )
    fun givenRegionalOrAgeRestriction_whenLookup_thenNotPlayable(detail: String) {
        payload = json("\"privacyStatus\":\"public\",\"uploadStatus\":\"processed\",\"embeddable\":true", detail)

        assertThat(client.lookup(listOf(ID))[ID]!!.available).isFalse()
    }

    @Test
    fun givenEmbeddingDisabledOrLive_whenLookup_thenNotPlayable() {
        payload = json("\"privacyStatus\":\"public\",\"uploadStatus\":\"processed\",\"embeddable\":false", "")
        assertThat(client.lookup(listOf(ID))[ID]!!.available).isFalse()
        payload = json("\"privacyStatus\":\"public\",\"uploadStatus\":\"processed\",\"embeddable\":true", "")
            .replace("\"none\"", "\"live\"")
        assertThat(client.lookup(listOf(ID))[ID]!!.available).isFalse()
    }

    @Test
    fun givenSuccessfulEmptyList_whenLookup_thenAbsenceNotProviderFailure() {
        payload = "{\"items\":[]}"

        assertThat(client.lookup(listOf(ID))).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(ints = [403, 429, 500])
    fun givenProviderFailure_whenLookup_thenSanitized503WithoutKeyOrCause(code: Int) {
        status = code
        payload = "synthetic-key should never escape"

        assertThatThrownBy { client.lookup(listOf(ID)) }
            .isInstanceOf(ResponseStatusException::class.java)
            .hasMessageNotContaining("synthetic-key")
            .hasMessageNotContaining("http://")
            .hasMessageNotContaining("key=")
            .hasNoCause()
            .satisfies({ e ->
                assertThat((e as ResponseStatusException).statusCode.value()).isEqualTo(503)
            })
    }

    @Test
    fun givenMalformedProviderResponse_whenLookup_thenFailureNotAbsentVideo() {
        payload = "{\"error\":\"synthetic-key\"}"

        assertThatThrownBy { client.lookup(listOf(ID)) }
            .isInstanceOf(ResponseStatusException::class.java)
            .hasNoCause()
    }

    @Test
    fun givenOversizedProviderBody_whenLookup_thenBoundedSanitizedFailure() {
        payload = " ".repeat(524_289)

        assertThatThrownBy { client.lookup(listOf(ID)) }
            .isInstanceOf(ResponseStatusException::class.java)
            .hasNoCause()
            .hasMessageNotContaining("synthetic-key")
    }

    @Test
    fun givenForeignThumbnailHost_whenLookup_thenNotPlayable() {
        payload = payload.replace("https://i.ytimg.com/", "https://untrusted.example/")

        assertThat(client.lookup(listOf(ID))[ID]!!.available).isFalse()
    }

    private companion object {
        const val ID = "AbCdEfGhI_1"

        fun json(status: String, detail: String): String =
            """
            {"items":[{"id":"AbCdEfGhI_1","snippet":{"title":"일상","channelTitle":"테스트 채널",
            "liveBroadcastContent":"none","thumbnails":{"high":{"url":"https://i.ytimg.com/vi/AbCdEfGhI_1/hqdefault.jpg"}}},
            "contentDetails":{"duration":"PT3M24S"$detail},"status":{$status}}]}
            """.trimIndent() + "\n"
    }
}
