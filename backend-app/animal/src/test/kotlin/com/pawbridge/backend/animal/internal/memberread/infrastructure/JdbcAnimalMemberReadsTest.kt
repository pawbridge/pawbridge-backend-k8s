package com.pawbridge.backend.animal.internal.memberread.infrastructure

import com.pawbridge.backend.animal.api.AnimalMemberReadUnavailableException
import com.pawbridge.backend.animal.api.AnimalMemberReads.Animal
import com.pawbridge.backend.animal.api.AnimalMemberReads.Page
import com.pawbridge.backend.animal.api.AnimalMemberReads.PageRequest
import com.pawbridge.backend.animal.api.AnimalShelterReads
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
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionOperations
import java.sql.ResultSet
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import java.util.stream.Stream
import javax.sql.DataSource

class JdbcAnimalMemberReadsTest {
    private val jdbc: NamedParameterJdbcOperations = mock(NamedParameterJdbcOperations::class.java)
    private val shelters: AnimalShelterReads = mock(AnimalShelterReads::class.java)
    private val reads = JdbcAnimalMemberReads(jdbc, TransactionOperations.withoutTransaction(), shelters)

    private fun row(id: Long): Animal {
        return Animal(id, null, "DOG", null, null, null, null, null, "ADOPTED", null, null, null, null, null, null)
    }

    private fun rows(rows: List<Animal>) {
        `when`(jdbc.query(anyString(), any(MapSqlParameterSource::class.java), any<RowMapper<Animal>>())).thenReturn(rows)
    }

    @Test
    fun givenSourceSupportedCollectionSort__whenRegisteredPage__thenDeduplicateRootsAfterSqlLimitBeforeCountInference() {
        rows(listOf(row(8), row(8)))

        val result = reads.byShelter(7L, PageRequest(0, 2, "shelter.animals.id,asc"))

        assertThat(result).isEqualTo(Page(listOf(row(8)), 0, 2, 1, 1, true))
        verify(jdbc).query(
            argThat(
                ArgumentMatcher<String> { sql ->
                    sql.contains("LEFT JOIN pawbridge_animal.animals sort_0 ON sort_0.shelter_id=s.id") &&
                        sql.endsWith(" ORDER BY sort_0.id ASC LIMIT :limit OFFSET :offset") &&
                        !sql.contains("DISTINCT") && !sql.contains("GROUP BY") &&
                        !sql.contains("sort_0.api_source") && !sql.contains("sort_0.status")
                },
            ),
            any(MapSqlParameterSource::class.java),
            any<RowMapper<Animal>>(),
        )
        verify(jdbc, never()).queryForObject(anyString(), any(MapSqlParameterSource::class.java), eq(Long::class.javaObjectType))
    }

    @ParameterizedTest
    @MethodSource("collectionSortPaths")
    fun givenSourceAssociationPath__whenSorting__thenPreserveGrammarTerminalEntityAndNestedJoin(sort: String, order: String) {
        rows(listOf(row(8)))

        assertThat(reads.byShelter(7L, PageRequest(0, 2, sort))!!.content).containsExactly(row(8))

        verify(jdbc).query(
            argThat(
                ArgumentMatcher<String> { sql ->
                    sql.contains("LEFT JOIN pawbridge_animal.animals sort_0 ON sort_0.shelter_id=s.id") &&
                        sql.endsWith(" ORDER BY " + order + " LIMIT :limit OFFSET :offset") &&
                        !sql.contains("DISTINCT") && !sql.contains("NULLS") &&
                        !sql.contains("sort_0.api_source=") && !sql.contains("sort_0.status=")
                },
            ),
            any(MapSqlParameterSource::class.java),
            any<RowMapper<Animal>>(),
        )
    }

