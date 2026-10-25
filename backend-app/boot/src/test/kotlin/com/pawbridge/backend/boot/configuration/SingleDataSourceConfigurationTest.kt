package com.pawbridge.backend.boot.configuration

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.verify
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection
import java.sql.Statement
import java.util.function.Function
import javax.sql.DataSource

class SingleDataSourceConfigurationTest {
    @ParameterizedTest
    @CsvSource("absent,10,2", "1,1,1", "4,4,2", "50,50,2")
    fun given__default_or_explicit_capacity__when__creating_the_single_pool__then__share_one_read_write_pool_without_startup_connection(
        requested: String,
        maximum: Int,
        minimumIdle: Int,
    ) {
        val values = settings()
        if (requested != "absent") {
            values["PAWBRIDGE_DB_POOL_MAX_SIZE"] = requested
        }
        ReadConfiguration().dataSource(ReadDatabaseSettings.fromEnvironment(Function { key -> values[key] })).use { pool ->
            assertThat(pool.poolName).isEqualTo("pawbridge-db")
            assertThat(pool.username).isEqualTo("synthetic_app")
            assertThat(pool.isReadOnly).isFalse()
            assertThat(pool.maximumPoolSize).isEqualTo(maximum)
            assertThat(pool.minimumIdle).isEqualTo(minimumIdle)
            assertThat(pool.connectionTimeout).isEqualTo(5000)
            assertThat(pool.validationTimeout).isEqualTo(1000)
            assertThat(pool.initializationFailTimeout).isEqualTo(-1)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "51", "invalid"])
    fun given__out_of_range_capacity__when__validating__then__reject_before_pool_construction(requested: String) {
        val values = settings()
        values["PAWBRIDGE_DB_POOL_MAX_SIZE"] = requested
        assertThatThrownBy { ReadDatabaseSettings.fromEnvironment(Function { key -> values[key] }) }
            .hasMessage("Missing or invalid explicit setting: PAWBRIDGE_DB_POOL_MAX_SIZE")
    }

    @Test
    fun given__read_and_write_managers_on_the_same_pool__when__running_transactions__then__only_read_templates_mark_the_database_transaction_read_only() {
        val source: DataSource = mock(DataSource::class.java)
        val connection: Connection = mock(Connection::class.java)
        val statement: Statement = mock(Statement::class.java)
        whenever(source.connection).thenReturn(connection)
        whenever(connection.autoCommit).thenReturn(true)
        whenever(connection.createStatement()).thenReturn(statement)
        val configuration = ReadConfiguration()
        val readManager: JdbcTransactionManager = configuration.transactionManager(source)
        val readOnly: TransactionTemplate = configuration.readOnlyTransaction(readManager)

        readOnly.executeWithoutResult { }
        verify(statement).executeUpdate("SET TRANSACTION READ ONLY")
        verify(connection).setReadOnly(true)

        val writeStatement: Statement = mock(Statement::class.java)
        whenever(connection.createStatement()).thenReturn(writeStatement)
        val write = TransactionTemplate(JdbcTransactionManager(source))
        write.executeWithoutResult { }
        verify(writeStatement, never()).executeUpdate("SET TRANSACTION READ ONLY")
    }

    private fun settings(): MutableMap<String, String> {
        return HashMap(
            mapOf(
                "PAWBRIDGE_DB_URL" to "jdbc:postgresql://127.0.0.1:1/pb_never_connected",
                "PAWBRIDGE_DB_USER" to "synthetic_app",
                "PAWBRIDGE_DB_PASSWORD" to "synthetic",
            ),
        )
    }
}
