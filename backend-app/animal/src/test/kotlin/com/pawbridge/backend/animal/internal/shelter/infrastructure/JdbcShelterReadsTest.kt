package com.pawbridge.backend.animal.internal.shelter.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.Details
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.Order
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.PageRequest
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.PublicInformation
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.Search
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.Summary
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations
import org.springframework.transaction.support.TransactionOperations
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.stream.Stream

class JdbcShelterReadsTest {
    private val jdbc: NamedParameterJdbcOperations = mockk<NamedParameterJdbcOperations>()
    private val discoveryJdbc: NamedParameterJdbcOperations = mockk<NamedParameterJdbcOperations>()
    private val mapper: ObjectMapper = ObjectMapper().registerModule(JavaTimeModule())
    private val shelters = JdbcShelterReads(
        jdbc,
        discoveryJdbc,
        TransactionOperations.withoutTransaction(),
        TransactionOperations.withoutTransaction(),
        mapper,
    )

    @ParameterizedTest
    @MethodSource("filters")
    fun `검색 조건이 서로 경쟁할 때__목록을 조회하면__원래의 우선순위와 와일드카드 의미를 유지한다`(
        request: Search,
        predicate: String,
        parameter: String?,
        value: String?,
    ) {
        // given
        every { jdbc.query(any<String>(), any<MapSqlParameterSource>(), any<RowMapper<Summary>>()) } returns listOf()

        // when
        val result = shelters.search(request, FIRST)

        // then
        val sql = slot<String>()
        val parameters = slot<MapSqlParameterSource>()
        assertThat(result.content()).isEmpty()
        verify(exactly = 1) { jdbc.query(capture(sql), capture(parameters), any<RowMapper<Summary>>()) }
        assertThat(sql.captured).contains(
            "FROM pawbridge_animal.shelters s",
            predicate,
            " ORDER BY s.name ASC LIMIT :limit OFFSET :offset",
        )
        if (parameter != null) {
            assertThat(parameters.captured.getValue(parameter)).isEqualTo(value)
        }
        if (request.keyword != null && !request.keyword!!.isBlank() && request.address != null && !request.address!!.isBlank()) {
            assertThat(parameters.captured.getValue("address")).isEqualTo("%서울_%")
        }
        verify(exactly = 0) {
            jdbc.queryForObject(any<String>(), any<MapSqlParameterSource>(), Long::class.javaObjectType)
        }
        confirmVerified(discoveryJdbc)
    }

    @Test
    fun `일부 페이지와 가득 찬 페이지가 있을 때__목록을 조회하면__원래의 PageableExecutionUtils 건수 결정을 유지한다`() {
        // given
        val row = Summary(4L, "r", "센터", null, null, null, null)

        every { jdbc.query(any<String>(), any<MapSqlParameterSource>(), any<RowMapper<Summary>>()) } returns listOf(row)

        // when
        val partial = shelters.search(Search(null, null, null, null), PageRequest(2, 20, listOf()))

        // then
        assertThat(partial.totalElements()).isEqualTo(41)
        verify(exactly = 0) {
            jdbc.queryForObject(any<String>(), any<MapSqlParameterSource>(), Long::class.javaObjectType)
        }

        // given
        every { jdbc.queryForObject(any<String>(), any<MapSqlParameterSource>(), Long::class.javaObjectType) } returns 99L

        // when
        val full = shelters.search(Search(null, null, null, null), PageRequest(0, 1, listOf()))

        // then
        assertThat(full.totalElements()).isEqualTo(99)

        // given
        every { jdbc.query(any<String>(), any<MapSqlParameterSource>(), any<RowMapper<Summary>>()) } returns listOf()

        // when
        val beyond = shelters.search(Search(null, null, null, null), PageRequest(7, 20, listOf()))

        // then
        assertThat(beyond.totalElements()).isEqualTo(99)
        verify(exactly = 2) {
            jdbc.queryForObject(
                match<String> { it.contains("count(*)") },
                any<MapSqlParameterSource>(),
                Long::class.javaObjectType,
            )
        }
    }