    @Test
    fun givenSharedCollectionPrefixes__whenOrderingMultipleProperties__thenReuseJoinsAndOriginalOrderWithoutExtraFilters() {
        rows(listOf(row(9), row(9), row(8)))

        val page = reads.byShelter(7L, PageRequest(2, 3, "shelter.animals.breed,shelter.animals.shelter.name,asc"))

        assertThat(page).isEqualTo(Page(listOf(row(9), row(8)), 2, 3, 8, 3, true))
        verify(jdbc).query(
            argThat(
                ArgumentMatcher<String> { sql ->
                    sql.contains("LEFT JOIN pawbridge_animal.animals sort_0 ON sort_0.shelter_id=s.id") &&
                        sql.contains("LEFT JOIN pawbridge_animal.shelters sort_1 ON sort_1.id=sort_0.shelter_id") &&
                        sql.endsWith(" ORDER BY sort_0.breed ASC,sort_1.name ASC LIMIT :limit OFFSET :offset") &&
                        !sql.contains("sort_2") && !sql.contains("DISTINCT") &&
                        !sql.contains("sort_0.api_source") && !sql.contains("sort_0.status")
                },
            ),
            argThat(ArgumentMatcher<MapSqlParameterSource> { params -> params.getValue("offset") == 6L }),
            any<RowMapper<Animal>>(),
        )
        verify(jdbc, never()).queryForObject(anyString(), any(MapSqlParameterSource::class.java), eq(Long::class.javaObjectType))
    }

    @Test
    fun givenCollectionFullAndEmptyLaterPages__whenCounting__thenCountRootManualAnimalsWithoutSortJoins() {
        `when`(jdbc.queryForObject(anyString(), any(MapSqlParameterSource::class.java), eq(Long::class.javaObjectType)))
            .thenReturn(99L)
        rows(listOf(row(9), row(8)))

        var page = reads.byShelter(7L, PageRequest(1, 2, "shelter.animals.id,asc"))

        assertThat(page).isEqualTo(Page(listOf(row(9), row(8)), 1, 2, 99, 50, false))
        rows(listOf())
        page = reads.byShelter(7L, PageRequest(50, 2, "shelter.animals.id,asc"))
        assertThat(page).isEqualTo(Page(listOf(), 50, 2, 99, 50, true))
        verify(jdbc, times(2)).queryForObject(
            eq("SELECT count(*) FROM pawbridge_animal.animals a WHERE a.shelter_id=:shelterId AND a.api_source='MANUAL'"),
            any(MapSqlParameterSource::class.java),
            eq(Long::class.javaObjectType),
        )
    }

    @Test
    fun givenNullOrEmptyIds__whenBatch__thenNoQueryAndEmptyMutableList() {
        (reads.batch(null) as MutableList<Animal>).add(row(1))

        assertThat(reads.batch(listOf())).isEmpty()

        verifyNoInteractions(jdbc, shelters)
    }

    @Test
    fun givenDuplicateMissingAndNullIds__whenBatch__thenUseOneInQueryWithoutSourceStatusOrOrderPolicy() {
        val ids = ArrayList<Long?>()
        ids.add(8L)
        ids.add(8L)
        ids.add(404L)
        ids.add(null)
        rows(listOf(row(8)))

        assertThat(reads.batch(ids)).containsExactly(row(8))

        val sql = ArgumentCaptor.forClass(String::class.java)
        val params = ArgumentCaptor.forClass(MapSqlParameterSource::class.java)
        verify(jdbc).query(sql.capture(), params.capture(), any<RowMapper<Animal>>())
        assertThat(sql.value).contains("LEFT JOIN pawbridge_animal.shelters", " WHERE a.id IN (:ids)")
            .doesNotContain("ORDER BY", "a.api_source=", "a.status=", "DISTINCT", "LIMIT")
        assertThat(params.value.getValue("ids")).isEqualTo(ids)
        verifyNoMoreInteractions(jdbc)
    }

