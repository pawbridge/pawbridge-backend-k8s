package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pawbridge.userservice.controller.FavoriteController;
import com.pawbridge.userservice.exception.common.GlobalExceptionRestAdvice;
import com.pawbridge.userservice.service.FavoriteService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Original controller and original global advice, without security, Redis or a database. */
class F14LegacyFavoriteMvcTest {
    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "9223372036854775808", "-9223372036854775809"})
    void givenMalformedAnimalId__whenOriginalMvcConverts__thenActualSpringMismatchUsesOriginalRuntime500(String id) throws Exception {
        FavoriteService service = mock(FavoriteService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new FavoriteController(service))
                .setControllerAdvice(new GlobalExceptionRestAdvice()).build();
        var result = mvc.perform(get("/api/v1/favorites/{id}/check", id).header("X-User-Id", "42"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("\uc11c\ubc84 \uc5d0\ub7ec!"))
                .andExpect(content().json("{\"code\":500,\"message\":\"\uc11c\ubc84 \uc5d0\ub7ec!\",\"data\":null}", JsonCompareMode.STRICT)).andReturn();
        assertThat(result.getResolvedException()).isInstanceOf(MethodArgumentTypeMismatchException.class);
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE, Long.MAX_VALUE})
    void givenRepresentableAnimalId__whenOriginalMvcConverts__thenRawLongReachesTheServiceAndBooleanEnvelope(long animalId)
            throws Exception {
        FavoriteService service = mock(FavoriteService.class);
        when(service.isFavorite(42L, animalId)).thenReturn(true);
        var mvc = MockMvcBuilders.standaloneSetup(new FavoriteController(service))
                .setControllerAdvice(new GlobalExceptionRestAdvice()).build();
        mvc.perform(get("/api/v1/favorites/{id}/check", animalId).header("X-User-Id", "42"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));
        verify(service).isFavorite(42L, animalId); verifyNoMoreInteractions(service);
    }
}
