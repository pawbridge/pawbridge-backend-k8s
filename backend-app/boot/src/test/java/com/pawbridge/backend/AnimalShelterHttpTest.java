package com.pawbridge.backend;

import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads;
import com.pawbridge.backend.boot.configuration.AnimalShelterConfiguration;
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(properties = "pawbridge.animal-shelters.enabled=true",
        excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes = {AnimalShelterConfiguration.class, ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class AnimalShelterHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean ShelterReads shelters;

    @Test
    void given__enabled_shelters_without_explicit_postgresql__when__requesting_four_canonical_gets__then__reach_only_general_reads_without_jwt_or_sessions() throws Exception {
        when(shelters.search(any(), any())).thenReturn(new ShelterReads.Page<>(List.of(), 0));
        var detail = new ShelterReads.Details(42L, "CARE-42", "fixture", null, null, null, null, null, null, null, null, null, null, null);
        when(shelters.findById(42)).thenReturn(detail);
        when(shelters.findByCareRegNo("CARE-42")).thenReturn(detail);
        when(shelters.count()).thenReturn(17L);
        mvc.perform(get("/api/v1/shelters").param("keyword", "fixture").header("Authorization", "Bearer invalid"))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(get("/api/v1/shelters/42").param("view", "detail")).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(42));
        mvc.perform(get("/api/v1/shelters/by-care-reg-no/CARE-42")).andExpect(status().isOk()).andExpect(jsonPath("$.careRegNo").value("CARE-42"));
        mvc.perform(get("/api/v1/shelters/count")).andExpect(status().isOk()).andExpect(content().string("17"));
        verify(shelters).search(any(), any()); verify(shelters).findById(42); verify(shelters).findByCareRegNo("CARE-42"); verify(shelters).count();
    }

    @Test
    void given__general_reads_only__when__requesting_discovery_neighbors_or_mutations_even_as_admin__then__deny_before_shelter_code() throws Exception {
        String admin = "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN");
        for (String path : List.of("/api/v1/shelters/discovery", "/api/v1/shelters/42/observations",
                "/api/v1/shelters/abc", "/api/v1/shelters/-1", "/api/v1/shelters/42/extra",
                "/api/v1/shelters/by-care-reg-no/CARE-42/extra", "/api/v1/shelters/count/extra", "/api/shelters/42")) {
            mvc.perform(get(path).header("Authorization", admin)).andExpect(status().isForbidden());
        }
        for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
            mvc.perform(request(method, "/api/v1/shelters/42").header("Authorization", admin)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(shelters);
    }

    @Test
    void given__existing_user_private_read__when__jwt_is_missing_or_invalid__then__keep_existing_authentication_rejection() throws Exception {
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
        verifyNoInteractions(shelters);
    }
}
