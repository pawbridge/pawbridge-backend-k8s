package com.pawbridge.backend.animal.internal.shelter.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.shelter.application.ShelterNotFoundException
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.Discovery
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.Observation
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.Preview
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionOperations
import java.lang.reflect.Proxy
import java.sql.ResultSet
import java.sql.SQLException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.stream.Stream
import javax.sql.DataSource

class JdbcShelterDiscoveryReadTest {
    private val jdbc: NamedParameterJdbcOperations = mockk<NamedParameterJdbcOperations>()
    private val discovery: NamedParameterJdbcOperations = mockk<NamedParameterJdbcOperations>()
    private val shelters = JdbcShelterReads(
        jdbc,
        discovery,
        TransactionOperations.withoutTransaction(),
        TransactionOperations.withoutTransaction(),
        ObjectMapper(),
    )

    @Test
    fun `조건에 맞는 보호소가 있을 때__탐색하면__보호 입소 수를 재사용하고 페이지 전체에 한정된 미리보기 쿼리 한 번만 쓴다`() {
        // given
        val first = shelterRow(4, "센터4", 8)
        val second = shelterRow(9, "센터9", 3)

        every {
            discovery.queryForObject(any<String>(), any<MapSqlParameterSource>(), Long::class.javaObjectType)
        } returns 25L
        every { discovery.query(any<String>(), any<MapSqlParameterSource>(), any<RowMapper<Discovery>>()) } answers {
            val mapper = arg<RowMapper<Discovery>>(2)
            listOf(mapper.mapRow(first, 0)!!, mapper.mapRow(second, 1)!!)
        }
        every { discovery.query(any<String>(), any<MapSqlParameterSource>(), any<RowCallbackHandler>()) } answers {
            val callback = arg<RowCallbackHandler>(2)
            callback.processRow(previewRow(40, 4, TO))
            callback.processRow(previewRow(41, 4, TO.minusDays(1)))
            callback.processRow(previewRow(90, 9, TO.minusDays(2)))
        }

        // when
        val result = shelters.discover(" 100%_! ", " 서울 ", FROM, TO, 1, 12)

        // then
        val params = slot<MapSqlParameterSource>()
        assertThat(result.totalElements()).isEqualTo(25)
        assertThat(result.content().map { item -> item.id }).containsExactly(4L, 9L)
        assertThat(result.content().first().animals).containsExactly(
            Preview(40, null, "DOG", "UNKNOWN", null, null, TO),
            Preview(41, null, "DOG", "UNKNOWN", null, null, TO.minusDays(1)),
        )
        assertThat(result.content()[1].animals!!.map { preview -> preview.id }).containsExactly(90L)
        verify(exactly = 1) {
            discovery.queryForObject(
                match<String> { sql ->
                    sql.contains("COUNT(DISTINCT s.id)") && sql.contains("a.status = 'PROTECT'") &&
                        sql.contains("a.happen_date BETWEEN :from AND :to") &&
                        sql.contains("COALESCE(s.address, '') ILIKE :address ESCAPE '!'") &&
                        sql.contains("s.name ILIKE :keyword") && sql.contains("s.address ILIKE :keyword")
                },
                capture(params),
                Long::class.javaObjectType,
            )
        }
        assertThat(params.captured.getValue("keyword")).isEqualTo("%100!%!_!!%")
        assertThat(params.captured.getValue("address")).isEqualTo("%서울%")
        assertThat(params.captured.getValue("from")).isEqualTo(FROM)
        assertThat(params.captured.getValue("to")).isEqualTo(TO)
        assertThat(params.captured.getValue("offset")).isEqualTo(12L)
        assertThat(params.captured.getValue("ids")).isEqualTo(listOf(4L, 9L))
        verify(exactly = 1) {
            discovery.query(
                match<String> {
                    it.contains("ORDER BY protected_count DESC, s.name ASC, s.id ASC LIMIT :limit OFFSET :offset")
                },
                any<MapSqlParameterSource>(),
                any<RowMapper<Discovery>>(),
            )
        }
        verify(exactly = 1) {
            discovery.query(
                match<String> { sql ->
                    sql.contains("ROW_NUMBER() OVER (PARTITION BY shelter_id ORDER BY happen_date DESC, id ASC)") &&
                        sql.contains("FROM pawbridge_animal.animals") &&
                        sql.contains("WHERE rn <= 3 ORDER BY shelter_id, rn")
                },
                any<MapSqlParameterSource>(),
                any<RowCallbackHandler>(),
            )
        }
        confirmVerified(discovery, jdbc)
    }

