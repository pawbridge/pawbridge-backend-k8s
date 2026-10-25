package com.pawbridge.backend.animal.internal.discovery.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
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
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.web.PageableHandlerMethodArgumentResolver
import org.springframework.format.support.DefaultFormattingConversionService
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.LocalDate
import java.time.LocalDateTime

class AnimalDiscoveryControllerTest {
    private val animals = mockk<AnimalDiscovery>()
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
    }

    @Test
    fun `검색 결과 한 건이 있을 때__기본 목록을 조회하면__일곱 개 페이지 필드와 기본 정렬로 조회하고 요약에는 상세 전용 필드를 싣지 않는다`() {
        // given
        every { animals.search(any(), any()) } returns Page(listOf(summary()), 0, 20, 1, 1, true, true)

        // when
        val response = mvc.perform(get("/api/v1/animals")).andReturn().response
        val body: JsonNode = json.readTree(response.contentAsString)

        // then
        assertThat(response.status).isEqualTo(200)
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
        val page = slot<PageRequest>()
        verify(exactly = 1) { animals.search(Search.empty(), capture(page)) }
        assertThat(page.captured).isEqualTo(PageRequest(0, 20, listOf(Order("createdAt", false)), true))
        confirmVerified(animals)
    }

    @Test
    fun `모든 필터와 페이지 정렬 파라미터가 주어졌을 때__목록을 조회하면__열거형과 ISO 날짜를 바인딩해 그대로 검색한다`() {
        // given
        every { animals.search(any(), any()) } returns Page(listOf(summary()), 0, 20, 1, 1, true, true)

        // when
        val response = mvc.perform(
            get("/api/v1/animals")
                .param("species", "CAT").param("breed", "코리안 숏헤어")
                .param("gender", "FEMALE").param("neuterStatus", "YES").param("status", "ADOPTED")
                .param("minAge", "2").param("maxAge", "8").param("region", "서울").param("city", "강남")
                .param("keyword", "귀가 접힌").param("noticeNo", "서울-1").param("shelterId", "11")
                .param("intakeFrom", "2026-09-01").param("intakeTo", "2026-09-30")
                .param("page", "2").param("size", "30").param("sort", "age,asc"),
        ).andReturn().response

        // then
        assertThat(response.status).isEqualTo(200)
        verify(exactly = 1) {
            animals.search(
                Search(
                    Species.CAT, "코리안 숏헤어", Gender.FEMALE, NeuterStatus.YES,
                    AnimalStatus.ADOPTED, 2, 8, "서울", "강남", "귀가 접힌", "서울-1", 11L,
                    LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
                ),
                PageRequest(2, 30, listOf(Order("age", true)), true),
            )
        }
        confirmVerified(animals)
    }

    @Test
    fun `잘못된 열거형과 날짜일 때__목록을 조회하면__질의 전에 바인딩 400을 돌려준다`() {
        // given

        // when
        val wrongSpecies = mvc.perform(get("/api/v1/animals").param("species", "dog")).andReturn().response
        val wrongDate = mvc.perform(get("/api/v1/animals").param("intakeFrom", "2026-02-30")).andReturn().response

        // then
        assertThat(wrongSpecies.status).isEqualTo(400)
        assertThat(wrongDate.status).isEqualTo(400)
        assertThat(json.readTree(wrongSpecies.contentAsString).path("message").asText()).startsWith("species: ")
        assertThat(json.readTree(wrongDate.contentAsString).path("message").asText()).startsWith("intakeFrom: ")
        confirmVerified(animals)
    }

    @Test
    fun `없는 동물과 잘못된 정렬과 대기 중인 투영일 때__조회하면__원본의 오류 형태로 404와 400과 503을 돌려준다`() {
        // given
        every { animals.findById(any()) } throws AnimalNotFoundException()
        every { animals.search(any(), any()) } throws
            IllegalArgumentException("지원하지 않는 정렬 기준입니다: unknown") andThenThrows
            SearchProjectionPendingException()

        // when
        val missing = mvc.perform(get("/api/v1/animals/72")).andReturn().response
        val badSort = mvc.perform(get("/api/v1/animals")).andReturn().response
        val pending = mvc.perform(get("/api/v1/animals")).andReturn().response

        // then
        assertError(missing, 404, "동물을 찾을 수 없습니다")
        assertError(badSort, 400, "지원하지 않는 정렬 기준입니다: unknown")
        assertError(pending, 503, "검색 정보를 갱신 중입니다. 잠시 후 다시 시도해주세요")
        verify(exactly = 1) { animals.findById(72L) }
    }

    @Test
    fun `입양된 동물 상세일 때__상세를 조회하면__원본 28개 필드만 싣고 봉투나 보호소 비공개 정보는 싣지 않는다`() {
        // given
        val date = LocalDate.of(2026, 9, 1)
        val time = date.atTime(13, 14, 15, 123456000)

        every { animals.findById(any()) } returns Details(
            72L, "N-72", Species.DOG, "말티즈", Gender.FEMALE,
            NeuterStatus.YES, 2022, 4, "3.5(Kg)", "흰색", "접힌 귀", AnimalStatus.ADOPTED,
            date, date.plusDays(10), "강남", date.minusDays(1), "one", "two", "설명", 0, 8L, "보호소",
            "D-72", "종료(입양)", time, ApiSource.APMS_ANIMAL, time, time,
        )

        // when
        val response = mvc.perform(get("/api/v1/animals/72")).andReturn().response
        val body: JsonNode = json.readTree(response.contentAsString)

        // then
        assertThat(response.status).isEqualTo(200)
        assertThat(body.size()).isEqualTo(28)
        assertThat(body.path("status").asText()).isEqualTo("ADOPTED")
        assertThat(body.path("weight").asText()).isEqualTo("3.5(Kg)")
        assertThat(body.path("happenDate").asText()).isEqualTo("2026-08-31")
        assertThat(body.path("apmsUpdatedAt").asText()).isEqualTo("2026-09-01T13:14:15.123456")
        assertThat(body.has("data") || body.has("shelterAddress") || body.has("shelterPhone")).isFalse()
        verify(exactly = 1) { animals.findById(72L) }
        confirmVerified(animals)
    }

    @Test
    fun `중첩된 유사 동물 경로일 때__조회하면__이 컨트롤러에 매핑되지 않아 404이다`() {
        // given

        // when
        val response = mvc.perform(get("/api/v1/animals/72/similar")).andReturn().response

        // then
        assertThat(response.status).isEqualTo(404)
        confirmVerified(animals)
    }

    @Test
    fun `APMS 번호가 공백을 포함할 때__APMS 번호로 조회하면__정확한 값을 바인딩하고 기존 상세 필드를 모두 보존한다`() {
        // given
        val date = LocalDate.of(2026, 9, 1)
        val time = date.atTime(13, 14, 15, 123456000)

        every { animals.findByApmsDesertionNo(any()) } returns Details(
            72L, "N-72", Species.DOG,
            "말티즈", Gender.FEMALE, NeuterStatus.YES, 2022, 4, "3.5(Kg)", "흰색", "접힌 귀",
            AnimalStatus.ADOPTED, date, date.plusDays(10), "강남", date.minusDays(1), "one", "two", "설명", 0,
            8L, "보호소", " D-72 ", "종료(입양)", time, ApiSource.APMS_ANIMAL, time, time,
        )

        // when
        val response = mvc.perform(get("/api/v1/animals/apms/{number}", " D-72 ")).andReturn().response
        val body: JsonNode = json.readTree(response.contentAsString)

        // then
        assertThat(response.status).isEqualTo(200)
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
        verify(exactly = 1) { animals.findByApmsDesertionNo(" D-72 ") }
        confirmVerified(animals)
    }

    @Test
    fun `기본 페이지와 요청된 페이지일 때__곧 만료되는 동물을 조회하면__기본 정렬과 요청 정렬을 바인딩하고 원본 일곱 필드로 응답한다`() {
        // given
        every { animals.findExpiringSoonAnimals(any()) } returns Page(listOf(summary()), 0, 20, 1, 1, true, true)

        // when
        val defaultResponse = mvc.perform(get("/api/v1/animals/expiring-soon")).andReturn().response
        val requestedResponse = mvc.perform(
            get("/api/v1/animals/expiring-soon").param("page", "2").param("size", "30")
                .param("sort", "unknown,desc"),
        ).andReturn().response

        // then
        val body: JsonNode = json.readTree(defaultResponse.contentAsString)
        assertThat(defaultResponse.status).isEqualTo(200)
        assertThat(body.size()).isEqualTo(7)
        assertThat(body.path("content").get(0).path("id").asLong()).isEqualTo(72L)
        assertThat(body.path("content").get(0).has("birthYear")).isFalse()
        assertThat(body.path("number").asInt()).isZero()
        assertThat(body.path("size").asInt()).isEqualTo(20)
        assertThat(requestedResponse.status).isEqualTo(200)
        verify(exactly = 1) {
            animals.findExpiringSoonAnimals(PageRequest(0, 20, listOf(Order("noticeEndDate", true)), true))
        }
        verify(exactly = 1) {
            animals.findExpiringSoonAnimals(PageRequest(2, 30, listOf(Order("unknown", false)), true))
        }
        confirmVerified(animals)
    }

    @Test
    fun `없는 APMS 번호와 DB 오류와 잘못된 정렬일 때__추가 조회를 하면__원본의 404와 검증 400과 비공개 DB 오류 500 형태를 유지한다`() {
        // given
        every { animals.findByApmsDesertionNo("missing") } throws AnimalNotFoundException()
        every { animals.findByApmsDesertionNo("failure") } throws DataAccessResourceFailureException("synthetic private SQL")
        every { animals.findExpiringSoonAnimals(any()) } throws IllegalArgumentException("정렬 기준은 하나만 지정할 수 있습니다.")

        // when
        val missing = mvc.perform(get("/api/v1/animals/apms/missing")).andReturn().response
        val failure = mvc.perform(get("/api/v1/animals/apms/failure")).andReturn().response
        val badSort = mvc.perform(get("/api/v1/animals/expiring-soon?sort=createdAt,asc&sort=id,desc")).andReturn().response

        // then
        assertError(missing, 404, "동물을 찾을 수 없습니다")
        assertError(failure, 500, "데이터베이스 오류가 발생했습니다.")
        assertError(badSort, 400, "정렬 기준은 하나만 지정할 수 있습니다.")
    }

    private fun assertError(response: MockHttpServletResponse, code: Int, message: String) {
        val body: JsonNode = json.readTree(response.contentAsString)
        assertThat(response.status).isEqualTo(code)
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
