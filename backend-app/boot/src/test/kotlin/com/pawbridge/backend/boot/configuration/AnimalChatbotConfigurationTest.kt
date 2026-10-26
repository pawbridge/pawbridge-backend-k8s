package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.client.PythonChatbotClient
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.service.ChatbotRateLimitService
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.service.ChatbotService
import com.pawbridge.backend.animal.internal.chatbot.web.ChatbotController
import com.pawbridge.backend.animal.internal.chatbot.web.ChatbotExceptionHandler
import io.mockk.confirmVerified
import io.mockk.excludeRecords
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.redisson.api.RedissonClient
import org.redisson.config.Config
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.data.auditing.AuditingHandler
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.JpaTransactionManager
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
    fun `챗봇이 꺼져 있을 때__조립하면__클라이언트와 엔티티 매니저와 데이터베이스를 만들지 않는다`(
        flag: String,
    ) {
        // given
        var runner = web().withPropertyValues("pawbridge.animal-query.backend=postgresql")
        if (flag != "absent") {
            runner = runner.withPropertyValues("pawbridge.animal-chatbot.enabled=$flag")
        }

        // when
        runner.run { context ->
            // then
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
    fun `PostgreSQL이 아닌 백엔드일 때__설정 없이 챗봇을 켜면__클라이언트와 기록기를 조립하지 않는다`(
        backend: String,
    ) {
        // given
        var runner = web().withPropertyValues("pawbridge.animal-chatbot.enabled=true")
        if (backend != "absent") {
            runner = runner.withPropertyValues("pawbridge.animal-query.backend=$backend")
        }

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
                .doesNotHaveBean(ChatbotService::class.java)
                .doesNotHaveBean(RedissonClient::class.java)
                .doesNotHaveBean(EntityManagerFactory::class.java)
                .doesNotHaveBean(DataSource::class.java)
        }
    }

    @Test
    fun `서블릿이 아닌 컨텍스트일 때__PostgreSQL 챗봇을 켜면__클라이언트와 기록기를 만들지 않는다`() {
        // given
        val runner = ApplicationContextRunner().withUserConfiguration(AnimalChatbotConfiguration::class.java)
            .withPropertyValues("pawbridge.animal-chatbot.enabled=true", "pawbridge.animal-query.backend=postgresql")

        // when
        runner
            .run { context ->
                // then
                assertThat(context).hasNotFailed()
                    .doesNotHaveBean(ChatbotService::class.java)
                    .doesNotHaveBean(RedissonClient::class.java)
                    .doesNotHaveBean(EntityManagerFactory::class.java)
            }
    }

    @Test
    fun `명시 설정과 Redis 팩토리가 있을 때__조립 후 닫으면__Redis를 한 번만 만들고 한 번 닫으며 비밀을 노출하지 않는다`() {
        // given
        val inputs = Inputs()
        val emf = AtomicReference<EntityManagerFactory>()

        justRun { inputs.redis.shutdown() }

        // when
        inputs.runner().run { context ->
            // then
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
            val manager = context.getBean("animalCommandJpaTransactionManager", JpaTransactionManager::class.java)
            assertThat(manager.defaultTimeout).isEqualTo(15)
            assertThat(emf.get().metamodel.entities.stream().map { type -> type.name }.toList())
                .contains("ChatbotSession", "ChatbotMessage", "ChatbotBlockLog")
            val reads = context.getBean("animalChatbotReads", TransactionTemplate::class.java)
            val writes = context.getBean("animalChatbotWrites", TransactionTemplate::class.java)
            assertThat(reads.isReadOnly).isTrue()
            assertThat(writes.isReadOnly).isFalse()
            assertThat(reads.propagationBehavior).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED)
            assertThat(writes.propagationBehavior).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED)
            assertThat(inputs.redisCreates.get()).isEqualTo(1)
            val redis = inputs.redisConfig.get().toYAML()
            assertThat(redis).contains("redis://127.0.0.1:6379").contains("synthetic-redis-password")
            assertThat(context.getBean("animalChatbotSettings").toString()).isEqualTo("AnimalChatbotSettings[redacted]")
            confirmVerified(inputs.writer, inputs.reader, inputs.redis, inputs.quota)
        }
        assertThat(emf.get().isOpen).isFalse()
        verify(exactly = 1) { inputs.redis.shutdown() }
        confirmVerified(inputs.writer, inputs.reader, inputs.redis, inputs.quota)
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
    fun `필수 설정이 빠졌을 때__켜면__Redis 생성 전에 비밀 없는 고정 메시지로 실패한다`(
        property: String,
    ) {
        // given
        val inputs = Inputs()

        // when
        inputs.runner().withPropertyValues("$property=").run { context ->
            // then
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasRootCauseMessage("Animal chatbot requires explicit valid configuration")
            assertThat(context.startupFailure.toString())
                .doesNotContain("synthetic-python-key", "synthetic-hmac-key", "synthetic-redis-password")
            assertThat(inputs.redisCreates.get()).isZero()
            confirmVerified(inputs.writer, inputs.reader, inputs.redis, inputs.quota)
        }
    }

    @Test
    fun `잘못된 비공개 엔드포인트가 주어졌을 때__켜면__엔드포인트나 자격 증명을 노출하지 않고 고정 오류로 거절한다`() {
        // given
        val inputs = Inputs()

        // when
        inputs.runner().withPropertyValues("python-ai-service.url=private endpoint with token").run { context ->
            // then
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasRootCauseMessage("Animal chatbot requires explicit valid configuration")
            assertThat(context.startupFailure.toString()).doesNotContain("private endpoint with token")
            assertThat(inputs.redisCreates.get()).isZero()
        }
    }

    /** The factory replaces Redis bootstrap; quota is a mock here, and its five-bucket semantics have module unit tests. */
    private inner class Inputs {
        val writer: DataSource = mockk<DataSource>()
        val reader: DataSource = mockk<DataSource>()
        val redis: RedissonClient = mockk<RedissonClient>()
        val quota: ChatbotRateLimitService = mockk<ChatbotRateLimitService>()
        val redisCreates = AtomicInteger()
        val redisConfig = AtomicReference<Config>()

        init {
            // Spring hashes the beans it registers; that is not an interaction with the collaborator.
            excludeRecords {
                writer.hashCode()
                reader.hashCode()
                redis.hashCode()
                quota.hashCode()
            }
        }

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
