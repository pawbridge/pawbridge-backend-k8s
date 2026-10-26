package com.pawbridge.backend.contract

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The public home video list (replaces the packaged F15 smoke). */
class HomeVideoContractTest : ApiContractTestBase() {
    private lateinit var expected: List<JsonNode>

    @BeforeEach
    fun seedVideos() {
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusNanos(123_456_000)
        val fresh = now.minus(1, ChronoUnit.DAYS)
        val stale = now.minus(31, ChronoUnit.DAYS)
        val nodes = JsonNodeFactory.instance
        val shown = ArrayList<JsonNode>()
        for ((index, id) in IDS.withIndex()) {
            val position = if (index < 4) index else -index
            val title = if (index == 0) null else "F15_PRIVATE_TITLE_NOT_LOGGED"
            val channel = if (index == 0) "" else "F15_PRIVATE_CHANNEL_NOT_LOGGED"
            val thumbnail = if (index == 0) null else "https://fixture.invalid/F15_PRIVATE_METADATA"
            val duration = if (index == 0) null else 3_000_000_000L
            val available = index != 5
            val checkedAt = if (index == 4) stale else fresh
            execute(
                "INSERT INTO pawbridge_community.home_videos(id,video_id,published,position,created_at,title,channel_title," +
                    "thumbnail_url,duration_seconds,available,checked_at) VALUES(?::uuid,?,true,?,?::timestamptz,?,?,?,?,?,?::timestamptz)",
                id, "F15Video%03d".format(index + 1), position, "2025-01-02T03:04:05.123456Z", title, channel, thumbnail, duration,
                available, checkedAt.toString(),
            )
            if (index < 3) {
                val node = nodes.objectNode()
                node.put("id", id)
                node.put("videoId", "F15Video%03d".format(index + 1))
                node.put("published", true)
                node.put("position", position)
                node.put("createdAt", "2025-01-02T03:04:05.123456Z")
                if (title == null) node.putNull("title") else node.put("title", title)
                node.put("channelTitle", channel)
                if (thumbnail == null) node.putNull("thumbnailUrl") else node.put("thumbnailUrl", thumbnail)
                if (duration == null) node.putNull("durationSeconds") else node.put("durationSeconds", duration)
                node.put("available", available)
                node.put("checkedAt", checkedAt.toString())
                shown.add(node)
            }
        }
        expected = shown
    }

    @Test
    fun `게시되고 확인이 최신인 영상일 때__목록을 조회하면__위치순 앞의 셋이 정확한 열한 필드로 나온다`() {
        // given
        // when
        val reply = assertNoSideEffects { get("/api/v1/home/videos", headers = FORGED) }

        // then
        assertThat(reply.status).isEqualTo(200)
        assertThat(reply.code).isEqualTo(200)
        assertThat(reply.message).isEqualTo("요청이 성공적으로 처리되었습니다.")
        assertThat(reply.data.toList()).`as`("Unavailable, expired and beyond-three videos are left out").isEqualTo(expected)
        for (video in reply.data) {
            assertThat(video.fieldNames().asSequence().toSet()).isEqualTo(FIELDS)
        }
    }

    @Test
    fun `형식이 깨진 베어러 토큰일 때__목록을 조회하면__공개 목록이 그대로다`() {
        // given
        // when
        val reply = assertNoSideEffects { get("/api/v1/home/videos", "definitely-not-a-jwt", FORGED) }

        // then
        assertThat(reply.status).isEqualTo(200)
        assertThat(reply.data.toList()).isEqualTo(expected)
    }

    @Test
    fun `확인 시각 열 권한이 없을 때__목록을 조회하면__안전한 503이고 비공개 메타데이터가 로그에 없으며 복구되면 조회된다`() {
        // given
        // when
        val (denied, logs) = capturingLogs {
            ContractPostgres.withRevoked("SELECT(checked_at)", "pawbridge_community.home_videos") {
                assertNoSideEffects { get("/api/v1/home/videos", headers = FORGED) }
            }
        }
        val recovered = get("/api/v1/home/videos")

        // then
        assertThat(denied.status).isEqualTo(503)
        assertThat(denied.text).isEqualTo("""{"code":503,"message":"영상을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.","data":null}""")
        assertThat(recovered.data.toList()).isEqualTo(expected)
        for (secret in listOf("F15_PRIVATE_TITLE_NOT_LOGGED", "F15_PRIVATE_CHANNEL_NOT_LOGGED", "F15_PRIVATE_METADATA",
            ContractPostgres.appPassword, ContractApplicationInitializer.jwtSecret)) {
            assertThat(logs.contains(secret)).`as`("The log must not contain video metadata or credentials").isFalse()
        }
    }

    @Test
    fun `보이는 영상이 모두 사라졌을 때__목록을 조회하면__빈 배열이다`() {
        // given
        for (id in IDS.take(4)) {
            execute("DELETE FROM pawbridge_community.home_videos WHERE id = ?::uuid", id)
        }

        // when
        val reply = assertNoSideEffects { get("/api/v1/home/videos", headers = FORGED) }

        // then
        assertThat(reply.status).isEqualTo(200)
        assertThat(reply.data.isArray).isTrue()
        assertThat(reply.data.size()).isZero()
    }

    private companion object {
        val IDS = (1..6).map { "f15b0000-0000-0000-0000-" + it.toString().padStart(12, '0') }
        val FORGED = mapOf("X-User-Id" to "9999999", "X-User-Role" to "ROLE_ADMIN")
        val FIELDS = setOf(
            "id", "videoId", "published", "position", "createdAt", "title", "channelTitle", "thumbnailUrl", "durationSeconds",
            "available", "checkedAt",
        )
    }
}
