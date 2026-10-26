package com.pawbridge.backend.contract

import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * `GET /api/v1/notes/stream` on a real socket: the handshake, the `ready` event, who may connect, when the server
 * ends a stream and the heartbeat. Delivery after a write is asserted next to each write contract.
 */
class NoteStreamContractTest : NoteContractBase() {
    override val ids = 9_980_000L..9_980_999L

    @BeforeEach
    fun seedMembers() {
        seedUser(CALLER, "F26caller")
        seedUser(OTHER, "F26other")
        seedMember(CALLER)
        seedMember(OTHER)
    }

    @Test
    fun `정상 토큰일 때__스트림을 열면__이벤트 스트림 헤더와 ready를 주고 행을 바꾸지 않는다`() {
        // given
        val caller = token(CALLER)
        val other = token(OTHER)

        // when
        val (streams, changed) = observingChanges { openStream(caller) to openStream(other) }
        val (first, second) = streams

        // then
        assertThat(changed).isEmpty()
        for (stream in listOf(first, second)) {
            assertThat(stream.status).isEqualTo(200)
            assertThat(stream.header("Content-Type")).startsWith("text/event-stream")
            assertThat(stream.header("Cache-Control")).isEqualTo("no-store")
            assertThat(stream.header("X-Accel-Buffering")).isEqualTo("no")
            assertThat(stream.header("Set-Cookie")).isNull()
            stream.await("ready", 1)
            assertThat(stream.payloads("ready")).containsExactly(MAPPER.readTree("{}"))
            stream.awaitExactly("ready", 1)
            assertThat(stream.count("resync")).isZero()
            assertThat(stream.count("heartbeat")).isZero()
        }
    }

    @ParameterizedTest(name = "{displayName}[{index}]")
    @CsvSource(
        "none,인증 토큰이 필요합니다.",
        "expired,유효하지 않거나 만료된 토큰입니다.",
        "no-expiry,토큰 파싱에 실패했습니다.",
        "garbage,유효하지 않거나 만료된 토큰입니다.",
    )
    fun `토큰이 없거나 결함이 있을 때__스트림을 열면__401로 거부하고 스트림을 열지 않는다`(kind: String, message: String) {
        // given
        val credential = when (kind) {
            "none" -> null
            "expired" -> token(CALLER, lifetime = Duration.ofSeconds(-60))
            "no-expiry" -> token(CALLER, lifetime = null)
            else -> "definitely-not-a-jwt"
        }

        // when
        val reply = withoutWrites { get(STREAM, credential) }

        // then
        assertSecurity(reply, 401, message)
    }

    @Test
    fun `이웃 경로일 때__스트림 아래를 열면__403으로 거부한다`() {
        // given
        val caller = token(CALLER)

        // when
        val reply = withoutWrites { get("$STREAM/extra", caller) }

        // then
        assertSecurity(reply, 403, "접근할 수 없습니다.")
    }

    @Test
    fun `토큰 만료가 가까울 때__스트림을 열면__만료 시각에 서버가 정상 종료하고 헤더로 늘릴 수 없다`() {
        // given: the stream helper also sends a forged `X-Auth-Expires-At` one hour ahead.
        val shortLived = token(CALLER, lifetime = Duration.ofSeconds(4))

        // when
        val stream = openStream(shortLived)
        stream.await("ready", 1)
        stream.awaitEnd(Duration.ofSeconds(12))

        // then
        assertThat(stream.count("resync")).isZero()
        assertThat(stream.count("heartbeat")).isZero()
    }

    @Test
    fun `열린 스트림이 있을 때__하트비트 주기가 지나면__모든 열린 스트림이 하트비트를 받는다`() {
        // given
        val first = openStream(token(CALLER))
        val second = openStream(token(OTHER))
        first.await("ready", 1)
        second.await("ready", 1)

        // when: the registry emits a heartbeat every 15 seconds; the interval is not configurable.
        first.await("heartbeat", 1, Duration.ofSeconds(25))
        second.await("heartbeat", 1, Duration.ofSeconds(25))

        // then
        assertThat(first.count("resync")).isZero()
        assertThat(second.count("resync")).isZero()
    }

    private companion object {
        const val STREAM = "/api/v1/notes/stream"
        const val CALLER = 9_980_001L
        const val OTHER = 9_980_002L
    }
}
