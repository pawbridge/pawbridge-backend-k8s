package com.pawbridge.backend.animal.internal.lostsearch.infrastructure

import com.pawbridge.backend.animal.anyNonNull
import com.pawbridge.backend.animal.eqNonNull
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Animal
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Shelter
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.AnimalStatus
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.Species
import com.pawbridge.backend.animal.internal.command.infrastructure.mapper.AnimalMapper
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository
import feign.FeignException
import feign.Request
import feign.Response
import feign.form.FormData
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.ArgumentMatchers.nullable
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.mock.web.MockMultipartFile
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.stream.Stream

class LostSearchServiceTest {
    private val client: PythonLostSearchClient = mock(PythonLostSearchClient::class.java)
    private val animals: AnimalRepository = mock(AnimalRepository::class.java)
    private val reader: JpaTransactionManager = mock(JpaTransactionManager::class.java)
    private val transaction: TransactionStatus = mock(TransactionStatus::class.java)
    private val mapper = AnimalMapper()
    private lateinit var service: LostSearchService

    @BeforeEach
    fun setUp() {
        `when`(reader.getTransaction(any())).thenAnswer { invocation ->
            assertThat((invocation.getArgument<Any>(0) as TransactionDefinition).isReadOnly).isTrue()
            transaction
        }
        `when`(
            client.search(
                anyString(), anyNonNull<FormData>(), anyString(),
                nullable(String::class.java), nullable(String::class.java), nullable(String::class.java), anyBoolean(),
            ),
        ).thenReturn(PythonLostSearchResponse(listOf()))
        service = LostSearchService(client, animals, mapper, KEY, reader)
    }

    private fun request(): LostSearchRequest {
        val request = LostSearchRequest()
        request.species = Species.DOG
        request.image = MockMultipartFile(
            "image",
            "untrusted-name.png",
            "image/png",
            "image-bytes".toByteArray(StandardCharsets.UTF_8),
        )
        return request
    }

    private fun candidate(id: Long): PythonLostSearchResponse.Candidate {
        return PythonLostSearchResponse.Candidate(id, 0.85, listOf(EVIDENCE))
    }

    private fun animal(id: Long, species: Species, status: AnimalStatus): Animal {
        val shelter = Shelter.builder().id(70L).name("보호소").careRegNo("care-70").phone("02-0000-0000").build()
        return Animal.builder().id(id).species(species).status(status).shelter(shelter).description("현재 설명").build()
    }

    private fun respond(response: PythonLostSearchResponse?) {
        doReturn(response).`when`(client).search(
            anyString(), anyNonNull<FormData>(), anyString(),
            nullable(String::class.java), nullable(String::class.java), nullable(String::class.java), anyBoolean(),
        )
    }

    private fun expectStatus(status: Int, call: () -> Unit) {
        assertThatThrownBy { call() }.isInstanceOfSatisfying(ResponseStatusException::class.java) { error ->
            assertThat(error.statusCode.value()).isEqualTo(status)
            assertThat(error.cause).isNull()
            assertThat(error.toString()).doesNotContain(KEY)
        }
    }

