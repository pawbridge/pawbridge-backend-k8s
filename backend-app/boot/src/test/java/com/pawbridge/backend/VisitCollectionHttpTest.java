package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.community.internal.visit.application.*;
import com.pawbridge.backend.community.internal.visit.web.*;
import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers=VisitCollectionController.class,
        properties={"pawbridge.visits.enabled=true","pawbridge.visits.collection-enabled=true"},
        excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={VisitCollectionController.class,VisitCollectionExceptionHandler.class,
        ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class,VisitCollectionHttpTest.Inputs.class})
class VisitCollectionHttpTest {
    static final Instant NOW=Instant.parse("2026-10-12T14:59:00Z");
    static final String ORIGIN="https://www.pawbridge.kr";
    @Autowired MockMvc mvc;
    @Autowired VisitIdentity identity;
    @MockitoBean VisitCollection visits;
    @TestConfiguration(proxyBeanMethods=false) static class Inputs {
        @Bean VisitIdentity identity(){return new VisitIdentity(Base64.getEncoder().encodeToString(new byte[32]));}
        @Bean Clock clock(){return Clock.fixed(NOW,ZoneOffset.UTC);}
        @Bean String origins(){return ORIGIN;}
    }
    @Test
    void given__first_and_existing_identity__when__initializing__then__host_cookie_until_midnight_without_count() throws Exception {
        var response=mvc.perform(post("/api/v1/visits/identity").header("Origin",ORIGIN)
                        .header("Authorization","Bearer invalid").header("X-User-Id","999"))
                .andExpect(status().isNoContent()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(content().string("")).andReturn().getResponse();
        String cookie=response.getHeader("Set-Cookie");
        assertThat(cookie).contains("__Host-pb_visit=","Path=/","Secure","HttpOnly","SameSite=Lax","Max-Age=60").doesNotContain("Domain=");
        String value=cookie.substring(cookie.indexOf('=')+1,cookie.indexOf(';'));
        assertThat(identity.identity(value,LocalDate.of(2026,10,12))).isNotNull();
        mvc.perform(post("/api/v1/visits/identity").header("Origin",ORIGIN).cookie(new Cookie(VisitIdentity.NAME,value)))
                .andExpect(status().isNoContent()).andExpect(header().doesNotExist("Set-Cookie"));
        verifyNoInteractions(visits);
    }
    @ParameterizedTest @ValueSource(strings={"missing","https://outside.fixture.invalid"})
    void given__unapproved_origin_even_for_bot__when__posting__then__403_before_cookie_or_storage(String origin) throws Exception {
        var request=post("/api/v1/visits/identity").header("User-Agent","crawler");
        if(!origin.equals("missing"))request.header("Origin",origin);
        mvc.perform(request).andExpect(status().isForbidden()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":403,\"message\":\"허용된 출처의 요청만 가능합니다.\",\"data\":null}",JsonCompareMode.STRICT));
        verifyNoInteractions(visits);
    }
    @Test
    void given__known_bot__when__initializing_or_counting__then__204_no_cookie_or_storage() throws Exception {
        for(String path:List.of("identity","today"))mvc.perform(post("/api/v1/visits/"+path).header("Origin",ORIGIN).header("User-Agent","HeadlessChrome"))
                .andExpect(status().isNoContent()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(header().doesNotExist("Set-Cookie")).andExpect(content().string(""));
        verifyNoInteractions(visits);
    }
    @Test
    void given__cookie_and_untrusted_identity_headers__when__counting__then__exact_day_count_without_identity_echo_or_mint() throws Exception {
        String cookie=identity.initialize(null,NOW).value();
        when(visits.count(cookie)).thenReturn(new TodayVisits(LocalDate.of(2026,10,12),7));
        mvc.perform(post("/api/v1/visits/today").header("Origin",ORIGIN).header("X-User-Id","999")
                        .header("Authorization","Bearer invalid").cookie(new Cookie(VisitIdentity.NAME,cookie)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{\"date\":\"2026-10-12\",\"count\":7}}",JsonCompareMode.STRICT));
        verify(visits).count(cookie);
    }
    @Test
    void given__missing_or_ambiguous_cookie__when__counting__then__409_without_mint() throws Exception {
        when(visits.count(null)).thenThrow(new VisitCollectionRejectedException(VisitCollectionRejectedException.Reason.COOKIE));
        for(boolean duplicate:List.of(false,true)){
            var request=post("/api/v1/visits/today").header("Origin",ORIGIN);
            if(duplicate)request.cookie(new Cookie(VisitIdentity.NAME,"synthetic-one"),new Cookie(VisitIdentity.NAME,"synthetic-two"));
            mvc.perform(request).andExpect(status().isConflict()).andExpect(header().string("Cache-Control","no-store"))
                    .andExpect(header().doesNotExist("Set-Cookie"))
                    .andExpect(content().json("{\"code\":409,\"message\":\"방문 쿠키 초기화가 필요합니다.\",\"data\":null}",JsonCompareMode.STRICT));
        }
        verify(visits,times(2)).count(null);
    }
    @ParameterizedTest @ValueSource(strings={"missing","invalid","USER"})
    void given__no_actual_admin_token_despite_role_header__when__reading_period__then__deny_without_query(String role) throws Exception {
        var request=get("/api/v1/admin/visits/stats/period").param("startDate","2026-10-12").param("endDate","2026-10-12")
                .header("X-User-Role","ROLE_ADMIN").header("X-User-Id","1");
        if(role.equals("invalid"))request.header("Authorization","Bearer invalid");
        if(role.equals("USER"))request.header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_USER"));
        mvc.perform(request).andExpect(status().is(role.equals("USER")?403:401));
        verifyNoInteractions(visits);
    }
    @Test
    void given__actual_admin_and_dates__when__reading_period__then__exact_envelope_and_explicit_parameter_binding() throws Exception {
        var day=LocalDate.of(2026,10,12);
        when(visits.period(day,day)).thenReturn(new VisitCollection.Period(day,day,day,NOW,List.of(new VisitCollection.Day(day,7))));
        String token=F3TestInputs.token(7,"ROLE_ADMIN");
        mvc.perform(get("/api/v1/admin/visits/stats/period").header("Authorization","Bearer "+token)
                        .header("X-User-Role","ROLE_USER").param("startDate",day.toString()).param("endDate",day.toString()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(content().json("{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{\"startDate\":\"2026-10-12\",\"endDate\":\"2026-10-12\",\"today\":\"2026-10-12\",\"collectionStartedAt\":\"2026-10-12T14:59:00Z\",\"daily\":[{\"date\":\"2026-10-12\",\"count\":7}]}}",JsonCompareMode.STRICT));
        mvc.perform(get("/api/v1/admin/visits/stats/period").header("Authorization","Bearer "+token).param("startDate","bad").param("endDate",day.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("날짜 형식을 확인해 주세요."));
        mvc.perform(get("/api/v1/admin/visits/stats/period").header("Authorization","Bearer "+token).param("startDate",day.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("필수 요청 파라미터 'endDate'(이)가 누락되었습니다."));
        verify(visits).period(day,day);
    }
    @Test
    void given__storage_or_runtime_failure__when__counting__then__safe_error_without_private_details() throws Exception {
        when(visits.count(null)).thenThrow(new DataAccessResourceFailureException("synthetic-private-sql"),new IllegalStateException("synthetic-private-key"));
        mvc.perform(post("/api/v1/visits/today").header("Origin",ORIGIN)).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control","no-store"))
                .andExpect(content().json("{\"code\":503,\"message\":\"방문 통계를 불러올 수 없습니다.\",\"data\":null}",JsonCompareMode.STRICT));
        mvc.perform(post("/api/v1/visits/today").header("Origin",ORIGIN)).andExpect(status().isInternalServerError())
                .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}",JsonCompareMode.STRICT));
    }
}
