package com.pawbridge.backend.animal.internal.shelter.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.shelter.application.ShelterNotFoundException
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.Discovery
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.Observation
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.Preview
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
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionOperations
import java.sql.ResultSet
import java.sql.SQLException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.stream.Stream
import javax.sql.DataSource

class JdbcShelterDiscoveryReadTest {
    private val jdbc: NamedParameterJdbcOperations = mock(NamedParameterJdbcOperations::class.java)
    private val discovery: NamedParameterJdbcOperations = mock(NamedParameterJdbcOperations::class.java)
    private val shelters = JdbcShelterReads(
        jdbc,
        discovery,
        TransactionOperations.withoutTransaction(),
        TransactionOperations.withoutTransaction(),
        ObjectMapper(),
    )

    @Test
    fun givenMatchingShelters__whenDiscovering__thenReuseProtectIntakeCountAndOneBoundedPreviewQueryForWholePage() {
        `when`(discovery.queryForObject(anyString(), any(MapSqlParameterSource::class.java), eq(Long::class.javaObjectType)))
            .thenReturn(25L)
        val first = shelterRow(4, "센터4", 8)
        val second = shelterRow(9, "센터9", 3)
        `when`(discovery.query(anyString(), any(MapSqlParameterSource::class.java), any<RowMapper<Discovery>>()))
            .thenAnswer { call ->
                val mapper = call.getArgument<RowMapper<Discovery>>(2)
                listOf(mapper.mapRow(first, 0), mapper.mapRow(second, 1))
            }
        doAnswer { call ->
            val callback = call.getArgument<RowCallbackHandler>(2)
            callback.processRow(previewRow(40, 4, TO))
            callback.processRow(previewRow(41, 4, TO.minusDays(1)))
            callback.processRow(previewRow(90, 9, TO.minusDays(2)))
            null
        }.`when`(discovery).query(anyString(), any(MapSqlParameterSource::class.java), any(RowCallbackHandler::class.java))

        val result = shelters.discover(" 100%_! ", " 서울 ", FROM, TO, 1, 12)

        assertThat(result.totalElements()).isEqualTo(25)
        assertThat(result.content().map { item -> item.id }).containsExactly(4L, 9L)
        assertThat(result.content().first().animals).containsExactly(
            Preview(40, null, "DOG", "UNKNOWN", null, null, TO),
            Preview(41, null, "DOG", "UNKNOWN", null, null, TO.minusDays(1)),
        )
        assertThat(result.content()[1].animals!!.map { preview -> preview.id }).containsExactly(90L)
        val params = ArgumentCaptor.forClass(MapSqlParameterSource::class.java)
        verify(discovery).queryForObject(
            argThat(
                ArgumentMatcher<String> { sql ->
                    sql.contains("COUNT(DISTINCT s.id)") && sql.contains("a.status = 'PROTECT'") &&
                        sql.contains("a.happen_date BETWEEN :from AND :to") &&
                        sql.contains("COALESCE(s.address, '') ILIKE :address ESCAPE '!'") &&
                        sql.contains("s.name ILIKE :keyword") && sql.contains("s.address ILIKE :keyword")
                },
            ),
            params.capture(),
            eq(Long::class.javaObjectType),
        )
        assertThat(params.value.getValue("keyword")).isEqualTo("%100!%!_!!%")
        assertThat(params.value.getValue("address")).isEqualTo("%서울%")
        assertThat(params.value.getValue("from")).isEqualTo(FROM)
        assertThat(params.value.getValue("to")).isEqualTo(TO)
        assertThat(params.value.getValue("offset")).isEqualTo(12L)
        assertThat(params.value.getValue("ids")).isEqualTo(listOf(4L, 9L))
        verify(discovery).query(
            contains("ORDER BY protected_count DESC, s.name ASC, s.id ASC LIMIT :limit OFFSET :offset"),
            any(MapSqlParameterSource::class.java),
            any<RowMapper<Discovery>>(),
        )
        verify(discovery).query(
            argThat(
                ArgumentMatcher<String> { sql ->
                    sql.contains("ROW_NUMBER() OVER (PARTITION BY shelter_id ORDER BY happen_date DESC, id ASC)") &&
                        sql.contains("FROM pawbridge_animal.animals") &&
                        sql.contains("WHERE rn <= 3 ORDER BY shelter_id, rn")
                },
            ),
            any(MapSqlParameterSource::class.java),
            any(RowCallbackHandler::class.java),
        )
        verifyNoMoreInteractions(discovery)
        verifyNoInteractions(jdbc)
    }

