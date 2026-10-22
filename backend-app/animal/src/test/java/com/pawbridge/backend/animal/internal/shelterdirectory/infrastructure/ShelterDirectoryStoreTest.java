package com.pawbridge.backend.animal.internal.shelterdirectory.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShelterDirectoryStoreTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 22, 0, 0);
    Connection connection = mock(Connection.class);
    PreparedStatement shelter = mock(PreparedStatement.class);
    PreparedStatement details = mock(PreparedStatement.class);
    PreparedStatement previous = mock(PreparedStatement.class);
    ResultSet saved = mock(ResultSet.class);
    SearchDocumentWriter search = mock(SearchDocumentWriter.class);
    ShelterDirectoryStore store = new ShelterDirectoryStore(new ObjectMapper(), search);
    Map<String, String> row = Map.of("careRegNo", "123456789012345", "careNm", "보호소", "careAddr", "새 주소",
            "dataStdDt", "2026-10-01");

    @BeforeEach void setup() throws Exception {
        when(connection.prepareStatement(startsWith("INSERT INTO pawbridge_animal.shelters("))).thenReturn(shelter);
        when(connection.prepareStatement(startsWith("INSERT INTO pawbridge_animal.shelter_public_information("))).thenReturn(details);
        when(connection.prepareStatement(startsWith("SELECT s.address,p.details FROM pawbridge_animal.shelters s"))).thenReturn(previous);
        when(previous.executeQuery()).thenReturn(saved);
    }

    @Test void givenSqlContract_whenSaving_thenKeepsOriginalPostgresqlUpsertsInAnimalSchema() throws Exception {
        when(saved.next()).thenReturn(false);
        store.save(connection, List.of(row), NOW);
        var sql = ArgumentCaptor.forClass(String.class);
        verify(connection, times(3)).prepareStatement(sql.capture());
        assertThat(sql.getAllValues().get(0)).contains("ON CONFLICT (care_reg_no) DO UPDATE SET name=EXCLUDED.name")
                .contains("address=COALESCE(EXCLUDED.address,shelters.address)");
        assertThat(sql.getAllValues().get(1)).contains("SELECT id,CAST(? AS jsonb),? FROM pawbridge_animal.shelters WHERE care_reg_no=?")
                .contains("ARRAY(SELECT key FROM jsonb_each(EXCLUDED.details) WHERE value='null'::jsonb)");
        assertThat(sql.getAllValues().get(2)).endsWith("WHERE s.care_reg_no=?\n FOR UPDATE OF s");
    }

    @Test void givenNewSnapshot_whenSaving_thenUpsertsShelterRefreshesSearchThenPatchesDetailsOnSameConnection() throws Exception {
        when(saved.next()).thenReturn(false);
        store.save(connection, List.of(row), NOW);
        var ordered = inOrder(previous, shelter, search, details);
        ordered.verify(previous).setString(1, "123456789012345");
        ordered.verify(shelter).executeUpdate();
        ordered.verify(search).shelter(connection, "123456789012345");
        ordered.verify(details).setString(1, "{\"sourceUpdatedDate\":\"2026-10-01\"}");
        ordered.verify(details).executeUpdate();
        verify(shelter).setObject(6, NOW); verify(shelter).setObject(7, NOW); verify(details).setObject(2, NOW);
    }

    @Test void givenNewerSavedSourceDate_whenSaving_thenSkipsOlderSnapshotRow() throws Exception {
        when(saved.next()).thenReturn(true);
        when(saved.getString(2)).thenReturn("{\"sourceUpdatedDate\":\"2026-10-02\"}");
        store.save(connection, List.of(row), NOW);
        verify(shelter, never()).executeUpdate(); verify(details, never()).executeUpdate();
        verifyNoInteractions(search);
    }

    @Test void givenChangedAddressWithoutCoordinates_whenSaving_thenClearsSavedMarker() throws Exception {
        when(saved.next()).thenReturn(true);
        when(saved.getString(1)).thenReturn("옛 주소");
        when(saved.getString(2)).thenReturn("{\"sourceUpdatedDate\":\"2026-09-01\"}");
        store.save(connection, List.of(row), NOW);
        verify(details).setString(1, "{\"sourceUpdatedDate\":\"2026-10-01\",\"latitude\":null,\"longitude\":null}");
    }
}
