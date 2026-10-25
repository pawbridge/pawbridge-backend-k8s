package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.StoreWishlistConfiguration
import com.pawbridge.backend.store.internal.wishlist.application.WishlistCommands
import com.pawbridge.backend.store.internal.wishlist.infrastructure.JdbcWishlistCommands
import com.pawbridge.backend.store.internal.wishlist.web.WishlistCommandController
import com.pawbridge.backend.store.internal.wishlist.web.WishlistCommandExceptionHandler
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.client.RestClient
import software.amazon.awssdk.services.s3.S3Client
import java.net.http.HttpClient
import java.time.Clock
import java.time.ZoneId
import java.util.function.Supplier

class StoreWishlistConfigurationTest {
    @Test
    fun given__absent_or_false_flag__when__loading_imported_configuration_without_inputs__then__no_writer_or_routes() {
        assertThat(PawBridgeApplication::class.java.getAnnotation(Import::class.java).value.map { it.java })
            .contains(StoreWishlistConfiguration::class.java)
        for (flag in arrayOf("", "pawbridge.store-wishlist.enabled=false")) {
            var runner = ApplicationContextRunner().withUserConfiguration(StoreWishlistConfiguration::class.java)
            if (flag.isNotEmpty()) {
                runner = runner.withPropertyValues(flag)
            }
            runner.run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBeansOfType(HikariDataSource::class.java)).isEmpty()
                assertThat(context.getBeansOfType(JdbcTemplate::class.java)).isEmpty()
                assertThat(context.getBeansOfType(TransactionTemplate::class.java)).isEmpty()
                assertThat(context.getBeansOfType(Clock::class.java)).isEmpty()
                assertThat(context.getBeansOfType(WishlistCommands::class.java)).isEmpty()
                assertThat(context.getBeansOfType(WishlistCommandController::class.java)).isEmpty()
                assertThat(context.getBeansOfType(WishlistCommandExceptionHandler::class.java)).isEmpty()
            }
        }
    }

    @Test
    fun given__explicit_flag__when__assembling_and_closing__then__bind_the_shared_pool_and_system_clock_without_own_pool_clients_or_connection() {
        val shared = mock(HikariDataSource::class.java)
        mockConstruction(HikariDataSource::class.java).use { constructions ->
            ApplicationContextRunner().withUserConfiguration(StoreWishlistConfiguration::class.java)
                .withBean("storeWishlistWriteDataSource", HikariDataSource::class.java, Supplier { shared })
                .withPropertyValues(
                    "pawbridge.store-wishlist.enabled=true",
                    "STORE_WISHLIST_DB_WRITER_USER=ignored-legacy-writer",
                    "STORE_WISHLIST_DB_WRITER_PASSWORD=ignored-legacy-only",
                )
                .run { context ->
                    assertThat(context).hasNotFailed()
                    assertThat(context.getBeansOfType(HikariDataSource::class.java))
                        .containsOnlyKeys("storeWishlistWriteDataSource")
                    val jdbc = context.getBean("storeWishlistWriteJdbc", JdbcTemplate::class.java)
                    val manager = context.getBean("storeWishlistWriteTransactionManager", JdbcTransactionManager::class.java)
                    val transaction = context.getBean("storeWishlistWriteTransaction", TransactionTemplate::class.java)
                    assertThat(jdbc.dataSource).isSameAs(shared)
                    assertThat(jdbc.queryTimeout).isEqualTo(5)
                    assertThat(manager.dataSource).isSameAs(shared)
                    assertThat(transaction.transactionManager).isSameAs(manager)
                    assertThat(transaction.timeout).isEqualTo(10)
                    assertThat(transaction.isReadOnly).isFalse()
                    val command = context.getBean(WishlistCommands::class.java) as JdbcWishlistCommands
                    assertThat((ReflectionTestUtils.getField(command, "jdbc") as NamedParameterJdbcTemplate).jdbcOperations)
                        .isSameAs(jdbc)
                    assertThat(ReflectionTestUtils.getField(command, "write")).isSameAs(transaction)
                    val clock = context.getBean("storeWishlistClock", Clock::class.java)
                    assertThat(clock.zone).isEqualTo(ZoneId.systemDefault())
                    assertThat(ReflectionTestUtils.getField(command, "clock")).isSameAs(clock)
                    assertThat(context.getBeansOfType(WishlistCommandController::class.java)).hasSize(1)
                    assertThat(context.getBeansOfType(WishlistCommandExceptionHandler::class.java)).hasSize(1)
                    assertThat(context.getBeansOfType(HttpClient::class.java)).isEmpty()
                    assertThat(context.getBeansOfType(RestClient::class.java)).isEmpty()
                    assertThat(context.getBeansOfType(S3Client::class.java)).isEmpty()
                }
            assertThat(constructions.constructed()).isEmpty()
            verify(shared, never()).getConnection()
            verify(shared, never()).getConnection(anyString(), anyString())
        }
    }
}