    @Test
    fun `매핑된 스칼라 정렬일 때__목록을 조회하면__13개 필드의 양방향과 대소문자 플래그를 새 동순위 기준 없이 유지한다`() {
        // given
        val columns = arrayOf(
            arrayOf("id", "id"), arrayOf("careRegNo", "care_reg_no"), arrayOf("name", "name"), arrayOf("phone", "phone"),
            arrayOf("address", "address"), arrayOf("ownerName", "owner_name"),
            arrayOf("organizationName", "organization_name"), arrayOf("email", "email"),
            arrayOf("introduction", "introduction"), arrayOf("adoptionProcedure", "adoption_procedure"),
            arrayOf("operatingHours", "operating_hours"),
            arrayOf("createdAt", "created_at"), arrayOf("updatedAt", "updated_at"),
        )

        every { jdbc.query(any<String>(), any<MapSqlParameterSource>(), any<RowMapper<Summary>>()) } returns listOf()

        for (column in columns) {
            for (ascending in listOf(true, false)) {
                // when
                shelters.search(
                    Search(null, null, null, null),
                    PageRequest(0, 20, listOf(Order(column[0], ascending, false))),
                )

                // then
                val suffix = " ORDER BY s." + column[1] + (if (ascending) " ASC" else " DESC") + " LIMIT :limit OFFSET :offset"
                verify(exactly = 1) {
                    jdbc.query(match<String> { it.endsWith(suffix) }, any<MapSqlParameterSource>(), any<RowMapper<Summary>>())
                }
            }
        }

        // when
        shelters.search(
            Search(null, null, null, null),
            PageRequest(0, 20, listOf(Order("name", true, true), Order("createdAt", false, false))),
        )
        val thrown = assertThatThrownBy {
            shelters.search(
                Search(null, null, null, null),
                PageRequest(0, 20, listOf(Order("name;drop table shelters", true, false))),
            )
        }

        // then
        verify(exactly = 1) {
            jdbc.query(
                match<String> { it.endsWith(" ORDER BY lower(s.name) ASC,s.created_at DESC LIMIT :limit OFFSET :offset") },
                any<MapSqlParameterSource>(),
                any<RowMapper<Summary>>(),
            )
        }
        thrown.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `컬렉션 정렬일 때__목록을 조회하면__엔티티 중복 제거와 부분 페이지 추정 전에 SQL LIMIT를 적용한다`() {
        // given
        val first = Summary(4L, "reg-4", "센터4", null, null, null, null)
        val second = Summary(9L, "reg-9", "센터9", null, null, null, null)
        val page = PageRequest(0, 2, listOf(Order("animals.id", true, false), Order("animals.breed", false, true)))

        every { jdbc.query(any<String>(), any<MapSqlParameterSource>(), any<RowMapper<Summary>>()) } returns listOf(first, first)

        // when
        val result = shelters.search(Search(null, null, null, null), page)

        // then
        assertThat(result.content()).containsExactly(first)
        assertThat(result.totalElements()).isOne()
        verify(exactly = 1) {
            jdbc.query(
                match<String> { sql ->
                    sql.contains("LEFT JOIN pawbridge_animal.animals sort_0 ON sort_0.shelter_id=s.id") &&
                        sql.contains(" ORDER BY sort_0.id ASC,lower(sort_0.breed) DESC LIMIT :limit OFFSET :offset") &&
                        !sql.contains("DISTINCT") && !sql.contains("sort_1")
                },
                any<MapSqlParameterSource>(),
                any<RowMapper<Summary>>(),
            )
        }
        verify(exactly = 0) {
            jdbc.queryForObject(any<String>(), any<MapSqlParameterSource>(), Long::class.javaObjectType)
        }

        // given
        every { jdbc.query(any<String>(), any<MapSqlParameterSource>(), any<RowMapper<Summary>>()) } returns listOf(first, second)
        every { jdbc.queryForObject(any<String>(), any<MapSqlParameterSource>(), Long::class.javaObjectType) } returns 99L

        // when
        val full = shelters.search(Search(null, null, null, null), page)

        // then
        assertThat(full.totalElements()).isEqualTo(99)
        verify(exactly = 1) {
            jdbc.queryForObject(
                match<String> { sql ->
                    sql.contains("count(*)") && !sql.contains("JOIN") && !sql.contains("ORDER BY")
                },
                any<MapSqlParameterSource>(),
                Long::class.javaObjectType,
            )
        }
    }

    @ParameterizedTest
    @MethodSource("associationSortPaths")
    fun `중첩되거나 카멜 표기의 연관 정렬일 때__목록을 조회하면__매핑된 연관 경로와 문자열 대소문자 처리를 유지한다`(
        path: String,
    ) {
        // given
        every { jdbc.query(any<String>(), any<MapSqlParameterSource>(), any<RowMapper<Summary>>()) } returns listOf()

        // when
        shelters.search(
            Search(null, null, null, null),
            PageRequest(0, 20, listOf(Order(path, true, true), Order("animals.species", false, true))),
        )

        // then
        verify(exactly = 1) {
            jdbc.query(
                match<String> { sql ->
                    sql.contains("LEFT JOIN pawbridge_animal.animals sort_0 ON sort_0.shelter_id=s.id") &&
                        sql.contains("LEFT JOIN pawbridge_animal.shelters sort_1 ON sort_1.id=sort_0.shelter_id") &&
                        sql.contains(" ORDER BY lower(sort_1.name) ASC,sort_0.species DESC LIMIT :limit OFFSET :offset")
                },
                any<MapSqlParameterSource>(),
                any<RowMapper<Summary>>(),
            )
        }
    }

    @ParameterizedTest
    @MethodSource("terminalSortPaths")
    fun `끝이 연관이거나 카멜 ID인 정렬일 때__목록을 조회하면__원래의 단일 기본 키 정렬을 쓴다`(
        path: String,
        expression: String,
    ) {
        // given
        every { jdbc.query(any<String>(), any<MapSqlParameterSource>(), any<RowMapper<Summary>>()) } returns listOf()

        // when
        shelters.search(Search(null, null, null, null), PageRequest(0, 20, listOf(Order(path, true, false))))

        // then
        verify(exactly = 1) {
            jdbc.query(
                match<String> { sql ->
                    sql.contains("LEFT JOIN pawbridge_animal.animals sort_0") &&
                        sql.contains(" ORDER BY " + expression + " ASC LIMIT :limit OFFSET :offset")
                },
                any<MapSqlParameterSource>(),
                any<RowMapper<Summary>>(),
            )
        }
    }

    @Test
    fun `선언된 쿼리 별칭으로 정렬하고 키워드로 검색할 때__목록을 조회하면__조인을 더하지 않고 원래의 루트 별칭을 쓴다`() {
        // given
        every { jdbc.query(any<String>(), any<MapSqlParameterSource>(), any<RowMapper<Summary>>()) } returns listOf()

        // when
        shelters.search(Search(null, null, null, "센터"), PageRequest(0, 20, listOf(Order("s.name", false, false))))

        // then
        verify(exactly = 1) {
            jdbc.query(
                match<String> { sql ->
                    sql.contains("WHERE s.name LIKE :keyword ESCAPE ''") &&
                        sql.contains(" ORDER BY s.name DESC LIMIT :limit OFFSET :offset") && !sql.contains("JOIN")
                },
                any<MapSqlParameterSource>(),
                any<RowMapper<Summary>>(),
            )
        }
    }

    @Test
    fun `페이지 오프셋이 JPA 정수 범위를 벗어날 때__목록을 조회하면__SQL 전에 원래의 입력 실패를 유지한다`() {
        // given
        // when
        val thrown = assertThatThrownBy {
            shelters.search(Search(null, null, null, null), PageRequest(Int.MAX_VALUE, 20, listOf()))
        }

        // then
        thrown.isInstanceOf(InvalidDataAccessApiUsageException::class.java)
            .hasMessage("Page offset exceeds Integer.MAX_VALUE (2147483647)")
        confirmVerified(jdbc)
    }

    @Test
    fun `상세와 저장된 공개 정보가 있을 때__ID나 등록번호로 찾으면__한 읽기 범위에서 둘을 읽고 모든 필드를 유지한다`() {
        // given
        val created = LocalDateTime.of(2026, 9, 29, 13, 4, 5, 123456000)
        val updated = created.plusDays(1)
        val row = mockk<ResultSet>()
        val publicRow = mockk<ResultSet>()
        val expectedPublic = PublicInformation(
            "APMS_SHELTER_INFO", "02-public", 37.5, 127.1,
            "09:00", "18:00", "10:00", "16:00", "월요일", "2026-09-29", updated,
        )
        val expected = Details(
            4L, "reg-4", "보호 센터", "02-1234", "서울", "대표", "시청", "sample@example.test",
            "소개", "절차", "09:00", created, updated, expectedPublic,
        )

        every { row.getObject("id", Long::class.javaObjectType) } returns 4L
        every { row.getString("care_reg_no") } returns "reg-4"
        every { row.getString("name") } returns "보호 센터"
        every { row.getString("phone") } returns "02-1234"
        every { row.getString("address") } returns "서울"
        every { row.getString("organization_name") } returns "시청"
        every { row.getString("owner_name") } returns "대표"
        every { row.getString("email") } returns "sample@example.test"
        every { row.getString("introduction") } returns "소개"
        every { row.getString("adoption_procedure") } returns "절차"
        every { row.getString("operating_hours") } returns "09:00"
        every { row.getObject("created_at", LocalDateTime::class.java) } returns created
        every { row.getObject("updated_at", LocalDateTime::class.java) } returns updated
        every { publicRow.getString("details") } returns
            """
            {"source":"ignored","phone":"02-public","latitude":37.5,"longitude":127.1,
             "weekdayOpen":"09:00","weekdayClose":"18:00","weekendOpen":"10:00","weekendClose":"16:00",
             "closedDays":"월요일","sourceUpdatedDate":"2026-09-29","collectedAt":"2000-01-01T00:00"}
            """.trimIndent()
        every { publicRow.getTimestamp("collected_at") } returns Timestamp.valueOf(updated)
        every {
            jdbc.query(
                match<String> { !it.contains("shelter_public_information") },
                any<MapSqlParameterSource>(),
                any<RowMapper<Details>>(),
            )
        } answers {
            listOf(arg<RowMapper<Details>>(2).mapRow(row, 0)!!)
        }
        every {
            jdbc.query(
                match<String> { it.contains("shelter_public_information") },
                any<MapSqlParameterSource>(),
                any<RowMapper<PublicInformation>>(),
            )
        } answers {
            listOf(arg<RowMapper<PublicInformation>>(2).mapRow(publicRow, 0)!!)
        }

        // when
        val byId = shelters.findById(4L)
        val byRegistration = shelters.findByCareRegNo("reg-4")

        // then
        assertThat(byId).isEqualTo(expected)
        assertThat(byRegistration).isEqualTo(expected)
        verify(exactly = 1) {
            jdbc.query(
                match<String> { it.contains("WHERE s.id=:value") },
                match<MapSqlParameterSource> { p -> p.getValue("value") == 4L },
                any<RowMapper<Details>>(),
            )
        }
        verify(exactly = 1) {
            jdbc.query(
                match<String> { it.contains("WHERE s.care_reg_no=:value") },
                match<MapSqlParameterSource> { p -> p.getValue("value") == "reg-4" },
                any<RowMapper<Details>>(),
            )
        }
        verify(exactly = 2) {
            jdbc.query(
                match<String> { it.contains("shelter_public_information") },
                match<MapSqlParameterSource> { p -> p.getValue("id") == 4L },
                any<RowMapper<PublicInformation>>(),
            )
        }
    }

    @Test
    fun `상세나 등록번호가 없을 때__찾으면__공개 정보를 읽지 않고 원래의 일반 실패를 유지한다`() {
        // given
        every { jdbc.query(any<String>(), any<MapSqlParameterSource>(), any<RowMapper<Details>>()) } returns listOf()

        // when
        val byId = assertThatThrownBy { shelters.findById(4L) }
        val byRegistration = assertThatThrownBy { shelters.findByCareRegNo("absent") }

        // then
        byId.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Shelter not found: 4")
        byRegistration.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Shelter not found: absent")
        verify(exactly = 0) {
            jdbc.query(
                match<String> { it.contains("shelter_public_information") },
                any<MapSqlParameterSource>(),
                any<RowMapper<PublicInformation>>(),
            )
        }
    }

    @Test
    fun `요약에 null이 있고 공개 정보가 없을 때__읽으면__지어낸 필드 없이 null을 유지한다`() {
        // given
        val details = Details(4L, "reg-4", "센터", null, null, null, null, null, null, null, null, null, null, null)

        every {
            jdbc.query(
                match<String> { it.contains("WHERE s.id=:value") },
                any<MapSqlParameterSource>(),
                any<RowMapper<Details>>(),
            )
        } returns listOf(details)
        every {
            jdbc.query(
                match<String> { it.contains("shelter_public_information") },
                any<MapSqlParameterSource>(),
                any<RowMapper<PublicInformation>>(),
            )
        } returns listOf()

        // when
        val found = shelters.findById(4L)

        // then
        assertThat(found).isEqualTo(details)
    }

    @Test
    fun `공개 정보가 잘못됐을 때__상세를 읽으면__원본 JSON이나 원인 없이 일반 실패를 유지한다`() {
        // given
        val details = Details(4L, "reg", "센터", null, null, null, null, null, null, null, null, null, null, null)
        val invalid = mockk<ResultSet>()

        every {
            jdbc.query(
                match<String> { it.contains("WHERE s.id=:value") },
                any<MapSqlParameterSource>(),
                any<RowMapper<Details>>(),
            )
        } returns listOf(details)
        every { invalid.getString("details") } returns "[\"synthetic-private-detail\"]"
        every {
            jdbc.query(
                match<String> { it.contains("shelter_public_information") },
                any<MapSqlParameterSource>(),
                any<RowMapper<PublicInformation>>(),
            )
        } answers {
            listOf(arg<RowMapper<PublicInformation>>(2).mapRow(invalid, 0)!!)
        }

        // when
        val thrown = assertThatThrownBy { shelters.findById(4L) }

        // then
        thrown.isInstanceOf(IllegalStateException::class.java)
            .hasNoCause()
            .hasMessage("Invalid shelter public information")
    }

    @Test
    fun `보호소 디렉터리 건수가 있을 때__건수를 세면__다른 읽기 없이 원래의 스칼라 Long을 돌려준다`() {
        // given
        every {
            jdbc.queryForObject(any<String>(), any<MapSqlParameterSource>(), Long::class.javaObjectType)
        } returns 9876543210L

        // when
        val count = shelters.count()

        // then
        assertThat(count).isEqualTo(9876543210L)
        verify(exactly = 1) {
            jdbc.queryForObject(
                "SELECT count(*) FROM pawbridge_animal.shelters",
                any<MapSqlParameterSource>(),
                Long::class.javaObjectType,
            )
        }
        confirmVerified(jdbc, discoveryJdbc)
    }

    companion object {
        private val FIRST = PageRequest(0, 20, listOf(Order("name", true, false)))

        @JvmStatic
        fun filters(): Stream<Arguments> = Stream.of(
            Arguments.of(
                Search("ignored", " 서울_ ", "ignored", " 센터% "),
                "WHERE s.address LIKE :address ESCAPE '' AND (s.name LIKE :keyword ESCAPE '' OR s.address LIKE :keyword ESCAPE '')",
                "keyword",
                "%센터%%",
            ),
            Arguments.of(
                Search("ignored", "   ", "ignored", "센터%"),
                "WHERE s.name LIKE :keyword ESCAPE '' OR s.address LIKE :keyword ESCAPE ''",
                "keyword",
                "%센터%%",
            ),
            Arguments.of(
                Search("ignored", "서울", "ignored", ""),
                "WHERE s.name LIKE :keyword ESCAPE '' OR s.address LIKE :keyword ESCAPE ''",
                "keyword",
                "%%",
            ),
            Arguments.of(
                Search(null, null, null, "a\\_%"),
                "WHERE s.name LIKE :keyword ESCAPE '' OR s.address LIKE :keyword ESCAPE ''",
                "keyword",
                "%a\\_%%",
            ),
            Arguments.of(
                Search(" A%_\\ ", "ignored", "ignored", null),
                "WHERE s.name LIKE :term ESCAPE '\\'",
                "term",
                "% A\\%\\_\\\\ %",
            ),
            Arguments.of(
                Search(null, " 서울% ", "ignored", null),
                "WHERE s.address LIKE :term ESCAPE '\\'",
                "term",
                "% 서울\\% %",
            ),
            Arguments.of(
                Search(null, null, "시_청", null),
                "WHERE s.organization_name LIKE :term ESCAPE '\\'",
                "term",
                "%시\\_청%",
            ),
            Arguments.of(Search(null, null, null, null), "SELECT s.id,s.care_reg_no", null, null),
        )

        @JvmStatic
        fun associationSortPaths(): Stream<String> =
            Stream.of("animals.shelter.name", "animals_shelter_name", "animalsShelterName")

        @JvmStatic
        fun terminalSortPaths(): Stream<Arguments> = Stream.of(
            Arguments.of("animals", "sort_0.id"),
            Arguments.of("animals.shelter", "sort_1.id"),
            Arguments.of("animalsId", "sort_0.id"),
            Arguments.of("AnimalsShelter", "sort_1.id"),
        )
    }
}