    @Test
    fun givenEmptyDiscoveryPage__whenDiscovering__thenKeepTotalWithoutQueryingPreviews() {
        `when`(discovery.queryForObject(anyString(), any(MapSqlParameterSource::class.java), eq(Long::class.javaObjectType)))
            .thenReturn(25L)
        `when`(discovery.query(anyString(), any(MapSqlParameterSource::class.java), any<RowMapper<Discovery>>()))
            .thenReturn(listOf())

        val result = shelters.discover("", "", FROM, TO, 3, 12)

        assertThat(result.content()).isEmpty()
        assertThat(result.totalElements()).isEqualTo(25)
        verify(discovery, never()).query(anyString(), any(MapSqlParameterSource::class.java), any(RowCallbackHandler::class.java))
    }

    @ParameterizedTest
    @MethodSource("invalidDiscovery")
    fun givenInvalidDiscoveryInput__whenDiscovering__thenRejectBeforeSql(
        keyword: String,
        from: LocalDate?,
        to: LocalDate?,
        page: Int,
        size: Int,
    ) {
        assertThatThrownBy { shelters.discover(keyword, "", from, to, page, size) }
            .isInstanceOf(IllegalArgumentException::class.java)

        verifyNoInteractions(discovery, jdbc)
    }

    @Test
    fun givenLastAllowedDiscoveryResultAnd366DayPeriod__whenDiscovering__thenRetainInclusiveBounds() {
        `when`(discovery.queryForObject(anyString(), any(MapSqlParameterSource::class.java), eq(Long::class.javaObjectType)))
            .thenReturn(0L)
        `when`(discovery.query(anyString(), any(MapSqlParameterSource::class.java), any<RowMapper<Discovery>>()))
            .thenReturn(listOf())

        assertThat(shelters.discover("", "", FROM, FROM.plusDays(365), 9999, 1).content()).isEmpty()
    }

    @Test
    fun givenObservationGapsAndOffsetTimestamp__whenReadingSeries__thenReturnOnlyRecordedDatesWithOriginalOffset() {
        `when`(discovery.queryForObject(anyString(), any(MapSqlParameterSource::class.java), eq(Boolean::class.javaObjectType)))
            .thenReturn(true)
        val observed = OffsetDateTime.parse("2026-09-03T10:11:12.123456+09:00")
        val row = mock(ResultSet::class.java)
        `when`(row.getObject("observation_date", LocalDate::class.java)).thenReturn(FROM.plusDays(2))
        `when`(row.getObject("observed_at", OffsetDateTime::class.java)).thenReturn(observed)
        `when`(row.getLong("protected_count")).thenReturn(8L)
        `when`(discovery.query(anyString(), any(MapSqlParameterSource::class.java), any<RowMapper<Observation>>()))
            .thenAnswer { call -> listOf(call.getArgument<RowMapper<Observation>>(2).mapRow(row, 0)) }

        assertThat(shelters.observations(4, FROM, TO)).containsExactly(Observation(FROM.plusDays(2), observed, 8))

        verify(discovery).queryForObject(
            contains("EXISTS(SELECT 1 FROM pawbridge_animal.shelters"),
            argThat(ArgumentMatcher<MapSqlParameterSource> { p -> p.getValue("id") == 4L }),
            eq(Boolean::class.javaObjectType),
        )
        verify(discovery).query(
            argThat(
                ArgumentMatcher<String> { sql ->
                    sql.contains("FROM pawbridge_animal.shelter_daily_observations") &&
                        sql.contains("observation_date BETWEEN :from AND :to ORDER BY observation_date") &&
                        !sql.contains("generate_series")
                },
            ),
            any(MapSqlParameterSource::class.java),
            any<RowMapper<Observation>>(),
        )
    }

