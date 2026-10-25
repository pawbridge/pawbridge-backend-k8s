package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.boot.javaIsBlank
import java.util.concurrent.atomic.AtomicBoolean
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.data.redis.connection.RedisPassword
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate

/** Explicit, dormant Store transport. A privately owned Redis factory never becomes a global bean. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge.store-payment-events", name = ["enabled"], havingValue = "true")
class StorePaymentTransportConfiguration {
    /** The factory may return null (a misbehaving implementation); the caller rejects that explicitly. */
    fun interface OwnedRedisFactory {
        fun create(settings: RedisStandaloneConfiguration): LettuceConnectionFactory?
    }

    @Bean
    @ConditionalOnMissingBean(name = ["storePaymentOwnedRedisFactory"])
    fun storePaymentOwnedRedisFactory(): OwnedRedisFactory {
        return OwnedRedisFactory { settings -> LettuceConnectionFactory(settings) }
    }

    @Bean(destroyMethod = "close")
    fun storePaymentRedisResource(
        @Qualifier("memberChatRedis") shared: ObjectProvider<StringRedisTemplate>,
        @Qualifier("storePaymentOwnedRedisFactory") factories: OwnedRedisFactory,
        environment: Environment,
    ): RedisResource {
        val borrowed = shared.getIfAvailable()
        if (borrowed != null) {
            return RedisResource(borrowed, null)
        }
        val settings = redisSettings(environment)
        var owned: LettuceConnectionFactory? = null
        try {
            owned = factories.create(settings)
            if (owned == null) {
                throw IllegalStateException()
            }
            owned.afterPropertiesSet()
            return RedisResource(StringRedisTemplate(owned), owned)
        } catch (failure: RuntimeException) {
            try {
                owned?.destroy()
            } catch (ignored: RuntimeException) {
                // The original initialization failure is the one reported.
            }
            throw IllegalStateException("Store payment Redis initialization failed")
        }
    }

    @Bean(destroyMethod = "")
    fun storePaymentRankingRedis(resource: RedisResource): StringRedisTemplate {
        return resource.template
    }

    private fun redisSettings(environment: Environment): RedisStandaloneConfiguration {
        try {
            val host = environment.getProperty("spring.data.redis.host")
            val port = environment.getProperty("spring.data.redis.port", Int::class.javaObjectType, 6379)
            val database = environment.getProperty("spring.data.redis.database", Int::class.javaObjectType, 0)
            if (host == null || javaIsBlank(host) || host.contains("://") || host.contains("/") || host.contains("@") ||
                host.any { Character.isWhitespace(it) } || port < 1 || port > 65535 || database < 0
            ) {
                throw IllegalArgumentException()
            }
            val settings = RedisStandaloneConfiguration(host, port)
            settings.database = database
            val username = environment.getProperty("spring.data.redis.username", "")
            val password = environment.getProperty("spring.data.redis.password", "")
            if (!javaIsBlank(username)) {
                settings.username = username
            }
            if (password.isNotEmpty()) {
                settings.password = RedisPassword.of(password)
            }
            return settings
        } catch (invalid: RuntimeException) {
            throw IllegalArgumentException(INVALID_REDIS)
        }
    }

    class RedisResource(
        val template: StringRedisTemplate,
        private val owned: LettuceConnectionFactory?,
    ) : AutoCloseable {
        private val closed = AtomicBoolean()

        override fun close() {
            if (owned != null && closed.compareAndSet(false, true)) {
                try {
                    owned.destroy()
                } catch (failure: RuntimeException) {
                    throw IllegalStateException("Store payment Redis resource did not close")
                }
            }
        }
    }

    private companion object {
        const val INVALID_REDIS = "Explicit valid Store payment Redis settings are required"
    }
}
