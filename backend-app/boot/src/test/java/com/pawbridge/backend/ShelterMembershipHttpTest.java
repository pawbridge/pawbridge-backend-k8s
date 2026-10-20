package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.user.internal.shelter.application.*;
import com.pawbridge.backend.user.internal.shelter.web.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(properties="pawbridge.shelter-membership.enabled=true",excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={ShelterApplicationController.class,AdminShelterApplicationController.class,AdminShelterMemberController.class,
        ShelterMembershipExceptionHandler.class,ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class})
class ShelterMembershipHttpTest {
    private static final String OWN="/api/v1/users/me/shelter-applications",ADMIN="/api/v1/admin/users/shelter-applications";
    @Autowired MockMvc mvc;@MockitoBean ShelterMembership memberships;
    @Test void given__verified_principals_and_all_eight_routes__when__calling__then__bind_explicit_ids_defaults_dates_bodies_and_original_envelopes()throws Exception{
        var row=row();var admin=new ShelterMembership.AdminApplication(row,"이름","fixture@example.invalid",null);
        when(memberships.submit(7," raw ")).thenReturn(row);when(memberships.mine(7,0,20)).thenReturn(new ShelterMembership.Page<>(List.of(row),1,0,20));
        when(memberships.list(7,ShelterMembership.Status.PENDING,0,20)).thenReturn(new ShelterMembership.Page<>(List.of(admin),1,0,20));when(memberships.detail(7,91)).thenReturn(admin);
        when(memberships.approve(7,91,"reg","note")).thenReturn(admin);when(memberships.reject(7,91,"reason")).thenReturn(admin);
        when(memberships.members(7,"reg",0,20)).thenReturn(new ShelterMembership.Page<>(List.of(new ShelterMembership.Member(8L,"이름",null,null)),1,0,20));
        var day=LocalDate.of(2026,10,20);when(memberships.statistics(7,day,day)).thenReturn(new ShelterMembership.Statistics(day,day,List.of(),2,3,4,5));
        mvc.perform(post(OWN).header("Authorization",token("ROLE_USER")).header("X-User-Id","999").contentType("application/json").content("{\"shelterName\":\" raw \"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.requestedAt").value("2026-10-20T01:02:03.123456")).andExpect(jsonPath("$.data.reason").isEmpty()).andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(get(OWN).header("Authorization",token("ROLE_USER"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1)).andExpect(jsonPath("$.data.sort.sorted").value(true));
        mvc.perform(get(ADMIN).param("status","PENDING").header("Authorization",token("ROLE_ADMIN"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].application.id").value(91));
        mvc.perform(get(ADMIN+"/91").header("Authorization",token("ROLE_ADMIN"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.applicantName").value("이름"));
        mvc.perform(post(ADMIN+"/91/approve").header("Authorization",token("ROLE_ADMIN")).contentType("application/json").content("{\"careRegNo\":\"reg\",\"note\":\"note\"}")).andExpect(status().isOk());
        mvc.perform(post(ADMIN+"/91/reject").header("Authorization",token("ROLE_ADMIN")).contentType("application/json").content("{\"reason\":\"reason\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/users/shelters/reg/members").header("Authorization",token("ROLE_ADMIN"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].approvalApplicationId").isEmpty());
        mvc.perform(get(ADMIN+"/stats").param("startDate",day.toString()).param("endDate",day.toString()).header("Authorization",token("ROLE_ADMIN"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.currentPending").value(3));
        verify(memberships).submit(7," raw ");verify(memberships).mine(7,0,20);verify(memberships).list(7,ShelterMembership.Status.PENDING,0,20);verify(memberships).detail(7,91);
        verify(memberships).approve(7,91,"reg","note");verify(memberships).reject(7,91,"reason");verify(memberships).members(7,"reg",0,20);verify(memberships).statistics(7,day,day);verifyNoMoreInteractions(memberships);cleared();
    }
    @ParameterizedTest @ValueSource(strings={"ROLE_USER","ROLE_ADMIN","ROLE_SHELTER"})
    void given__an_authenticated_role__when__reading_own_applications__then__allow_the_service_to_apply_current_database_policy(String role)throws Exception{
        when(memberships.mine(7,0,20)).thenReturn(new ShelterMembership.Page<>(List.of(),0,0,20));mvc.perform(get(OWN).header("Authorization",token(role))).andExpect(status().isOk());verify(memberships).mine(7,0,20);cleared();
    }
    @Test void given__missing_jwt_or_forged_admin_role__when__a_bad_id_and_body_are_sent__then__authenticate_and_authorize_before_binding()throws Exception{
        mvc.perform(post(ADMIN+"/bad/approve").header("X-User-Role","ROLE_ADMIN").contentType("application/json").content("{"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post(ADMIN+"/bad/approve").header("Authorization",token("ROLE_USER")).header("X-User-Role","ROLE_ADMIN").contentType("application/json").content("{"))
            .andExpect(status().isForbidden());verifyNoInteractions(memberships);cleared();
    }
    @Test void given__an_admin_with_malformed_parameters_or_missing_dates__when__binding__then__retain_local_empty_400_and_global_missing_parameter_envelope()throws Exception{
        mvc.perform(get(ADMIN+"/bad").header("Authorization",token("ROLE_ADMIN"))).andExpect(status().isBadRequest()).andExpect(content().string(""));
        mvc.perform(get(ADMIN).param("status","other").header("Authorization",token("ROLE_ADMIN"))).andExpect(status().isBadRequest()).andExpect(content().string(""));
        mvc.perform(get(ADMIN+"/stats").param("startDate","2026-10-20").header("Authorization",token("ROLE_ADMIN"))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("필수 요청 파라미터 'endDate'(이)가 누락되었습니다."));verifyNoInteractions(memberships);
    }
    @ParameterizedTest @ValueSource(strings={"business","database","commit"})
    void given__business_or_late_storage_failure__when__approving__then__return_safe_original_error_without_secret_cookie_or_context(String phase)throws Exception{
        RuntimeException failure=switch(phase){case "business"->new ShelterMembershipRejectedException(ShelterMembershipRejectedException.Reason.CONFLICT);
            case "database"->new org.springframework.dao.DataAccessResourceFailureException("synthetic-private-secret");default->new org.springframework.transaction.TransactionSystemException("synthetic-private-secret");};
        when(memberships.approve(7,91,"reg","note")).thenThrow(failure);
        var response=mvc.perform(post(ADMIN+"/91/approve").header("Authorization",token("ROLE_ADMIN")).contentType("application/json").content("{\"careRegNo\":\"reg\",\"note\":\"note\"}"))
            .andExpect(status().is(phase.equals("business")?409:500)).andExpect(header().doesNotExist("Set-Cookie")).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("synthetic-private-secret");assertThat(response.getHeader("Cache-Control")).contains("no-store");cleared();
    }
    @Test void given__invalid_request_bodies_and_adjacent_routes__when__calling__then__reject_without_business_invocation()throws Exception{
        mvc.perform(post(OWN).header("Authorization",token("ROLE_USER")).contentType("application/json").content("{\"shelterName\":\" \"}")).andExpect(status().isBadRequest());
        mvc.perform(post(ADMIN+"/91/reject").header("Authorization",token("ROLE_ADMIN")).contentType("application/json").content("{\"reason\":\" \"}")).andExpect(status().isBadRequest());
        mvc.perform(put(ADMIN+"/91/approve").header("Authorization",token("ROLE_ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(get(OWN+"/extra").header("Authorization",token("ROLE_USER"))).andExpect(status().isForbidden());verifyNoInteractions(memberships);
    }
    private static String token(String role){return "Bearer "+F3TestInputs.token(7,role);}
    private static void cleared(){assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();}
    private static ShelterMembership.Application row(){return new ShelterMembership.Application(91L,8L,"보호소",ShelterMembership.Status.PENDING,LocalDateTime.parse("2026-10-20T01:02:03.123456"),null,null,null,null);}
}
