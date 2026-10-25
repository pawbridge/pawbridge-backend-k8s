package com.pawbridge.backend.animal.internal.discovery.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.pawbridge.backend.animal.anyNonNull
import com.pawbridge.backend.animal.captureNonNull
import com.pawbridge.backend.animal.eqNonNull
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.AnimalStatus
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.ApiSource
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Details
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Gender
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.NeuterStatus
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Order
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Page
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.PageRequest
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Search
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Species
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Summary
import com.pawbridge.backend.animal.internal.discovery.application.AnimalNotFoundException
import com.pawbridge.backend.animal.internal.discovery.application.SearchProjectionPendingException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.web.PageableHandlerMethodArgumentResolver
import org.springframework.format.support.DefaultFormattingConversionService
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.LocalDate
import java.time.LocalDateTime

class AnimalDiscoveryControllerTest {
    private val animals: AnimalDiscovery = mock(AnimalDiscovery::class.java)
    private val json: ObjectMapper = JsonMapper.builder()
        .addModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build()
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun prepare() {
        mvc = MockMvcBuilders.standaloneSetup(AnimalDiscoveryController(animals))
            .setControllerAdvice(AnimalDiscoveryExceptionHandler())
            .setCustomArgumentResolvers(PageableHandlerMethodArgumentResolver())
            .setConversionService(DefaultFormattingConversionService())
            .setMessageConverters(MappingJackson2HttpMessageConverter(json))
            .build()
        `when`(animals.search(anyNonNull<Search>(), anyNonNull<PageRequest>()))
            .thenReturn(Page(listOf(summary()), 0, 20, 1, 1, true, true))
    }

    @Test
    fun list_has_exact_seven_page_fields_default_sort_and_non_null_source_summary() {
        val body: JsonNode = json.readTree(
            mvc.perform(get("/api/v1/animals")).andExpect(status().isOk())
                .andReturn().response.contentAsString,
        )

        assertThat(body.size()).isEqualTo(7)
        assertThat(
            body.has("content") && body.has("number") && body.has("size") && body.has("totalElements") &&
                body.has("totalPages") && body.has("first") && body.has("last"),
        ).isTrue()
        assertThat(body.path("content").get(0).path("species").asText()).isEqualTo("DOG")
        assertThat(body.path("content").get(0).path("createdAt").asText()).isEqualTo("2026-09-01T13:14:15.123456")
        assertThat(body.path("content").get(0).has("birthYear")).isFalse()
        assertThat(body.path("content").get(0).has("age")).isFalse()
        assertThat(body.path("content").get(0).has("weight")).isFalse()
        val page: ArgumentCaptor<PageRequest> = ArgumentCaptor.forClass(PageRequest::class.java)
        verify(animals).search(eqNonNull(Search.empty()), captureNonNull(page))
        assertThat(page.value).isEqualTo(PageRequest(0, 20, listOf(Order("createdAt", false)), true))
    }

    @Test
    fun binds_every_filter_enum_iso_date_and_requested_page_sort() {
        mvc.perform(
            get("/api/v1/animals")
                .param("species", "CAT").param("breed", "코리안 숏헤어")
                .param("gender", "FEMALE").param("neuterStatus", "YES").param("status", "ADOPTED")
                .param("minAge", "2").param("maxAge", "8").param("region", "서울").param("city", "강남")
                .param("keyword", "귀가 접힌").param("noticeNo", "서울-1").param("shelterId", "11")
                .param("intakeFrom", "2026-09-01").param("intakeTo", "2026-09-30")
                .param("page", "2").param("size", "30").param("sort", "age,asc"),
        ).andExpect(status().isOk())

        verify(animals).search(
            Search(
                Species.CAT, "코리안 숏헤어", Gender.FEMALE, NeuterStatus.YES,
                AnimalStatus.ADOPTED, 2, 8, "서울", "강남", "귀가 접힌", "서울-1", 11L,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
            ),
            PageRequest(2, 30, listOf(Order("age", true)), true),
        )
    }