    @Test
    fun remote_call_precedes_reader_transaction_and_preserves_rank_dedup_first_evidence_and_current_data() {
        val request = request()
        request.region = "  서울  "
        request.description = "  귀가 접힘  "
        request.lostDate = LocalDate.of(2026, 10, 20)
        `when`(
            client.search(
                anyString(), anyNonNull<FormData>(), anyString(),
                nullable(String::class.java), nullable(String::class.java), nullable(String::class.java), anyBoolean(),
            ),
        ).thenAnswer { invocation ->
            verifyNoInteractions(reader, animals)
            assertThat(invocation.getArgument<String>(0)).isEqualTo(KEY)
            val image: FormData = invocation.getArgument(1)
            assertThat(image.fileName).isEqualTo("photo")
            assertThat(image.contentType).isEqualTo("application/octet-stream")
            assertThat(image.data).isEqualTo("image-bytes".toByteArray(StandardCharsets.UTF_8))
            assertThat(invocation.getArgument<String>(3)).isEqualTo("2026-10-20")
            assertThat(invocation.getArgument<String>(4)).isEqualTo("서울")
            assertThat(invocation.getArgument<String>(5)).isEqualTo("귀가 접힘")
            PythonLostSearchResponse(
                listOf(
                    candidate(2),
                    candidate(1),
                    PythonLostSearchResponse.Candidate(2L, .9, listOf("DISCOVERY_PLACE_TEXT_MATCH")),
                    candidate(3),
                    candidate(4),
                    candidate(5),
                ),
            )
        }
        `when`(animals.findWithShelterByIdIn(listOf(2L, 1L, 3L, 4L, 5L))).thenReturn(
            listOf(
                animal(1, Species.DOG, AnimalStatus.PROTECT),
                animal(2, Species.DOG, AnimalStatus.NOTICE),
                animal(3, Species.CAT, AnimalStatus.NOTICE),
                animal(4, Species.DOG, AnimalStatus.ADOPTED),
            ),
        )

        val result = service.search(request)

        assertThat(result.candidates.map { c -> c.animal.id }).containsExactly(2L, 1L)
        assertThat(result.candidates.first().animal.description).isEqualTo("현재 설명")
        assertThat(result.candidates.first().shelterPhone).isEqualTo("02-0000-0000")
        assertThat(result.candidates.first().matchedEvidence).containsExactly(EVIDENCE)
        verify(reader).commit(transaction)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun current_status_filter_preserves_active_and_optional_resolved_sets(includeResolved: Boolean) {
        val request = request()
        request.includeAdoptedOrReturned = includeResolved
        val statuses = AnimalStatus.values().toList()
        respond(PythonLostSearchResponse((0 until statuses.size).map { i -> candidate((i + 1).toLong()) }))
        `when`(animals.findWithShelterByIdIn(anyList())).thenReturn(
            (0 until statuses.size).map { i -> animal((i + 1).toLong(), Species.DOG, statuses[i]) },
        )

        assertThat(service.search(request).candidates.map { c -> c.animal.status }).containsExactlyElementsOf(
            if (includeResolved) {
                listOf(AnimalStatus.NOTICE, AnimalStatus.PROTECT, AnimalStatus.ADOPTED, AnimalStatus.RETURNED)
            } else {
                listOf(AnimalStatus.NOTICE, AnimalStatus.PROTECT)
            },
        )
    }

    @Test
    fun empty_candidates_do_not_open_reader_or_query_database() {
        assertThat(service.search(request()).candidates).isEmpty()
        verifyNoInteractions(reader, animals)
    }

    @Test
    fun at_most_twenty_candidates_are_accepted_before_dedup_and_current_lookup() {
        respond(PythonLostSearchResponse(Collections.nCopies(20, candidate(1))))
        `when`(animals.findWithShelterByIdIn(listOf(1L)))
            .thenReturn(listOf(animal(1, Species.DOG, AnimalStatus.PROTECT)))

        assertThat(service.search(request()).candidates).hasSize(1)
    }

    @ParameterizedTest
    @MethodSource("invalidResponses")
    fun invalid_python_reply_is_503_and_releases_permit_without_database_access(response: PythonLostSearchResponse?) {
        respond(response)
        expectStatus(503) { service.search(request()) }
        respond(PythonLostSearchResponse(listOf()))
        assertThat(service.search(request()).candidates).isEmpty()
        verifyNoInteractions(reader, animals)
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 422, 413, 429, 503])
    fun remote_error_classification_discards_sensitive_exception_and_releases_permit(remoteStatus: Int) {
        val request = Request.create(
            Request.HttpMethod.POST,
            "http://synthetic.invalid/internal/animals/lost-candidates",
            mapOf("X-Internal-Api-Key" to listOf(KEY)),
            ByteArray(0),
            StandardCharsets.UTF_8,
            null,
        )
        val error = FeignException.errorStatus(
            "search",
            Response.builder().request(request).status(remoteStatus).reason(KEY).body(KEY, StandardCharsets.UTF_8).build(),
        )
        `when`(
            client.search(
                anyString(), anyNonNull<FormData>(), anyString(),
                nullable(String::class.java), nullable(String::class.java), nullable(String::class.java), anyBoolean(),
            ),
        ).thenThrow(error)

        expectStatus(
            if (remoteStatus == 413) {
                413
            } else if (remoteStatus == 400 || remoteStatus == 422) {
                400
            } else {
                503
            },
        ) { service.search(request()) }
        respond(PythonLostSearchResponse(listOf()))
        assertThat(service.search(request()).candidates).isEmpty()
        verifyNoInteractions(reader, animals)
    }

    @ParameterizedTest
    @ValueSource(strings = ["wrong-species", "missing-image", "empty-image", "oversized-image"])
    fun invalid_upload_is_rejected_before_getbytes_client_or_database(kind: String) {
        val request = request()
        var expected = 400
        when (kind) {
            "wrong-species" -> request.species = Species.ETC
            "missing-image" -> request.image = null
            "empty-image" -> request.image = MockMultipartFile("image", ByteArray(0))
            "oversized-image" -> {
                val image = mock(MultipartFile::class.java)
                `when`(image.isEmpty).thenReturn(false)
                `when`(image.size).thenReturn(LostSearchService.MAX_IMAGE_BYTES + 1)
                request.image = image
                expected = 413
            }
        }

        expectStatus(expected) { service.search(request) }

        verifyNoInteractions(client, reader, animals)
        if (kind == "oversized-image") {
            verify(request.image!!, never()).getBytes()
        }
        assertThat(service.search(request()).candidates).isEmpty()
    }

