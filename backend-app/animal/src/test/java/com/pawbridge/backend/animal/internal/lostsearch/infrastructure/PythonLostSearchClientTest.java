package com.pawbridge.backend.animal.internal.lostsearch.infrastructure;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.internal.command.infrastructure.mapper.AnimalMapper;
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository;
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.Species;
import feign.Client;
import feign.Request;
import feign.Response;
import feign.form.FormData;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PythonLostSearchClientTest {
    static final String KEY="synthetic-encoder-key-never-log";
    final Client transport=mock(Client.class);
    final ObjectMapper mapper=new ObjectMapper();
    PythonLostSearchClient client() { return new PythonLostSearchClient("http://synthetic.invalid",mapper,transport); }
    PythonLostSearchResponse search() {
        return client().search(KEY,new FormData("application/octet-stream","photo","image-bytes".getBytes(StandardCharsets.UTF_8)),
                "DOG","2026-10-20","서울","귀가 접힌 강아지",true);
    }
    Response reply(Request request,int status,String body) {
        return Response.builder().request(request).status(status).headers(Map.of("Content-Type",List.of("application/json")))
                .body(body,StandardCharsets.UTF_8).build();
    }

    @Test void original_multipart_parts_key_and_timeouts_are_preserved_without_spring_cloud_or_network() throws Exception {
        when(transport.execute(any(),any())).thenAnswer(invocation->{
            Request request=invocation.getArgument(0);Request.Options options=invocation.getArgument(1);
            assertThat(request.httpMethod()).isEqualTo(Request.HttpMethod.POST);
            assertThat(request.url()).isEqualTo("http://synthetic.invalid/internal/animals/lost-candidates");
            assertThat(request.headers().get("X-Internal-Api-Key")).containsExactly(KEY);
            assertThat(request.headers().get("Content-Type").iterator().next()).startsWith("multipart/form-data; charset=UTF-8; boundary=");
            String body=new String(request.body(),StandardCharsets.UTF_8);
            assertThat(body).contains("name=\"image\"; filename=\"photo\"","Content-Type: application/octet-stream","image-bytes",
                    "name=\"species\"","DOG","name=\"lostDate\"","2026-10-20","name=\"region\"","서울",
                    "name=\"description\"","귀가 접힌 강아지","name=\"includeAdoptedOrReturned\"","true").doesNotContain(KEY);
            assertThat(options.connectTimeoutMillis()).isEqualTo(3000);assertThat(options.readTimeoutMillis()).isEqualTo(45000);
            assertThat(options.isFollowRedirects()).isFalse();
            return reply(request,200,"{\"candidates\":[{\"animalId\":7,\"imageScore\":0.9,\"matchedEvidence\":[\"FOUND_ON_OR_AFTER_LOST_DATE\"]}]}");
        });
        var result=search();assertThat(result.candidates()).hasSize(1);assertThat(result.candidates().getFirst().animalId()).isEqualTo(7);
        verify(transport,times(1)).execute(any(),any());
    }

    @Test void null_optional_parts_are_omitted_and_false_remains_an_explicit_part() throws Exception {
        when(transport.execute(any(),any())).thenAnswer(invocation->{
            Request request=invocation.getArgument(0);String body=new String(request.body(),StandardCharsets.UTF_8);
            assertThat(body).doesNotContain("name=\"lostDate\"","name=\"region\"","name=\"description\"","null");
            assertThat(body).contains("name=\"includeAdoptedOrReturned\"","false");return reply(request,200,"{\"candidates\":[]}");
        });
        assertThat(client().search(KEY,new FormData("application/octet-stream","photo",new byte[]{1}),"CAT",null,null,null,false).candidates()).isEmpty();
    }

    @ParameterizedTest @ValueSource(ints={400,422,413,503})
    void non_success_status_is_classified_by_service_without_logging_internal_key(int status) throws Exception {
        when(transport.execute(any(),any())).thenAnswer(invocation->reply(invocation.getArgument(0),status,KEY));
        assertSanitizedServiceFailure(status==413?413:status==400||status==422?400:503);
        verify(transport,times(1)).execute(any(),any());
    }

    @Test void malformed_json_is_503_and_never_exposes_decoder_body_or_key() throws Exception {
        when(transport.execute(any(),any())).thenAnswer(invocation->reply(invocation.getArgument(0),200,KEY));
        assertSanitizedServiceFailure(503);verify(transport,times(1)).execute(any(),any());
    }

    @Test void io_failure_is_not_retried_and_is_sanitized_as_503() throws Exception {
        when(transport.execute(any(),any())).thenThrow(new IOException(KEY));
        assertSanitizedServiceFailure(503);verify(transport,times(1)).execute(any(),any());
    }

    void assertSanitizedServiceFailure(int status) {
        var log=(Logger)LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var events=new ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();events.start();log.addAppender(events);
        var repository=mock(AnimalRepository.class);var manager=mock(JpaTransactionManager.class);
        try {
            var service=new LostSearchService(client(),repository,new AnimalMapper(),KEY,manager);
            var request=new LostSearchRequest();request.setSpecies(Species.DOG);
            request.setImage(new MockMultipartFile("image",new byte[]{1}));
            assertThatThrownBy(()->service.search(request)).isInstanceOfSatisfying(ResponseStatusException.class,error->{
                assertThat(error.getStatusCode().value()).isEqualTo(status);assertThat(error.getCause()).isNull();
                assertThat(error.toString()).doesNotContain(KEY);
            });
            assertThat(events.list).noneMatch(event->event.getFormattedMessage().contains(KEY));
            verifyNoInteractions(repository,manager);
        } finally { log.detachAppender(events);events.stop(); }
    }
}
