package com.pawbridge.backend;

import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads;
import com.pawbridge.backend.boot.configuration.AnimalShelterConfiguration;
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

@WebMvcTest(properties = "pawbridge.animal-query.backend=postgresql",
        excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes = {AnimalShelterConfiguration.class, ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class AnimalShelterDisabledHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean ShelterReads shelters;

    @Test
    void given__default_disabled_shelters_even_with_postgresql__when__requesting_six_gets_as_anonymous_or_admin__then__keep_default_deny() throws Exception {
        for (String path : List.of("/api/v1/shelters", "/api/v1/shelters/42", "/api/v1/shelters/by-care-reg-no/CARE-42",
                "/api/v1/shelters/count", "/api/v1/shelters/discovery", "/api/v1/shelters/42/observations")) {
            mvc.perform(get(path)).andExpect(status().isForbidden());
            mvc.perform(get(path).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN"))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(shelters);
    }
}