    @Test
    fun `탐색 페이지가 비었을 때__탐색하면__미리보기를 조회하지 않고 총계만 유지한다`() {
        // given
        every {
            discovery.queryForObject(any<String>(), any<MapSqlParameterSource>(), Long::class.javaObjectType)
        } returns 25L
        every { discovery.query(any<String>(), any<MapSqlParameterSource>(), any<RowMapper<Discovery>>()) } returns listOf()

        // when
        val result = shelters.discover("", "", FROM, TO, 3, 12)

        // then
        assertThat(result.content()).isEmpty()
        assertThat(result.totalElements()).isEqualTo(25)
        verify(exactly = 0) {
            discovery.query(any<String>(), any<MapSqlParameterSource>(), any<RowCallbackHandler>())
        }
    }

    @ParameterizedTest
    @MethodSource("invalidDiscovery")
    fun `탐색 입력이 잘못됐을 때__탐색하면__SQL 전에 거부한다`(
        keyword: String,
        from: LocalDate?,
        to: LocalDate?,
        page: Int,
        size: Int,
    ) {
        // given
        // when
        val thrown = assertThatThrownBy { shelters.discover(keyword, "", from, to, page, size) }

        // then
        thrown.isInstanceOf(IllegalArgumentException::class.java)
        confirmVerified(discovery, jdbc)
    }

    @Test
    fun `허용된 마지막 탐색 결과와 366일 기간일 때__탐색하면__양끝 포함 경계를 유지한다`() {
        // given
        every {
            discovery.queryForObject(any<String>(), any<MapSqlParameterSource>(), Long::class.javaObjectType)
        } returns 0L
        every { discovery.query(any<String>(), any<MapSqlParameterSource>(), any<RowMapper<Discovery>>()) } returns listOf()

        // when
        val result = shelters.discover("", "", FROM, FROM.plusDays(365), 9999, 1)

        // then
        assertThat(result.content()).isEmpty()
    }

    @Test
    fun `관측 공백과 오프셋 타임스탬프가 있을 때__시계열을 읽으면__기록된 날짜만 원래 오프셋 그대로 돌려준다`() {
        // given
        val observed = OffsetDateTime.parse("2026-09-03T10:11:12.123456+09:00")
        val row = mockk<ResultSet>()

        every {
            discovery.queryForObject(any<String>(), any<MapSqlParameterSource>(), Boolean::class.javaObjectType)
        } returns true
        every { row.getObject("observation_date", LocalDate::class.java) } returns FROM.plusDays(2)
        every { row.getObject("observed_at", OffsetDateTime::class.java) } returns observed
        every { row.getLong("protected_count") } returns 8L
        every { discovery.query(any<String>(), any<MapSqlParameterSource>(), any<RowMapper<Observation>>()) } answers {
            listOf(arg<RowMapper<Observation>>(2).mapRow(row, 0)!!)
        }

        // when
        val series = shelters.observations(4, FROM, TO)

        // then
        assertThat(series).containsExactly(Observation(FROM.plusDays(2), observed, 8))
        verify(exactly = 1) {
            discovery.queryForObject(
                match<String> { it.contains("EXISTS(SELECT 1 FROM pawbridge_animal.shelters") },
                match<MapSqlParameterSource> { p -> p.getValue("id") == 4L },
                Boolean::class.javaObjectType,
            )
        }
        verify(exactly = 1) {
            discovery.query(
                match<String> { sql ->
                    sql.contains("FROM pawbridge_animal.shelter_daily_observations") &&
                        sql.contains("observation_date BETWEEN :from AND :to ORDER BY observation_date") &&
                        !sql.contains("generate_series")
                },
                any<MapSqlParameterSource>(),
                any<RowMapper<Observation>>(),
            )
        }
    }

