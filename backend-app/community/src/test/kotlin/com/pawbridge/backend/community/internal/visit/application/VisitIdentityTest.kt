package com.pawbridge.backend.community.internal.visit.application

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.Base64

class VisitIdentityTest {
    private val cookie = VisitIdentity(KEY)

    @Test
    fun `첫 방문일 때__초기화하면__보안 호스트 쿠키를 만들고 자정에 만료된다`() {
        // given
        val now = Instant.parse("2026-10-12T14:59:00Z")

        // when
        val created = cookie.initialize(null, now)!!

        // then
        assertThat(created.maxAgeSeconds).isEqualTo(60)
        assertThat(cookie.identity(created.value, LocalDate.of(2026, 10, 12))).isNotNull()
        assertThat(cookie.initialize(created.value, now)).isNull()
    }

    @Test
    fun `변조되었거나 만료된 쿠키일 때__검증하면__신원 없이 거부한다`() {
        // given
        val value = cookie.initialize(null, Instant.parse("2026-10-12T01:00:00Z"))!!.value

        // when
        val expired = cookie.identity(value, LocalDate.of(2026, 10, 13))

        // then
        assertThat(expired).isNull()
        assertThat(
            cookie.identity(value.substring(0, value.length - 8) + "AAAAAAAA", LocalDate.of(2026, 10, 12)),
        ).isNull()
        assertThat(cookie.identity("v1.2026-10-12.x.y", LocalDate.of(2026, 10, 12))).isNull()
        assertThat(cookie.identity(null, LocalDate.of(2026, 10, 12))).isNull()
    }

    @Test
    fun `인스턴스가 달라도 같은 키일 때__검증하고 해시하면__하루 안에서는 안정적이고 다음 날과는 연결되지 않는다`() {
        // given
        val second = VisitIdentity(KEY)
        val now = Instant.parse("2026-10-12T01:00:00Z")
        val date = cookie.day(now)
        val value = cookie.initialize(null, now)!!.value

        // when
        val id = second.identity(value, date)

        // then
        assertThat(id).isNotNull()
        assertThat(second.hash(date, id!!)).isEqualTo(cookie.hash(date, id)).hasSize(64)
        assertThat(second.hash(date.plusDays(1), id)).isNotEqualTo(cookie.hash(date, id))
        assertThat(cookie.initialize(null, now)!!.value).isNotEqualTo(value)
    }

    @Test
    fun `키가 없거나 약할 때__설정하면__닫힌 채로 실패한다`() {
        // given
        val missing = ""
        val weak = "invalid*"

        // when
        val missingThrown = assertThatThrownBy { VisitIdentity(missing) }
        val weakThrown = assertThatThrownBy { VisitIdentity(weak) }

        // then
        missingThrown.isInstanceOf(IllegalArgumentException::class.java)
        weakThrown.isInstanceOf(IllegalArgumentException::class.java)
    }

    companion object {
        val KEY: String = Base64.getEncoder().encodeToString(ByteArray(32))
    }
}
