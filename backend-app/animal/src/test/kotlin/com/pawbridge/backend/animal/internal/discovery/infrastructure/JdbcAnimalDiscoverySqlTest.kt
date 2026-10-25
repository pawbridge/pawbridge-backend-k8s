package com.pawbridge.backend.animal.internal.discovery.infrastructure

import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.AnimalStatus
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Details
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Gender
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.NeuterStatus
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Order
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Page
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.PageRequest
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Search
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Species
import com.pawbridge.backend.animal.internal.discovery.application.AnimalNotFoundException
import com.pawbridge.backend.animal.internal.discovery.application.SearchProjectionPendingException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations
import org.springframework.jdbc.core.namedparam.SqlParameterSource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionOperations
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

class JdbcAnimalDiscoverySqlTest {
    private lateinit var analyzer: KoreanSearchAnalyzer
    private lateinit var jdbc: NamedParameterJdbcOperations
    private lateinit var animals: JdbcAnimalDiscovery
    private val calls = ArrayList<Call>()

    @BeforeEach
    fun prepare() {
        analyzer = KoreanSearchAnalyzer()
        jdbc = mock(NamedParameterJdbcOperations::class.java)
        animals = JdbcAnimalDiscovery(jdbc, TransactionOperations.withoutTransaction(), analyzer)
        `when`(jdbc.queryForObject(anyString(), any(SqlParameterSource::class.java), eq(Boolean::class.javaObjectType)))
            .thenAnswer { call ->
                capture(call.getArgument<String>(0), call.getArgument<SqlParameterSource>(1))
                false
            }
        `when`(jdbc.queryForObject(anyString(), any(SqlParameterSource::class.java), eq(Long::class.javaObjectType)))
            .thenAnswer { call ->
                capture(call.getArgument<String>(0), call.getArgument<SqlParameterSource>(1))
                101L
            }
        `when`(jdbc.query(anyString(), any(SqlParameterSource::class.java), any<RowMapper<Any>>())).thenAnswer { call ->
            capture(call.getArgument<String>(0), call.getArgument<SqlParameterSource>(1))
            listOf<Any>()
        }
    }

    @AfterEach
    fun close() {
        analyzer.close()
    }

    @Test
    fun defaults_limit_visible_statuses_and_exact_notice_removes_only_the_default_status_filter() {
        animals.search(Search.empty(), page("createdAt", false))
        assertThat(calls.first().sql).contains("a.status IN ('NOTICE','PROTECT')")
        assertThat(calls.last().sql).endsWith("ORDER BY a.created_at DESC NULLS LAST,a.id ASC LIMIT :limit OFFSET :offset")
        assertThat(calls.last().parameters).containsEntry("limit", 20).containsEntry("offset", 0L)
        calls.clear()

        animals.search(search(null, null, "  공고-1  ", null), page("createdAt", false))
        assertThat(calls.first().sql).contains("a.apms_notice_no=:notice").doesNotContain("a.status IN")
        assertThat(calls.first().parameters).containsEntry("notice", "공고-1")
        calls.clear()

        animals.search(search(null, null, "공고-1", AnimalStatus.ADOPTED), page("createdAt", false))
        assertThat(calls.first().sql).contains("a.apms_notice_no=:notice", "a.status=:status")
        assertThat(calls.first().parameters).containsEntry("status", "ADOPTED")
    }

    @Test
    fun all_filters_combine_and_breed_address_wildcards_are_literal() {
        val from = LocalDate.of(2026, 1, 1)
        val to = from.plusDays(365)
        val request = Search(
            Species.DOG, " Mix%_! ", Gender.FEMALE, NeuterStatus.NO,
            AnimalStatus.PROTECT, 2, 9, " 서울% 강남_ ", "역삼!", "검은 말티즈", "N-1", 88L, from, to,
        )

        animals.search(request, page("age", true))

        val count = calls[1]
        assertThat(count.sql).contains(
            "a.apms_notice_no=:notice", "a.species=:species", "a.gender=:gender",
            "a.neuter_status=:neuter", "a.shelter_id=:shelter", "a.happen_date BETWEEN :intakeFrom AND :intakeTo",
            "a.status=:status", "a.birth_year<=:maxBirthYear", "a.birth_year>=:minBirthYear",
            "lower(s.address) LIKE :region0 ESCAPE '!'", "lower(s.address) LIKE :region1 ESCAPE '!'",
            "lower(s.address) LIKE :city0 ESCAPE '!'", "lower(a.breed) LIKE :breedPart ESCAPE '!'",
            "(ad.tokens || sd.tokens) @@ plainto_tsquery('simple',:keywordTokens)",
        )
        assertThat(count.parameters).containsEntry("species", "DOG").containsEntry("gender", "FEMALE")
            .containsEntry("neuter", "NO").containsEntry("shelter", 88L).containsEntry("intakeFrom", from)
            .containsEntry("intakeTo", to).containsEntry("maxBirthYear", LocalDate.now().year - 2)
            .containsEntry("minBirthYear", LocalDate.now().year - 9).containsEntry("region0", "%서울!%%")
            .containsEntry("region1", "%강남!_%").containsEntry("city0", "%역삼!!%")
            .containsEntry("breedPart", "%mix!%!_!!%").containsEntry("breedExact", "mix%_!")
        assertThat(calls.last().sql).contains("a.birth_year DESC NULLS LAST,a.id ASC")
    }

