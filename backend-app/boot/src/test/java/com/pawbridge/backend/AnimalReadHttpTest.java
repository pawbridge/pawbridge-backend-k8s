package com.pawbridge.backend;

import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery;
import com.pawbridge.backend.animal.internal.discovery.application.AnimalNotFoundException;
import com.pawbridge.backend.animal.internal.discovery.web.AnimalDiscoveryController;
import com.pawbridge.backend.animal.internal.discovery.web.AnimalDiscoveryExceptionHandler;
import com.pawbridge.backend.animal.internal.recommendation.application.AnimalRecommendations;
import com.pawbridge.backend.animal.internal.recommendation.web.AnimalRecommendationController;
import com.pawbridge.backend.animal.internal.recommendation.web.AnimalRecommendationExceptionHandler;
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

@WebMvcTest(properties = {"pawbridge.animal-discovery.enabled=true", "pawbridge.animal-recommendations.enabled=true"},
        excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes = {AnimalDiscoveryController.class, AnimalDiscoveryExceptionHandler.class,
        AnimalRecommendationController.class, AnimalRecommendationExceptionHandler.class,
        ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class AnimalReadHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean AnimalDiscovery animals;
    @MockitoBean AnimalRecommendations recommendations;

    @Test
    void given__enabled_public_reads__when__anonymous_or_bad_token_requests_exact_routes__then__reach_the_owned_controllers_without_sessions() throws Exception {
        when(animals.search(any(), any())).thenReturn(new AnimalDiscovery.Page(List.of(), 0, 20, 0, 0, true, true));
        when(animals.findById(42)).thenThrow(new AnimalNotFoundException());
        when(recommendations.recommend(42)).thenReturn(List.of());
        mvc.perform(get("/api/v1/animals").param("species", "DOG").header("Authorization", "Bearer invalid"))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(get("/api/v1/animals/42").param("view", "detail"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
        mvc.perform(get("/api/v1/animals/42/similar").param("view", "cards").header("Authorization", "Bearer invalid"))
                .andExpect(status().isOk()).andExpect(content().json("[]")).andExpect(header().doesNotExist("Set-Cookie"));
        verify(animals).search(any(), any());
        verify(animals).findById(42);
        verify(recommendations).recommend(42);
    }

    @Test
    void given__unimported_neighbors_and_mutations__when__requesting_even_with_admin_token__then__deny_without_invoking_animal_code() throws Exception {
        String admin = "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN");
        for (String path : List.of("/api/v1/animals/apms/D-42", "/api/v1/animals/expiring-soon", "/api/v1/animals/abc",
                "/api/v1/animals/-1", "/api/v1/animals/42/extra", "/api/v1/animals/42/similar/extra",
                "/api/v1/animals/abc/similar", "/api/animals/42/similar")) {
            mvc.perform(get(path).header("Authorization", admin)).andExpect(status().isForbidden());
        }
        for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
            mvc.perform(request(method, "/api/v1/animals/42").header("Authorization", admin)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(animals, recommendations);
    }

    @Test
    void given__existing_protected_route__when__token_is_missing_or_invalid__then__keep_jwt_rejection_before_mvc() throws Exception {
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Set-Cookie"));
        verifyNoInteractions(animals, recommendations);
    }
}
