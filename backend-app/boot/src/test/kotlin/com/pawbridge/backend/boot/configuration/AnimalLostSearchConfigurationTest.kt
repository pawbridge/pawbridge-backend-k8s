package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.command.infrastructure.service.AnimalCommandService
import com.pawbridge.backend.animal.internal.lostsearch.infrastructure.LostSearchService
import com.pawbridge.backend.animal.internal.lostsearch.infrastructure.PythonLostSearchClient
import com.pawbridge.backend.animal.internal.lostsearch.web.LostSearchController
import com.pawbridge.backend.boot.security.PostTokenAuthenticator
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import io.mockk.confirmVerified
import io.mockk.excludeRecords
import io.mockk.mockk
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.test.util.AopTestUtils
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.nio.charset.StandardCharsets
import java.util.Date
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier
import javax.crypto.SecretKey
import javax.sql.DataSource

class AnimalLostSearchConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = ["absent", "false", "yes", "on", "1"])
    fun `기능이 명시적 true가 아닐 때__입력 없이 조립하면__리더도 클라이언트도 만들지 않는다`(
        flag: String,
    ) {
        // given
        var runner = runner()
        if (flag != "absent") {
            runner = runner.withPropertyValues("pawbridge.animal-lost-search.enabled=$flag")
        }

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
                .doesNotHaveBean(EntityManagerFactory::class.java)
                .doesNotHaveBean(PythonLostSearchClient::class.java)
                .doesNotHaveBean(LostSearchService::class.java)
                .doesNotHaveBean(LostSearchController::class.java)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `명시한 기능과 URL이 주어졌을 때__조립하면__연결 없이 리더 단위와 URL을 선택한다`(
        preferred: Boolean,
    ) {
        // given
        val reader: DataSource = mockk<DataSource>()
        val writer: DataSource = mockk<DataSource>()
        val owned = AtomicReference<EntityManagerFactory>()

        excludeRecords { reader.hashCode() }
        excludeRecords { writer.hashCode() }

        var runner = readerInputs(reader).withBean("writeDataSource", DataSource::class.java, Supplier { writer })
            .withPropertyValues(
                "pawbridge.animal-lost-search.enabled=TrUe",
                "python-ai-service.url=https://fallback.example.invalid",
            )
        if (preferred) {
            runner = runner.withPropertyValues("lost-search.python-url=https://preferred.example.invalid")
        }

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
                .hasSingleBean(LostSearchService::class.java)
                .hasSingleBean(LostSearchController::class.java)
            val emf = context.getBean("animalLostSearchEntityManagerFactory", EntityManagerFactory::class.java)
            owned.set(emf)
            val factory = context.getBean(
                "&animalLostSearchEntityManagerFactory",
                LocalContainerEntityManagerFactoryBean::class.java,
            )
            val model = "com.pawbridge.backend.animal.internal.command.infrastructure.entity."
            assertThat(factory.persistenceUnitInfo!!.managedClassNames)
                .containsExactlyInAnyOrder(model + "Animal", model + "Shelter", model + "BaseTimeEntity")
            assertThat(factory.dataSource).isSameAs(reader)
            assertThat(factory.jpaPropertyMap)
                .containsEntry("hibernate.boot.allow_jdbc_metadata_access", false)
                .containsEntry("hibernate.hbm2ddl.auto", "none")
                .containsEntry("jakarta.persistence.schema-generation.database.action", "none")
                .containsEntry("hibernate.default_schema", "pawbridge_animal")
                .containsEntry("hibernate.jdbc.time_zone", "UTC")
            val manager = context.getBean("animalLostSearchTransactions", JpaTransactionManager::class.java)
            assertThat(manager.dataSource).isSameAs(reader)
            assertThat(manager.entityManagerFactory).isSameAs(emf)
            assertThat(manager.defaultTimeout).isEqualTo(5)
            val service = context.getBean(LostSearchService::class.java)
            val reads = ReflectionTestUtils.getField(service, "reads") as TransactionTemplate
            assertThat(reads.transactionManager).isSameAs(manager)
            assertThat(reads.isReadOnly).isTrue()
            assertThat(reads.isolationLevel).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT)
            assertThat(ReflectionTestUtils.getField(service, "internalKey")).isEqualTo("p10-public-synthetic-key")
            assertThat(repositoryUnit(ReflectionTestUtils.getField(service, "animals")!!)).isSameAs(emf)
            val endpoint: Any = ReflectionTestUtils.getField(context.getBean(PythonLostSearchClient::class.java), "endpoint")!!
            assertThat(endpoint.toString()).contains(
                if (preferred) "https://preferred.example.invalid" else "https://fallback.example.invalid",
            )
            assertThat(context.getBeansOfType(DataSource::class.java)).hasSize(2)
            assertThat(context.getBeansOfType(EntityManagerFactory::class.java)).hasSize(1)
            assertThat(context).doesNotHaveBean("jpaAuditingHandler").doesNotHaveBean(AnimalCommandService::class.java)
            confirmVerified(reader, writer)
        }
        assertThat(owned.get().isOpen).isFalse()
    }

    @Test
    fun `명령과 사진 검색이 켜졌을 때__조립하면__각 리포지토리가 이름 붙은 리더 또는 기록 단위에 머문다`() {
        // given
        val reader: DataSource = mockk<DataSource>()
        val writer: DataSource = mockk<DataSource>()
        val units = AtomicReference<List<EntityManagerFactory>>()

        excludeRecords { reader.hashCode() }
        excludeRecords { writer.hashCode() }

        // when
        readerInputs(reader).withBean("writeDataSource", DataSource::class.java, Supplier { writer })
            .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { JdbcTemplate(writer) })
            .withPropertyValues(
                "pawbridge.animal-lost-search.enabled=true",
                "pawbridge.animal-commands.enabled=true",
                "lost-search.python-url=https://fixture.example.invalid",
            ).run { context ->
                // then
                assertThat(context).hasNotFailed()
                units.set(context.getBeansOfType(EntityManagerFactory::class.java).values.toList())
                assertThat(units.get()).hasSize(2)
                val read = context.getBean("animalLostSearchEntityManagerFactory", EntityManagerFactory::class.java)
                val write = context.getBean("animalCommandEntityManagerFactory", EntityManagerFactory::class.java)
                assertThat(read).isNotSameAs(write)
                assertThat(
                    repositoryUnit(ReflectionTestUtils.getField(context.getBean(LostSearchService::class.java), "animals")!!),
                ).isSameAs(read)
                assertThat(
                    repositoryUnit(
                        ReflectionTestUtils.getField(context.getBean(AnimalCommandService::class.java), "animalRepository")!!,
                    ),
                ).isSameAs(write)
                assertThat(
                    context.getBean("animalLostSearchTransactions", JpaTransactionManager::class.java).dataSource,
                ).isSameAs(reader)
                assertThat(
                    context.getBean("animalCommandJpaTransactionManager", JpaTransactionManager::class.java).dataSource,
                ).isSameAs(writer)
                confirmVerified(reader, writer)
            }
        assertThat(units.get()).allSatisfy { factory -> assertThat(factory.isOpen).isFalse() }
    }

    @Test
    fun `기능은 켰지만 파이썬 URL이 없을 때__조립하면__외부 대체 경로를 고르지 않고 실패한다`() {
        // given
        val reader: DataSource = mockk<DataSource>()

        excludeRecords { reader.hashCode() }

        val runner = readerInputs(reader).withPropertyValues("pawbridge.animal-lost-search.enabled=true")

        // when
        runner.run { context ->
            // then
            assertThat(context).hasFailed()
            confirmVerified(reader)
        }
    }

    private fun repositoryUnit(repository: Any): EntityManagerFactory {
        val target: Any = AopTestUtils.getUltimateTargetObject(repository)
        return (ReflectionTestUtils.getField(target, "entityManager") as EntityManager).entityManagerFactory
    }

    private fun runner(): ApplicationContextRunner {
        return ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration::class.java))
            .withUserConfiguration(AnimalLostSearchConfiguration::class.java, AnimalCommandConfiguration::class.java)
    }

    private fun readerInputs(reader: DataSource): ApplicationContextRunner {
        return runner().withBean("dataSource", DataSource::class.java, Supplier { reader })
            .withBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
            .withPropertyValues("python-ai-service.internal-api-key=p10-public-synthetic-key")
    }
}

@Configuration(proxyBeanMethods = false)
class LostSearchTestSecurity {
    @Bean
    fun tokens(): PostTokenAuthenticator = PostTokenAuthenticator(KEY)

    companion object {
        // JvmField and JvmStatic keep the Java Owned PostgreSQL test source-compatible while it is still Java.
        @JvmField
        val KEY: SecretKey =
            Keys.hmacShaKeyFor("p10-public-synthetic-security-key-only-for-test".toByteArray(StandardCharsets.UTF_8))

        @JvmStatic
        fun token(): String {
            return "Bearer " + Jwts.builder()
                .claim("userId", 7L)
                .claim("role", "ROLE_ADMIN")
                .expiration(Date(System.currentTimeMillis() + 300_000))
                .signWith(KEY)
                .compact()
        }
    }
}
