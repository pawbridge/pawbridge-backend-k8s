package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.store.api.StorePaymentEvents
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.excludeRecords
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ZSetOperations
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.ZoneId
import java.util.function.Supplier
import javax.sql.DataSource

/** Real writer-unit wiring and dormant Redis resource contracts; no DB/Redis connection is requested. */
class StorePaymentEventsConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = ["absent", "false", "yes", "on"])
    fun `결제 이벤트가 명시적으로 켜지지 않았을 때__조립하면__비즈니스·쓰기·레디스 자원을 만들지 않는다`(
        flag: String,
    ) {
        // given
        var runner = runner()
        if (flag != "absent") {
            runner = runner.withPropertyValues("pawbridge.store-payment-events.enabled=$flag")
        }

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
                .doesNotHaveBean(StorePaymentEvents::class.java)
                .doesNotHaveBean(EntityManagerFactory::class.java)
                .doesNotHaveBean(DataSource::class.java)
                .doesNotHaveBean(RedisConnectionFactory::class.java)
                .doesNotHaveBean(StringRedisTemplate::class.java)
        }
    }

    @Test
    fun `비즈니스만 명시됐을 때__조립하면__클라이언트 시작 없이 기록 DB 연결을 열지 않는다`() {
        // given
        val writer = mockk<DataSource>()
        val lettuce = mockk<LettuceConnectionFactory>()

        excludeRecords {
            writer.hashCode()
        }

        justRun { lettuce.afterPropertiesSet() }
        justRun { lettuce.destroy() }

        // when
        inputs(writer, lettuce).run { context ->
            // then
            assertThat(context).hasNotFailed()
                .hasSingleBean(StorePaymentEvents::class.java)
                .hasSingleBean(EntityManagerFactory::class.java)
                .hasSingleBean(DataSource::class.java)
                .doesNotHaveBean(RedisConnectionFactory::class.java)
                .hasSingleBean(StringRedisTemplate::class.java)
            assertThat(context.getBean("storePaymentEventClock", Clock::class.java).zone).isEqualTo(ZoneId.systemDefault())
            assertThat(context.getBean("storePaymentRankingRedis", StringRedisTemplate::class.java).connectionFactory)
                .isInstanceOf(LettuceConnectionFactory::class.java)
        }
        confirmVerified(writer)
    }

    @Test
    fun `기존 레디스 템플릿이 명시됐을 때__랭킹을 호출하면__다른 클라이언트 없이 원래 정렬 집합 증가를 위임한다`() {
        // given
        val writer = mockk<DataSource>()
        val lettuce = mockk<LettuceConnectionFactory>()
        val redis = mockk<StringRedisTemplate>()
        val sorted = mockk<ZSetOperations<String, String>>()

        excludeRecords {
            sorted.hashCode()
            writer.hashCode()
        }

        justRun { redis.setBeanClassLoader(any()) }
        justRun { redis.afterPropertiesSet() }
        every { redis.opsForZSet() } returns sorted
        every { sorted.incrementScore(any<String>(), any<String>(), any<Double>()) } returns 2.0

        // when
        inputs(writer, lettuce).withBean("memberChatRedis", StringRedisTemplate::class.java, Supplier { redis }).run { context ->
            assertThat(context).hasNotFailed().doesNotHaveBean(RedisConnectionFactory::class.java)
            context.getBean(StorePaymentEvents.Ranking::class.java).increment("store:ranking:daily:20261021", "73", 2.0)

            // then
            verify(exactly = 1) { sorted.incrementScore("store:ranking:daily:20261021", "73", 2.0) }
            confirmVerified(sorted)
        }
        confirmVerified(writer)
    }

    private fun runner(): ApplicationContextRunner {
        return ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration::class.java))
            .withUserConfiguration(StorePaymentEventsConfiguration::class.java)
    }

    private fun inputs(writer: DataSource, lettuce: LettuceConnectionFactory): ApplicationContextRunner {
        return runner()
            .withPropertyValues("pawbridge.store-payment-events.enabled=true", "spring.data.redis.host=127.0.0.1")
            .withBean(
                "storePaymentOwnedRedisFactory",
                StorePaymentTransportConfiguration.OwnedRedisFactory::class.java,
                Supplier {
                    StorePaymentTransportConfiguration.OwnedRedisFactory { settings ->
                        lettuce
                    }
                },
            )
            .withBean("writeDataSource", DataSource::class.java, Supplier { writer })
            .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { JdbcTemplate(writer) })
            .withBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
    }
}