    @Test
    fun exact_five_mib_file_is_accepted_and_null_optional_parts_remain_absent() {
        val request = request()
        request.image = MockMultipartFile("image", ByteArray(LostSearchService.MAX_IMAGE_BYTES.toInt()))

        assertThat(service.search(request).candidates).isEmpty()

        verify(client).search(
            eqNonNull(KEY), anyNonNull<FormData>(), eqNonNull("DOG"),
            isNull<String>(), isNull<String>(), isNull<String>(), eq(false),
        )
    }

    @Test
    fun blank_internal_key_fails_before_file_bytes_or_external_call() {
        val disabled = LostSearchService(client, animals, mapper, " ", reader)

        expectStatus(503) { disabled.search(request()) }

        verifyNoInteractions(client, reader, animals)
    }

    @Test
    fun image_read_failure_is_400_and_releases_permit() {
        val image = mock(MultipartFile::class.java)
        `when`(image.size).thenReturn(1L)
        `when`(image.bytes).thenThrow(IOException(KEY))
        val request = request()
        request.image = image

        expectStatus(400) { service.search(request) }

        assertThat(service.search(request()).candidates).isEmpty()
        verifyNoInteractions(reader, animals)
    }

    @Test
    fun database_failure_rolls_back_reader_and_releases_permit() {
        respond(PythonLostSearchResponse(listOf(candidate(1))))
        val failure = DataAccessResourceFailureException("synthetic database failure")
        `when`(animals.findWithShelterByIdIn(anyList())).thenThrow(failure)

        assertThatThrownBy { service.search(request()) }.isSameAs(failure)

        verify(reader).rollback(transaction)
        respond(PythonLostSearchResponse(listOf()))
        assertThat(service.search(request()).candidates).isEmpty()
    }

    @Test
    fun concurrent_call_is_busy_without_second_python_request_and_permit_returns_after_completion() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        `when`(
            client.search(
                anyString(), anyNonNull<FormData>(), anyString(),
                nullable(String::class.java), nullable(String::class.java), nullable(String::class.java), anyBoolean(),
            ),
        ).thenAnswer {
            entered.countDown()
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue()
            PythonLostSearchResponse(listOf())
        }
        Executors.newSingleThreadExecutor().use { executor ->
            val running = executor.submit(Callable { service.search(request()) })
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
                expectStatus(503) { service.search(request()) }
            } finally {
                release.countDown()
            }
            assertThat(running.get(5, TimeUnit.SECONDS).candidates).isEmpty()
            assertThat(service.search(request()).candidates).isEmpty()
        }
        verify(client, times(2)).search(
            anyString(), anyNonNull<FormData>(), anyString(),
            nullable(String::class.java), nullable(String::class.java), nullable(String::class.java), anyBoolean(),
        )
        verifyNoInteractions(reader, animals)
    }

    companion object {
        private const val KEY = "synthetic-lost-search-private-key"
        private const val EVIDENCE = "REGISTERED_DESCRIPTION_TEXT_MATCH"

        @JvmStatic
        fun invalidResponses(): Stream<PythonLostSearchResponse?> = Stream.of(
            null,
            PythonLostSearchResponse(null),
            PythonLostSearchResponse(Collections.nCopies(21, PythonLostSearchResponse.Candidate(1L, .5, listOf()))),
            PythonLostSearchResponse(listOf<PythonLostSearchResponse.Candidate?>(null)),
            PythonLostSearchResponse(listOf(PythonLostSearchResponse.Candidate(null, .5, listOf()))),
            PythonLostSearchResponse(listOf(PythonLostSearchResponse.Candidate(0L, .5, listOf()))),
            PythonLostSearchResponse(listOf(PythonLostSearchResponse.Candidate(1L, null, listOf()))),
            PythonLostSearchResponse(listOf(PythonLostSearchResponse.Candidate(1L, Double.NaN, listOf()))),
            PythonLostSearchResponse(listOf(PythonLostSearchResponse.Candidate(1L, Double.POSITIVE_INFINITY, listOf()))),
            PythonLostSearchResponse(listOf(PythonLostSearchResponse.Candidate(1L, .5, null))),
            PythonLostSearchResponse(listOf(PythonLostSearchResponse.Candidate(1L, .5, listOf<String?>(null)))),
            PythonLostSearchResponse(listOf(PythonLostSearchResponse.Candidate(1L, .5, listOf("UNRECOGNIZED_EVIDENCE")))),
        )
    }
}
