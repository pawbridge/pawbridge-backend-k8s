package com.pawbridge.backend.boot.configuration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
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
    fun given__transport_not_explicitly_enabled__when__settings_are_absent__then__create_no_redis_resource(
        flag: String,
    ) {
        var context = runner()
        if (flag != "absent") {
            context = context.withPropertyValues("pawbridge.store-payment-events.enabled=$flag")
        }
        context.run { app ->
            assertThat(app).hasNotFailed()
                .doesNotHaveBean(StringRedisTemplate::class.java)
                .doesNotHaveBean(RedisConnectionFactory::class.java)
                .doesNotHaveBean(StorePaymentTransportConfiguration.RedisResource::class.java)
        }
    }

    @Test
    fun given__an_existing_chat_template__when__store_transport_closes__then__borrow_the_same_identity_without_another_global_factory_or_owner_close() {
        val global = mock(RedisConnectionFactory::class.java)
        val shared = StringRedisTemplate(global)
        val factories = mock(StorePaymentTransportConfiguration.OwnedRedisFactory::class.java)
        runner().withPropertyValues("pawbridge.store-payment-events.enabled=true")
            .withBean("memberChatRedisConnectionFactory", RedisConnectionFactory::class.java, Supplier { global })
            .withBean("memberChatRedis", StringRedisTemplate::class.java, Supplier { shared })
            .withBean(
                "storePaymentOwnedRedisFactory",
                StorePaymentTransportConfiguration.OwnedRedisFactory::class.java,
                Supplier { factories },
            )
            .run { app ->
                assertThat(app).hasNotFailed().hasSingleBean(RedisConnectionFactory::class.java)
                assertThat(app.getBean("storePaymentRankingRedis")).isSameAs(shared)
                assertThat(app.getBean(RedisConnectionFactory::class.java)).isSameAs(global)
                app.getBean(StorePaymentTransportConfiguration.RedisResource::class.java).close()
                verifyNoInteractions(factories, global)
            }
        verifyNoInteractions(factories, global)
    }

    @Test
    fun given__no_chat_and_explicit_redis_settings__when__store_transport_closes__then__own_one_private_factory_and_destroy_it_exactly_once() {
        val owned = mock(LettuceConnectionFactory::class.java)
        val seen = AtomicReference<RedisStandaloneConfiguration>()
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
                verify(owned).afterPropertiesSet()
                verify(owned, never()).getConnection()
                val resource = app.getBean(StorePaymentTransportConfiguration.RedisResource::class.java)
                resource.close()
                resource.close()
            }
        verify(owned, times(1)).destroy()
        verify(owned, never()).getConnection()
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "blank", "uri", "port", "database"])
    fun given__invalid_required_redis_settings__when__enabling_without_chat__then__fail_safely_before_factory_creation(
        invalid: String,
    ) {
        val factories = mock(StorePaymentTransportConfiguration.OwnedRedisFactory::class.java)
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
        context.withBean(
            "storePaymentOwnedRedisFactory",
            StorePaymentTransportConfiguration.OwnedRedisFactory::class.java,
            Supplier { factories },
        )
            .run { app ->
                assertThat(app).hasFailed()
                assertThat(app.startupFailure)
                    .hasRootCauseMessage("Explicit valid Store payment Redis settings are required")
                assertThat(app.startupFailure.toString()).doesNotContain("synthetic-secret")
                verifyNoInteractions(factories)
            }
    }

    @ParameterizedTest
    @ValueSource(strings = ["create", "initialize"])
    fun given__owned_initialization_failure__when__enabling__then__release_partial_ownership_and_keep_private_causes_out_of_the_startup_error(
        stage: String,
    ) {
        val owned = mock(LettuceConnectionFactory::class.java)
        if (stage == "initialize") {
            doThrow(IllegalStateException("synthetic-private-factory-message")).`when`(owned).afterPropertiesSet()
        }
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
                assertThat(app).hasFailed()
                assertThat(app.startupFailure).hasRootCauseMessage("Store payment Redis initialization failed")
                assertThat(app.startupFailure.toString()).doesNotContain("synthetic-private-factory-message")
            }
        verify(owned, times(if (stage == "initialize") 1 else 0)).destroy()
        verify(owned, never()).getConnection()
    }
}