    @Test
    fun givenNullableAndPopulatedRow__whenMapping__thenRetainAll15FieldsAndMapperAge() {
        val rs = mock(ResultSet::class.java)
        assertThat(JdbcAnimalMemberReads.animal(rs, 0)).isEqualTo(
            Animal(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null),
        )
        val end = LocalDate.of(2026, 12, 31)
        val created = LocalDateTime.of(2026, 10, 20, 2, 3, 4, 123456000)
        `when`(rs.getObject("id", Long::class.javaObjectType)).thenReturn(9L)
        `when`(rs.getString("apms_notice_no")).thenReturn("공고")
        `when`(rs.getString("species")).thenReturn("CAT")
        `when`(rs.getString("breed")).thenReturn("품종")
        `when`(rs.getString("gender")).thenReturn("UNKNOWN")
        `when`(rs.getObject("birth_year", Int::class.javaObjectType)).thenReturn(2020)
        `when`(rs.getString("special_mark")).thenReturn("특징")
        `when`(rs.getString("status")).thenReturn("ADOPTION_PENDING")
        `when`(rs.getObject("notice_end_date", LocalDate::class.java)).thenReturn(end)
        `when`(rs.getString("image_url")).thenReturn("image")
        `when`(rs.getObject("favorite_count", Int::class.javaObjectType)).thenReturn(3)
        `when`(rs.getObject("shelter_id", Long::class.javaObjectType)).thenReturn(7L)
        `when`(rs.getString("shelter_name")).thenReturn("센터")
        `when`(rs.getObject("created_at", LocalDateTime::class.java)).thenReturn(created)

        assertThat(JdbcAnimalMemberReads.animal(rs, 0)).isEqualTo(
            Animal(
                9L, "공고", "CAT", "품종", "UNKNOWN", 2020,
                LocalDate.now().year - 2020, "특징", "ADOPTION_PENDING", end, "image", 3, 7L, "센터", created,
            ),
        )
    }

    @Test
    fun givenPartialLaterPage__whenByShelter__thenExactManualPredicateAndCountInferredWithoutStatusFilter() {
        rows(listOf(row(8)))

        val result = reads.byShelter(7L, PageRequest(2, 20, "createdAt,desc"))

        assertThat(result).isEqualTo(Page(listOf(row(8)), 2, 20, 41, 3, true))
        verify(jdbc).query(
            argThat(
                ArgumentMatcher<String> { s ->
                    s.contains("a.shelter_id=:shelterId AND a.api_source='MANUAL'") &&
                        s.endsWith(" ORDER BY a.created_at DESC LIMIT :limit OFFSET :offset") && !s.contains("a.status=")
                },
            ),
            argThat(
                ArgumentMatcher<MapSqlParameterSource> { p ->
                    p.getValue("shelterId") == 7L && p.getValue("offset") == 40L && p.getValue("limit") == 20
                },
            ),
            any<RowMapper<Animal>>(),
        )
        verify(jdbc, never()).queryForObject(anyString(), any(MapSqlParameterSource::class.java), eq(Long::class.javaObjectType))
    }

    @Test
    fun givenFullOrEmptyLaterPage__whenByShelter__thenUseSameManualCountAndPreservePageImplMetadata() {
        rows(listOf(row(8), row(9)))
        `when`(jdbc.queryForObject(anyString(), any(MapSqlParameterSource::class.java), eq(Long::class.javaObjectType)))
            .thenReturn(99L)

        var result = reads.byShelter(7L, PageRequest(1, 2, "id,asc"))!!

        assertThat(result.totalElements).isEqualTo(99)
        assertThat(result.totalPages).isEqualTo(50)
        assertThat(result.last).isFalse()
        rows(listOf())
        result = reads.byShelter(7L, PageRequest(50, 2, "id,asc"))!!
        assertThat(result.content).isEmpty()
        assertThat(result.totalElements).isEqualTo(99)
        assertThat(result.last).isTrue()
        verify(jdbc, times(2)).queryForObject(
            eq("SELECT count(*) FROM pawbridge_animal.animals a WHERE a.shelter_id=:shelterId AND a.api_source='MANUAL'"),
            any(MapSqlParameterSource::class.java),
            eq(Long::class.javaObjectType),
        )
    }

