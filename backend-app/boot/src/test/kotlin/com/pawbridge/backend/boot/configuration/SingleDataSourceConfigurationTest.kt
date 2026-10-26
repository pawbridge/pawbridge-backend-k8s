package com.pawbridge.backend.boot.configuration

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection
import java.sql.Statement
import java.util.function.Function
import javax.sql.DataSource

class SingleDataSourceConfigurationTest {
    @ParameterizedTest
    @CsvSource("absent,10,2", "1,1,1", "4,4,2", "50,50,2")
    fun `기본 또는 명시 용량이 주어졌을 때__단일 풀을 만들면__시작 연결 없이 읽기·쓰기 풀 하나를 공유한다`(
        requested: String,
        maximum: Int,
        minimumIdle: Int,
    ) {
        // given
        val values = settings()
        if (requested != "absent") {
            values["PAWBRIDGE_DB_POOL_MAX_SIZE"] = requested
        }

        // when
        ReadConfiguration().dataSource(ReadDatabaseSettings.fromEnvironment(Function { key -> values[key] })).use { pool ->
            // then
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
    fun `범위를 벗어난 용량이 주어졌을 때__검증하면__풀을 만들기 전에 거절한다`(requested: String) {
        // given
        val values = settings()
        values["PAWBRIDGE_DB_POOL_MAX_SIZE"] = requested

        // when
        // then
        assertThatThrownBy { ReadDatabaseSettings.fromEnvironment(Function { key -> values[key] }) }
            .hasMessage("Missing or invalid explicit setting: PAWBRIDGE_DB_POOL_MAX_SIZE")
    }

    @Test
    fun `읽기·쓰기 매니저가 같은 풀을 쓸 때__트랜잭션을 실행하면__읽기 템플릿만 읽기 전용으로 표시한다`() {
        // given
        val source = mockk<DataSource>()
        val connection = mockk<Connection>()
        val readStatement = mockk<Statement>()
        val writeStatement = mockk<Statement>()
        val configuration = ReadConfiguration()
        val readManager: JdbcTransactionManager = configuration.transactionManager(source)
        val readOnly: TransactionTemplate = configuration.readOnlyTransaction(readManager)
        val write = TransactionTemplate(JdbcTransactionManager(source))

        every { source.connection } returns connection
        every { connection.autoCommit } returns true
        every { connection.createStatement() } returnsMany listOf(readStatement, writeStatement)
        every { readStatement.executeUpdate(any<String>()) } returns 0
        every { writeStatement.executeUpdate(any<String>()) } returns 0
        justRun { readStatement.close() }
        justRun { writeStatement.close() }
        justRun { connection.setReadOnly(any()) }
        justRun { connection.setAutoCommit(any()) }
        justRun { connection.commit() }
        justRun { connection.close() }

        // when
        readOnly.executeWithoutResult { }
        write.executeWithoutResult { }

        // then
        verify(exactly = 1) { readStatement.executeUpdate("SET TRANSACTION READ ONLY") }
        verify(exactly = 1) { connection.setReadOnly(true) }
        verify(exactly = 0) { writeStatement.executeUpdate("SET TRANSACTION READ ONLY") }
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
