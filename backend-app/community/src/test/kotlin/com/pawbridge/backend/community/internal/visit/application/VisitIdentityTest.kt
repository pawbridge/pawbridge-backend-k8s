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
    fun givenFirstVisit_whenInitialize_thenSecureHostCookieAndMidnightExpiry() {
        val now = Instant.parse("2026-10-12T14:59:00Z")
        val created = cookie.initialize(null, now)!!
        assertThat(created.maxAgeSeconds).isEqualTo(60)
        assertThat(cookie.identity(created.value, LocalDate.of(2026, 10, 12))).isNotNull()
        assertThat(cookie.initialize(created.value, now)).isNull()
    }

    @Test
    fun givenTamperedOrExpiredCookie_whenValidate_thenRejectWithoutIdentity() {
        val value = cookie.initialize(null, Instant.parse("2026-10-12T01:00:00Z"))!!.value
        assertThat(cookie.identity(value, LocalDate.of(2026, 10, 13))).isNull()
        assertThat(
            cookie.identity(value.substring(0, value.length - 8) + "AAAAAAAA", LocalDate.of(2026, 10, 12)),
        ).isNull()
        assertThat(cookie.identity("v1.2026-10-12.x.y", LocalDate.of(2026, 10, 12))).isNull()
        assertThat(cookie.identity(null, LocalDate.of(2026, 10, 12))).isNull()
    }

    @Test
    fun givenSameKeyAcrossInstances_whenValidateAndHash_thenStableWithinDayAndUnlinkedNextDay() {
        val second = VisitIdentity(KEY)
        val now = Instant.parse("2026-10-12T01:00:00Z")
        val date = cookie.day(now)
        val value = cookie.initialize(null, now)!!.value
        val id = second.identity(value, date)
        assertThat(id).isNotNull()
        assertThat(second.hash(date, id!!)).isEqualTo(cookie.hash(date, id)).hasSize(64)
        assertThat(second.hash(date.plusDays(1), id)).isNotEqualTo(cookie.hash(date, id))
        assertThat(cookie.initialize(null, now)!!.value).isNotEqualTo(value)
    }

    @Test
    fun givenMissingOrWeakKey_whenConfigure_thenFailClosed() {
        assertThatThrownBy { VisitIdentity("") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { VisitIdentity("invalid*") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    companion object {
        val KEY: String = Base64.getEncoder().encodeToString(ByteArray(32))
    }
}