    @Test
    fun givenMissingShelterOrNoRecordedObservations__whenReading__thenKeep404OrEmptySeries() {
        `when`(discovery.queryForObject(anyString(), any(MapSqlParameterSource::class.java), eq(Boolean::class.javaObjectType)))
            .thenReturn(false)

        assertThatThrownBy { shelters.observations(4, FROM, TO) }.isInstanceOf(ShelterNotFoundException::class.java)

        verify(discovery, never()).query(anyString(), any(MapSqlParameterSource::class.java), any<RowMapper<Observation>>())
        `when`(discovery.queryForObject(anyString(), any(MapSqlParameterSource::class.java), eq(Boolean::class.javaObjectType)))
            .thenReturn(true)
        `when`(discovery.query(anyString(), any(MapSqlParameterSource::class.java), any<RowMapper<Observation>>()))
            .thenReturn(listOf())
        assertThat(shelters.observations(4, FROM, TO)).isEmpty()
    }

    @Test
    fun givenPublicConstructor__whenReadingRegularAndDiscoveryFlows__thenPreserveTheirDifferentTransactionSettings() {
        val dataSource = mock(DataSource::class.java)
        val manager = mock(PlatformTransactionManager::class.java)
        `when`(manager.getTransaction(any())).thenAnswer { SimpleTransactionStatus() }
        `when`(dataSource.connection).thenThrow(SQLException("synthetic unreachable data source"))
        val actual = JdbcShelterReads(dataSource, manager, ObjectMapper())

        assertThatThrownBy { actual.count() }.isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy { actual.discover("", "", FROM, TO, 0, 25) }.isInstanceOf(IllegalArgumentException::class.java)

        val definitions = ArgumentCaptor.forClass(TransactionDefinition::class.java)
        verify(manager, times(2)).getTransaction(definitions.capture())
        assertThat(definitions.allValues[0].isReadOnly).isTrue()
        assertThat(definitions.allValues[0].timeout).isEqualTo(TransactionDefinition.TIMEOUT_DEFAULT)
        assertThat(definitions.allValues[0].isolationLevel).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT)
        assertThat(definitions.allValues[1].isReadOnly).isTrue()
        assertThat(definitions.allValues[1].timeout).isEqualTo(5)
        assertThat(definitions.allValues[1].isolationLevel).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ)
        verify(manager, times(2)).rollback(any())
    }

    private fun shelterRow(id: Long, name: String, protectedCount: Long): ResultSet {
        val row = mock(ResultSet::class.java)
        `when`(row.getLong("id")).thenReturn(id)
        `when`(row.getString("care_reg_no")).thenReturn("reg-" + id)
        `when`(row.getString("name")).thenReturn(name)
        `when`(row.getString("address")).thenReturn("서울")
        `when`(row.getString("phone")).thenReturn("02")
        `when`(row.getLong("protected_count")).thenReturn(protectedCount)
        return row
    }

    private fun previewRow(id: Long, shelterId: Long, date: LocalDate): ResultSet {
        val row = mock(ResultSet::class.java)
        `when`(row.getLong("id")).thenReturn(id)
        `when`(row.getLong("shelter_id")).thenReturn(shelterId)
        `when`(row.getString("species")).thenReturn("DOG")
        `when`(row.getString("gender")).thenReturn("UNKNOWN")
        `when`(row.getObject("happen_date", LocalDate::class.java)).thenReturn(date)
        return row
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