    @Test
    fun `보호소가 없거나 기록된 관측이 없을 때__읽으면__404 또는 빈 시계열을 유지한다`() {
        // given
        every {
            discovery.queryForObject(any<String>(), any<MapSqlParameterSource>(), Boolean::class.javaObjectType)
        } returns false

        // when
        val thrown = assertThatThrownBy { shelters.observations(4, FROM, TO) }

        // then
        thrown.isInstanceOf(ShelterNotFoundException::class.java)
        verify(exactly = 0) { discovery.query(any<String>(), any<MapSqlParameterSource>(), any<RowMapper<Observation>>()) }

        // given
        every {
            discovery.queryForObject(any<String>(), any<MapSqlParameterSource>(), Boolean::class.javaObjectType)
        } returns true
        every { discovery.query(any<String>(), any<MapSqlParameterSource>(), any<RowMapper<Observation>>()) } returns listOf()

        // when
        val series = shelters.observations(4, FROM, TO)

        // then
        assertThat(series).isEmpty()
    }

    @Test
    fun `공개 생성자일 때__일반 흐름과 탐색 흐름을 읽으면__서로 다른 트랜잭션 설정을 유지한다`() {
        // given
        val dataSource = mockk<DataSource>()
        val manager = mockk<PlatformTransactionManager>()

        every { manager.getTransaction(any()) } answers { SimpleTransactionStatus() }
        justRun { manager.rollback(any()) }
        every { dataSource.connection } throws SQLException("synthetic unreachable data source")
        val actual = JdbcShelterReads(dataSource, manager, ObjectMapper())

        // when
        val count = assertThatThrownBy { actual.count() }
        val invalid = assertThatThrownBy { actual.discover("", "", FROM, TO, 0, 25) }

        // then
        val definitions = mutableListOf<TransactionDefinition>()
        count.isInstanceOf(DataAccessException::class.java)
        invalid.isInstanceOf(IllegalArgumentException::class.java)
        verify(exactly = 2) { manager.getTransaction(capture(definitions)) }
        assertThat(definitions[0].isReadOnly).isTrue()
        assertThat(definitions[0].timeout).isEqualTo(TransactionDefinition.TIMEOUT_DEFAULT)
        assertThat(definitions[0].isolationLevel).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT)
        assertThat(definitions[1].isReadOnly).isTrue()
        assertThat(definitions[1].timeout).isEqualTo(5)
        assertThat(definitions[1].isolationLevel).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ)
        verify(exactly = 2) { manager.rollback(any()) }
    }

    private fun shelterRow(id: Long, name: String, protectedCount: Long): ResultSet {
        return row(
            "id" to id,
            "care_reg_no" to "reg-" + id,
            "name" to name,
            "address" to "서울",
            "phone" to "02",
            "protected_count" to protectedCount,
        )
    }

    private fun previewRow(id: Long, shelterId: Long, date: LocalDate): ResultSet {
        return row(
            "id" to id,
            "shelter_id" to shelterId,
            "species" to "DOG",
            "gender" to "UNKNOWN",
            "happen_date" to date,
        )
    }

    /**
     * A single-row in-memory ResultSet (a data fake, not a mock): `get*` with a column name reads that cell,
     * and every other method returns the type's zero value.
     */
    private fun row(vararg cells: Pair<String, Any?>): ResultSet {
        val values = cells.toMap()
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
                    "getLong" -> if (value == null) 0L else (value as Number).toLong()
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

    companion object {
        private val FROM: LocalDate = LocalDate.of(2026, 9, 1)
        private val TO: LocalDate = LocalDate.of(2026, 9, 30)

        @JvmStatic
        fun invalidDiscovery(): Stream<Arguments> = Stream.of(
            Arguments.of("", FROM, TO, -1, 12),
            Arguments.of("", FROM, TO, 0, 0),
            Arguments.of("", FROM, TO, 0, 25),
            Arguments.of("", FROM, TO, 416, 24),
            Arguments.of("x".repeat(101), FROM, TO, 0, 12),
            Arguments.of("", null, TO, 0, 12),
            Arguments.of("", TO, FROM, 0, 12),
            Arguments.of("", FROM, FROM.plusDays(366), 0, 12),
        )
    }
}
