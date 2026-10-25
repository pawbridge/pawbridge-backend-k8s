package com.pawbridge.backend;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement;
import com.pawbridge.backend.community.internal.homevideo.web.HomeVideoManagementController;
import com.pawbridge.backend.community.internal.homevideo.web.HomeVideoManagementExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The container constraint List<@NotNull UUID> is a type-use annotation. Kotlin only writes it to the class file with
 * -Xemit-jvm-type-annotations, so this fails (200 or a 409 from the service) if that compiler flag is removed.
 */
@WebMvcTest(controllers=HomeVideoManagementController.class,properties="pawbridge.home-videos.management-enabled=true",excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={HomeVideoManagementController.class,HomeVideoManagementExceptionHandler.class,ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class})
class HomeVideoOrderRequestValidationHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean HomeVideoManagement videos;

    @Test
    void givenNullElementInIds__whenReordering__thenRejectWith400BeforeTheService() throws Exception {
        mvc.perform(put("/api/v1/admin/videos/order").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[null],\"revision\":5}").header("Authorization", HomeVideoManagementHttpTest.admin()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(videos);
    }
}