    @Test
    fun givenEmptyFirstAndStaleCountFinalPage__whenByShelter__thenKeepSourceInferenceAndTotalAdaptation() {
        rows(listOf())
        assertThat(reads.byShelter(7L, PageRequest(0, 2, "id,asc"))).isEqualTo(Page(listOf(), 0, 2, 0, 0, true))
        verify(jdbc, never()).queryForObject(anyString(), any(MapSqlParameterSource::class.java), eq(Long::class.javaObjectType))
        rows(listOf(row(8), row(9)))
        `when`(jdbc.queryForObject(anyString(), any(MapSqlParameterSource::class.java), eq(Long::class.javaObjectType)))
            .thenReturn(1L)

        assertThat(reads.byShelter(7L, PageRequest(2, 2, "id,asc"))!!.totalElements).isEqualTo(6)
    }

    @Test
    fun givenSingleWireSort__whenOrdering__thenRetainAll25ScalarsAndImmediateShelterFieldsWithoutTieOrNullPolicy() {
        val fields = arrayOf(
            arrayOf("id", "id"), arrayOf("apmsDesertionNo", "apms_desertion_no"), arrayOf("apmsNoticeNo", "apms_notice_no"),
            arrayOf("species", "species"), arrayOf("breed", "breed"), arrayOf("birthYear", "birth_year"),
            arrayOf("weight", "weight"), arrayOf("color", "color"),
            arrayOf("gender", "gender"), arrayOf("neuterStatus", "neuter_status"), arrayOf("specialMark", "special_mark"),
            arrayOf("apmsProcessState", "apms_process_state"),
            arrayOf("noticeStartDate", "notice_start_date"), arrayOf("noticeEndDate", "notice_end_date"),
            arrayOf("apmsUpdatedAt", "apms_updated_at"),
            arrayOf("happenDate", "happen_date"), arrayOf("happenPlace", "happen_place"), arrayOf("imageUrl", "image_url"),
            arrayOf("imageUrl2", "image_url2"),
            arrayOf("status", "status"), arrayOf("apiSource", "api_source"), arrayOf("favoriteCount", "favorite_count"),
            arrayOf("description", "description"),
            arrayOf("createdAt", "created_at"), arrayOf("updatedAt", "updated_at"),
        )
        for (field in fields) {
            for (direction in listOf("asc", "desc")) {
                assertThat(JdbcAnimalMemberReads.ordering(field[0] + "," + direction))
                    .isEqualTo(" ORDER BY a." + field[1] + " " + direction.uppercase(Locale.ROOT))
            }
        }
        assertThat(JdbcAnimalMemberReads.ordering("BirthYear,asc")).isEqualTo(" ORDER BY a.birth_year ASC")
        assertThat(JdbcAnimalMemberReads.ordering("Shelter.Name,asc")).isEqualTo(" ORDER BY s.name ASC")
        assertThat(JdbcAnimalMemberReads.ordering("shelter.name,desc")).isEqualTo(" ORDER BY s.name DESC")
        assertThat(JdbcAnimalMemberReads.ordering("shelterName,asc")).isEqualTo(" ORDER BY s.name ASC")
        assertThat(JdbcAnimalMemberReads.ordering("breed,id,desc,ignorecase"))
            .isEqualTo(" ORDER BY lower(a.breed) DESC,a.id DESC")
        assertThat(JdbcAnimalMemberReads.ordering("species,asc,ignorecase")).isEqualTo(" ORDER BY a.species ASC")
        assertThat(JdbcAnimalMemberReads.ordering(null)).isEqualTo(" ORDER BY a.created_at DESC")
        assertThat(JdbcAnimalMemberReads.ordering(" ")).isEmpty()
    }

    @Test
    fun givenFlattenedMultipleSortsOrInjectedPath__whenByShelter__thenFailBeforeSqlWithoutInventingPairParsing() {
        for (sort in listOf("breed,desc,createdAt,asc", "id;drop table animals,asc")) {
            assertThatThrownBy { reads.byShelter(7L, PageRequest(0, 20, sort)) }
                .isInstanceOf(AnimalMemberReadUnavailableException::class.java)
        }
        verifyNoInteractions(jdbc)
    }

