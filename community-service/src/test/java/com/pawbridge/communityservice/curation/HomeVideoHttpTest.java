package com.pawbridge.communityservice.curation;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.pawbridge.communityservice.curation.HomeVideoModels.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.dao.DataAccessResourceFailureException;

class HomeVideoHttpTest {
    final HomeVideoService service=mock(HomeVideoService.class);
    MockMvc mvc;
    @BeforeEach void prepare() {
        mvc=MockMvcBuilders.standaloneSetup(new HomeVideoController(service))
                .setControllerAdvice(new HomeVideoExceptionAdvice()).build();
    }
    @Test void givenAnonymousVisitor_whenHomeList_thenDbListWithoutAdminIdentity() throws Exception {
        when(service.home()).thenReturn(List.of());
        mvc.perform(get("/api/v1/home/videos")).andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray());
        verify(service).home();
    }
    @Test void givenMissingOrOrdinaryRole_whenAdminList_thenForbidden() throws Exception {
        mvc.perform(get("/api/v1/admin/videos")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/videos").header("X-User-Role","ROLE_USER")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void givenAdmin_whenPreview_thenBindOnlyUrl() throws Exception {
        mvc.perform(post("/api/v1/admin/videos/preview").header("X-User-Role","ROLE_ADMIN")
                .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://youtu.be/AbCdEfGhI_1\"}"))
                .andExpect(status().isOk());
        verify(service).preview("https://youtu.be/AbCdEfGhI_1");
    }
    @Test void givenInvalidInput_whenCreateOrReorder_thenBadRequestWithoutMutation() throws Exception {
        mvc.perform(post("/api/v1/admin/videos").header("X-User-Role","ROLE_ADMIN")
                .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"\",\"revision\":-1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/admin/videos/order").header("X-User-Role","ROLE_ADMIN")
                .contentType(MediaType.APPLICATION_JSON).content("{\"ids\":null,\"revision\":0}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void givenStorageFailure_whenAdminList_thenSanitized503() throws Exception {
        when(service.board()).thenThrow(new DataAccessResourceFailureException("synthetic-key SQL values"));
        mvc.perform(get("/api/v1/admin/videos").header("X-User-Role","ROLE_ADMIN"))
                .andExpect(status().isServiceUnavailable()).andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("synthetic-key"))));
    }
}
