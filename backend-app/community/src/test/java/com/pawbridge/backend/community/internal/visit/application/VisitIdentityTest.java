package com.pawbridge.backend.community.internal.visit.application;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class VisitIdentityTest {
    static final String KEY=Base64.getEncoder().encodeToString(new byte[32]);
    final VisitIdentity cookie=new VisitIdentity(KEY);
    @Test void givenFirstVisit_whenInitialize_thenSecureHostCookieAndMidnightExpiry() {
        Instant now=Instant.parse("2026-10-12T14:59:00Z");
        var created=cookie.initialize(null,now);
        assertThat(created.maxAgeSeconds()).isEqualTo(60);
        assertThat(cookie.identity(created.value(),LocalDate.of(2026,10,12))).isNotNull();
        assertThat(cookie.initialize(created.value(),now)).isNull();
    }
    @Test void givenTamperedOrExpiredCookie_whenValidate_thenRejectWithoutIdentity() {
        String value=cookie.initialize(null,Instant.parse("2026-10-12T01:00:00Z")).value();
        assertThat(cookie.identity(value,LocalDate.of(2026,10,13))).isNull();
        assertThat(cookie.identity(value.substring(0,value.length()-8)+"AAAAAAAA",LocalDate.of(2026,10,12))).isNull();
        assertThat(cookie.identity("v1.2026-10-12.x.y",LocalDate.of(2026,10,12))).isNull();
        assertThat(cookie.identity(null,LocalDate.of(2026,10,12))).isNull();
    }
    @Test void givenSameKeyAcrossInstances_whenValidateAndHash_thenStableWithinDayAndUnlinkedNextDay() {
        var second=new VisitIdentity(KEY);
        var now=Instant.parse("2026-10-12T01:00:00Z");
        var date=cookie.day(now);
        var value=cookie.initialize(null,now).value();
        String id=second.identity(value,date);
        assertThat(id).isNotNull();
        assertThat(second.hash(date,id)).isEqualTo(cookie.hash(date,id)).hasSize(64);
        assertThat(second.hash(date.plusDays(1),id)).isNotEqualTo(cookie.hash(date,id));
        assertThat(cookie.initialize(null,now).value()).isNotEqualTo(value);
    }
    @Test void givenMissingOrWeakKey_whenConfigure_thenFailClosed() {
        assertThatThrownBy(() -> new VisitIdentity("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VisitIdentity("invalid*")).isInstanceOf(IllegalArgumentException.class);
    }
}
