package com.pawbridge.animalservice.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pawbridge.animalservice.client.PythonAiServiceClient;
import com.pawbridge.animalservice.client.PythonLostSearchClient;
import com.pawbridge.animalservice.entity.Animal;
import com.pawbridge.animalservice.enums.AnimalStatus;
import com.pawbridge.animalservice.enums.Species;
import com.pawbridge.animalservice.exception.RecommendationUnavailableException;
import com.pawbridge.animalservice.mapper.AnimalMapper;
import com.pawbridge.animalservice.repository.AnimalRepository;
import feign.FeignException;
import feign.Request;
import feign.Response;
import java.nio.charset.StandardCharsets;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnimalRecommendationDiagnosticsTest {
    private final AnimalRepository animals = mock(AnimalRepository.class);
    private final AnimalMapper mapper = mock(AnimalMapper.class);
    private final PythonAiServiceClient legacy = mock(PythonAiServiceClient.class);
    private final PythonLostSearchClient python = mock(PythonLostSearchClient.class);
    private final Logger logger = (Logger) LoggerFactory.getLogger(AnimalRecommendationService.class);
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();

    @BeforeEach
    void capture_logs() {
        events.setContext(logger.getLoggerContext());
        events.start();
        logger.addAppender(events);
        when(animals.findById(73L)).thenReturn(Optional.of(Animal.builder().id(73L)
                .species(Species.DOG).status(AnimalStatus.PROTECT).build()));
    }

    @AfterEach
    void detach_logs() {
        logger.detachAppender(events);
        events.stop();
    }

    @Test
    void upstream_failure_records_status_without_headers_body_message_or_traceback() {
        Request request = Request.create(Request.HttpMethod.GET,
                "http://example.invalid/internal?token=private-test-token",
                Map.of("X-Internal-Api-Key", List.of("private-test-key")), null, StandardCharsets.UTF_8);
        FeignException failure = FeignException.errorStatus("recommend", Response.builder()
                .request(request).status(503).reason("Unavailable")
                .body("private-database-details", StandardCharsets.UTF_8).build());
        when(python.recommend("test-internal-key", 73L, "DOG")).thenThrow(failure);
        assertThatThrownBy(() -> service("test-internal-key").recommend(73L))
                .isInstanceOf(RecommendationUnavailableException.class);
        ILoggingEvent event = only_failure();
        assertThat(event.getFormattedMessage()).contains("animalId=73", "reason=UPSTREAM_FAILURE", "upstreamStatus=503")
                .doesNotContain("private", "test-internal-key", "http://");
        assertThat(event.getThrowableProxy()).isNull();
        verify(animals, never()).findWithShelterByIdIn(any());
        verifyNoInteractions(legacy);
    }

    @Test
    void connection_failure_records_cause_type_without_exception_details() {
        when(python.recommend("test-internal-key", 73L, "DOG"))
                .thenThrow(new RuntimeException("private-upstream-message", new SocketTimeoutException("private-connection-detail")));
        assertThatThrownBy(() -> service("test-internal-key").recommend(73L))
                .isInstanceOf(RecommendationUnavailableException.class);
        assertThat(only_failure().getFormattedMessage()).contains("reason=UPSTREAM_FAILURE", "causeType=SocketTimeoutException")
                .doesNotContain("private");
    }

    @Test
    void missing_auth_configuration_is_distinguished_without_calling_python() {
        assertThatThrownBy(() -> service("").recommend(73L)).isInstanceOf(RecommendationUnavailableException.class);
        assertThat(only_failure().getFormattedMessage()).contains("animalId=73", "reason=INTERNAL_AUTH_MISSING");
        verifyNoInteractions(python, legacy);
    }

    @Test
    void malformed_response_is_distinguished_without_logging_payload() {
        when(python.recommend("test-internal-key", 73L, "DOG")).thenReturn(List.of(-1L));
        assertThatThrownBy(() -> service("test-internal-key").recommend(73L)).isInstanceOf(RecommendationUnavailableException.class);
        assertThat(only_failure().getFormattedMessage()).contains("animalId=73", "reason=INVALID_UPSTREAM_RESPONSE");
        verify(animals, never()).findWithShelterByIdIn(any());
    }

    private AnimalRecommendationService service(String key) {
        return new AnimalRecommendationService(animals, mapper, legacy, python, "postgresql", key);
    }

    private ILoggingEvent only_failure() {
        assertThat(events.list).hasSize(1);
        return events.list.get(0);
    }
}