    @Test
    fun allowed_sort_aliases_preserve_both_directions_and_age_reversal() {
        val columns = mapOf(
            "createdAt" to "created_at", "created_at" to "created_at",
            "happenDate" to "happen_date", "happen_date" to "happen_date",
            "updatedAt" to "updated_at", "updated_at" to "updated_at",
            "noticeEndDate" to "notice_end_date", "notice_end_date" to "notice_end_date",
            "birthYear" to "birth_year", "birth_year" to "birth_year", "age" to "birth_year",
            "apmsDesertionNo" to "apms_desertion_no", "apms_desertion_no" to "apms_desertion_no",
            "favoriteCount" to "favorite_count", "favorite_count" to "favorite_count",
        )
        for (entry in columns.entries) {
            for (asc in listOf(true, false)) {
                animals.search(Search.empty(), page(entry.key, asc))
                val direction = if (if (entry.key == "age") !asc else asc) " ASC" else " DESC"
                assertThat(calls.last().sql).contains("ORDER BY a." + entry.value + direction + " NULLS LAST,a.id ASC")
            }
        }
    }

    @Test
    fun nori_keyword_uses_both_documents_and_relation_queries_keep_relation_evidence() {
        animals.search(search("검은색 말티즈", null, null, null), page("relevance", true))

        assertThat(calls).hasSize(3)
        assertThat(calls.first().sql).contains("a.search_dirty", "s.search_dirty", "a.status IN ('NOTICE','PROTECT')")
        val sql = calls.last().sql
        assertThat(sql).contains(
            "pawbridge_animal.animal_search_documents", "pawbridge_animal.shelter_search_documents",
            "(ad.tokens || sd.tokens) @@ plainto_tsquery('simple',:keywordTokens)", "UNION ALL SELECT resident.id",
            "ts_rank_cd(", "ORDER BY relevance DESC,a.created_at DESC NULLS LAST,a.id ASC",
        ).doesNotContain(" LIKE ")
        assertThat(calls.last().parameters["keywordTokens"] as String).contains("검", "말티즈").doesNotContain("색")
        calls.clear()

        animals.search(search("귀가 접힌", null, null, null), page("relevance", false))
        assertThat(calls.last().sql).contains("ad.relation_tokens @@ plainto_tsquery('simple',:relation)")
            .doesNotContain("UNION ALL SELECT resident.id")
        assertThat(calls.last().parameters).containsKey("relation")
    }

    @Test
    fun related_pending_projection_stops_before_count_and_content_but_plain_breed_does_not_require_projection() {
        `when`(jdbc.queryForObject(anyString(), any(SqlParameterSource::class.java), eq(Boolean::class.javaObjectType)))
            .thenReturn(true)

        assertThatThrownBy { animals.search(search("강아지", null, null, null), page("createdAt", false)) }
            .isInstanceOf(SearchProjectionPendingException::class.java)

        verify(jdbc, never()).queryForObject(anyString(), any(SqlParameterSource::class.java), eq(Long::class.javaObjectType))
        verify(jdbc, never()).query(anyString(), any(SqlParameterSource::class.java), any<RowMapper<Any>>())
        animals.search(search(null, "말티즈", null, null), page("relevance", false))
        assertThat(calls.last().sql).contains("lower(a.breed) LIKE :breedPart").doesNotContain("search_documents")
    }

