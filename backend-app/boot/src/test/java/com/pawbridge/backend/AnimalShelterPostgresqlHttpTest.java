package com.pawbridge.backend;

import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads;
import com.pawbridge.backend.boot.configuration.AnimalShelterConfiguration;
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import java.time.LocalDate;
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

@WebMvcTest(properties = {"pawbridge.animal-shelters.enabled=true", "pawbridge.animal-query.backend=postgresql"},
        excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes = {AnimalShelterConfiguration.class, ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class AnimalShelterPostgresqlHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean ShelterReads shelters;

    @Test
    void given__explicit_postgresql_and_shelters_enabled__when__requesting_exact_discovery_gets__then__reach_source_selected_controller_without_jwt() throws Exception {
        when(shelters.discover(anyString(), anyString(), any(), any(), anyInt(), anyInt())).thenReturn(new ShelterReads.Page<>(List.of(), 0));
        when(shelters.observations(eq(42L), any(), any())).thenReturn(List.of());
        mvc.perform(get("/api/v1/shelters/discovery").param("intakeFrom", "2026-10-01").param("intakeTo", "2026-10-20")
                        .header("Authorization", "Bearer invalid"))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(get("/api/v1/shelters/42/observations").param("from", "2026-10-01").param("to", "2026-10-20"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(shelters).discover("", "", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 20), 0, 12);
        verify(shelters).observations(42, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 20));
    }

    @Test
    void given__postgresql_routes_enabled__when__requesting_adjacent_paths_or_writes__then__deny_without_broadening_nested_access() throws Exception {
        String admin = "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN");
        for (String path : List.of("/api/v1/shelters/discovery/extra", "/api/v1/shelters/42/observations/extra",
                "/api/v1/shelters/abc/observations", "/api/v1/shelters/-1/observations", "/api/shelters/discovery")) {
            mvc.perform(get(path).header("Authorization", admin)).andExpect(status().isForbidden());
        }
        for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
            for (String path : List.of("/api/v1/shelters/discovery", "/api/v1/shelters/42/observations")) {
                mvc.perform(request(method, path).header("Authorization", admin)).andExpect(status().isForbidden());
            }
        }
        verifyNoInteractions(shelters);
    }
}
