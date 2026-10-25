package com.pawbridge.backend.animal.internal.shelterdirectory.infrastructure

import com.pawbridge.backend.animal.anyNonNull
import com.pawbridge.backend.animal.eqNonNull
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.LocalDateTime
import javax.sql.DataSource

class ShelterDirectoryCollectorTest {
    private val source: DataSource = mock(DataSource::class.java)
    private val connection: Connection = mock(Connection::class.java)
    private val client: ShelterDirectoryClient = mock(ShelterDirectoryClient::class.java)
    private val store: ShelterDirectoryStore = mock(ShelterDirectoryStore::class.java)
    private val statement: PreparedStatement = mock(PreparedStatement::class.java)
    private val result: ResultSet = mock(ResultSet::class.java)
    private val collector = ShelterDirectoryCollector(source, client, store)

    @BeforeEach
    fun setup() {
        `when`(source.connection).thenReturn(connection)
        `when`(connection.prepareStatement(anyString())).thenReturn(statement)
        `when`(statement.executeQuery()).thenReturn(result)
        `when`(result.next()).thenReturn(true)
        `when`(result.getObject(1)).thenReturn(1)
    }

    @Test
    fun givenAnimalJobOwnsLock_whenCollecting_thenDoesNotCallApiOrWrite() {
        `when`(result.getObject(1)).thenReturn(0)

        assertThatThrownBy { collector.collect() }.hasMessage("SHELTER_DIRECTORY_BUSY")

        verifyNoInteractions(client, store)
        verify(connection, never()).commit()
        verify(connection).prepareStatement(ACQUIRE)
        verify(statement).setInt(1, 1)
    }

    @Test
    fun givenLockQueryFailure_whenCollecting_thenDiscardsConnectionWithoutApiOrWrite() {
        `when`(statement.executeQuery()).thenThrow(SQLException("lock failure"))

        assertThatThrownBy { collector.collect() }.isInstanceOf(SQLException::class.java)

        verify(connection).abort(any())
        verifyNoInteractions(client, store)
    }

    @Test
    fun givenApiFailure_whenCollecting_thenNoWritesAndLockReleased() {
        `when`(client.collect()).thenThrow(ShelterDirectoryClient.unavailable())

        assertThatThrownBy { collector.collect() }.hasMessage("SHELTER_DIRECTORY_UNAVAILABLE")

        verifyNoInteractions(store)
        verify(connection).prepareStatement(RELEASE)
        verify(connection, never()).commit()
    }

    @Test
    fun givenWriteFailure_whenCollecting_thenRollsBackEntireSnapshot() {
        `when`(client.collect()).thenReturn(listOf(mapOf("careRegNo" to "123456789012345")))
        doThrow(SQLException("write failure")).`when`(store)
            .save(eqNonNull(connection), anyNonNull<List<Map<String, String>>>(), anyNonNull<LocalDateTime>())

        assertThatThrownBy { collector.collect() }.isInstanceOf(SQLException::class.java)

        verify(connection).rollback()
        verify(connection, never()).commit()
    }

    @Test
    fun givenSnapshot_whenCollecting_thenCommitsBeforeReleasingLock() {
        val rows = listOf(mapOf("careRegNo" to "123456789012345"))
        `when`(client.collect()).thenReturn(rows)

        assertThat(collector.collect()).isEqualTo(1)

        val ordered = inOrder(store, connection)
        ordered.verify(connection).autoCommit = false
        ordered.verify(store).save(eqNonNull(connection), eqNonNull(rows), anyNonNull<LocalDateTime>())
        ordered.verify(connection).commit()
        ordered.verify(connection).autoCommit = true
        ordered.verify(connection).prepareStatement(RELEASE)
        verify(connection, never()).abort(any())
    }

    @Test
    fun givenUnconfirmedRelease_whenCollecting_thenDiscardsConnection() {
        `when`(client.collect()).thenReturn(listOf(mapOf("careRegNo" to "123456789012345")))
        `when`(result.getObject(1)).thenReturn(1, 0)

        assertThat(collector.collect()).isEqualTo(1)

        verify(connection).abort(any())
    }

    companion object {
        private const val ACQUIRE = "SELECT CASE WHEN pg_try_advisory_lock(1346459458,?) THEN 1 ELSE 0 END"
        private const val RELEASE = "SELECT CASE WHEN pg_advisory_unlock(1346459458,?) THEN 1 ELSE 0 END"
    }
}
