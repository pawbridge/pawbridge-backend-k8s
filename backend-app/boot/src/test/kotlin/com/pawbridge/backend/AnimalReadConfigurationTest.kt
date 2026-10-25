package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Details
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Species
import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer
import com.pawbridge.backend.animal.internal.discovery.web.AnimalDiscoveryController
import com.pawbridge.backend.animal.internal.discovery.web.AnimalDiscoveryExceptionHandler
import com.pawbridge.backend.animal.internal.recommendation.application.AnimalRecommendations
import com.pawbridge.backend.animal.internal.recommendation.application.RecommendationUnavailableException
import com.pawbridge.backend.animal.internal.recommendation.infrastructure.PythonAnimalRecommendations
import com.pawbridge.backend.animal.internal.recommendation.web.AnimalRecommendationController
import com.pawbridge.backend.animal.internal.recommendation.web.AnimalRecommendationExceptionHandler
import com.pawbridge.backend.boot.configuration.AnimalDiscoveryConfiguration
import com.pawbridge.backend.boot.configuration.AnimalRecommendationsConfiguration
import io.mockk.confirmVerified
import io.mockk.excludeRecords
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.aop.support.AopUtils
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier
import javax.sql.DataSource

class AnimalReadConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = ["absent", "false", "yes", "on", "1"])
    fun `기능이 꺼져 있고 AI·DB 입력이 없을 때__조립하면__동물 빈을 만들지 않는다`(
        flag: String,
    ) {
        // given
        var runner = ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration::class.java))
            .withUserConfiguration(AnimalDiscoveryConfiguration::class.java, AnimalRecommendationsConfiguration::class.java)
        if (flag != "absent") {
            runner = runner.withPropertyValues(
                "pawbridge.animal-discovery.enabled=$flag",
                "pawbridge.animal-recommendations.enabled=$flag",
            )
        }

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
                .doesNotHaveBean(AnimalDiscovery::class.java)
                .doesNotHaveBean(KoreanSearchAnalyzer::class.java)
                .doesNotHaveBean(AnimalDiscoveryController::class.java)
                .doesNotHaveBean(AnimalDiscoveryExceptionHandler::class.java)
                .doesNotHaveBean(AnimalRecommendations::class.java)
                .doesNotHaveBean(PythonAnimalRecommendations::class.java)
                .doesNotHaveBean(AnimalRecommendationController::class.java)
                .doesNotHaveBean(AnimalRecommendationExceptionHandler::class.java)
        }
    }

    @Test
    fun `탐색이 켜졌을 때__조립하고 닫으면__읽기 입력을 재사용하고 하나뿐인 분석기를 닫는다`() {
        // given
        val source = mockk<DataSource>()
        val manager = mockk<PlatformTransactionManager>()
        val owned = AtomicReference<KoreanSearchAnalyzer>()

        // when
        inputs(source, manager).withPropertyValues("pawbridge.animal-discovery.enabled=true").run { context ->
            // then
            assertThat(context).hasNotFailed()
            val animals = context.getBean(AnimalDiscovery::class.java)
            val jdbc = (ReflectionTestUtils.getField(animals, "jdbc") as NamedParameterJdbcTemplate).jdbcTemplate as JdbcTemplate
            assertThat(jdbc.dataSource).isSameAs(source)
            assertThat(jdbc.queryTimeout).isEqualTo(5)
            val transaction = ReflectionTestUtils.getField(animals, "transactions") as TransactionTemplate
            assertThat(transaction.transactionManager).isSameAs(manager)
            assertThat(transaction.isReadOnly).isTrue()
            assertThat(transaction.isolationLevel).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ)
            assertThat(transaction.timeout).isEqualTo(5)
            assertThat(context.getBeansOfType(KoreanSearchAnalyzer::class.java)).hasSize(1)
            owned.set(context.getBean(KoreanSearchAnalyzer::class.java))
            assertThat(ReflectionTestUtils.getField(animals, "analyzer")).isSameAs(owned.get())
            assertThat(owned.get().tokens("말티즈")).contains("말티즈")
            assertThat(context.getBeansOfType(AnimalDiscoveryController::class.java)).hasSize(1)
            assertThat(context.getBeansOfType(AnimalDiscoveryExceptionHandler::class.java)).hasSize(1)
            assertThat(context).doesNotHaveBean(AnimalRecommendations::class.java)
            assertThat(PawBridgeApplication::class.java.getAnnotation(Import::class.java).value.map { it.java })
                .contains(AnimalDiscoveryConfiguration::class.java, AnimalRecommendationsConfiguration::class.java)
            confirmNoBusinessCalls(source, manager)
        }
        assertThatThrownBy { owned.get().tokens("말티즈") }.isInstanceOf(IllegalStateException::class.java)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `추천만 켜지고 URL이 명시됐을 때__조립하면__탐색 경로 없이 읽기를 재사용하고 URL 우선순위를 지킨다`(
        preferred: Boolean,
    ) {
        // given
        val source = mockk<DataSource>()
        val manager = mockk<PlatformTransactionManager>()
        var runner = inputs(source, manager).withPropertyValues(
            "pawbridge.animal-recommendations.enabled=true",
            "python-ai-service.url=https://fallback.example.invalid",
        )
        if (preferred) {
            runner = runner.withPropertyValues("lost-search.python-url=https://preferred.example.invalid")
        }

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
            assertThat(context.getBeansOfType(AnimalDiscovery::class.java)).hasSize(1)
            assertThat(context.getBeansOfType(KoreanSearchAnalyzer::class.java)).hasSize(1)
            assertThat(context)
                .doesNotHaveBean(AnimalDiscoveryController::class.java)
                .doesNotHaveBean(AnimalDiscoveryExceptionHandler::class.java)
            val recommendations = context.getBean(AnimalRecommendations::class.java)
            assertThat(ReflectionTestUtils.getField(recommendations, "animals"))
                .isSameAs(context.getBean(AnimalDiscovery::class.java))
            val jdbc = (ReflectionTestUtils.getField(recommendations, "jdbc") as NamedParameterJdbcTemplate).jdbcTemplate as JdbcTemplate
            assertThat(jdbc.dataSource).isSameAs(source)
            val reads = ReflectionTestUtils.getField(recommendations, "reads") as TransactionTemplate
            assertThat(reads.transactionManager).isSameAs(manager)
            assertThat(reads.isReadOnly).isTrue()
            assertThat(reads.timeout).isEqualTo(5)
            assertThat(AopUtils.isAopProxy(recommendations)).isFalse()
            assertThat(AopUtils.isAopProxy(context.getBean(AnimalRecommendationController::class.java))).isFalse()
            val target = ReflectionTestUtils.getField(context.getBean(PythonAnimalRecommendations::class.java), "endpoint").toString()
            assertThat(target).contains(
                if (preferred) "https://preferred.example.invalid" else "https://fallback.example.invalid",
            )
            if (preferred) {
                assertThat(target).doesNotContain("https://fallback.example.invalid")
            }
            confirmNoBusinessCalls(source, manager)
        }
    }

    @Test
    fun `추천이 켜졌지만 두 URL이 모두 없을 때__조립하면__대체 빈을 만들지 않고 설정 실패로 끝난다`() {
        // given
        val source = mockk<DataSource>()
        val manager = mockk<PlatformTransactionManager>()
        val runner = inputs(source, manager).withPropertyValues("pawbridge.animal-recommendations.enabled=true")

        // when
        runner.run { context ->
            // then
            assertThat(context).hasFailed()
        }
    }

    @Test
    fun `키가 없고 URL이 명시됐을 때__조립된 컨트롤러를 부르면__외부 트랜잭션 없이 ETC는 빈 값 DOG는 사용 불가로 둔다`() {
        // given
        val animals = mockk<AnimalDiscovery>()
        val source = mockk<DataSource>()
        val manager = mockk<PlatformTransactionManager>()
        val runner = ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration::class.java))
            .withUserConfiguration(AnimalRecommendationsConfiguration::class.java)
            .withBean(AnimalDiscovery::class.java, Supplier { animals })
            .withBean("dataSource", DataSource::class.java, Supplier { source })
            .withBean("transactionManager", PlatformTransactionManager::class.java, Supplier { manager })
            .withBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
            .withPropertyValues(
                "pawbridge.animal-recommendations.enabled=true",
                "python-ai-service.url=https://fixture.example.invalid",
            )

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
            assertThat(ReflectionTestUtils.getField(context.getBean(PythonAnimalRecommendations::class.java), "internalKey"))
                .isEqualTo("")
            val controller = context.getBean(AnimalRecommendationController::class.java)
            for (species in arrayOf(Species.ETC, Species.DOG)) {
                every { animals.findById(42L) } answers {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
                    source(species)
                }
                if (species == Species.ETC) {
                    assertThat(controller.recommend(javaLong(42L))).isEmpty()
                } else {
                    assertThatThrownBy { controller.recommend(javaLong(42L)) }
                        .isInstanceOf(RecommendationUnavailableException::class.java)
                }
            }
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            confirmNoBusinessCalls(source, manager)
        }
    }

    companion object {
        /** Spring hashes registered beans, so ignore those calls and fail on any other call to the mocks. */
        private fun confirmNoBusinessCalls(vararg mocks: Any) {
            for (mock in mocks) {
                excludeRecords {
                    mock.hashCode()
                    mock.toString()
                    mock.equals(any())
                }
            }
            confirmVerified(*mocks)
        }

        private fun inputs(source: DataSource, manager: PlatformTransactionManager): ApplicationContextRunner {
            return ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration::class.java))
                .withUserConfiguration(AnimalDiscoveryConfiguration::class.java, AnimalRecommendationsConfiguration::class.java)
                .withBean("dataSource", DataSource::class.java, Supplier { source })
                .withBean("transactionManager", PlatformTransactionManager::class.java, Supplier { manager })
                .withBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
        }

        /** The controller declares its path variable as `java.lang.Long`, which Kotlin does not map from `Long`. */
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN", "DEPRECATION")
        private fun javaLong(value: Long): java.lang.Long = java.lang.Long(value)

        private fun source(species: Species): Details {
            return Details(
                42L, null, species, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            )
        }
    }
}
