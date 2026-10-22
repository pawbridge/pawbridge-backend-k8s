package com.pawbridge.backend.animal.internal.shelterdirectory.infrastructure;

import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShelterDirectoryCollectorTest {
    private static final String ACQUIRE = "SELECT CASE WHEN pg_try_advisory_lock(1346459458,?) THEN 1 ELSE 0 END";
    private static final String RELEASE = "SELECT CASE WHEN pg_advisory_unlock(1346459458,?) THEN 1 ELSE 0 END";
    DataSource source=mock(DataSource.class);
    Connection connection=mock(Connection.class);
    ShelterDirectoryClient client=mock(ShelterDirectoryClient.class);
    ShelterDirectoryStore store=mock(ShelterDirectoryStore.class);
    PreparedStatement statement=mock(PreparedStatement.class);
    ResultSet result=mock(ResultSet.class);
    ShelterDirectoryCollector collector=new ShelterDirectoryCollector(source,client,store);
    @BeforeEach void setup() throws Exception {
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true); when(result.getObject(1)).thenReturn(1);
    }
    @Test void givenAnimalJobOwnsLock_whenCollecting_thenDoesNotCallApiOrWrite() throws Exception {
        when(result.getObject(1)).thenReturn(0);
        assertThatThrownBy(collector::collect).hasMessage("SHELTER_DIRECTORY_BUSY");
        verifyNoInteractions(client,store); verify(connection,never()).commit();
        verify(connection).prepareStatement(ACQUIRE); verify(statement).setInt(1,1);
    }
    @Test void givenLockQueryFailure_whenCollecting_thenDiscardsConnectionWithoutApiOrWrite() throws Exception {
        when(statement.executeQuery()).thenThrow(new SQLException("lock failure"));
        assertThatThrownBy(collector::collect).isInstanceOf(SQLException.class);
        verify(connection).abort(any()); verifyNoInteractions(client,store);
    }
    @Test void givenApiFailure_whenCollecting_thenNoWritesAndLockReleased() throws Exception {
        when(client.collect()).thenThrow(ShelterDirectoryClient.unavailable());
        assertThatThrownBy(collector::collect).hasMessage("SHELTER_DIRECTORY_UNAVAILABLE");
        verifyNoInteractions(store); verify(connection).prepareStatement(RELEASE);
        verify(connection,never()).commit();
    }
    @Test void givenWriteFailure_whenCollecting_thenRollsBackEntireSnapshot() throws Exception {
        when(client.collect()).thenReturn(List.of(Map.of("careRegNo","123456789012345")));
        doThrow(new SQLException("write failure")).when(store).save(eq(connection),anyList(),any());
        assertThatThrownBy(collector::collect).isInstanceOf(SQLException.class);
        verify(connection).rollback(); verify(connection,never()).commit();
    }
    @Test void givenSnapshot_whenCollecting_thenCommitsBeforeReleasingLock() throws Exception {
        var rows=List.of(Map.of("careRegNo","123456789012345"));
        when(client.collect()).thenReturn(rows);
        assertThat(collector.collect()).isEqualTo(1);
        var ordered=inOrder(store,connection);
        ordered.verify(connection).setAutoCommit(false);
        ordered.verify(store).save(eq(connection),eq(rows),any());
        ordered.verify(connection).commit(); ordered.verify(connection).setAutoCommit(true);
        ordered.verify(connection).prepareStatement(RELEASE);
        verify(connection,never()).abort(any());
    }
    @Test void givenUnconfirmedRelease_whenCollecting_thenDiscardsConnection() throws Exception {
        when(client.collect()).thenReturn(List.of(Map.of("careRegNo","123456789012345")));
        when(result.getObject(1)).thenReturn(1, 0);
        assertThat(collector.collect()).isEqualTo(1);
        verify(connection).abort(any());
    }
}
