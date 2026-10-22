package com.pawbridge.backend;

import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsBatchRunner;
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
@ContextConfiguration(classes = {ApmsManualSyncHttpTest.ControllerInputs.class, ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class ApmsManualSyncDisabledHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean ApmsBatchRunner runner;

    @Test
    void given__default_flag__when__anonymous_or_admin_posts_manual_sync__then__keep_default_deny_without_runner() throws Exception {
        for (String token : List.of("", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN"))) {
            var request = post("/api/v1/batch/apms/sync");
            if (!token.isEmpty()) request = request.header("Authorization", token);
            mvc.perform(request).andExpect(status().isForbidden());
        }
        verifyNoInteractions(runner);
    }
}