    @Test
    fun empty_and_beyond_count_pages_keep_source_page_fields_without_content_query() {
        `when`(jdbc.queryForObject(anyString(), any(SqlParameterSource::class.java), eq(Long::class.javaObjectType)))
            .thenReturn(0L)

        val empty = animals.search(Search.empty(), page("createdAt", false))

        assertThat(empty).isEqualTo(Page(listOf(), 0, 20, 0, 0, true, true))
        verify(jdbc, never()).query(anyString(), any(SqlParameterSource::class.java), any<RowMapper<Any>>())
        `when`(jdbc.queryForObject(anyString(), any(SqlParameterSource::class.java), eq(Long::class.javaObjectType)))
            .thenReturn(1L)
        val beyond = animals.search(Search.empty(), PageRequest(499, 20, listOf(), true))
        assertThat(beyond.number()).isEqualTo(499)
        assertThat(beyond.totalElements()).isEqualTo(1)
        assertThat(beyond.last()).isTrue()
        assertThat(beyond.first()).isFalse()
        verify(jdbc, never()).query(anyString(), any(SqlParameterSource::class.java), any<RowMapper<Any>>())
    }

    @Test
    fun missing_detail_has_no_status_filter_and_raises_source_not_found() {
        assertThatThrownBy { animals.findById(72L) }.isInstanceOf(AnimalNotFoundException::class.java)

        assertThat(calls.first().sql)
            .contains("pawbridge_animal.animals", "pawbridge_animal.shelters", "WHERE a.id=:id")
            .doesNotContain("status IN", "search_documents")
        assertThat(calls.first().parameters).containsEntry("id", 72L)
    }

    @Test
    fun all_query_methods_are_read_only_repeatable_read_with_five_second_timeouts() {
        val source = mock(DataSource::class.java)
        val connection = mock(Connection::class.java)
        val statement = mock(PreparedStatement::class.java)
        `when`(source.connection).thenReturn(connection)
        `when`(connection.prepareStatement(anyString())).thenReturn(statement)
        `when`(statement.executeQuery()).thenThrow(SQLException("synthetic unavailable database"))
        val manager = mock(PlatformTransactionManager::class.java)
        `when`(manager.getTransaction(any())).thenReturn(SimpleTransactionStatus())
        val configured = JdbcAnimalDiscovery(source, manager, analyzer)

        assertThatThrownBy { configured.search(Search.empty(), page("createdAt", false)) }
            .isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy { configured.findByApmsDesertionNo("D-72") }.isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy { configured.findExpiringSoonAnimals(page("unknown", false)) }
            .isInstanceOf(DataAccessException::class.java)

        verify(manager, times(3)).getTransaction(
            argThat(
                ArgumentMatcher<TransactionDefinition> { definition ->
                    definition.isReadOnly &&
                        definition.isolationLevel == TransactionDefinition.ISOLATION_REPEATABLE_READ &&
                        definition.timeout == 5
                },
            ),
        )
        verify(statement, times(3)).setQueryTimeout(5)
    }

    @Test
    fun apms_number_binds_exact_value_and_reads_one_shelter_join_without_status_filter() {
        val row = mock(ResultSet::class.java)
        `when`(row.getLong("id")).thenReturn(72L)
        `when`(row.getLong("shelter_id")).thenReturn(8L)
        `when`(row.getString("species")).thenReturn("DOG")
        `when`(row.getString("gender")).thenReturn("UNKNOWN")
        `when`(row.getString("neuter_status")).thenReturn("UNKNOWN")
        `when`(row.getString("status")).thenReturn("ADOPTED")
        `when`(row.getString("api_source")).thenReturn("APMS_ANIMAL")
        `when`(row.getString("apms_desertion_no")).thenReturn(" D-72 ")
        `when`(jdbc.query(anyString(), any(SqlParameterSource::class.java), any<RowMapper<Any>>())).thenAnswer { call ->
            capture(call.getArgument<String>(0), call.getArgument<SqlParameterSource>(1))
            val mapper = call.getArgument<RowMapper<Details>>(2)
            listOf(mapper.mapRow(row, 0))
        }

        val detail = animals.findByApmsDesertionNo(" D-72 ")

        assertThat(detail.id).isEqualTo(72L)
        assertThat(detail.shelterId).isEqualTo(8L)
        assertThat(detail.status).isEqualTo(AnimalStatus.ADOPTED)
        assertThat(detail.apmsDesertionNo).isEqualTo(" D-72 ")
        assertThat(calls).hasSize(1)
        assertThat(calls.first().sql)
            .contains("JOIN pawbridge_animal.shelters", "WHERE a.apms_desertion_no=:number")
            .doesNotContain("status IN", "search_documents", "count(*)")
        assertThat(calls.first().parameters).containsOnly(entry("number", " D-72 "))
        verify(jdbc, never()).queryForObject(anyString(), any(SqlParameterSource::class.java), eq(Long::class.javaObjectType))
    }