    @Test
    fun givenMaximumJpaOffset__whenCalculatingLast__thenPreserveSourceIntOverflow() {
        rows(listOf())
        `when`(jdbc.queryForObject(anyString(), any(MapSqlParameterSource::class.java), eq(Long::class.javaObjectType)))
            .thenReturn(99L)

        val result = reads.byShelter(7L, PageRequest(Int.MAX_VALUE, 1, "id,asc"))!!

        assertThat(result.number).isEqualTo(Int.MAX_VALUE)
        assertThat(result.totalElements).isEqualTo(99)
        // PageImpl.hasNext uses int page+1, including the existing overflow at MAX_VALUE.
        assertThat(result.last).isFalse()
    }

    @Test
    fun givenQueryFailure__whenBatchOrCount__thenPropagateUnavailableInsteadOfEmpty() {
        val cause = IllegalStateException("selected read failed")
        `when`(jdbc.query(anyString(), any(MapSqlParameterSource::class.java), any<RowMapper<Animal>>())).thenThrow(cause)
        assertThatThrownBy { reads.batch(listOf(8L)) }
            .isInstanceOf(AnimalMemberReadUnavailableException::class.java)
            .hasCause(cause)
        rows(listOf())
        `when`(jdbc.queryForObject(anyString(), any(MapSqlParameterSource::class.java), eq(Long::class.javaObjectType)))
            .thenThrow(cause)

        assertThatThrownBy { reads.byShelter(7L, PageRequest(1, 20, "id,asc")) }
            .isInstanceOf(AnimalMemberReadUnavailableException::class.java)
            .hasCause(cause)
    }

    @Test
    fun givenRegistrationLookup__whenDelegating__thenReuseFrozenShelterProjectionAndFailure() {
        val value = AnimalShelterReads.Shelter(7L, "r", "센터")
        `when`(shelters.findByCareRegNo(" r ")).thenReturn(value)

        assertThat(reads.findByCareRegNo(" r ")).isSameAs(value)

        verifyNoInteractions(jdbc)
    }

    @Test
    fun givenPublicConstructor__whenReadScopeStarts__thenRequestDefaultReadOnlyTxBeforeValidation() {
        val manager = mock(PlatformTransactionManager::class.java)
        val source = mock(DataSource::class.java)
        `when`(manager.getTransaction(any())).thenReturn(SimpleTransactionStatus())
        val publicReads = JdbcAnimalMemberReads(source, manager, shelters)

        assertThatThrownBy { publicReads.byShelter(7L, PageRequest(Int.MAX_VALUE, 20, "id,asc")) }
            .isInstanceOf(AnimalMemberReadUnavailableException::class.java)

        val definition = ArgumentCaptor.forClass(TransactionDefinition::class.java)
        verify(manager).getTransaction(definition.capture())
        assertThat(definition.value.isReadOnly).isTrue()
        assertThat(definition.value.propagationBehavior).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED)
        assertThat(definition.value.isolationLevel).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT)
        assertThat(definition.value.timeout).isEqualTo(TransactionDefinition.TIMEOUT_DEFAULT)
        verify(manager).rollback(any())
        verifyNoInteractions(source)
    }

    companion object {
        @JvmStatic
        fun collectionSortPaths(): Stream<Arguments> = Stream.of(
            Arguments.of("shelter.animals.id,asc", "sort_0.id ASC"),
            Arguments.of("shelter_animals_id,asc", "sort_0.id ASC"),
            Arguments.of("shelterAnimalsId,asc", "sort_0.id ASC"),
            Arguments.of("Shelter_Animals_Id,asc", "sort_0.id ASC"),
            Arguments.of("shelter.animals,asc", "sort_0.id ASC"),
            Arguments.of("shelter.animals.shelter.name,asc", "sort_1.name ASC"),
            Arguments.of("shelterAnimalsShelterName,asc", "sort_1.name ASC"),
            Arguments.of("shelter.animals.shelter,asc", "sort_1.id ASC"),
            Arguments.of("shelter.animals.shelter.animals.breed,asc", "sort_2.breed ASC"),
            Arguments.of("shelterAnimalsShelterAnimalsSpecies,asc", "sort_2.species ASC"),
            Arguments.of("shelter.animals.weight,desc,ignorecase", "lower(sort_0.weight) DESC"),
            Arguments.of("shelter.animals.status,desc,ignorecase", "sort_0.status DESC"),
        )
    }
}
