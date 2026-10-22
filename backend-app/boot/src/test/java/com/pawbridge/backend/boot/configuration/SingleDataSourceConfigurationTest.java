package com.pawbridge.backend.boot.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class SingleDataSourceConfigurationTest {
    @ParameterizedTest
    @CsvSource({"absent,10,2", "1,1,1", "4,4,2", "50,50,2"})
    void given__default_or_explicit_capacity__when__creating_the_single_pool__then__share_one_read_write_pool_without_startup_connection(
            String requested, int maximum, int minimumIdle) {
        var values = settings();
        if (!requested.equals("absent")) values.put("PAWBRIDGE_DB_POOL_MAX_SIZE", requested);
        try (var pool = new ReadConfiguration().dataSource(ReadDatabaseSettings.fromEnvironment(values::get))) {
            assertThat(pool.getPoolName()).isEqualTo("pawbridge-db");
            assertThat(pool.getUsername()).isEqualTo("synthetic_app");
            assertThat(pool.isReadOnly()).isFalse();
            assertThat(pool.getMaximumPoolSize()).isEqualTo(maximum);
            assertThat(pool.getMinimumIdle()).isEqualTo(minimumIdle);
            assertThat(pool.getConnectionTimeout()).isEqualTo(5000);
            assertThat(pool.getValidationTimeout()).isEqualTo(1000);
            assertThat(pool.getInitializationFailTimeout()).isEqualTo(-1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "51", "invalid"})
    void given__out_of_range_capacity__when__validating__then__reject_before_pool_construction(String requested) {
        var values = settings();
        values.put("PAWBRIDGE_DB_POOL_MAX_SIZE", requested);
        assertThatThrownBy(() -> ReadDatabaseSettings.fromEnvironment(values::get))
                .hasMessage("Missing or invalid explicit setting: PAWBRIDGE_DB_POOL_MAX_SIZE");
    }

    @Test
    void given__read_and_write_managers_on_the_same_pool__when__running_transactions__then__only_read_templates_mark_the_database_transaction_read_only()
            throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.createStatement()).thenReturn(statement);
        var configuration = new ReadConfiguration();
        JdbcTransactionManager readManager = configuration.transactionManager(source);
        TransactionTemplate readOnly = configuration.readOnlyTransaction(readManager);

        readOnly.executeWithoutResult(status -> { });
        verify(statement).executeUpdate("SET TRANSACTION READ ONLY");
        verify(connection).setReadOnly(true);

        Statement writeStatement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(writeStatement);
        var write = new TransactionTemplate(new JdbcTransactionManager(source));
        write.executeWithoutResult(status -> { });
        verify(writeStatement, never()).executeUpdate("SET TRANSACTION READ ONLY");
    }

    private static Map<String, String> settings() {
        return new HashMap<>(Map.of("PAWBRIDGE_DB_URL", "jdbc:postgresql://127.0.0.1:1/pb_never_connected",
                "PAWBRIDGE_DB_USER", "synthetic_app", "PAWBRIDGE_DB_PASSWORD", "synthetic"));
    }
}
