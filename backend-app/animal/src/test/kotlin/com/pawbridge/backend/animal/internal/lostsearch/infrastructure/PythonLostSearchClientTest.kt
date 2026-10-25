package com.pawbridge.backend.animal.internal.lostsearch.infrastructure

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.Species
import com.pawbridge.backend.animal.internal.command.infrastructure.mapper.AnimalMapper
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository
import feign.Client
import feign.Request
import feign.Response
import feign.form.FormData
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.slf4j.LoggerFactory
import org.springframework.mock.web.MockMultipartFile
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.web.server.ResponseStatusException
import java.io.IOException
import java.nio.charset.StandardCharsets

class PythonLostSearchClientTest {
    private val transport: Client = mock(Client::class.java)
    private val mapper = ObjectMapper()

    private fun client(): PythonLostSearchClient {
        return PythonLostSearchClient("http://synthetic.invalid", mapper, transport)
    }

    private fun search(): PythonLostSearchResponse? {
        return client().search(
            KEY,
            FormData("application/octet-stream", "photo", "image-bytes".toByteArray(StandardCharsets.UTF_8)),
            "DOG",
            "2026-10-20",
            "서울",
            "귀가 접힌 강아지",
            true,
        )
    }

    private fun reply(request: Request, status: Int, body: String): Response {
        return Response.builder()
            .request(request)
            .status(status)
            .headers(mapOf("Content-Type" to listOf("application/json")))
            .body(body, StandardCharsets.UTF_8)
            .build()
    }

    @Test
    fun original_multipart_parts_key_and_timeouts_are_preserved_without_spring_cloud_or_network() {
        `when`(transport.execute(any(), any())).thenAnswer { invocation ->
            val request: Request = invocation.getArgument(0)
            val options: Request.Options = invocation.getArgument(1)
            assertThat(request.httpMethod()).isEqualTo(Request.HttpMethod.POST)
            assertThat(request.url()).isEqualTo("http://synthetic.invalid/internal/animals/lost-candidates")
            assertThat(request.headers()["X-Internal-Api-Key"]).containsExactly(KEY)
            assertThat(request.headers()["Content-Type"]!!.iterator().next())
                .startsWith("multipart/form-data; charset=UTF-8; boundary=")
            val body = String(request.body(), StandardCharsets.UTF_8)
            assertThat(body).contains(
                "name=\"image\"; filename=\"photo\"", "Content-Type: application/octet-stream", "image-bytes",
                "name=\"species\"", "DOG", "name=\"lostDate\"", "2026-10-20", "name=\"region\"", "서울",
                "name=\"description\"", "귀가 접힌 강아지", "name=\"includeAdoptedOrReturned\"", "true",
            ).doesNotContain(KEY)
            assertThat(options.connectTimeoutMillis()).isEqualTo(3000)
            assertThat(options.readTimeoutMillis()).isEqualTo(45000)
            assertThat(options.isFollowRedirects).isFalse()
            reply(
                request,
                200,
                "{\"candidates\":[{\"animalId\":7,\"imageScore\":0.9,\"matchedEvidence\":[\"FOUND_ON_OR_AFTER_LOST_DATE\"]}]}",
            )
        }

        val result = search()!!

        assertThat(result.candidates).hasSize(1)
        assertThat(result.candidates!!.first()!!.animalId).isEqualTo(7L)
        verify(transport, times(1)).execute(any(), any())
    }

    @Test
    fun null_optional_parts_are_omitted_and_false_remains_an_explicit_part() {
        `when`(transport.execute(any(), any())).thenAnswer { invocation ->
            val request: Request = invocation.getArgument(0)
            val body = String(request.body(), StandardCharsets.UTF_8)
            assertThat(body).doesNotContain("name=\"lostDate\"", "name=\"region\"", "name=\"description\"", "null")
            assertThat(body).contains("name=\"includeAdoptedOrReturned\"", "false")
            reply(request, 200, "{\"candidates\":[]}")
        }

        assertThat(
            client().search(
                KEY,
                FormData("application/octet-stream", "photo", byteArrayOf(1)),
                "CAT",
                null,
                null,
                null,
                false,
            )!!.candidates,
        ).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 422, 413, 503])
    fun non_success_status_is_classified_by_service_without_logging_internal_key(status: Int) {
        `when`(transport.execute(any(), any())).thenAnswer { invocation ->
            reply(invocation.getArgument(0), status, KEY)
        }

        assertSanitizedServiceFailure(
            if (status == 413) {
                413
            } else if (status == 400 || status == 422) {
                400
            } else {
                503
            },
        )

        verify(transport, times(1)).execute(any(), any())
    }

    @Test
    fun malformed_json_is_503_and_never_exposes_decoder_body_or_key() {
        `when`(transport.execute(any(), any())).thenAnswer { invocation ->
            reply(invocation.getArgument(0), 200, KEY)
        }

        assertSanitizedServiceFailure(503)

        verify(transport, times(1)).execute(any(), any())
    }

    @Test
    fun io_failure_is_not_retried_and_is_sanitized_as_503() {
        `when`(transport.execute(any(), any())).thenThrow(IOException(KEY))

        assertSanitizedServiceFailure(503)

        verify(transport, times(1)).execute(any(), any())
    }

    private fun assertSanitizedServiceFailure(status: Int) {
        val log = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val events = ListAppender<ILoggingEvent>()
        events.start()
        log.addAppender(events)
        val repository = mock(AnimalRepository::class.java)
        val manager = mock(JpaTransactionManager::class.java)
        try {
            val service = LostSearchService(client(), repository, AnimalMapper(), KEY, manager)
            val request = LostSearchRequest()
            request.species = Species.DOG
            request.image = MockMultipartFile("image", byteArrayOf(1))

            assertThatThrownBy { service.search(request) }
                .isInstanceOfSatisfying(ResponseStatusException::class.java) { error ->
                    assertThat(error.statusCode.value()).isEqualTo(status)
                    assertThat(error.cause).isNull()
                    assertThat(error.toString()).doesNotContain(KEY)
                }

            assertThat(events.list).noneMatch { event -> event.formattedMessage.contains(KEY) }
            verifyNoInteractions(repository, manager)
        } finally {
            log.detachAppender(events)
            events.stop()
        }
    }

    companion object {
        private const val KEY = "synthetic-encoder-key-never-log"
    }
}
