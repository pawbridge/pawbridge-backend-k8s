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
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations
import org.springframework.jdbc.core.namedparam.SqlParameterSource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionOperations
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

class JdbcAnimalDiscoverySqlTest {
    private lateinit var analyzer: KoreanSearchAnalyzer
    private val jdbc = mockk<NamedParameterJdbcOperations>()
    private lateinit var animals: JdbcAnimalDiscovery
    private val calls = ArrayList<Call>()

    @BeforeEach
    fun prepare() {
        analyzer = KoreanSearchAnalyzer()
        animals = JdbcAnimalDiscovery(jdbc, TransactionOperations.withoutTransaction(), analyzer)
    }

    @AfterEach
    fun close() {
        analyzer.close()
    }

    @Test
    fun `기본 검색과 공고번호 검색일 때__검색하면__기본 노출 상태만 제한하고 정확한 공고번호는 기본 상태 필터만 뺀다`() {
        // given
        every { jdbc.queryForObject(any<String>(), any<SqlParameterSource>(), Long::class.javaObjectType) } answers {
            record(firstArg(), secondArg())
            101L
        }
        every { jdbc.query(any<String>(), any<SqlParameterSource>(), any<RowMapper<Any>>()) } answers {
            record(firstArg(), secondArg())
            listOf<Any>()
        }

        // when
        animals.search(Search.empty(), page("createdAt", false))

        // then
        assertThat(calls.first().sql).contains("a.status IN ('NOTICE','PROTECT')")
        assertThat(calls.last().sql).endsWith("ORDER BY a.created_at DESC NULLS LAST,a.id ASC LIMIT :limit OFFSET :offset")
        assertThat(calls.last().parameters).containsEntry("limit", 20).containsEntry("offset", 0L)
        calls.clear()

        // when
        animals.search(search(null, null, "  공고-1  ", null), page("createdAt", false))

        // then
        assertThat(calls.first().sql).contains("a.apms_notice_no=:notice").doesNotContain("a.status IN")
        assertThat(calls.first().parameters).containsEntry("notice", "공고-1")
        calls.clear()

        // when
        animals.search(search(null, null, "공고-1", AnimalStatus.ADOPTED), page("createdAt", false))

        // then
        assertThat(calls.first().sql).contains("a.apms_notice_no=:notice", "a.status=:status")
        assertThat(calls.first().parameters).containsEntry("status", "ADOPTED")
    }

