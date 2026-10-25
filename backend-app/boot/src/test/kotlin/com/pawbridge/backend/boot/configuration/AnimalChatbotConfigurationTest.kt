package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.client.PythonChatbotClient
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.repository.ChatbotBlockLogRepository
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.repository.ChatbotMessageRepository
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.repository.ChatbotSessionRepository
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.service.ChatbotRateLimitService
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.service.ChatbotService
import com.pawbridge.backend.animal.internal.chatbot.web.ChatbotController
import com.pawbridge.backend.animal.internal.chatbot.web.ChatbotExceptionHandler
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.redisson.api.RedissonClient
import org.redisson.config.Config
import org.redisson.config.SingleServerConfig
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.data.auditing.AuditingHandler
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.test.util.AopTestUtils
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier
import javax.sql.DataSource

class AnimalChatbotConfigurationTest {
    private fun web(): WebApplicationContextRunner {
        return WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration::class.java))
            .withUserConfiguration(AnimalChatbotConfiguration::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["absent", "false"])
    fun given__chatbot_off_without_settings__when__assembling__then__create_no_chatbot_client_entity_manager_or_database_resource(
        flag: String,
    ) {
        var runner = web().withPropertyValues("pawbridge.animal-query.backend=postgresql")
        if (flag != "absent") {
            runner = runner.withPropertyValues("pawbridge.animal-chatbot.enabled=$flag")
        }
        runner.run { context ->
            assertThat(context).hasNotFailed()
                .doesNotHaveBean(ChatbotService::class.java)
                .doesNotHaveBean(ChatbotController::class.java)
                .doesNotHaveBean(PythonChatbotClient::class.java)
                .doesNotHaveBean(RedissonClient::class.java)
                .doesNotHaveBean(EntityManagerFactory::class.java)
                .doesNotHaveBean(DataSource::class.java)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["absent", "http", "mysql"])
    fun given__non_postgresql_backend__when__enabling_chatbot_without_settings__then__keep_client_and_writer_unassembled(
        backend: String,
    ) {
        var runner = web().withPropertyValues("pawbridge.animal-chatbot.enabled=true")
        if (backend != "absent") {
            runner = runner.withPropertyValues("pawbridge.animal-query.backend=$backend")
        }
        runner.run { context ->
            assertThat(context).hasNotFailed()
                .doesNotHaveBean(ChatbotService::class.java)
                .doesNotHaveBean(RedissonClient::class.java)
                .doesNotHaveBean(EntityManagerFactory::class.java)
                .doesNotHaveBean(DataSource::class.java)
        }
    }

    @Test
    fun given__non_servlet_context__when__enabling_postgresql_chatbot__then__do_not_create_clients_or_writer() {
        ApplicationContextRunner().withUserConfiguration(AnimalChatbotConfiguration::class.java)
            .withPropertyValues("pawbridge.animal-chatbot.enabled=true", "pawbridge.animal-query.backend=postgresql")
            .run { context ->
                assertThat(context).hasNotFailed()
                    .doesNotHaveBean(ChatbotService::class.java)
                    .doesNotHaveBean(RedissonClient::class.java)
                    .doesNotHaveBean(EntityManagerFactory::class.java)
            }
    }

    @Test
    fun given__explicit_settings_and_injected_redis_factory__when__assembling_then_closing__then__reuse_one_writer_shared_em_and_short_transactions_and_close_owned_client_once() {
        val inputs = Inputs()
        val emf = AtomicReference<EntityManagerFactory>()
        inputs.runner().run { context ->
            assertThat(context).hasNotFailed()
                .hasSingleBean(ChatbotService::class.java)
                .hasSingleBean(ChatbotController::class.java)
                .hasSingleBean(ChatbotExceptionHandler::class.java)
                .hasSingleBean(PythonChatbotClient::class.java)
                .hasSingleBean(RedissonClient::class.java)
                .hasSingleBean(JpaAuditingConfiguration::class.java)
                .hasSingleBean(AuditingHandler::class.java)
            assertThat(context.getBeansOfType(EntityManagerFactory::class.java)).hasSize(1)
            assertThat(context.getBeansOfType(DataSource::class.java)).hasSize(2)
            emf.set(context.getBean("animalCommandEntityManagerFactory", EntityManagerFactory::class.java))
            assertThat(
                context.getBean("&animalCommandEntityManagerFactory", LocalContainerEntityManagerFactoryBean::class.java)
                    .dataSource,
            ).isSameAs(inputs.writer)
            val manager = context.getBean("animalCommandJpaTransactionManager", JpaTransactionManager::class.java)
            assertThat(manager.dataSource).isSameAs(inputs.writer)
            assertThat(manager.entityManagerFactory).isSameAs(emf.get())
            assertThat(manager.defaultTimeout).isEqualTo(15)
            val shared = context.getBean("animalCommandEntityManager", EntityManager::class.java)
            val repositories: List<Any> = listOf(
                context.getBean(ChatbotSessionRepository::class.java),
                context.getBean(ChatbotMessageRepository::class.java),
                context.getBean(ChatbotBlockLogRepository::class.java),
            )
            for (repository in repositories) {
                val target: Any = AopTestUtils.getUltimateTargetObject(repository)
                assertThat(ReflectionTestUtils.getField(target, "entityManager")).isSameAs(shared)
            }
            assertThat(emf.get().metamodel.entities.stream().map { type -> type.name }.toList())
                .contains("ChatbotSession", "ChatbotMessage", "ChatbotBlockLog")
            val reads = context.getBean("animalChatbotReads", TransactionTemplate::class.java)
            val writes = context.getBean("animalChatbotWrites", TransactionTemplate::class.java)
            assertThat(reads.transactionManager).isSameAs(manager)
            assertThat(writes.transactionManager).isSameAs(manager)
            assertThat(reads.isReadOnly).isTrue()
            assertThat(writes.isReadOnly).isFalse()
            assertThat(reads.propagationBehavior).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED)
            assertThat(writes.propagationBehavior).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED)
            val service = context.getBean(ChatbotService::class.java)
            assertThat(ReflectionTestUtils.getField(service, "reads")).isSameAs(reads)
            assertThat(ReflectionTestUtils.getField(service, "writes")).isSameAs(writes)
            assertThat(ReflectionTestUtils.getField(service, "chatbotRateLimitService")).isSameAs(inputs.quota)
            assertThat(inputs.redisCreates.get()).isEqualTo(1)
            val redis = ReflectionTestUtils.getField(inputs.redisConfig.get(), "singleServerConfig") as SingleServerConfig
            assertThat(redis.address).isEqualTo("redis://127.0.0.1:6379")
            assertThat(redis.database).isZero()
            assertThat(redis.password).isEqualTo("synthetic-redis-password")
            assertThat(context.getBean("animalChatbotSettings").toString()).isEqualTo("AnimalChatbotSettings[redacted]")
            verifyNoInteractions(inputs.writer, inputs.reader, inputs.redis, inputs.quota)
        }
        assertThat(emf.get().isOpen).isFalse()
        verify(inputs.redis).shutdown()
        verifyNoMoreInteractions(inputs.redis)
        verifyNoInteractions(inputs.writer, inputs.reader, inputs.quota)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "python-ai-service.url",
            "python-ai-service.internal-api-key",
            "chatbot.security.ip-hash-secret",
            "spring.data.redis.host",
        ],
    )
    fun given__missing_required_configuration__when__enabling__then__fail_with_fixed_nonsecret_message_before_redis_creation(
        property: String,
    ) {
        val inputs = Inputs()
        inputs.runner().withPropertyValues("$property=").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasRootCauseMessage("Animal chatbot requires explicit valid configuration")
            assertThat(context.startupFailure.toString())
                .doesNotContain("synthetic-python-key", "synthetic-hmac-key", "synthetic-redis-password")
            assertThat(inputs.redisCreates.get()).isZero()
            verifyNoInteractions(inputs.writer, inputs.reader, inputs.redis, inputs.quota)
        }
    }

    @Test
    fun given__malformed_private_endpoint__when__enabling__then__reject_with_fixed_error_without_echoing_endpoint_or_credentials() {
        val inputs = Inputs()
        inputs.runner().withPropertyValues("python-ai-service.url=private endpoint with token").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasRootCauseMessage("Animal chatbot requires explicit valid configuration")
            assertThat(context.startupFailure.toString()).doesNotContain("private endpoint with token")
            assertThat(inputs.redisCreates.get()).isZero()
        }
    }

    /** The factory replaces Redis bootstrap; quota is a mock here, and its five-bucket semantics have module unit tests. */
    private inner class Inputs {
        val writer: DataSource = mock(DataSource::class.java)
        val reader: DataSource = mock(DataSource::class.java)
        val redis: RedissonClient = mock(RedissonClient::class.java)
        val quota: ChatbotRateLimitService = mock(ChatbotRateLimitService::class.java)
        val redisCreates = AtomicInteger()
        val redisConfig = AtomicReference<Config>()

        fun runner(): WebApplicationContextRunner {
            return web()
                .withBean("writeDataSource", DataSource::class.java, Supplier { writer })
                .withBean("dataSource", DataSource::class.java, Supplier { reader })
                .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { JdbcTemplate(writer) })
                .withBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
                .withBean(ChatbotRateLimitService::class.java, Supplier { quota })
                .withBean(
                    "animalChatbotRedisClientFactory",
                    AnimalChatbotConfiguration.RedisClientFactory::class.java,
                    Supplier {
                        AnimalChatbotConfiguration.RedisClientFactory { config ->
                            redisCreates.incrementAndGet()
                            redisConfig.set(config)
                            redis
                        }
                    },
                )
                .withPropertyValues(
                    "pawbridge.animal-chatbot.enabled=true",
                    "pawbridge.animal-query.backend=postgresql",
                    "python-ai-service.url=http://python.example.invalid:8000",
                    "python-ai-service.internal-api-key=synthetic-python-key",
                    "chatbot.security.ip-hash-secret=synthetic-hmac-key",
                    "spring.data.redis.host=127.0.0.1",
                    "spring.data.redis.password=synthetic-redis-password",
                )
        }
    }
}
