package com.pawbridge.communityservice.visits;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class VisitCookieTest {
    static final String KEY=Base64.getEncoder().encodeToString(new byte[32]);
    final VisitCookie cookie=new VisitCookie(KEY);
    @Test void givenFirstVisit_whenInitialize_thenSecureHostCookieAndMidnightExpiry() {
        Instant now=Instant.parse("2026-10-12T14:59:00Z");
        var created=cookie.initialize(null,now);
        assertThat(created.toString()).contains("__Host-pb_visit=","Path=/","Secure","HttpOnly","SameSite=Lax","Max-Age=60");
        assertThat(created.toString()).doesNotContain("Domain=");
        assertThat(cookie.identity(created.getValue(),LocalDate.of(2026,10,12))).isNotNull();
        assertThat(cookie.initialize(created.getValue(),now)).isNull();
    }
    @Test void givenTamperedOrExpiredCookie_whenValidate_thenRejectWithoutIdentity() {
        String value=cookie.initialize(null,Instant.parse("2026-10-12T01:00:00Z")).getValue();
        assertThat(cookie.identity(value,LocalDate.of(2026,10,13))).isNull();
        assertThat(cookie.identity(value.substring(0,value.length()-8)+"AAAAAAAA",LocalDate.of(2026,10,12))).isNull();
        assertThat(cookie.identity("v1.2026-10-12.x.y",LocalDate.of(2026,10,12))).isNull();
        assertThat(cookie.identity(null,LocalDate.of(2026,10,12))).isNull();
    }
    @Test void givenSameKeyAcrossInstances_whenValidateAndHash_thenStableWithinDayAndUnlinkedNextDay() {
        var second=new VisitCookie(KEY);
        var now=Instant.parse("2026-10-12T01:00:00Z");
        var date=cookie.day(now);
        var value=cookie.initialize(null,now).getValue();
        String id=second.identity(value,date);
        assertThat(id).isNotNull();
        assertThat(second.hash(date,id)).isEqualTo(cookie.hash(date,id)).hasSize(64);
        assertThat(second.hash(date.plusDays(1),id)).isNotEqualTo(cookie.hash(date,id));
        assertThat(cookie.initialize(null,now).getValue()).isNotEqualTo(value);
    }
    @Test void givenMissingOrWeakKey_whenConfigure_thenFailClosed() {
        assertThatThrownBy(() -> new VisitCookie("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VisitCookie("invalid*")).isInstanceOf(IllegalArgumentException.class);
    }
}
