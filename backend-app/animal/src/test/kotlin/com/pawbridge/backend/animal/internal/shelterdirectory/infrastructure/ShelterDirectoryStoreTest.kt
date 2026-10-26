package com.pawbridge.backend.animal.internal.shelterdirectory.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.LocalDateTime

class ShelterDirectoryStoreTest {
    private val connection: Connection = mockk<Connection>()
    private val shelter: PreparedStatement = mockk<PreparedStatement>()
    private val details: PreparedStatement = mockk<PreparedStatement>()
    private val previous: PreparedStatement = mockk<PreparedStatement>()
    private val saved: ResultSet = mockk<ResultSet>()
    private val search: SearchDocumentWriter = mockk<SearchDocumentWriter>()
    private val store = ShelterDirectoryStore(ObjectMapper(), search)
    private val row: Map<String, String> = mapOf(
        "careRegNo" to "123456789012345",
        "careNm" to "보호소",
        "careAddr" to "새 주소",
        "dataStdDt" to "2026-10-01",
    )

    @Test
    fun `SQL 계약이 있을 때__저장하면__동물 스키마의 원래 PostgreSQL upsert를 유지한다`() {
        // given
        every { connection.prepareStatement(match<String> { it.startsWith("INSERT INTO pawbridge_animal.shelters(") }) } returns shelter
        every {
            connection.prepareStatement(match<String> { it.startsWith("INSERT INTO pawbridge_animal.shelter_public_information(") })
        } returns details
        every {
            connection.prepareStatement(match<String> { it.startsWith("SELECT s.address,p.details FROM pawbridge_animal.shelters s") })
        } returns previous
        every { previous.executeQuery() } returns saved
        justRun { previous.setString(any(), any()) }
        justRun { previous.close() }
        every { saved.next() } returns false
        justRun { saved.close() }
        justRun { shelter.setString(any(), any()) }
        justRun { shelter.setObject(any<Int>(), any()) }
        every { shelter.executeUpdate() } returns 1
        justRun { shelter.close() }
        justRun { search.shelter(any(), any()) }
        justRun { details.setString(any(), any()) }
        justRun { details.setObject(any<Int>(), any()) }
        every { details.executeUpdate() } returns 1
        justRun { details.close() }

        // when
        store.save(connection, listOf(row), NOW)

        // then
        val sql = mutableListOf<String>()
        verify(exactly = 3) { connection.prepareStatement(capture(sql)) }
        assertThat(sql[0]).contains("ON CONFLICT (care_reg_no) DO UPDATE SET name=EXCLUDED.name")
            .contains("address=COALESCE(EXCLUDED.address,shelters.address)")
        assertThat(sql[1]).contains("SELECT id,CAST(? AS jsonb),? FROM pawbridge_animal.shelters WHERE care_reg_no=?")
            .contains("ARRAY(SELECT key FROM jsonb_each(EXCLUDED.details) WHERE value='null'::jsonb)")
        assertThat(sql[2]).endsWith("WHERE s.care_reg_no=?\n FOR UPDATE OF s")
    }

