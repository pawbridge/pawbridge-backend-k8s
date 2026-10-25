package com.pawbridge.backend.animal.internal.shelterdirectory.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.startsWith
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.LocalDateTime

class ShelterDirectoryStoreTest {
    private val connection: Connection = mock(Connection::class.java)
    private val shelter: PreparedStatement = mock(PreparedStatement::class.java)
    private val details: PreparedStatement = mock(PreparedStatement::class.java)
    private val previous: PreparedStatement = mock(PreparedStatement::class.java)
    private val saved: ResultSet = mock(ResultSet::class.java)
    private val search: SearchDocumentWriter = mock(SearchDocumentWriter::class.java)
    private val store = ShelterDirectoryStore(ObjectMapper(), search)
    private val row: Map<String, String> = mapOf(
        "careRegNo" to "123456789012345",
        "careNm" to "보호소",
        "careAddr" to "새 주소",
        "dataStdDt" to "2026-10-01",
    )

    @BeforeEach
    fun setup() {
        `when`(connection.prepareStatement(startsWith("INSERT INTO pawbridge_animal.shelters("))).thenReturn(shelter)
        `when`(connection.prepareStatement(startsWith("INSERT INTO pawbridge_animal.shelter_public_information(")))
            .thenReturn(details)
        `when`(connection.prepareStatement(startsWith("SELECT s.address,p.details FROM pawbridge_animal.shelters s")))
            .thenReturn(previous)
        `when`(previous.executeQuery()).thenReturn(saved)
    }

    @Test
    fun givenSqlContract_whenSaving_thenKeepsOriginalPostgresqlUpsertsInAnimalSchema() {
        `when`(saved.next()).thenReturn(false)

        store.save(connection, listOf(row), NOW)

        val sql = ArgumentCaptor.forClass(String::class.java)
        verify(connection, times(3)).prepareStatement(sql.capture())
        assertThat(sql.allValues[0]).contains("ON CONFLICT (care_reg_no) DO UPDATE SET name=EXCLUDED.name")
            .contains("address=COALESCE(EXCLUDED.address,shelters.address)")
        assertThat(sql.allValues[1]).contains("SELECT id,CAST(? AS jsonb),? FROM pawbridge_animal.shelters WHERE care_reg_no=?")
            .contains("ARRAY(SELECT key FROM jsonb_each(EXCLUDED.details) WHERE value='null'::jsonb)")
        assertThat(sql.allValues[2]).endsWith("WHERE s.care_reg_no=?\n FOR UPDATE OF s")
    }

    @Test
    fun givenNewSnapshot_whenSaving_thenUpsertsShelterRefreshesSearchThenPatchesDetailsOnSameConnection() {
        `when`(saved.next()).thenReturn(false)

        store.save(connection, listOf(row), NOW)

        val ordered = inOrder(previous, shelter, search, details)
        ordered.verify(previous).setString(1, "123456789012345")
        ordered.verify(shelter).executeUpdate()
        ordered.verify(search).shelter(connection, "123456789012345")
        ordered.verify(details).setString(1, "{\"sourceUpdatedDate\":\"2026-10-01\"}")
        ordered.verify(details).executeUpdate()
        verify(shelter).setObject(6, NOW)
        verify(shelter).setObject(7, NOW)
        verify(details).setObject(2, NOW)
    }

    @Test
    fun givenNewerSavedSourceDate_whenSaving_thenSkipsOlderSnapshotRow() {
        `when`(saved.next()).thenReturn(true)
        `when`(saved.getString(2)).thenReturn("{\"sourceUpdatedDate\":\"2026-10-02\"}")

        store.save(connection, listOf(row), NOW)

        verify(shelter, never()).executeUpdate()
        verify(details, never()).executeUpdate()
        verifyNoInteractions(search)
    }

    @Test
    fun givenChangedAddressWithoutCoordinates_whenSaving_thenClearsSavedMarker() {
        `when`(saved.next()).thenReturn(true)
        `when`(saved.getString(1)).thenReturn("옛 주소")
        `when`(saved.getString(2)).thenReturn("{\"sourceUpdatedDate\":\"2026-09-01\"}")

        store.save(connection, listOf(row), NOW)

        verify(details).setString(1, "{\"sourceUpdatedDate\":\"2026-10-01\",\"latitude\":null,\"longitude\":null}")
    }

    companion object {
        private val NOW: LocalDateTime = LocalDateTime.of(2026, 10, 22, 0, 0)
    }
}