    @Test
    fun malformed_enum_and_date_are_binding_400_before_query() {
        val wrongSpecies = mvc.perform(get("/api/v1/animals").param("species", "dog"))
            .andExpect(status().isBadRequest()).andReturn().response
        val wrongDate = mvc.perform(get("/api/v1/animals").param("intakeFrom", "2026-02-30"))
            .andExpect(status().isBadRequest()).andReturn().response

        assertThat(json.readTree(wrongSpecies.contentAsString).path("message").asText()).startsWith("species: ")
        assertThat(json.readTree(wrongDate.contentAsString).path("message").asText()).startsWith("intakeFrom: ")
        verifyNoInteractions(animals)
    }

    @Test
    fun missing_detail_condition_error_and_pending_projection_keep_source_error_shapes() {
        `when`(animals.findById(72L)).thenThrow(AnimalNotFoundException())
        assertError("/api/v1/animals/72", 404, "동물을 찾을 수 없습니다")
        doThrow(IllegalArgumentException("지원하지 않는 정렬 기준입니다: unknown")).`when`(animals)
            .search(anyNonNull<Search>(), anyNonNull<PageRequest>())
        assertError("/api/v1/animals", 400, "지원하지 않는 정렬 기준입니다: unknown")
        doThrow(SearchProjectionPendingException()).`when`(animals)
            .search(anyNonNull<Search>(), anyNonNull<PageRequest>())
        assertError("/api/v1/animals", 503, "검색 정보를 갱신 중입니다. 잠시 후 다시 시도해주세요")
    }

    @Test
    fun detail_exposes_all_28_source_fields_without_envelope_or_shelter_private_data() {
        val date = LocalDate.of(2026, 9, 1)
        val time = date.atTime(13, 14, 15, 123456000)
        `when`(animals.findById(72L)).thenReturn(
            Details(
                72L, "N-72", Species.DOG, "말티즈", Gender.FEMALE,
                NeuterStatus.YES, 2022, 4, "3.5(Kg)", "흰색", "접힌 귀", AnimalStatus.ADOPTED,
                date, date.plusDays(10), "강남", date.minusDays(1), "one", "two", "설명", 0, 8L, "보호소",
                "D-72", "종료(입양)", time, ApiSource.APMS_ANIMAL, time, time,
            ),
        )

        val body: JsonNode = json.readTree(
            mvc.perform(get("/api/v1/animals/72")).andExpect(status().isOk())
                .andReturn().response.contentAsString,
        )

        assertThat(body.size()).isEqualTo(28)
        assertThat(body.path("status").asText()).isEqualTo("ADOPTED")
        assertThat(body.path("weight").asText()).isEqualTo("3.5(Kg)")
        assertThat(body.path("happenDate").asText()).isEqualTo("2026-08-31")
        assertThat(body.path("apmsUpdatedAt").asText()).isEqualTo("2026-09-01T13:14:15.123456")
        assertThat(body.has("data") || body.has("shelterAddress") || body.has("shelterPhone")).isFalse()
    }

    @Test
    fun nested_similarity_endpoint_is_not_mapped_in_this_controller() {
        mvc.perform(get("/api/v1/animals/72/similar")).andExpect(status().isNotFound())

        verifyNoInteractions(animals)
    }

    @Test
    fun apms_number_lookup_binds_exact_value_and_preserves_all_existing_detail_fields() {
        val date = LocalDate.of(2026, 9, 1)
        val time = date.atTime(13, 14, 15, 123456000)
        `when`(animals.findByApmsDesertionNo(" D-72 ")).thenReturn(
            Details(
                72L, "N-72", Species.DOG,
                "말티즈", Gender.FEMALE, NeuterStatus.YES, 2022, 4, "3.5(Kg)", "흰색", "접힌 귀",
                AnimalStatus.ADOPTED, date, date.plusDays(10), "강남", date.minusDays(1), "one", "two", "설명", 0,
                8L, "보호소", " D-72 ", "종료(입양)", time, ApiSource.APMS_ANIMAL, time, time,
            ),
        )

        val body: JsonNode = json.readTree(
            mvc.perform(get("/api/v1/animals/apms/{number}", " D-72 "))
                .andExpect(status().isOk()).andReturn().response.contentAsString,
        )

        val fields = HashSet<String>()
        body.fieldNames().forEachRemaining { name -> fields.add(name) }
        assertThat(fields).containsExactlyInAnyOrder(
            "id", "apmsNoticeNo", "species", "breed", "gender", "neuterStatus",
            "birthYear", "age", "weight", "color", "specialMark", "status", "noticeStartDate", "noticeEndDate",
            "happenPlace", "happenDate", "imageUrl", "imageUrl2", "description", "favoriteCount", "shelterId",
            "shelterName", "apmsDesertionNo", "apmsProcessState", "apmsUpdatedAt", "apiSource", "createdAt", "updatedAt",
        )
        assertThat(body.path("apmsDesertionNo").asText()).isEqualTo(" D-72 ")
        assertThat(body.path("status").asText()).isEqualTo("ADOPTED")
        assertThat(body.path("updatedAt").asText()).isEqualTo("2026-09-01T13:14:15.123456")
        verify(animals).findByApmsDesertionNo(" D-72 ")
        verifyNoMoreInteractions(animals)
    }

