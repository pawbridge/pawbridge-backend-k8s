package com.pawbridge.backend.animal.internal.shelter.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.Details
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.Order
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.PageRequest
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.PublicInformation
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.Search
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.Summary
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatcher
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.argThat
import org.mockito.ArgumentMatchers.contains
import org.mockito.ArgumentMatchers.endsWith
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
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
    private val jdbc: NamedParameterJdbcOperations = mock(NamedParameterJdbcOperations::class.java)
    private val discoveryJdbc: NamedParameterJdbcOperations = mock(NamedParameterJdbcOperations::class.java)
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
    fun givenCompetingSearchParameters__whenListing__thenPreserveSourcePrecedenceAndWildcardSemantics(
        request: Search,
        predicate: String,
        parameter: String?,
        value: String?,
    ) {
        `when`(jdbc.query(anyString(), any(MapSqlParameterSource::class.java), any<RowMapper<Summary>>()))
            .thenReturn(listOf())

        assertThat(shelters.search(request, FIRST).content()).isEmpty()

        val sql = ArgumentCaptor.forClass(String::class.java)
        val parameters = ArgumentCaptor.forClass(MapSqlParameterSource::class.java)
        verify(jdbc).query(sql.capture(), parameters.capture(), any<RowMapper<Summary>>())
        assertThat(sql.value).contains(
            "FROM pawbridge_animal.shelters s",
            predicate,
            " ORDER BY s.name ASC LIMIT :limit OFFSET :offset",
        )
        if (parameter != null) {
            assertThat(parameters.value.getValue(parameter)).isEqualTo(value)
        }
        if (request.keyword != null && !request.keyword!!.isBlank() && request.address != null && !request.address!!.isBlank()) {
            assertThat(parameters.value.getValue("address")).isEqualTo("%서울_%")
        }
        verify(jdbc, never()).queryForObject(anyString(), any(MapSqlParameterSource::class.java), eq(Long::class.javaObjectType))
        verifyNoInteractions(discoveryJdbc)
    }

    @Test
    fun givenPartialAndFullPages__whenListing__thenPreserveSourcePageableExecutionUtilsCountDecisions() {
        val row = Summary(4L, "r", "센터", null, null, null, null)
        `when`(jdbc.query(anyString(), any(MapSqlParameterSource::class.java), any<RowMapper<Summary>>()))
            .thenReturn(listOf(row))
        assertThat(shelters.search(Search(null, null, null, null), PageRequest(2, 20, listOf())).totalElements())
            .isEqualTo(41)
        verify(jdbc, never()).queryForObject(anyString(), any(MapSqlParameterSource::class.java), eq(Long::class.javaObjectType))
        `when`(jdbc.queryForObject(anyString(), any(MapSqlParameterSource::class.java), eq(Long::class.javaObjectType)))
            .thenReturn(99L)
        assertThat(shelters.search(Search(null, null, null, null), PageRequest(0, 1, listOf())).totalElements())
            .isEqualTo(99)
        `when`(jdbc.query(anyString(), any(MapSqlParameterSource::class.java), any<RowMapper<Summary>>()))
            .thenReturn(listOf())
        assertThat(shelters.search(Search(null, null, null, null), PageRequest(7, 20, listOf())).totalElements())
            .isEqualTo(99)
        verify(jdbc, times(2)).queryForObject(
            contains("count(*)"),
            any(MapSqlParameterSource::class.java),
            eq(Long::class.javaObjectType),
        )
    }

    @Test
    fun givenMappedScalarSorts__whenListing__thenPreserveAll13FieldsBothDirectionsAndCaseFlagWithoutNewTieBreak() {
        `when`(jdbc.query(anyString(), any(MapSqlParameterSource::class.java), any<RowMapper<Summary>>()))
            .thenReturn(listOf())
        val columns = arrayOf(
            arrayOf("id", "id"), arrayOf("careRegNo", "care_reg_no"), arrayOf("name", "name"), arrayOf("phone", "phone"),
            arrayOf("address", "address"), arrayOf("ownerName", "owner_name"),
            arrayOf("organizationName", "organization_name"), arrayOf("email", "email"),
            arrayOf("introduction", "introduction"), arrayOf("adoptionProcedure", "adoption_procedure"),
            arrayOf("operatingHours", "operating_hours"),
            arrayOf("createdAt", "created_at"), arrayOf("updatedAt", "updated_at"),
        )
        for (column in columns) {
            for (ascending in listOf(true, false)) {
                shelters.search(
                    Search(null, null, null, null),
                    PageRequest(0, 20, listOf(Order(column[0], ascending, false))),
                )
                verify(jdbc).query(
                    endsWith(" ORDER BY s." + column[1] + (if (ascending) " ASC" else " DESC") + " LIMIT :limit OFFSET :offset"),
                    any(MapSqlParameterSource::class.java),
                    any<RowMapper<Summary>>(),
                )
            }
        }
        shelters.search(
            Search(null, null, null, null),
            PageRequest(0, 20, listOf(Order("name", true, true), Order("createdAt", false, false))),
        )
        verify(jdbc).query(
            endsWith(" ORDER BY lower(s.name) ASC,s.created_at DESC LIMIT :limit OFFSET :offset"),
            any(MapSqlParameterSource::class.java),
            any<RowMapper<Summary>>(),
        )
        assertThatThrownBy {
            shelters.search(
                Search(null, null, null, null),
                PageRequest(0, 20, listOf(Order("name;drop table shelters", true, false))),
            )
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun givenCollectionSort__whenListing__thenApplySqlLimitBeforeEntityDeduplicationAndPartialPageInference() {
        val first = Summary(4L, "reg-4", "센터4", null, null, null, null)
        val second = Summary(9L, "reg-9", "센터9", null, null, null, null)
        `when`(jdbc.query(anyString(), any(MapSqlParameterSource::class.java), any<RowMapper<Summary>>()))
            .thenReturn(listOf(first, first))
        val page = PageRequest(0, 2, listOf(Order("animals.id", true, false), Order("animals.breed", false, true)))

        val result = shelters.search(Search(null, null, null, null), page)

        assertThat(result.content()).containsExactly(first)
        assertThat(result.totalElements()).isOne()
        verify(jdbc).query(
            argThat(
                ArgumentMatcher<String> { sql ->
                    sql.contains("LEFT JOIN pawbridge_animal.animals sort_0 ON sort_0.shelter_id=s.id") &&
                        sql.contains(" ORDER BY sort_0.id ASC,lower(sort_0.breed) DESC LIMIT :limit OFFSET :offset") &&
                        !sql.contains("DISTINCT") && !sql.contains("sort_1")
                },
            ),
            any(MapSqlParameterSource::class.java),
            any<RowMapper<Summary>>(),
        )
        verify(jdbc, never()).queryForObject(anyString(), any(MapSqlParameterSource::class.java), eq(Long::class.javaObjectType))
        `when`(jdbc.query(anyString(), any(MapSqlParameterSource::class.java), any<RowMapper<Summary>>()))
            .thenReturn(listOf(first, second))
        `when`(jdbc.queryForObject(anyString(), any(MapSqlParameterSource::class.java), eq(Long::class.javaObjectType)))
            .thenReturn(99L)
        assertThat(shelters.search(Search(null, null, null, null), page).totalElements()).isEqualTo(99)
        verify(jdbc).queryForObject(
            argThat(
                ArgumentMatcher<String> { sql ->
                    sql.contains("count(*)") && !sql.contains("JOIN") && !sql.contains("ORDER BY")
                },
            ),
            any(MapSqlParameterSource::class.java),
            eq(Long::class.javaObjectType),
        )
    }

    @ParameterizedTest
    @MethodSource("associationSortPaths")
    fun givenNestedOrCamelAssociationSort__whenListing__thenPreserveMappedAssociationPathsAndStringCaseHandling(
        path: String,
    ) {
        `when`(jdbc.query(anyString(), any(MapSqlParameterSource::class.java), any<RowMapper<Summary>>()))
            .thenReturn(listOf())

        shelters.search(
            Search(null, null, null, null),
            PageRequest(0, 20, listOf(Order(path, true, true), Order("animals.species", false, true))),
        )

        verify(jdbc).query(
            argThat(
                ArgumentMatcher<String> { sql ->
                    sql.contains("LEFT JOIN pawbridge_animal.animals sort_0 ON sort_0.shelter_id=s.id") &&
                        sql.contains("LEFT JOIN pawbridge_animal.shelters sort_1 ON sort_1.id=sort_0.shelter_id") &&
                        sql.contains(" ORDER BY lower(sort_1.name) ASC,sort_0.species DESC LIMIT :limit OFFSET :offset")
                },
            ),
            any(MapSqlParameterSource::class.java),
            any<RowMapper<Summary>>(),
        )
    }

    @ParameterizedTest
    @MethodSource("terminalSortPaths")
    fun givenTerminalAssociationOrCamelIdSort__whenListing__thenUseOriginalSinglePrimaryKeyOrdering(
        path: String,
        expression: String,
    ) {
        `when`(jdbc.query(anyString(), any(MapSqlParameterSource::class.java), any<RowMapper<Summary>>()))
            .thenReturn(listOf())

        shelters.search(Search(null, null, null, null), PageRequest(0, 20, listOf(Order(path, true, false))))

        verify(jdbc).query(
            argThat(
                ArgumentMatcher<String> { sql ->
                    sql.contains("LEFT JOIN pawbridge_animal.animals sort_0") &&
                        sql.contains(" ORDER BY " + expression + " ASC LIMIT :limit OFFSET :offset")
                },
            ),
            any(MapSqlParameterSource::class.java),
            any<RowMapper<Summary>>(),
        )
    }

    @Test
    fun givenDeclaredQueryAliasSort__whenSearchingKeyword__thenUseOriginalRootAliasWithoutAddingJoin() {
        `when`(jdbc.query(anyString(), any(MapSqlParameterSource::class.java), any<RowMapper<Summary>>()))
            .thenReturn(listOf())

        shelters.search(Search(null, null, null, "센터"), PageRequest(0, 20, listOf(Order("s.name", false, false))))

        verify(jdbc).query(
            argThat(
                ArgumentMatcher<String> { sql ->
                    sql.contains("WHERE s.name LIKE :keyword ESCAPE ''") &&
                        sql.contains(" ORDER BY s.name DESC LIMIT :limit OFFSET :offset") && !sql.contains("JOIN")
                },
            ),
            any(MapSqlParameterSource::class.java),
            any<RowMapper<Summary>>(),
        )
    }

    @Test
    fun givenPageOffsetOutsideJpaIntegerRange__whenListing__thenKeepSourceInputFailureBeforeSql() {
        assertThatThrownBy {
            shelters.search(Search(null, null, null, null), PageRequest(Int.MAX_VALUE, 20, listOf()))
        }.isInstanceOf(InvalidDataAccessApiUsageException::class.java)
            .hasMessage("Page offset exceeds Integer.MAX_VALUE (2147483647)")

        verifyNoInteractions(jdbc)
    }

    @Test
    fun givenDetailAndStoredPublicInformation__whenFindingByIdOrRegistration__thenReadBothInOneReadScopeAndKeepAllFields() {
        val created = LocalDateTime.of(2026, 9, 29, 13, 4, 5, 123456000)
        val updated = created.plusDays(1)
        val row = mock(ResultSet::class.java)
        `when`(row.getObject("id", Long::class.javaObjectType)).thenReturn(4L)
        `when`(row.getString("care_reg_no")).thenReturn("reg-4")
        `when`(row.getString("name")).thenReturn("보호 센터")
        `when`(row.getString("phone")).thenReturn("02-1234")
        `when`(row.getString("address")).thenReturn("서울")
        `when`(row.getString("organization_name")).thenReturn("시청")
        `when`(row.getString("owner_name")).thenReturn("대표")
        `when`(row.getString("email")).thenReturn("sample@example.test")
        `when`(row.getString("introduction")).thenReturn("소개")
        `when`(row.getString("adoption_procedure")).thenReturn("절차")
        `when`(row.getString("operating_hours")).thenReturn("09:00")
        `when`(row.getObject("created_at", LocalDateTime::class.java)).thenReturn(created)
        `when`(row.getObject("updated_at", LocalDateTime::class.java)).thenReturn(updated)
        val publicRow = mock(ResultSet::class.java)
        `when`(publicRow.getString("details")).thenReturn(
            """
            {"source":"ignored","phone":"02-public","latitude":37.5,"longitude":127.1,
             "weekdayOpen":"09:00","weekdayClose":"18:00","weekendOpen":"10:00","weekendClose":"16:00",
             "closedDays":"월요일","sourceUpdatedDate":"2026-09-29","collectedAt":"2000-01-01T00:00"}
            """.trimIndent(),
        )
        `when`(publicRow.getTimestamp("collected_at")).thenReturn(Timestamp.valueOf(updated))
        `when`(jdbc.query(anyString(), any(MapSqlParameterSource::class.java), any<RowMapper<Details>>()))
            .thenAnswer { call ->
                val sql: String = call.getArgument(0)
                val mapping = call.getArgument<RowMapper<*>>(2)
                listOf(mapping.mapRow(if (sql.contains("shelter_public_information")) publicRow else row, 0))
            }
        val expectedPublic = PublicInformation(
            "APMS_SHELTER_INFO", "02-public", 37.5, 127.1,
            "09:00", "18:00", "10:00", "16:00", "월요일", "2026-09-29", updated,
        )
        val expected = Details(
            4L, "reg-4", "보호 센터", "02-1234", "서울", "대표", "시청", "sample@example.test",
            "소개", "절차", "09:00", created, updated, expectedPublic,
        )

        assertThat(shelters.findById(4L)).isEqualTo(expected)
        assertThat(shelters.findByCareRegNo("reg-4")).isEqualTo(expected)

        verify(jdbc).query(
            contains("WHERE s.id=:value"),
            argThat(ArgumentMatcher<MapSqlParameterSource> { p -> p.getValue("value") == 4L }),
            any<RowMapper<Details>>(),
        )
        verify(jdbc).query(
            contains("WHERE s.care_reg_no=:value"),
            argThat(ArgumentMatcher<MapSqlParameterSource> { p -> p.getValue("value") == "reg-4" }),
            any<RowMapper<Details>>(),
        )
        verify(jdbc, times(2)).query(
            contains("shelter_public_information"),
            argThat(ArgumentMatcher<MapSqlParameterSource> { p -> p.getValue("id") == 4L }),
            any<RowMapper<PublicInformation>>(),
        )
    }

    @Test
    fun givenMissingDetailOrRegistration__whenFinding__thenKeepSourceGenericFailureWithoutPublicInformationRead() {
        `when`(jdbc.query(anyString(), any(MapSqlParameterSource::class.java), any<RowMapper<Details>>()))
            .thenReturn(listOf())

        assertThatThrownBy { shelters.findById(4L) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Shelter not found: 4")
        assertThatThrownBy { shelters.findByCareRegNo("absent") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Shelter not found: absent")

        verify(jdbc, never()).query(
            contains("shelter_public_information"),
            any(MapSqlParameterSource::class.java),
            any<RowMapper<PublicInformation>>(),
        )
    }

    @Test
    fun givenNullableSummaryAndNoPublicInformation__whenReading__thenRetainNullsWithoutFabricatedFields() {
        val details = Details(4L, "reg-4", "센터", null, null, null, null, null, null, null, null, null, null, null)
        `when`(jdbc.query(contains("WHERE s.id=:value"), any(MapSqlParameterSource::class.java), any<RowMapper<Details>>()))
            .thenReturn(listOf(details))
        `when`(
            jdbc.query(
                contains("shelter_public_information"),
                any(MapSqlParameterSource::class.java),
                any<RowMapper<PublicInformation>>(),
            ),
        ).thenReturn(listOf())

        assertThat(shelters.findById(4L)).isEqualTo(details)
    }

    @Test
    fun givenMalformedPublicInformation__whenReadingDetail__thenKeepGenericFailureWithoutRawJsonOrCause() {
        val details = Details(4L, "reg", "센터", null, null, null, null, null, null, null, null, null, null, null)
        `when`(jdbc.query(contains("WHERE s.id=:value"), any(MapSqlParameterSource::class.java), any<RowMapper<Details>>()))
            .thenReturn(listOf(details))
        val invalid = mock(ResultSet::class.java)
        `when`(invalid.getString("details")).thenReturn("[\"synthetic-private-detail\"]")
        `when`(
            jdbc.query(
                contains("shelter_public_information"),
                any(MapSqlParameterSource::class.java),
                any<RowMapper<PublicInformation>>(),
            ),
        ).thenAnswer { call -> listOf(call.getArgument<RowMapper<PublicInformation>>(2).mapRow(invalid, 0)) }

        assertThatThrownBy { shelters.findById(4L) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasNoCause()
            .hasMessage("Invalid shelter public information")
    }

    @Test
    fun givenDirectoryCount__whenCounting__thenReturnOriginalScalarLongWithoutOtherReads() {
        `when`(jdbc.queryForObject(anyString(), any(MapSqlParameterSource::class.java), eq(Long::class.javaObjectType)))
            .thenReturn(9876543210L)

        assertThat(shelters.count()).isEqualTo(9876543210L)

        verify(jdbc).queryForObject(
            eq("SELECT count(*) FROM pawbridge_animal.shelters"),
            any(MapSqlParameterSource::class.java),
            eq(Long::class.javaObjectType),
        )
        verifyNoMoreInteractions(jdbc)
        verifyNoInteractions(discoveryJdbc)
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