    @Test
    fun `새 스냅샷일 때__저장하면__보호소를 upsert하고 검색을 갱신한 뒤 같은 연결에서 상세를 패치한다`() {
        // given
        every { connection.prepareStatement(match<String> { it.startsWith("INSERT INTO pawbridge_animal.shelters(") }) } returns shelter
        every {
            connection.prepareStatement(match<String> { it.startsWith("INSERT INTO pawbridge_animal.shelter_public_information(") })
        } returns details
        every {
            connection.prepareStatement(match<String> { it.startsWith("SELECT s.address,p.details FROM pawbridge_animal.shelters s") })
        } returns previous
        every { previous.executeQuery() } returns saved
        justRun { previous.setString(any(), any()) }
        justRun { previous.close() }
        every { saved.next() } returns false
        justRun { saved.close() }
        justRun { shelter.setString(any(), any()) }
        justRun { shelter.setObject(any<Int>(), any()) }
        every { shelter.executeUpdate() } returns 1
        justRun { shelter.close() }
        justRun { search.shelter(any(), any()) }
        justRun { details.setString(any(), any()) }
        justRun { details.setObject(any<Int>(), any()) }
        every { details.executeUpdate() } returns 1
        justRun { details.close() }

        // when
        store.save(connection, listOf(row), NOW)

        // then
        verifyOrder {
            previous.setString(1, "123456789012345")
            shelter.executeUpdate()
            search.shelter(connection, "123456789012345")
            details.setString(1, "{\"sourceUpdatedDate\":\"2026-10-01\"}")
            details.executeUpdate()
        }
        verify(exactly = 1) { shelter.setObject(6, NOW) }
        verify(exactly = 1) { shelter.setObject(7, NOW) }
        verify(exactly = 1) { details.setObject(2, NOW) }
    }

    @Test
    fun `저장된 원본 날짜가 더 새로울 때__저장하면__오래된 스냅샷 행을 건너뛴다`() {
        // given
        every {
            connection.prepareStatement(match<String> { it.startsWith("INSERT INTO pawbridge_animal.shelters(") })
        } returns shelter
        every {
            connection.prepareStatement(match<String> { it.startsWith("INSERT INTO pawbridge_animal.shelter_public_information(") })
        } returns details
        every {
            connection.prepareStatement(match<String> { it.startsWith("SELECT s.address,p.details FROM pawbridge_animal.shelters s") })
        } returns previous
        every { previous.executeQuery() } returns saved
        justRun { previous.setString(any(), any()) }
        justRun { previous.close() }
        every { saved.next() } returns true
        every { saved.getString(2) } returns "{\"sourceUpdatedDate\":\"2026-10-02\"}"
        justRun { saved.close() }
        justRun { shelter.close() }
        justRun { details.close() }

        // when
        store.save(connection, listOf(row), NOW)

        // then
        verify(exactly = 0) { shelter.executeUpdate() }
        verify(exactly = 0) { details.executeUpdate() }
        confirmVerified(search)
    }

    @Test
    fun `좌표 없이 주소가 바뀌었을 때__저장하면__저장된 마커를 지운다`() {
        // given
        every { connection.prepareStatement(match<String> { it.startsWith("INSERT INTO pawbridge_animal.shelters(") }) } returns shelter
        every {
            connection.prepareStatement(match<String> { it.startsWith("INSERT INTO pawbridge_animal.shelter_public_information(") })
        } returns details
        every {
            connection.prepareStatement(match<String> { it.startsWith("SELECT s.address,p.details FROM pawbridge_animal.shelters s") })
        } returns previous
        every { previous.executeQuery() } returns saved
        justRun { previous.setString(any(), any()) }
        justRun { previous.close() }
        every { saved.next() } returns true
        every { saved.getString(1) } returns "옛 주소"
        every { saved.getString(2) } returns "{\"sourceUpdatedDate\":\"2026-09-01\"}"
        justRun { saved.close() }
        justRun { shelter.setString(any(), any()) }
        justRun { shelter.setObject(any<Int>(), any()) }
        every { shelter.executeUpdate() } returns 1
        justRun { shelter.close() }
        justRun { search.shelter(any(), any()) }
        justRun { details.setString(any(), any()) }
        justRun { details.setObject(any<Int>(), any()) }
        every { details.executeUpdate() } returns 1
        justRun { details.close() }

        // when
        store.save(connection, listOf(row), NOW)

        // then
        verify(exactly = 1) {
            details.setString(1, "{\"sourceUpdatedDate\":\"2026-10-01\",\"latitude\":null,\"longitude\":null}")
        }
    }

    companion object {
        private val NOW: LocalDateTime = LocalDateTime.of(2026, 10, 22, 0, 0)
    }
}
