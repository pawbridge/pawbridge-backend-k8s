package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.StoreWishlistConfiguration
import com.pawbridge.backend.store.internal.wishlist.application.WishlistCommands
import com.pawbridge.backend.store.internal.wishlist.web.WishlistCommandController
import com.pawbridge.backend.store.internal.wishlist.web.WishlistCommandExceptionHandler
import com.zaxxer.hikari.HikariDataSource
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mockConstruction
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.client.RestClient
import software.amazon.awssdk.services.s3.S3Client
import java.net.http.HttpClient
import java.time.Clock
import java.time.ZoneId
import java.util.function.Supplier

class StoreWishlistConfigurationTest {
    @Test
    fun `플래그가 없거나 false일 때__입력 없이 가져온 설정을 로드하면__작성기와 경로가 없다`() {
        // given
        val flags = arrayOf("", "pawbridge.store-wishlist.enabled=false")

        // when
        val imports = PawBridgeApplication::class.java.getAnnotation(Import::class.java).value.map { it.java }

        // then
        assertThat(imports).contains(StoreWishlistConfiguration::class.java)
        for (flag in flags) {
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
    fun `명시적 플래그가 있을 때__조립하고 닫으면__자체 풀과 클라이언트와 연결 없이 공유 풀과 시스템 시계를 묶는다`() {
        // given
        val shared = mockk<HikariDataSource>()

        justRun { shared.close() }

        // MockK cannot count constructor calls, so this test keeps Mockito's mockConstruction
        // to prove that the configuration never builds its own HikariDataSource.
        mockConstruction(HikariDataSource::class.java).use { constructions ->
            // when
            ApplicationContextRunner().withUserConfiguration(StoreWishlistConfiguration::class.java)
                .withBean("storeWishlistWriteDataSource", HikariDataSource::class.java, Supplier { shared })
                .withPropertyValues(
                    "pawbridge.store-wishlist.enabled=true",
                    "STORE_WISHLIST_DB_WRITER_USER=ignored-legacy-writer",
                    "STORE_WISHLIST_DB_WRITER_PASSWORD=ignored-legacy-only",
                )
                .run { context ->
                    // then
                    assertThat(context).hasNotFailed()
                    assertThat(context.getBeansOfType(HikariDataSource::class.java))
                        .containsOnlyKeys("storeWishlistWriteDataSource")
                    val jdbc = context.getBean("storeWishlistWriteJdbc", JdbcTemplate::class.java)
                    val transaction = context.getBean("storeWishlistWriteTransaction", TransactionTemplate::class.java)
                    assertThat(jdbc.queryTimeout).isEqualTo(5)
                    assertThat(transaction.timeout).isEqualTo(10)
                    assertThat(transaction.isReadOnly).isFalse()
                    val clock = context.getBean("storeWishlistClock", Clock::class.java)
                    assertThat(clock.zone).isEqualTo(ZoneId.systemDefault())
                    assertThat(context.getBeansOfType(WishlistCommandController::class.java)).hasSize(1)
                    assertThat(context.getBeansOfType(WishlistCommandExceptionHandler::class.java)).hasSize(1)
                    assertThat(context.getBeansOfType(HttpClient::class.java)).isEmpty()
                    assertThat(context.getBeansOfType(RestClient::class.java)).isEmpty()
                    assertThat(context.getBeansOfType(S3Client::class.java)).isEmpty()
                }

            // then
            assertThat(constructions.constructed()).isEmpty()
            verify(exactly = 0) { shared.connection }
            verify(exactly = 0) { shared.getConnection(any(), any()) }
        }
    }
}
