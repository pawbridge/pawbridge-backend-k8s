package com.pawbridge.backend.animal.internal.shelter.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.internal.shelter.application.ShelterNotFoundException;
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionOperations;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JdbcShelterDiscoveryReadTest {
    private final NamedParameterJdbcOperations jdbc = mock(NamedParameterJdbcOperations.class);
    private final NamedParameterJdbcOperations discovery = mock(NamedParameterJdbcOperations.class);
    private final JdbcShelterReads shelters = new JdbcShelterReads(jdbc, discovery,
            TransactionOperations.withoutTransaction(), TransactionOperations.withoutTransaction(), new ObjectMapper());
    private static final LocalDate FROM = LocalDate.of(2026, 9, 1), TO = LocalDate.of(2026, 9, 30);

    @Test
    void givenMatchingShelters__whenDiscovering__thenReuseProtectIntakeCountAndOneBoundedPreviewQueryForWholePage() throws Exception {
        when(discovery.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(25L);
        ResultSet first = shelterRow(4, "센터4", 8), second = shelterRow(9, "센터9", 3);
        when(discovery.query(anyString(), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<Discovery>>any()))
                .thenAnswer(call -> List.of(call.<RowMapper<Discovery>>getArgument(2).mapRow(first, 0),
                        call.<RowMapper<Discovery>>getArgument(2).mapRow(second, 1)));
        doAnswer(call -> {
            RowCallbackHandler callback = call.getArgument(2);
            callback.processRow(previewRow(40, 4, TO));
            callback.processRow(previewRow(41, 4, TO.minusDays(1)));
            callback.processRow(previewRow(90, 9, TO.minusDays(2)));
            return null;
        }).when(discovery).query(anyString(), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));

        var result = shelters.discover(" 100%_! ", " 서울 ", FROM, TO, 1, 12);

        assertThat(result.totalElements()).isEqualTo(25);
        assertThat(result.content()).extracting(Discovery::id).containsExactly(4L, 9L);
        assertThat(result.content().getFirst().animals()).containsExactly(new Preview(40, null, "DOG", "UNKNOWN", null, null, TO),
                new Preview(41, null, "DOG", "UNKNOWN", null, null, TO.minusDays(1)));
        assertThat(result.content().get(1).animals()).extracting(Preview::id).containsExactly(90L);
        var params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(discovery).queryForObject(argThat((String sql) -> sql.contains("COUNT(DISTINCT s.id)") && sql.contains("a.status = 'PROTECT'")
                && sql.contains("a.happen_date BETWEEN :from AND :to") && sql.contains("COALESCE(s.address, '') ILIKE :address ESCAPE '!'")
                && sql.contains("s.name ILIKE :keyword") && sql.contains("s.address ILIKE :keyword")), params.capture(), eq(Long.class));
        assertThat(params.getValue().getValue("keyword")).isEqualTo("%100!%!_!!%");
        assertThat(params.getValue().getValue("address")).isEqualTo("%서울%");
        assertThat(params.getValue().getValue("from")).isEqualTo(FROM);
        assertThat(params.getValue().getValue("to")).isEqualTo(TO);
        assertThat(params.getValue().getValue("offset")).isEqualTo(12L);
        assertThat(params.getValue().getValue("ids")).isEqualTo(List.of(4L, 9L));
        verify(discovery).query(contains("ORDER BY protected_count DESC, s.name ASC, s.id ASC LIMIT :limit OFFSET :offset"),
                any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<Discovery>>any());
        verify(discovery).query(argThat((String sql) -> sql.contains("ROW_NUMBER() OVER (PARTITION BY shelter_id ORDER BY happen_date DESC, id ASC)")
                && sql.contains("FROM pawbridge_animal.animals") && sql.contains("WHERE rn <= 3 ORDER BY shelter_id, rn")),
                any(MapSqlParameterSource.class), any(RowCallbackHandler.class));
        verifyNoMoreInteractions(discovery);
        verifyNoInteractions(jdbc);
    }

    @Test
    void givenEmptyDiscoveryPage__whenDiscovering__thenKeepTotalWithoutQueryingPreviews() {
        when(discovery.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(25L);
        when(discovery.query(anyString(), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<Discovery>>any())).thenReturn(List.of());
        var result = shelters.discover("", "", FROM, TO, 3, 12);
        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isEqualTo(25);
        verify(discovery, never()).query(anyString(), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));
    }

    @ParameterizedTest
    @MethodSource("invalidDiscovery")
    void givenInvalidDiscoveryInput__whenDiscovering__thenRejectBeforeSql(String keyword, LocalDate from, LocalDate to, int page, int size) {
        assertThatThrownBy(() -> shelters.discover(keyword, "", from, to, page, size)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(discovery, jdbc);
    }
    static Stream<Arguments> invalidDiscovery() {
        return Stream.of(Arguments.of("", FROM, TO, -1, 12), Arguments.of("", FROM, TO, 0, 0),
                Arguments.of("", FROM, TO, 0, 25), Arguments.of("", FROM, TO, 416, 24),
                Arguments.of("x".repeat(101), FROM, TO, 0, 12), Arguments.of("", null, TO, 0, 12),
                Arguments.of("", TO, FROM, 0, 12), Arguments.of("", FROM, FROM.plusDays(366), 0, 12));
    }

    @Test
    void givenLastAllowedDiscoveryResultAnd366DayPeriod__whenDiscovering__thenRetainInclusiveBounds() {
        when(discovery.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(0L);
        when(discovery.query(anyString(), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<Discovery>>any())).thenReturn(List.of());
        assertThat(shelters.discover("", "", FROM, FROM.plusDays(365), 9999, 1).content()).isEmpty();
    }

    @Test
    void givenObservationGapsAndOffsetTimestamp__whenReadingSeries__thenReturnOnlyRecordedDatesWithOriginalOffset() throws Exception {
        when(discovery.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Boolean.class))).thenReturn(true);
        OffsetDateTime observed = OffsetDateTime.parse("2026-09-03T10:11:12.123456+09:00");
        ResultSet row = mock(ResultSet.class);
        when(row.getObject("observation_date", LocalDate.class)).thenReturn(FROM.plusDays(2));
        when(row.getObject("observed_at", OffsetDateTime.class)).thenReturn(observed);
        when(row.getLong("protected_count")).thenReturn(8L);
        when(discovery.query(anyString(), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<Observation>>any()))
                .thenAnswer(call -> List.of(call.<RowMapper<Observation>>getArgument(2).mapRow(row, 0)));
        assertThat(shelters.observations(4, FROM, TO)).containsExactly(new Observation(FROM.plusDays(2), observed, 8));
        verify(discovery).queryForObject(contains("EXISTS(SELECT 1 FROM pawbridge_animal.shelters"),
                argThat((MapSqlParameterSource p) -> p.getValue("id").equals(4L)), eq(Boolean.class));
        verify(discovery).query(argThat((String sql) -> sql.contains("FROM pawbridge_animal.shelter_daily_observations")
                && sql.contains("observation_date BETWEEN :from AND :to ORDER BY observation_date") && !sql.contains("generate_series")),
                any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<Observation>>any());
    }

    @Test
    void givenMissingShelterOrNoRecordedObservations__whenReading__thenKeep404OrEmptySeries() {
        when(discovery.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Boolean.class))).thenReturn(false);
        assertThatThrownBy(() -> shelters.observations(4, FROM, TO)).isInstanceOf(ShelterNotFoundException.class);
        verify(discovery, never()).query(anyString(), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<Observation>>any());
        when(discovery.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Boolean.class))).thenReturn(true);
        when(discovery.query(anyString(), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<Observation>>any())).thenReturn(List.of());
        assertThat(shelters.observations(4, FROM, TO)).isEmpty();
    }

    @Test
    void givenPublicConstructor__whenReadingRegularAndDiscoveryFlows__thenPreserveTheirDifferentTransactionSettings() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        when(dataSource.getConnection()).thenThrow(new SQLException("synthetic unreachable data source"));
        var actual = new JdbcShelterReads(dataSource, manager, new ObjectMapper());
        assertThatThrownBy(actual::count).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> actual.discover("", "", FROM, TO, 0, 25)).isInstanceOf(IllegalArgumentException.class);
        var definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(manager, times(2)).getTransaction(definitions.capture());
        assertThat(definitions.getAllValues().get(0).isReadOnly()).isTrue();
        assertThat(definitions.getAllValues().get(0).getTimeout()).isEqualTo(TransactionDefinition.TIMEOUT_DEFAULT);
        assertThat(definitions.getAllValues().get(0).getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT);
        assertThat(definitions.getAllValues().get(1).isReadOnly()).isTrue();
        assertThat(definitions.getAllValues().get(1).getTimeout()).isEqualTo(5);
        assertThat(definitions.getAllValues().get(1).getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        verify(manager, times(2)).rollback(any());
    }

    private static ResultSet shelterRow(long id, String name, long protectedCount) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(id);
        when(row.getString("care_reg_no")).thenReturn("reg-" + id);
        when(row.getString("name")).thenReturn(name);
        when(row.getString("address")).thenReturn("서울");
        when(row.getString("phone")).thenReturn("02");
        when(row.getLong("protected_count")).thenReturn(protectedCount);
        return row;
    }
    private static ResultSet previewRow(long id, long shelterId, LocalDate date) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(id);
        when(row.getLong("shelter_id")).thenReturn(shelterId);
        when(row.getString("species")).thenReturn("DOG");
        when(row.getString("gender")).thenReturn("UNKNOWN");
        when(row.getObject("happen_date", LocalDate.class)).thenReturn(date);
        return row;
    }
}