    @Test
    fun expiring_soon_binds_default_and_requested_pages_with_original_seven_field_wire() {
        `when`(animals.findExpiringSoonAnimals(anyNonNull<PageRequest>()))
            .thenReturn(Page(listOf(summary()), 0, 20, 1, 1, true, true))

        val body: JsonNode = json.readTree(
            mvc.perform(get("/api/v1/animals/expiring-soon")).andExpect(status().isOk())
                .andReturn().response.contentAsString,
        )

        assertThat(body.size()).isEqualTo(7)
        assertThat(body.path("content").get(0).path("id").asLong()).isEqualTo(72L)
        assertThat(body.path("content").get(0).has("birthYear")).isFalse()
        assertThat(body.path("number").asInt()).isZero()
        assertThat(body.path("size").asInt()).isEqualTo(20)
        verify(animals).findExpiringSoonAnimals(PageRequest(0, 20, listOf(Order("noticeEndDate", true)), true))
        mvc.perform(
            get("/api/v1/animals/expiring-soon").param("page", "2").param("size", "30")
                .param("sort", "unknown,desc"),
        ).andExpect(status().isOk())
        verify(animals).findExpiringSoonAnimals(PageRequest(2, 30, listOf(Order("unknown", false)), true))
        verifyNoMoreInteractions(animals)
    }

    @Test
    fun extra_queries_keep_original_not_found_validation_and_private_database_error_wire() {
        `when`(animals.findByApmsDesertionNo("missing")).thenThrow(AnimalNotFoundException())
        assertError("/api/v1/animals/apms/missing", 404, "동물을 찾을 수 없습니다")
        `when`(animals.findByApmsDesertionNo("failure"))
            .thenThrow(DataAccessResourceFailureException("synthetic private SQL"))
        assertError("/api/v1/animals/apms/failure", 500, "데이터베이스 오류가 발생했습니다.")
        `when`(animals.findExpiringSoonAnimals(anyNonNull<PageRequest>()))
            .thenThrow(IllegalArgumentException("정렬 기준은 하나만 지정할 수 있습니다."))
        assertError(
            "/api/v1/animals/expiring-soon?sort=createdAt,asc&sort=id,desc",
            400,
            "정렬 기준은 하나만 지정할 수 있습니다.",
        )
    }

    private fun assertError(url: String, code: Int, message: String) {
        val response = mvc.perform(get(url)).andExpect(status().`is`(code)).andReturn().response
        val body: JsonNode = json.readTree(response.contentAsString)
        assertThat(body.size()).isEqualTo(3)
        assertThat(body.path("code").asInt()).isEqualTo(code)
        assertThat(body.path("message").asText()).isEqualTo(message)
        assertThat(body.has("data") && body.path("data").isNull()).isTrue()
    }

    private fun summary(): Summary {
        return Summary(
            72L, "N-72", Species.DOG, "말티즈", Gender.FEMALE, null, null, null,
            AnimalStatus.PROTECT, LocalDate.of(2026, 9, 11), "one", 0, 8L, "보호소",
            LocalDateTime.of(2026, 9, 1, 13, 14, 15, 123456000),
        )
    }
}
