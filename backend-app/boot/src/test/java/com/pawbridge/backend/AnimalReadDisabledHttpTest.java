package com.pawbridge.backend;

import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery;
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
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes = {AnimalDiscoveryController.class, AnimalDiscoveryExceptionHandler.class,
        AnimalRecommendationController.class, AnimalRecommendationExceptionHandler.class,
        ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class AnimalReadDisabledHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean AnimalDiscovery animals;
    @MockitoBean AnimalRecommendations recommendations;

    @Test
    void given__default_feature_flags__when__requesting_any_animal_read_even_with_admin_token__then__keep_default_deny() throws Exception {
        for (String path : List.of("/api/v1/animals", "/api/v1/animals/42", "/api/v1/animals/42/similar")) {
            mvc.perform(get(path)).andExpect(status().isForbidden());
            mvc.perform(get(path).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(animals, recommendations);
    }
}