    @Test
    fun `모든 필터가 함께 주어졌을 때__검색하면__필터를 모두 결합하고 품종·주소 와일드카드는 문자 그대로 다룬다`() {
        // given
        val from = LocalDate.of(2026, 1, 1)
        val to = from.plusDays(365)
        val request = Search(
            Species.DOG, " Mix%_! ", Gender.FEMALE, NeuterStatus.NO,
            AnimalStatus.PROTECT, 2, 9, " 서울% 강남_ ", "역삼!", "검은 말티즈", "N-1", 88L, from, to,
        )

        every { jdbc.queryForObject(any<String>(), any<SqlParameterSource>(), Boolean::class.javaObjectType) } answers {
            record(firstArg(), secondArg())
            false
        }
        every { jdbc.queryForObject(any<String>(), any<SqlParameterSource>(), Long::class.javaObjectType) } answers {
            record(firstArg(), secondArg())
            101L
        }
        every { jdbc.query(any<String>(), any<SqlParameterSource>(), any<RowMapper<Any>>()) } answers {
            record(firstArg(), secondArg())
            listOf<Any>()
        }

        // when
        animals.search(request, page("age", true))

        // then
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
    fun `허용된 정렬 별칭일 때__검색하면__두 방향과 나이 반전을 그대로 유지한다`() {
        // given
        val columns = mapOf(
            "createdAt" to "created_at", "created_at" to "created_at",
            "happenDate" to "happen_date", "happen_date" to "happen_date",
            "updatedAt" to "updated_at", "updated_at" to "updated_at",
            "noticeEndDate" to "notice_end_date", "notice_end_date" to "notice_end_date",
            "birthYear" to "birth_year", "birth_year" to "birth_year", "age" to "birth_year",
            "apmsDesertionNo" to "apms_desertion_no", "apms_desertion_no" to "apms_desertion_no",
            "favoriteCount" to "favorite_count", "favorite_count" to "favorite_count",
        )

        every { jdbc.queryForObject(any<String>(), any<SqlParameterSource>(), Long::class.javaObjectType) } answers {
            record(firstArg(), secondArg())
            101L
        }
        every { jdbc.query(any<String>(), any<SqlParameterSource>(), any<RowMapper<Any>>()) } answers {
            record(firstArg(), secondArg())
            listOf<Any>()
        }

        for (entry in columns.entries) {
            for (asc in listOf(true, false)) {
                // when
                animals.search(Search.empty(), page(entry.key, asc))

                // then
                val direction = if (if (entry.key == "age") !asc else asc) " ASC" else " DESC"
                assertThat(calls.last().sql).contains("ORDER BY a." + entry.value + direction + " NULLS LAST,a.id ASC")
            }
        }
    }

    @Test
    fun `노리 키워드와 관계 검색어일 때__검색하면__두 문서를 함께 쓰고 관계 질의는 관계 근거를 유지한다`() {
        // given
        every { jdbc.queryForObject(any<String>(), any<SqlParameterSource>(), Boolean::class.javaObjectType) } answers {
            record(firstArg(), secondArg())
            false
        }
        every { jdbc.queryForObject(any<String>(), any<SqlParameterSource>(), Long::class.javaObjectType) } answers {
            record(firstArg(), secondArg())
            101L
        }
        every { jdbc.query(any<String>(), any<SqlParameterSource>(), any<RowMapper<Any>>()) } answers {
            record(firstArg(), secondArg())
            listOf<Any>()
        }

        // when
        animals.search(search("검은색 말티즈", null, null, null), page("relevance", true))

        // then
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

        // when
        animals.search(search("귀가 접힌", null, null, null), page("relevance", false))

        // then
        assertThat(calls.last().sql).contains("ad.relation_tokens @@ plainto_tsquery('simple',:relation)")
            .doesNotContain("UNION ALL SELECT resident.id")
        assertThat(calls.last().parameters).containsKey("relation")
    }

    @Test
    fun `연관 투영이 대기 중일 때__키워드로 검색하면__개수·본문 조회 전에 멈추고 단순 품종 검색은 투영을 요구하지 않는다`() {
        // given
        every { jdbc.queryForObject(any<String>(), any<SqlParameterSource>(), Boolean::class.javaObjectType) } returns true
        every { jdbc.queryForObject(any<String>(), any<SqlParameterSource>(), Long::class.javaObjectType) } answers {
            record(firstArg(), secondArg())
            101L
        }
        every { jdbc.query(any<String>(), any<SqlParameterSource>(), any<RowMapper<Any>>()) } answers {
            record(firstArg(), secondArg())
            listOf<Any>()
        }

        // when
        val pending = assertThatThrownBy {
            animals.search(search("강아지", null, null, null), page("createdAt", false))
        }

        // then
        pending.isInstanceOf(SearchProjectionPendingException::class.java)
        verify(exactly = 0) {
            jdbc.queryForObject(any<String>(), any<SqlParameterSource>(), Long::class.javaObjectType)
        }
        verify(exactly = 0) { jdbc.query(any<String>(), any<SqlParameterSource>(), any<RowMapper<Any>>()) }

        // when
        animals.search(search(null, "말티즈", null, null), page("relevance", false))

        // then
        assertThat(calls.last().sql).contains("lower(a.breed) LIKE :breedPart").doesNotContain("search_documents")
    }

    @Test
    fun `개수가 0이거나 범위를 넘는 페이지일 때__검색하면__본문 조회 없이 원본의 페이지 필드를 유지한다`() {
        // given
        every {
            jdbc.queryForObject(any<String>(), any<SqlParameterSource>(), Long::class.javaObjectType)
        } returns 0L andThen 1L

        // when
        val empty = animals.search(Search.empty(), page("createdAt", false))

        // then
        assertThat(empty).isEqualTo(Page(listOf(), 0, 20, 0, 0, true, true))
        verify(exactly = 0) { jdbc.query(any<String>(), any<SqlParameterSource>(), any<RowMapper<Any>>()) }

        // when
        val beyond = animals.search(Search.empty(), PageRequest(499, 20, listOf(), true))

        // then
        assertThat(beyond.number()).isEqualTo(499)
        assertThat(beyond.totalElements()).isEqualTo(1)
        assertThat(beyond.last()).isTrue()
        assertThat(beyond.first()).isFalse()
        verify(exactly = 0) { jdbc.query(any<String>(), any<SqlParameterSource>(), any<RowMapper<Any>>()) }
    }

    @Test
    fun `없는 동물 ID일 때__상세를 조회하면__상태 필터 없이 조회하고 원본의 not found를 던진다`() {
        // given
        every { jdbc.query(any<String>(), any<SqlParameterSource>(), any<RowMapper<Any>>()) } answers {
            record(firstArg(), secondArg())
            listOf<Any>()
        }

        // when
        val thrown = assertThatThrownBy { animals.findById(72L) }

        // then
        thrown.isInstanceOf(AnimalNotFoundException::class.java)
        assertThat(calls.first().sql)
            .contains("pawbridge_animal.animals", "pawbridge_animal.shelters", "WHERE a.id=:id")
            .doesNotContain("status IN", "search_documents")
        assertThat(calls.first().parameters).containsEntry("id", 72L)
    }

    @Test
    fun `DB 질의가 실패하는 상황일 때__모든 조회 메서드를 호출하면__읽기 전용 REPEATABLE_READ와 5초 제한을 쓰고 DataAccessException을 던진다`() {
        // given
        val source = mockk<DataSource>()
        val connection = mockk<Connection>()
        val statement = mockk<PreparedStatement>()
        val manager = mockk<PlatformTransactionManager>()

        every { source.connection } returns connection
        every { connection.prepareStatement(any<String>()) } returns statement
        every { statement.executeQuery() } throws SQLException("synthetic unavailable database")
        every { manager.getTransaction(any()) } returns SimpleTransactionStatus()
        justRun { manager.rollback(any()) }
        justRun { statement.queryTimeout = any() }
        justRun { statement.setString(any(), any()) }
        justRun { statement.setObject(any(), any()) }
        justRun { statement.close() }
        justRun { connection.close() }

        val configured = JdbcAnimalDiscovery(source, manager, analyzer)

        // when
        val search = assertThatThrownBy { configured.search(Search.empty(), page("createdAt", false)) }
        val apms = assertThatThrownBy { configured.findByApmsDesertionNo("D-72") }
        val expiring = assertThatThrownBy { configured.findExpiringSoonAnimals(page("unknown", false)) }

        // then
        search.isInstanceOf(DataAccessException::class.java)
        apms.isInstanceOf(DataAccessException::class.java)
        expiring.isInstanceOf(DataAccessException::class.java)
        verify(exactly = 3) {
            manager.getTransaction(
                match<TransactionDefinition> { definition ->
                    definition.isReadOnly &&
                        definition.isolationLevel == TransactionDefinition.ISOLATION_REPEATABLE_READ &&
                        definition.timeout == 5
                },
            )
        }
        verify(exactly = 3) { statement.queryTimeout = 5 }
    }

    @Test
    fun `보호소가 붙은 한 행일 때__APMS 번호로 조회하면__정확한 값을 바인딩하고 상태 필터 없이 보호소 조인 하나만 읽는다`() {
        // given
        val row = row(
            mapOf(
                "id" to 72L,
                "shelter_id" to 8L,
                "species" to "DOG",
                "gender" to "UNKNOWN",
                "neuter_status" to "UNKNOWN",
                "status" to "ADOPTED",
                "api_source" to "APMS_ANIMAL",
                "apms_desertion_no" to " D-72 ",
            ),
        )

        every { jdbc.query(any<String>(), any<SqlParameterSource>(), any<RowMapper<Details>>()) } answers {
            record(firstArg(), secondArg())
            val mapper = thirdArg<RowMapper<Details>>()
            listOf(mapper.mapRow(row, 0))
        }

        // when
        val detail = animals.findByApmsDesertionNo(" D-72 ")

        // then
        assertThat(detail.id).isEqualTo(72L)
        assertThat(detail.shelterId).isEqualTo(8L)
        assertThat(detail.status).isEqualTo(AnimalStatus.ADOPTED)
        assertThat(detail.apmsDesertionNo).isEqualTo(" D-72 ")
        assertThat(calls).hasSize(1)
        assertThat(calls.first().sql)
            .contains("JOIN pawbridge_animal.shelters", "WHERE a.apms_desertion_no=:number")
            .doesNotContain("status IN", "search_documents", "count(*)")
        assertThat(calls.first().parameters).containsOnly(entry("number", " D-72 "))
        verify(exactly = 0) {
            jdbc.queryForObject(any<String>(), any<SqlParameterSource>(), Long::class.javaObjectType)
        }
    }

    @Test
    fun `없는 APMS 번호와 DB 장애일 때__APMS 번호로 조회하면__대체 조회 없이 not found와 장애를 그대로 전파한다`() {
        // given
        val failure = DataAccessResourceFailureException("synthetic unavailable")

        every {
            jdbc.query(any<String>(), any<SqlParameterSource>(), any<RowMapper<Any>>())
        } returns listOf<Any>() andThenThrows failure

        // when
        val missing = assertThatThrownBy { animals.findByApmsDesertionNo("missing") }
        val unavailable = assertThatThrownBy { animals.findByApmsDesertionNo("D-72") }

        // then
        missing.isInstanceOf(AnimalNotFoundException::class.java)
        unavailable.isSameAs(failure)
        verify(exactly = 2) { jdbc.query(any<String>(), any<SqlParameterSource>(), any<RowMapper<Any>>()) }
        verify(exactly = 0) {
            jdbc.queryForObject(any<String>(), any<SqlParameterSource>(), Long::class.javaObjectType)
        }
    }

    @Test
    fun `보호 상태 동물일 때__곧 만료되는 동물을 조회하면__PROTECT와 JVM 날짜 범위와 고정 정렬을 쓰고 요청된 단일 정렬은 무시한다`() {
        // given
        every { jdbc.queryForObject(any<String>(), any<SqlParameterSource>(), Long::class.javaObjectType) } answers {
            record(firstArg(), secondArg())
            101L
        }
        every { jdbc.query(any<String>(), any<SqlParameterSource>(), any<RowMapper<Any>>()) } answers {
            record(firstArg(), secondArg())
            listOf<Any>()
        }

        // when
        val before = LocalDate.now()
        animals.findExpiringSoonAnimals(PageRequest(2, 20, listOf(Order("unknown", false)), true))
        val after = LocalDate.now()

        // then
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
    fun `개수가 0이거나 범위를 넘는 페이지일 때__곧 만료되는 동물을 조회하면__본문을 읽지 않는다`() {
        // given
        val totals = AtomicInteger()

        every { jdbc.queryForObject(any<String>(), any<SqlParameterSource>(), Long::class.javaObjectType) } answers {
            record(firstArg(), secondArg())
            totals.getAndIncrement().toLong()
        }

        // when
        val empty = animals.findExpiringSoonAnimals(PageRequest(0, 20, listOf(), true))
        val beyond = animals.findExpiringSoonAnimals(PageRequest(499, 20, listOf(), true))

        // then
        assertThat(empty).isEqualTo(Page(listOf(), 0, 20, 0, 0, true, true))
        assertThat(beyond).isEqualTo(Page(listOf(), 499, 20, 1, 1, false, true))
        assertThat(calls).hasSize(2)
        verify(exactly = 0) { jdbc.query(any<String>(), any<SqlParameterSource>(), any<RowMapper<Any>>()) }
    }

    @Test
    fun `페이지 범위를 벗어나거나 정렬이 여러 개일 때__곧 만료되는 동물을 조회하면__질의 SQL 전에 실패한다`() {
        // given
        val invalidPages = listOf(
            PageRequest(0, 20, listOf(), false),
            PageRequest(0, 101, listOf(), true),
            PageRequest(500, 20, listOf(), true),
            PageRequest(0, 20, listOf(Order("createdAt", true), Order("id", true)), true),
        )

        for (invalid in invalidPages) {
            // when
            val thrown = assertThatThrownBy { animals.findExpiringSoonAnimals(invalid) }

            // then
            thrown.isInstanceOf(IllegalArgumentException::class.java)
        }
        confirmVerified(jdbc)
    }

    private fun search(keyword: String?, breed: String?, notice: String?, status: AnimalStatus?): Search {
        return Search(null, breed, null, null, status, null, null, null, null, keyword, notice, null, null, null)
    }

    private fun page(property: String, ascending: Boolean): PageRequest {
        return PageRequest(0, 20, listOf(Order(property, ascending)), true)
    }

    private fun record(sql: String, source: SqlParameterSource) {
        val parameters = LinkedHashMap<String, Any?>()
        for (key in source.parameterNames!!) {
            parameters[key] = source.getValue(key)
        }
        calls.add(Call(sql, parameters))
    }

    /**
     * A hand-written in-memory ResultSet over one row (a data fake, not a mock): `get*` with a column name reads the
     * map, and every other method returns the type's zero value.
     */
    private fun row(values: Map<String, Any?>): ResultSet {
        return Proxy.newProxyInstance(
            ResultSet::class.java.classLoader,
            arrayOf(ResultSet::class.java),
        ) { proxy, method, args ->
            val name = method.name
            if (method.declaringClass == Any::class.java) {
                return@newProxyInstance when (name) {
                    "equals" -> proxy === args[0]
                    "hashCode" -> System.identityHashCode(proxy)
                    else -> "InMemoryResultSet"
                }
            }
            if (name.startsWith("get") && args != null && args[0] is String) {
                val value = values[args[0] as String]
                return@newProxyInstance when (name) {
                    "getLong" -> (value as Long?) ?: 0L
                    else -> value
                }
            }
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                else -> null
            }
        } as ResultSet
    }

    private data class Call(val sql: String, val parameters: Map<String, Any?>)
}
