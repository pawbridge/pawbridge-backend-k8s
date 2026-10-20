package com.pawbridge.backend;

import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics;
import com.pawbridge.backend.boot.configuration.AnimalStatisticsConfiguration;
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
@ContextConfiguration(classes = {AnimalStatisticsConfiguration.class, ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class AnimalStatisticsDisabledHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean AnimalStatistics statistics;

    @Test
    void given__default_statistics_flag__when__requesting_all_five_gets_as_anonymous_or_admin__then__keep_default_deny_without_controller_calls() throws Exception {
        for (String path : List.of("/api/v1/animals/stats/today", "/api/v1/animals/stats/status", "/api/v1/animals/stats/regional",
                "/api/v1/admin/stats/daily-animals", "/api/v1/admin/stats/intake-trend")) {
            mvc.perform(get(path)).andExpect(status().isForbidden());
            mvc.perform(get(path).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN"))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(statistics);
    }
}
