package com.pawbridge.backend.animal.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery;
import com.pawbridge.backend.animal.internal.discovery.web.AnimalDiscoveryController;
import com.pawbridge.backend.animal.internal.discovery.web.AnimalDiscoveryExceptionHandler;
import com.pawbridge.backend.animal.internal.recommendation.application.AnimalRecommendations;
import com.pawbridge.backend.animal.internal.recommendation.web.AnimalRecommendationController;
import com.pawbridge.backend.animal.internal.recommendation.web.AnimalRecommendationExceptionHandler;
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads;
import com.pawbridge.backend.animal.internal.shelter.web.ShelterReadController;
import com.pawbridge.backend.animal.internal.shelter.web.ShelterReadExceptionHandler;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.MissingPathVariableException;

/**
 * Locks in the Java behaviour of animal path variables after the Java to Kotlin conversion.
 *
 * <p>A Kotlin {@code @PathVariable id: Long?} makes Spring treat the variable as optional, so a blank
 * segment ({@code /%20}) reached the service as null. The Java original declared a required boxed
 * {@code Long}, which fails with {@link MissingPathVariableException} before the handler runs. Every path
 * variable of the controllers below must keep that behaviour.
 *
 * <p>The services are bare mocks, so for a numeric id the handlers themselves may fail afterwards (for
 * example mapping a null result). Those tests therefore assert only the service call, not the response.
 *
 * <p>Standalone MockMvc with the module exception handlers and mocked services only. It does not cover the
 * servlet container, the security filter chain or error dispatch.
 */
class AnimalPathVariableRegressionTest {

    /** Expanded into the URI template, so it reaches Spring as the blank segment {@code %20}. */
    private static final String BLANK_SEGMENT = " ";

    private static final long ID = 10L;

    /** Body written by the module exception handlers for the Java original. */
    private static final String SERVER_ERROR_BODY = "{\"code\":500,\"message\":\"서버 내부 오류가 발생했습니다.\",\"data\":null}";

    private final AnimalDiscovery animals = mock(AnimalDiscovery.class);
    private final AnimalRecommendations recommendations = mock(AnimalRecommendations.class);
    private final ShelterReads shelters = mock(ShelterReads.class);

    private MockMvc discoveryMvc;
    private MockMvc recommendationMvc;
    private MockMvc shelterMvc;

    @BeforeEach
    void setUp() {
        discoveryMvc = MockMvcBuilders
                .standaloneSetup(new AnimalDiscoveryController(animals))
                .setControllerAdvice(new AnimalDiscoveryExceptionHandler())
                .build();
        recommendationMvc = MockMvcBuilders
                .standaloneSetup(new AnimalRecommendationController(recommendations))
                .setControllerAdvice(new AnimalRecommendationExceptionHandler())
                .build();
        shelterMvc = MockMvcBuilders
                .standaloneSetup(new ShelterReadController(shelters))
                .setControllerAdvice(new ShelterReadExceptionHandler())
                .build();
    }

    // AnimalDiscoveryController

    @Test
    void givenBlankAnimalId__whenGettingAnimalDetail__thenFailAsMissingPathVariableWithoutCallingService() throws Exception {
        MvcResult result = discoveryMvc.perform(get("/api/v1/animals/{id}", BLANK_SEGMENT)).andReturn();

        assertJavaOriginalServerError(result);
        verifyNoInteractions(animals);
    }

    @Test
    void givenNumericAnimalId__whenGettingAnimalDetail__thenPassTheIdToService() throws Exception {
        discoveryMvc.perform(get("/api/v1/animals/{id}", ID));

        verify(animals).findById(ID);
    }

    // AnimalRecommendationController

    @Test
    void givenBlankAnimalId__whenGettingSimilarAnimals__thenFailAsMissingPathVariableWithoutCallingService() throws Exception {
        MvcResult result = recommendationMvc.perform(get("/api/v1/animals/{id}/similar", BLANK_SEGMENT)).andReturn();

        assertJavaOriginalServerError(result);
        verifyNoInteractions(recommendations);
    }

    @Test
    void givenNumericAnimalId__whenGettingSimilarAnimals__thenPassTheIdToService() throws Exception {
        recommendationMvc.perform(get("/api/v1/animals/{id}/similar", ID));

        verify(recommendations).recommend(ID);
    }

    // ShelterReadController

    @Test
    void givenBlankShelterId__whenGettingShelterDetail__thenFailAsMissingPathVariableWithoutCallingService() throws Exception {
        MvcResult result = shelterMvc.perform(get("/api/v1/shelters/{id}", BLANK_SEGMENT)).andReturn();

        assertJavaOriginalServerError(result);
        verifyNoInteractions(shelters);
    }

    @Test
    void givenNumericShelterId__whenGettingShelterDetail__thenPassTheIdToService() throws Exception {
        shelterMvc.perform(get("/api/v1/shelters/{id}", ID));

        verify(shelters).findById(ID);
    }

    // helpers

    /** Java original: 500, the handler's generic error body, and MissingPathVariableException as the resolved exception. */
    private static void assertJavaOriginalServerError(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(SERVER_ERROR_BODY);
        assertThat(result.getResolvedException()).isInstanceOf(MissingPathVariableException.class);
    }
}
