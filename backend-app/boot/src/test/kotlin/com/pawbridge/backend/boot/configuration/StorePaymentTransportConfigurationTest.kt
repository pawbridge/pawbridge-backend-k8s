package com.pawbridge.backend.boot.configuration

import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.excludeRecords
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier

class StorePaymentTransportConfigurationTest {
    private fun runner(): ApplicationContextRunner {
        return ApplicationContextRunner().withUserConfiguration(StorePaymentTransportConfiguration::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["absent", "false", "yes", "on"])
    fun `전송이 명시적으로 켜지지 않았을 때__설정이 없으면__레디스 자원을 만들지 않는다`(
        flag: String,
    ) {
        // given
        var context = runner()
        if (flag != "absent") {
            context = context.withPropertyValues("pawbridge.store-payment-events.enabled=$flag")
        }

        // when
        context.run { app ->
            // then
            assertThat(app).hasNotFailed()
                .doesNotHaveBean(StringRedisTemplate::class.java)
                .doesNotHaveBean(RedisConnectionFactory::class.java)
                .doesNotHaveBean(StorePaymentTransportConfiguration.RedisResource::class.java)
        }
    }

    @Test
    fun `기존 채팅 템플릿이 있을 때__스토어 전송이 닫히면__같은 것을 빌리고 새 팩토리도 소유 해제도 없다`() {
        // given
        val global = mockk<RedisConnectionFactory>()
        val shared = StringRedisTemplate(global)
        val factories = mockk<StorePaymentTransportConfiguration.OwnedRedisFactory>()

        excludeRecords {
            factories.hashCode()
            global.hashCode()
        }

        // when
        runner().withPropertyValues("pawbridge.store-payment-events.enabled=true")
            .withBean("memberChatRedisConnectionFactory", RedisConnectionFactory::class.java, Supplier { global })
            .withBean("memberChatRedis", StringRedisTemplate::class.java, Supplier { shared })
            .withBean(
                "storePaymentOwnedRedisFactory",
                StorePaymentTransportConfiguration.OwnedRedisFactory::class.java,
                Supplier { factories },
            )
            .run { app ->
                // then
                assertThat(app).hasNotFailed().hasSingleBean(RedisConnectionFactory::class.java)
                assertThat(app.getBean("storePaymentRankingRedis")).isSameAs(shared)
                assertThat(app.getBean(RedisConnectionFactory::class.java)).isSameAs(global)
                app.getBean(StorePaymentTransportConfiguration.RedisResource::class.java).close()
                confirmVerified(factories, global)
            }
        confirmVerified(factories, global)
    }

    @Test
    fun `채팅이 없고 레디스 설정이 명시됐을 때__스토어 전송이 닫히면__전용 팩토리 하나를 소유하고 정확히 한 번 파기한다`() {
        // given
        val owned = mockk<LettuceConnectionFactory>()
        val seen = AtomicReference<RedisStandaloneConfiguration>()

        justRun { owned.afterPropertiesSet() }
        justRun { owned.destroy() }

        // when
        runner().withPropertyValues(
            "pawbridge.store-payment-events.enabled=true",
            "spring.data.redis.host=127.0.0.1",
            "spring.data.redis.port=6381",
            "spring.data.redis.database=2",
            "spring.data.redis.username=synthetic-user",
            "spring.data.redis.password=synthetic-private-password",
        )
            .withBean(
                "storePaymentOwnedRedisFactory",
                StorePaymentTransportConfiguration.OwnedRedisFactory::class.java,
                Supplier {
                    StorePaymentTransportConfiguration.OwnedRedisFactory { settings ->
                        seen.set(settings)
                        owned
                    }
                },
            )
            .run { app ->
                // then
                assertThat(app).hasNotFailed()
                    .hasSingleBean(StringRedisTemplate::class.java)
                    .doesNotHaveBean(RedisConnectionFactory::class.java)
                assertThat(app.getBean("storePaymentRankingRedis", StringRedisTemplate::class.java).connectionFactory)
                    .isSameAs(owned)
                assertThat(seen.get().hostName).isEqualTo("127.0.0.1")
                assertThat(seen.get().port).isEqualTo(6381)
                assertThat(seen.get().database).isEqualTo(2)
                assertThat(seen.get().username).isEqualTo("synthetic-user")
                assertThat(String(seen.get().password.get())).isEqualTo("synthetic-private-password")
                verify(exactly = 1) { owned.afterPropertiesSet() }
                verify(exactly = 0) { owned.connection }
                val resource = app.getBean(StorePaymentTransportConfiguration.RedisResource::class.java)
                resource.close()
                resource.close()
            }
        verify(exactly = 1) { owned.destroy() }
        verify(exactly = 0) { owned.connection }
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "blank", "uri", "port", "database"])
    fun `필수 레디스 설정이 잘못됐을 때__채팅 없이 켜면__팩토리를 만들기 전에 안전하게 실패한다`(
        invalid: String,
    ) {
        // given
        val factories = mockk<StorePaymentTransportConfiguration.OwnedRedisFactory>()

        excludeRecords {
            factories.hashCode()
        }

        var context = runner().withPropertyValues("pawbridge.store-payment-events.enabled=true")
        if (invalid != "missing") {
            context = context.withPropertyValues("spring.data.redis.host=127.0.0.1")
        }
        context = when (invalid) {
            "blank" -> context.withPropertyValues("spring.data.redis.host= ")
            "uri" -> context.withPropertyValues("spring.data.redis.host=redis://synthetic-secret@host.invalid")
            "port" -> context.withPropertyValues("spring.data.redis.port=70000")
            "database" -> context.withPropertyValues("spring.data.redis.database=-1")
            else -> context
        }

        // when
        context.withBean(
            "storePaymentOwnedRedisFactory",
            StorePaymentTransportConfiguration.OwnedRedisFactory::class.java,
            Supplier { factories },
        )
            .run { app ->
                // then
                assertThat(app).hasFailed()
                assertThat(app.startupFailure)
                    .hasRootCauseMessage("Explicit valid Store payment Redis settings are required")
                assertThat(app.startupFailure.toString()).doesNotContain("synthetic-secret")
                confirmVerified(factories)
            }
    }

    @ParameterizedTest
    @ValueSource(strings = ["create", "initialize"])
    fun `소유 초기화가 실패할 때__켜면__부분 소유를 해제하고 비공개 원인을 시작 오류에 싣지 않는다`(
        stage: String,
    ) {
        // given
        val owned = mockk<LettuceConnectionFactory>()

        if (stage == "initialize") {
            every { owned.afterPropertiesSet() } throws IllegalStateException("synthetic-private-factory-message")
        }
        justRun { owned.destroy() }

        // when
        runner().withPropertyValues("pawbridge.store-payment-events.enabled=true", "spring.data.redis.host=127.0.0.1")
            .withBean(
                "storePaymentOwnedRedisFactory",
                StorePaymentTransportConfiguration.OwnedRedisFactory::class.java,
                Supplier {
                    StorePaymentTransportConfiguration.OwnedRedisFactory { settings ->
                        if (stage == "create") {
                            throw IllegalStateException("synthetic-private-factory-message")
                        }
                        owned
                    }
                },
            )
            .run { app ->
                // then
                assertThat(app).hasFailed()
                assertThat(app.startupFailure).hasRootCauseMessage("Store payment Redis initialization failed")
                assertThat(app.startupFailure.toString()).doesNotContain("synthetic-private-factory-message")
            }
        verify(exactly = if (stage == "initialize") 1 else 0) { owned.destroy() }
        verify(exactly = 0) { owned.connection }
    }
}
