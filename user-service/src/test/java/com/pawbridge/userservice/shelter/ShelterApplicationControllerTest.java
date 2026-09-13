package com.pawbridge.userservice.shelter;

import com.pawbridge.userservice.exception.common.GlobalExceptionRestAdvice;
import com.pawbridge.userservice.exception.common.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ShelterApplicationControllerTest {
    private final ShelterApplicationService service = mock(ShelterApplicationService.class);
    private MockMvc mvc;
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new ShelterApplicationController(service),
                new AdminShelterApplicationController(service), new AdminShelterMemberController(service))
                .setControllerAdvice(new GlobalExceptionRestAdvice()).build();
    }
    @Test void blankShelterNameDoesNotSubmit() throws Exception {
        mvc.perform(post("/api/v1/users/me/shelter-applications")
                .contentType("application/json").content("{\"shelterName\":\" \"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void approvalRequiresRegistrationAndVerificationNote() throws Exception {
        for (String body : new String[]{"{\"careRegNo\":\"123\"}", "{\"note\":\"checked\"}"}) {
            mvc.perform(post("/api/v1/admin/users/shelter-applications/5/approve")
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }
    @Test void rejectionRequiresReason() throws Exception {
        mvc.perform(post("/api/v1/admin/users/shelter-applications/5/reject")
                .contentType("application/json").content("{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void missingAuthenticationIsUnauthorized() throws Exception {
        when(service.mine(null, 0, 20)).thenThrow(new ShelterApplicationException(ErrorCode.TOKEN_INVALID));
        mvc.perform(get("/api/v1/users/me/shelter-applications")).andExpect(status().isUnauthorized());
    }
    @Test void adminPermissionFailureIsForbidden() throws Exception {
        when(service.detail("Bearer member", 5L)).thenThrow(new ShelterApplicationException(ErrorCode.SHELTER_APPLICATION_FORBIDDEN));
        mvc.perform(get("/api/v1/admin/users/shelter-applications/5").header("Authorization", "Bearer member"))
                .andExpect(status().isForbidden());
    }
    @Test void validApprovalPassesReviewerInputToService() throws Exception {
        mvc.perform(post("/api/v1/admin/users/shelter-applications/5/approve")
                .header("Authorization", "Bearer admin").contentType("application/json")
                .content("{\"careRegNo\":\"123\",\"note\":\"verified\"}"))
                .andExpect(status().isOk());
        verify(service).approve("Bearer admin", 5L, "123", "verified");
    }
    @Test void memberLookupPassesRegistrationAndPagination() throws Exception {
        when(service.members("Bearer admin", "123", 2, 10)).thenReturn(org.springframework.data.domain.Page.empty(org.springframework.data.domain.PageRequest.of(2, 10)));
        mvc.perform(get("/api/v1/admin/users/shelters/123/members")
                .header("Authorization", "Bearer admin").param("page", "2").param("size", "10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").isArray());
        verify(service).members("Bearer admin", "123", 2, 10);
    }
    @Test void memberLookupPropagatesForbidden() throws Exception {
        when(service.members("Bearer member", "123", 0, 20))
                .thenThrow(new ShelterApplicationException(ErrorCode.SHELTER_APPLICATION_FORBIDDEN));
        mvc.perform(get("/api/v1/admin/users/shelters/123/members").header("Authorization", "Bearer member"))
                .andExpect(status().isForbidden());
    }
}
