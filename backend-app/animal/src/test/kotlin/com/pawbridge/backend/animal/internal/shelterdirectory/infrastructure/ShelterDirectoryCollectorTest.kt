package com.pawbridge.backend.animal.internal.shelterdirectory.infrastructure

import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import javax.sql.DataSource

class ShelterDirectoryCollectorTest {
    private val source: DataSource = mockk<DataSource>()
    private val connection: Connection = mockk<Connection>()
    private val client: ShelterDirectoryClient = mockk<ShelterDirectoryClient>()
    private val store: ShelterDirectoryStore = mockk<ShelterDirectoryStore>()
    private val statement: PreparedStatement = mockk<PreparedStatement>()
    private val result: ResultSet = mockk<ResultSet>()
    private val collector = ShelterDirectoryCollector(source, client, store)

    @Test
    fun `동물 작업이 잠금을 쥐고 있을 때__수집하면__API 호출이나 쓰기를 하지 않는다`() {
        // given
        every { source.connection } returns connection
        every { connection.prepareStatement(any<String>()) } returns statement
        justRun { connection.close() }
        justRun { statement.setInt(any(), any()) }
        justRun { statement.queryTimeout = any() }
        every { statement.executeQuery() } returns result
        justRun { statement.close() }
        every { result.next() } returns true
        every { result.getObject(1) } returns 0
        justRun { result.close() }

        // when
        val thrown = assertThatThrownBy { collector.collect() }

        // then
        thrown.hasMessage("SHELTER_DIRECTORY_BUSY")
        confirmVerified(client, store)
        verify(exactly = 0) { connection.commit() }
        verify(exactly = 1) { connection.prepareStatement(ACQUIRE) }
        verify(exactly = 1) { statement.setInt(1, 1) }
    }

    @Test
    fun `잠금 쿼리가 실패할 때__수집하면__API 호출이나 쓰기 없이 연결을 폐기한다`() {
        // given
        every { source.connection } returns connection
        every { connection.prepareStatement(any<String>()) } returns statement
        justRun { connection.abort(any()) }
        justRun { connection.close() }
        justRun { statement.setInt(any(), any()) }
        justRun { statement.queryTimeout = any() }
        every { statement.executeQuery() } throws SQLException("lock failure")
        justRun { statement.close() }

        // when
        val thrown = assertThatThrownBy { collector.collect() }

        // then
        thrown.isInstanceOf(SQLException::class.java)
        verify(exactly = 1) { connection.abort(any()) }
        confirmVerified(client, store)
    }

    @Test
    fun `API가 실패할 때__수집하면__쓰기 없이 잠금을 해제한다`() {
        // given
        every { source.connection } returns connection
        every { connection.prepareStatement(any<String>()) } returns statement
        justRun { connection.close() }
        justRun { statement.setInt(any(), any()) }
        justRun { statement.queryTimeout = any() }
        every { statement.executeQuery() } returns result
        justRun { statement.close() }
        every { result.next() } returns true
        every { result.getObject(1) } returns 1
        justRun { result.close() }
        every { client.collect() } throws ShelterDirectoryClient.unavailable()

        // when
        val thrown = assertThatThrownBy { collector.collect() }

        // then
        thrown.hasMessage("SHELTER_DIRECTORY_UNAVAILABLE")
        confirmVerified(store)
        verify(exactly = 1) { connection.prepareStatement(RELEASE) }
        verify(exactly = 0) { connection.commit() }
    }

    @Test
    fun `쓰기가 실패할 때__수집하면__스냅샷 전체를 롤백한다`() {
        // given
        val rows = listOf(mapOf("careRegNo" to "123456789012345"))

        every { source.connection } returns connection
        every { connection.prepareStatement(any<String>()) } returns statement
        justRun { connection.autoCommit = any() }
        justRun { connection.rollback() }
        justRun { connection.close() }
        justRun { statement.setInt(any(), any()) }
        justRun { statement.queryTimeout = any() }
        every { statement.executeQuery() } returns result
        justRun { statement.close() }
        every { result.next() } returns true
        every { result.getObject(1) } returns 1
        justRun { result.close() }
        every { client.collect() } returns rows
        every { store.save(any(), any(), any()) } throws SQLException("write failure")

        // when
        val thrown = assertThatThrownBy { collector.collect() }

        // then
        thrown.isInstanceOf(SQLException::class.java)
        verify(exactly = 1) { store.save(connection, rows, any()) }
        verify(exactly = 1) { connection.rollback() }
        verify(exactly = 0) { connection.commit() }
    }

    @Test
    fun `스냅샷이 있을 때__수집하면__잠금을 해제하기 전에 커밋한다`() {
        // given
        val rows = listOf(mapOf("careRegNo" to "123456789012345"))

        every { source.connection } returns connection
        every { connection.prepareStatement(any<String>()) } returns statement
        justRun { connection.autoCommit = any() }
        justRun { connection.commit() }
        justRun { connection.close() }
        justRun { statement.setInt(any(), any()) }
        justRun { statement.queryTimeout = any() }
        every { statement.executeQuery() } returns result
        justRun { statement.close() }
        every { result.next() } returns true
        every { result.getObject(1) } returns 1
        justRun { result.close() }
        every { client.collect() } returns rows
        justRun { store.save(any(), any(), any()) }

        // when
        val count = collector.collect()

        // then
        assertThat(count).isEqualTo(1)
        verifyOrder {
            connection.autoCommit = false
            store.save(connection, rows, any())
            connection.commit()
            connection.autoCommit = true
            connection.prepareStatement(RELEASE)
        }
        verify(exactly = 0) { connection.abort(any()) }
    }

    @Test
    fun `잠금 해제가 확인되지 않을 때__수집하면__연결을 폐기한다`() {
        // given
        val rows = listOf(mapOf("careRegNo" to "123456789012345"))

        every { source.connection } returns connection
        every { connection.prepareStatement(any<String>()) } returns statement
        justRun { connection.autoCommit = any() }
        justRun { connection.commit() }
        justRun { connection.abort(any()) }
        justRun { connection.close() }
        justRun { statement.setInt(any(), any()) }
        justRun { statement.queryTimeout = any() }
        every { statement.executeQuery() } returns result
        justRun { statement.close() }
        every { result.next() } returns true
        every { result.getObject(1) } returnsMany listOf(1, 0)
        justRun { result.close() }
        every { client.collect() } returns rows
        justRun { store.save(any(), any(), any()) }

        // when
        val count = collector.collect()

        // then
        assertThat(count).isEqualTo(1)
        verify(exactly = 1) { connection.abort(any()) }
    }

    companion object {
        private const val ACQUIRE = "SELECT CASE WHEN pg_try_advisory_lock(1346459458,?) THEN 1 ELSE 0 END"
        private const val RELEASE = "SELECT CASE WHEN pg_advisory_unlock(1346459458,?) THEN 1 ELSE 0 END"
    }
}