    @Test
    fun missing_apms_and_database_failures_propagate_without_fallback_queries() {
        assertThatThrownBy { animals.findByApmsDesertionNo("missing") }.isInstanceOf(AnimalNotFoundException::class.java)
        val failure = DataAccessResourceFailureException("synthetic unavailable")
        `when`(jdbc.query(anyString(), any(SqlParameterSource::class.java), any<RowMapper<Any>>())).thenThrow(failure)

        assertThatThrownBy { animals.findByApmsDesertionNo("D-72") }.isSameAs(failure)

        verify(jdbc, times(2)).query(anyString(), any(SqlParameterSource::class.java), any<RowMapper<Any>>())
        verify(jdbc, never()).queryForObject(anyString(), any(SqlParameterSource::class.java), eq(Long::class.javaObjectType))
    }

    @Test
    fun expiring_soon_uses_protect_inclusive_jvm_dates_and_fixed_order_ignoring_single_requested_sort() {
        val before = LocalDate.now()
        animals.findExpiringSoonAnimals(PageRequest(2, 20, listOf(Order("unknown", false)), true))
        val after = LocalDate.now()

        assertThat(calls).hasSize(2)
        val count = calls.first()
        val content = calls.last()
        assertThat(count.sql).contains("a.status=:status", "a.notice_end_date BETWEEN :today AND :deadline")
            .doesNotContain("search_documents", "search_dirty")
        assertThat(count.parameters).containsEntry("status", "PROTECT")
        val today = count.parameters["today"] as LocalDate
        assertThat(today).isBetween(before, after)
        assertThat(count.parameters).containsEntry("deadline", today.plusDays(3))
        assertThat(content.sql).endsWith("ORDER BY a.notice_end_date ASC NULLS LAST,a.id ASC LIMIT :limit OFFSET :offset")
        assertThat(content.parameters).containsEntry("limit", 20).containsEntry("offset", 40L)
    }

    @Test
    fun expiring_empty_and_beyond_count_pages_do_not_read_content() {
        val totals = AtomicInteger()
        `when`(jdbc.queryForObject(anyString(), any(SqlParameterSource::class.java), eq(Long::class.javaObjectType)))
            .thenAnswer { call ->
                capture(call.getArgument<String>(0), call.getArgument<SqlParameterSource>(1))
                totals.getAndIncrement().toLong()
            }

        val empty = animals.findExpiringSoonAnimals(PageRequest(0, 20, listOf(), true))
        assertThat(empty).isEqualTo(Page(listOf(), 0, 20, 0, 0, true, true))
        val beyond = animals.findExpiringSoonAnimals(PageRequest(499, 20, listOf(), true))
        assertThat(beyond).isEqualTo(Page(listOf(), 499, 20, 1, 1, false, true))

        assertThat(calls).hasSize(2)
        verify(jdbc, never()).query(anyString(), any(SqlParameterSource::class.java), any<RowMapper<Any>>())
    }

    @Test
    fun expiring_page_bounds_and_multiple_sorts_fail_before_query_sql() {
        for (
            invalid in listOf(
                PageRequest(0, 20, listOf(), false),
                PageRequest(0, 101, listOf(), true),
                PageRequest(500, 20, listOf(), true),
                PageRequest(0, 20, listOf(Order("createdAt", true), Order("id", true)), true),
            )
        ) {
            assertThatThrownBy { animals.findExpiringSoonAnimals(invalid) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        verifyNoInteractions(jdbc)
    }

    private fun search(keyword: String?, breed: String?, notice: String?, status: AnimalStatus?): Search {
        return Search(null, breed, null, null, status, null, null, null, null, keyword, notice, null, null, null)
    }

    private fun page(property: String, ascending: Boolean): PageRequest {
        return PageRequest(0, 20, listOf(Order(property, ascending)), true)
    }

    private fun capture(sql: String, source: SqlParameterSource) {
        val parameters = LinkedHashMap<String, Any?>()
        for (key in source.parameterNames!!) {
            parameters[key] = source.getValue(key)
        }
        calls.add(Call(sql, parameters))
    }

    private data class Call(val sql: String, val parameters: Map<String, Any?>)
}
