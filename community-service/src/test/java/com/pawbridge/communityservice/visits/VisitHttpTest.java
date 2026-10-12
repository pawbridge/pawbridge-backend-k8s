package com.pawbridge.communityservice.visits;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

class VisitHttpTest {
    final VisitService service=mock(VisitService.class);
    final Instant now=Instant.parse("2026-10-12T01:00:00Z");
    final VisitCookie cookies=new VisitCookie(VisitCookieTest.KEY);
    MockMvc mvc;
    @BeforeEach void setup() {
        mvc=MockMvcBuilders.standaloneSetup(new VisitController(service,cookies,Clock.fixed(now,ZoneOffset.UTC),"https://www.pawbridge.kr"))
                .setControllerAdvice(new VisitExceptionAdvice()).build();
    }
    @Test void givenAllowedOrigin_whenBootstrap_thenIssueCookieWithoutCounting() throws Exception {
        mvc.perform(post("/api/v1/visits/identity").header("Origin","https://www.pawbridge.kr"))
                .andExpect(status().isNoContent()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(header().string("Set-Cookie",org.hamcrest.Matchers.containsString("HttpOnly")));
        verifyNoInteractions(service);
    }
    @Test void givenForeignOrMissingOrigin_whenWrite_thenRejectBeforeAnyStorage() throws Exception {
        mvc.perform(post("/api/v1/visits/today").header("Origin","https://evil.invalid")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/visits/identity")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void givenKnownBot_whenWrite_thenNoCookieAndNoCount() throws Exception {
        for (String path:List.of("identity","today"))
            mvc.perform(post("/api/v1/visits/"+path).header("Origin","https://www.pawbridge.kr").header("User-Agent","GPTBot"))
                    .andExpect(status().isNoContent()).andExpect(header().doesNotExist("Set-Cookie"));
        verifyNoInteractions(service);
    }
    @Test void givenCookie_whenCount_thenSendCountWithBodyAndNoIdentityEcho() throws Exception {
        var value=cookies.initialize(null,now).getValue();
        when(service.count(value)).thenReturn(new VisitModels.Today(cookies.day(now),1));
        mvc.perform(post("/api/v1/visits/today").header("Origin","https://www.pawbridge.kr").cookie(new Cookie(VisitCookie.NAME,value)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.count").value(1))
                .andExpect(header().doesNotExist("Set-Cookie")).andExpect(header().string("Cache-Control","no-store"));
        verify(service).count(value);
    }
    @Test void givenCookieNotSaved_whenCount_thenConflictWithoutCookieMint() throws Exception {
        when(service.count(null)).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT,"방문 쿠키 초기화가 필요합니다."));
        mvc.perform(post("/api/v1/visits/today").header("Origin","https://www.pawbridge.kr"))
                .andExpect(status().isConflict()).andExpect(header().doesNotExist("Set-Cookie"));
    }
    @Test void givenMissingOrForgedUserRole_whenAdmin_thenForbidden() throws Exception {
        mvc.perform(get("/api/v1/admin/visits/stats/period").param("startDate","2026-10-12").param("endDate","2026-10-12"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/visits/stats/period").header("X-User-Role","ROLE_USER")
                .param("startDate","2026-10-12").param("endDate","2026-10-12")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void givenDatabaseFailure_whenRead_then503WithoutSensitiveError() throws Exception {
        when(service.today()).thenThrow(new DataAccessResourceFailureException("synthetic secret SQL"));
        mvc.perform(get("/api/v1/visits/today")).andExpect(status().isServiceUnavailable())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("synthetic secret"))));
    }
}
