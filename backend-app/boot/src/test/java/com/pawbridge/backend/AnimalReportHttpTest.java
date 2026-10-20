package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.community.internal.report.application.AnimalReports;
import com.pawbridge.backend.community.internal.report.application.AnimalReports.*;
import com.pawbridge.backend.community.internal.report.web.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(controllers=AnimalReportController.class,properties="pawbridge.animal-reports.enabled=true",
        excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={AnimalReportController.class,AnimalReportExceptionHandler.class,
        ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class})
class AnimalReportHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean AnimalReports reports;
    private static final String ROOT="/api/v1/reports";
    private static final String BODY="{\"kind\":\"MISSING\",\"occurredOn\":\"2026-10-20\",\"region\":\"서울\",\"species\":\"개\",\"description\":\"내용\"}";
    private static Details details(){return new Details(42L,7L,"작성자","실종 · 서울 · 개","내용",List.of(),Kind.MISSING,
            LocalDate.of(2026,10,20),null,"서울",null,"개",null,null,null,null,null,
            LocalDateTime.of(2026,10,20,12,0),LocalDateTime.of(2026,10,20,12,0),null,null,null);}
    private static MockMultipartFile body(String json){return new MockMultipartFile("report","","application/json",json.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private static String token(){return "Bearer "+F3TestInputs.token(7,"ROLE_USER");}
    @Test void given__anonymous_reader__when__listing_and_getting__then__return_exact_page_defaults_and_twenty_two_fields()throws Exception{
        when(reports.list(any())).thenReturn(new Slice(List.of(details()),1));when(reports.get(42)).thenReturn(details());
        mvc.perform(get(ROOT)).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("요청이 성공적으로 처리되었습니다."))
                .andExpect(jsonPath("$.data.content[0].length()").value(22)).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1)).andExpect(jsonPath("$.data.number").value(0))
                .andExpect(jsonPath("$.data.size").value(12)).andExpect(jsonPath("$.data.sort.sorted").value(true))
                .andExpect(header().doesNotExist("Set-Cookie"));
        verify(reports).list(new Filter(0,12,null,null,null,null,null,null,null));
        mvc.perform(get(ROOT+"/42")).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(22))
                .andExpect(jsonPath("$.data.kind").value("MISSING")).andExpect(jsonPath("$.data.province").isEmpty());
    }
    @Test void given__all_search_fields__when__listing__then__bind_original_enum_and_date_names()throws Exception{
        when(reports.list(any())).thenReturn(new Slice(List.of(),0));
        mvc.perform(get(ROOT).param("page","2").param("size","5").param("kind","SIGHTING").param("keyword","개%")
                .param("province","서울특별시").param("district","강남구").param("animalType","DOG")
                .param("from","2026-10-01").param("to","2026-10-20")).andExpect(status().isOk());
        verify(reports).list(new Filter(2,5,Kind.SIGHTING,"개%","서울특별시","강남구",AnimalType.DOG,LocalDate.of(2026,10,1),LocalDate.of(2026,10,20)));
    }
    @ParameterizedTest @CsvSource({"page,-1,페이지 범위를 확인해 주세요.","size,51,페이지 범위를 확인해 주세요.","page,wrong,제보 검색의 종류·날짜·페이지 형식을 확인해 주세요.","kind,UNKNOWN,제보 검색의 종류·날짜·페이지 형식을 확인해 주세요.","from,2026-99-01,제보 검색의 종류·날짜·페이지 형식을 확인해 주세요."})
    void given__invalid_search_binding__when__listing__then__return_source_400_before_service(String key,String value,String message)throws Exception{
        mvc.perform(get(ROOT).param(key,value)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(message));verifyNoInteractions(reports);
    }
    @Test void given__verified_author_and_forged_headers__when__creating_updating_deleting__then__use_jwt_principal_and_source_envelopes()throws Exception{
        when(reports.create(any(),isNull(),eq(7L))).thenReturn(details());when(reports.update(eq(42L),any(),isNull(),eq(7L))).thenReturn(details());
        mvc.perform(multipart(ROOT).file(body(BODY)).header("Authorization",token()).header("X-User-Id","999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("요청이 성공적으로 처리되었습니다."))
                .andExpect(jsonPath("$.data.authorId").value(7)).andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(multipart(HttpMethod.PUT,ROOT+"/42").file(body(BODY)).header("Authorization",token()).header("X-User-Id","999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.reportId").value(42));
        mvc.perform(delete(ROOT+"/42").header("Authorization",token()).header("X-User-Id","999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("제보가 삭제되었습니다.")).andExpect(jsonPath("$.data").isEmpty());
        verify(reports).create(argThat(d->d.kind()==Kind.MISSING),isNull(),eq(7L));verify(reports).update(eq(42L),any(),isNull(),eq(7L));verify(reports).delete(42,7L);
    }
    @ParameterizedTest @ValueSource(strings={"POST","PUT","DELETE"})
    void given__no_jwt_and_invalid_body__when__mutating__then__authentication_precedes_binding_and_forged_identity_has_no_effect(String method)throws Exception{
        var request=method.equals("DELETE")?delete(ROOT+"/not-a-number"):
                multipart(HttpMethod.valueOf(method),ROOT+(method.equals("PUT")?"/not-a-number":"")).file(body("not-json"));
        mvc.perform(request.header("X-User-Id","7").header("X-User-Role","ROLE_ADMIN"))
                .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Set-Cookie"));verifyNoInteractions(reports);
    }
    @ParameterizedTest @ValueSource(ints={400,403,404})
    void given__source_policy_failure__when__authenticated_update_runs__then__preserve_status_and_reason(int code)throws Exception{
        String reason=code==400?"제보 종류는 변경할 수 없습니다.":null;
        when(reports.update(eq(42L),any(),any(),eq(7L))).thenThrow(new ResponseStatusException(HttpStatus.valueOf(code),reason));
        mvc.perform(multipart(HttpMethod.PUT,ROOT+"/42").file(body(BODY)).header("Authorization",token()))
                .andExpect(status().is(code)).andExpect(jsonPath("$.message").value(reason==null?"요청을 처리하지 못했습니다.":reason));
    }
    @Test void given__authenticated_bad_json_or_id_and_unexposed_neighbors__when__binding__then__keep_400_and_exact_route_403()throws Exception{
        mvc.perform(multipart(ROOT).file(body("not-json")).header("Authorization",token())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("요청 본문 형식이 올바르지 않습니다."));
        mvc.perform(delete(ROOT+"/wrong").header("Authorization",token())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("제보 검색의 종류·날짜·페이지 형식을 확인해 주세요."));
        mvc.perform(get(ROOT+"/42/extra")).andExpect(status().isForbidden());
        mvc.perform(delete(ROOT+"/42/extra").header("Authorization",token())).andExpect(status().isForbidden());verifyNoInteractions(reports);
    }
    @Test void given__private_runtime_failure__when__handling_request__then__emit_generic_500_and_type_only_log()throws Exception{
        String secret="synthetic-private-report-body-storage-token";when(reports.get(42)).thenThrow(new IllegalStateException(secret));
        Logger log=(Logger)LoggerFactory.getLogger(AnimalReportExceptionHandler.class);var appender=new ListAppender<ILoggingEvent>();appender.start();log.addAppender(appender);
        try{mvc.perform(get(ROOT+"/42")).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.message").value("서버 에러!"));
            assertThat(appender.list).hasSize(1);assertThat(appender.list.getFirst().getFormattedMessage()).contains("IllegalStateException").doesNotContain(secret);assertThat(appender.list.getFirst().getThrowableProxy()).isNull();}
        finally{log.detachAppender(appender);appender.stop();}
    }
}

@WebMvcTest(controllers=AnimalReportController.class,properties="pawbridge.animal-reports.enabled=false",
        excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={AnimalReportController.class,AnimalReportExceptionHandler.class,
        ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class})
class AnimalReportDisabledHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean AnimalReports reports;

    @ParameterizedTest @CsvSource({"GET,/api/v1/reports","GET,/api/v1/reports/42","POST,/api/v1/reports",
            "PUT,/api/v1/reports/42","DELETE,/api/v1/reports/42"})
    void given__reports_disabled__when__requesting_public_or_authenticated_routes__then__deny_before_any_report_query(String method,String path)throws Exception{
        var request=request(HttpMethod.valueOf(method),path);
        if(!method.equals("GET"))request.header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_USER"));
        mvc.perform(request).andExpect(status().isForbidden()).andExpect(header().doesNotExist("Set-Cookie"));
        verifyNoInteractions(reports);
    }
}
