package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.store.api.StorePaymentEvents
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ZSetOperations
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.test.util.AopTestUtils
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.ZoneId
import java.util.function.Supplier
import javax.sql.DataSource

/** Real writer-unit wiring and dormant Redis resource contracts; no DB/Redis connection is requested. */
class StorePaymentEventsConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = ["absent", "false", "yes", "on"])
    fun given__payment_events_not_explicitly_enabled__when__assembling__then__create_no_business_writer_or_redis_resources(
        flag: String,
    ) {
        var runner = runner()
        if (flag != "absent") {
            runner = runner.withPropertyValues("pawbridge.store-payment-events.enabled=$flag")
        }
        runner.run { context ->
            assertThat(context).hasNotFailed()
                .doesNotHaveBean(StorePaymentEvents::class.java)
                .doesNotHaveBean(EntityManagerFactory::class.java)
                .doesNotHaveBean(DataSource::class.java)
                .doesNotHaveBean(RedisConnectionFactory::class.java)
                .doesNotHaveBean(StringRedisTemplate::class.java)
        }
    }

    @Test
    fun given__explicit_business_only__when__assembling__then__reuse_one_store_writer_and_actual_stock_projection_without_starting_clients() {
        val writer: DataSource = mock(DataSource::class.java)
        inputs(writer).run { context ->
            assertThat(context).hasNotFailed()
                .hasSingleBean(StorePaymentEvents::class.java)
                .hasSingleBean(EntityManagerFactory::class.java)
                .hasSingleBean(DataSource::class.java)
                .doesNotHaveBean(RedisConnectionFactory::class.java)
                .hasSingleBean(StringRedisTemplate::class.java)
            val service = context.getBean(StorePaymentEvents::class.java)
            val shared = context.getBean("storeDirectOrderEntityManager", EntityManager::class.java)
            val factory = context.getBean("storeDirectOrderEntityManagerFactory", EntityManagerFactory::class.java)
            val writes = context.getBean("storeDirectOrderWrites", TransactionTemplate::class.java)
            assertThat(field(service, "writes")).isSameAs(writes)
            assertThat(writes.transactionManager).isSameAs(context.getBean("storeDirectOrderJpaTransactionManager"))
            assertThat(
                context.getBean("storeDirectOrderJpaTransactionManager", JpaTransactionManager::class.java).dataSource,
            ).isSameAs(writer)
            assertThat(shared.entityManagerFactory).isSameAs(factory)
            assertThat(repositoryEntityManager(field(service, "orders")).entityManagerFactory).isSameAs(factory)
            val mandatory = field(service, "mandatoryWrites") as TransactionTemplate
            assertThat(mandatory.transactionManager).isSameAs(context.getBean("storeDirectOrderJpaTransactionManager"))
            assertThat(mandatory.propagationBehavior).isEqualTo(TransactionDefinition.PROPAGATION_MANDATORY)
            assertThat(mandatory.isReadOnly).isFalse()
            val bridge = context.getBean("storePaymentEventStocks", StorePaymentEvents.StockRestorer::class.java)
            val stock = field(bridge, "delegate")
            val snapshots = field(stock, "snapshots")
            val documents = field(snapshots, "documents")
            assertThat(repositoryEntityManager(field(stock, "products")).entityManagerFactory).isSameAs(factory)
            assertThat(repositoryEntityManager(field(stock, "skus")).entityManagerFactory).isSameAs(factory)
            assertThat(field(documents, "jdbc")).isSameAs(context.getBean("storeDirectOrderSearchJdbc"))
            assertThat((field(documents, "jdbc") as NamedParameterJdbcTemplate).jdbcTemplate.dataSource).isSameAs(writer)
            assertThat(field(documents, "terms")).isSameAs(context.getBean("storeDirectOrderSearchTerms"))
            assertThat(context.getBean("storePaymentEventClock", Clock::class.java).zone).isEqualTo(ZoneId.systemDefault())
            assertThat(context.getBean("storePaymentRankingRedis", StringRedisTemplate::class.java).connectionFactory)
                .isInstanceOf(LettuceConnectionFactory::class.java)
        }
        verifyNoInteractions(writer)
    }

    @Test
    fun given__an_explicit_existing_redis_template__when__ranking_is_invoked__then__delegate_original_sorted_set_increment_without_creating_another_client() {
        val writer: DataSource = mock(DataSource::class.java)
        val redis = mock(StringRedisTemplate::class.java)
        @Suppress("UNCHECKED_CAST")
        val sorted = mock(ZSetOperations::class.java) as ZSetOperations<String, String>
        whenever(redis.opsForZSet()).thenReturn(sorted)
        inputs(writer).withBean("memberChatRedis", StringRedisTemplate::class.java, Supplier { redis }).run { context ->
            assertThat(context).hasNotFailed().doesNotHaveBean(RedisConnectionFactory::class.java)
            assertThat(context.getBean("storePaymentRankingRedis")).isSameAs(redis)
            context.getBean(StorePaymentEvents.Ranking::class.java).increment("store:ranking:daily:20261021", "73", 2.0)
            verify(sorted).incrementScore("store:ranking:daily:20261021", "73", 2.0)
            verifyNoMoreInteractions(sorted)
        }
        verifyNoInteractions(writer)
    }

    private fun runner(): ApplicationContextRunner {
        return ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration::class.java))
            .withUserConfiguration(StorePaymentEventsConfiguration::class.java)
    }

    private fun inputs(writer: DataSource): ApplicationContextRunner {
        return runner()
            .withPropertyValues("pawbridge.store-payment-events.enabled=true", "spring.data.redis.host=127.0.0.1")
            .withBean(
                "storePaymentOwnedRedisFactory",
                StorePaymentTransportConfiguration.OwnedRedisFactory::class.java,
                Supplier {
                    StorePaymentTransportConfiguration.OwnedRedisFactory { settings ->
                        mock(LettuceConnectionFactory::class.java)
                    }
                },
            )
            .withBean("writeDataSource", DataSource::class.java, Supplier { writer })
            .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { JdbcTemplate(writer) })
            .withBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
    }

    private fun field(target: Any?, name: String): Any {
        return ReflectionTestUtils.getField(target!!, name)!!
    }

    private fun repositoryEntityManager(repository: Any): EntityManager {
        return field(AopTestUtils.getUltimateTargetObject<Any>(repository), "entityManager") as EntityManager
    }
}
