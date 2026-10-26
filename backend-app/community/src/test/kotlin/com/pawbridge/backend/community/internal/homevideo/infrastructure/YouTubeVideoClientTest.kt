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
    fun `공개되고 임베드 가능한 영상일 때__조회하면__메타데이터를 돌려주고 ids 요청 한 번만 보낸다`() {
        // given
        val ids = listOf(ID, "OtherVideo1")

        // when
        val result = client.lookup(ids)

        // then
        assertThat(result[ID]!!.available).isTrue()
        assertThat(result[ID]!!.durationSeconds).isEqualTo(204)
        assertThat(query).contains("part=snippet,contentDetails,status", "id=AbCdEfGhI_1,OtherVideo1")
    }

    @ParameterizedTest
    @ValueSource(strings = ["private", "unlisted"])
    fun `비공개 영상일 때__조회하면__재생할 수 없다`(privacy: String) {
        // given
        payload = json("\"privacyStatus\":\"" + privacy + "\",\"uploadStatus\":\"processed\",\"embeddable\":true", "")

        // when
        val result = client.lookup(listOf(ID))

        // then
        assertThat(result[ID]!!.available).isFalse()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            ",\"regionRestriction\":{\"blocked\":[\"KR\"]}",
            ",\"regionRestriction\":{\"allowed\":[\"US\"]}",
            ",\"contentRating\":{\"ytRating\":\"ytAgeRestricted\"}",
        ],
    )
    fun `지역 또는 연령 제한이 있을 때__조회하면__재생할 수 없다`(detail: String) {
        // given
        payload = json("\"privacyStatus\":\"public\",\"uploadStatus\":\"processed\",\"embeddable\":true", detail)

        // when
        val result = client.lookup(listOf(ID))

        // then
        assertThat(result[ID]!!.available).isFalse()
    }

    @Test
    fun `임베드가 꺼졌거나 라이브일 때__조회하면__재생할 수 없다`() {
        // given
        payload = json("\"privacyStatus\":\"public\",\"uploadStatus\":\"processed\",\"embeddable\":false", "")

        // when
        val notEmbeddable = client.lookup(listOf(ID))

        // then
        assertThat(notEmbeddable[ID]!!.available).isFalse()

        // given
        payload = json("\"privacyStatus\":\"public\",\"uploadStatus\":\"processed\",\"embeddable\":true", "")
            .replace("\"none\"", "\"live\"")

        // when
        val live = client.lookup(listOf(ID))

        // then
        assertThat(live[ID]!!.available).isFalse()
    }

    @Test
    fun `성공했지만 목록이 비었을 때__조회하면__공급자 실패가 아니라 부재로 본다`() {
        // given
        payload = "{\"items\":[]}"

        // when
        val result = client.lookup(listOf(ID))

        // then
        assertThat(result).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(ints = [403, 429, 500])
    fun `공급자가 실패할 때__조회하면__키나 원인 없이 정제된 503을 돌려준다`(code: Int) {
        // given
        status = code
        payload = "synthetic-key should never escape"

        // when
        val thrown = assertThatThrownBy { client.lookup(listOf(ID)) }

        // then
        thrown
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
    fun `공급자 응답이 잘못된 형식일 때__조회하면__영상 부재가 아니라 실패로 본다`() {
        // given
        payload = "{\"error\":\"synthetic-key\"}"

        // when
        val thrown = assertThatThrownBy { client.lookup(listOf(ID)) }

        // then
        thrown
            .isInstanceOf(ResponseStatusException::class.java)
            .hasNoCause()
    }

    @Test
    fun `공급자 본문이 너무 클 때__조회하면__제한된 정제 실패를 돌려준다`() {
        // given
        payload = " ".repeat(524_289)

        // when
        val thrown = assertThatThrownBy { client.lookup(listOf(ID)) }

        // then
        thrown
            .isInstanceOf(ResponseStatusException::class.java)
            .hasNoCause()
            .hasMessageNotContaining("synthetic-key")
    }

    @Test
    fun `썸네일 호스트가 외부일 때__조회하면__재생할 수 없다`() {
        // given
        payload = payload.replace("https://i.ytimg.com/", "https://untrusted.example/")

        // when
        val result = client.lookup(listOf(ID))

        // then
        assertThat(result[ID]!!.available).isFalse()
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
