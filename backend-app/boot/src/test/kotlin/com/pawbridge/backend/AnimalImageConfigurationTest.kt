package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.api.AnimalCommandActors
import com.pawbridge.backend.animal.internal.command.infrastructure.service.AnimalCommandService
import com.pawbridge.backend.animal.internal.image.infrastructure.AnimalImageService
import com.pawbridge.backend.animal.internal.image.infrastructure.S3AnimalImages
import com.pawbridge.backend.animal.internal.image.web.AnimalImageController
import com.pawbridge.backend.animal.internal.image.web.AnimalImageExceptionHandler
import com.pawbridge.backend.boot.configuration.AnimalCommandConfiguration
import com.pawbridge.backend.boot.configuration.AnimalImageConfiguration
import com.pawbridge.backend.boot.configuration.PostWriteSettings
import io.mockk.confirmVerified
import io.mockk.excludeRecords
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import software.amazon.awssdk.services.s3.S3Client
import java.util.function.Supplier
import javax.sql.DataSource

/** No real DB/storage client: assembly identity, fail-closed opt-in and borrowed-close contracts only. */
class AnimalImageConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = ["absent", "false", "yes", "on", "1", "unknown"])
    fun `명시적 true가 아닌 이미지 플래그__입력 없이 조립하면__이미지 경로와 저장소를 만들지 않는다`(
        flag: String,
    ) {
        // given
        var runner = runner()
        if (flag != "absent") {
            runner = runner.withPropertyValues("pawbridge.animal-images.enabled=$flag")
        }

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
                .doesNotHaveBean(S3AnimalImages::class.java)
                .doesNotHaveBean(AnimalImageService::class.java)
                .doesNotHaveBean(AnimalImageController::class.java)
                .doesNotHaveBean(AnimalImageExceptionHandler::class.java)
                .doesNotHaveBean(AnimalCommandActors::class.java)
                .doesNotHaveBean(S3Client::class.java)
        }
    }

    @Test
    fun `이미지 활성화__조립하면__두 번째 행위자 빈을 노출하지 않고 저장소 클라이언트는 한 번만 닫는다`() {
        // given
        val writer = mockk<DataSource>()
        val storage = mockk<S3Client>()
        excludeRecords {
            writer.hashCode()
            writer.toString()
            writer.equals(any())
            storage.hashCode()
            storage.toString()
            storage.equals(any())
        }
        val settings = F3TestInputs.settings()
        justRun { storage.close() }

        val runner = inputs(writer, storage, settings).withPropertyValues("pawbridge.animal-images.enabled=TrUe")

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
                .hasSingleBean(S3AnimalImages::class.java)
                .hasSingleBean(AnimalImageService::class.java)
                .hasSingleBean(AnimalImageController::class.java)
                .hasSingleBean(AnimalImageExceptionHandler::class.java)
                .doesNotHaveBean(AnimalCommandActors::class.java)
                .doesNotHaveBean(AnimalCommandService::class.java)
            confirmVerified(writer, storage)
        }

        // then
        // The Post configuration owns the client; the image feature must not close it a second time.
        verify(exactly = 1) { storage.close() }
        confirmVerified(storage)
        confirmVerified(writer)
    }

    @Test
    fun `이미지와 동물 명령 활성화__조립하면__주입 가능한 행위자 빈을 하나만 유지한다`() {
        // given
        val writer = mockk<DataSource>()
        val storage = mockk<S3Client>()
        excludeRecords {
            writer.hashCode()
            writer.toString()
            writer.equals(any())
            storage.hashCode()
            storage.toString()
            storage.equals(any())
        }
        justRun { storage.close() }

        val runner = inputs(writer, storage, F3TestInputs.settings())
            .withUserConfiguration(AnimalCommandConfiguration::class.java)
            .withBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
            .withPropertyValues("pawbridge.animal-images.enabled=true", "pawbridge.animal-commands.enabled=true")

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
                .hasSingleBean(AnimalCommandActors::class.java)
                .hasSingleBean(AnimalCommandService::class.java)
                .hasSingleBean(AnimalImageService::class.java)
            confirmVerified(writer, storage)
        }
    }

    @Test
    fun `다른 소스를 쓰는 행위자 JDBC__이미지를 활성화하면__DB 연결을 열기 전에 실패한다`() {
        // given
        val writer = mockk<DataSource>()
        val other = mockk<DataSource>()
        val storage = mockk<S3Client>()
        excludeRecords {
            writer.hashCode()
            writer.toString()
            writer.equals(any())
            other.hashCode()
            other.toString()
            other.equals(any())
            storage.hashCode()
            storage.toString()
            storage.equals(any())
        }
        val runner = runner()
            .withBean(
                "postS3Client",
                S3Client::class.java,
                Supplier { storage },
                BeanDefinitionCustomizer { definition -> definition.setDestroyMethodName("") },
            )
            .withBean(PostWriteSettings::class.java, Supplier { F3TestInputs.settings() })
            .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { JdbcTemplate(other) })
            .withBean("writeTransactionManager", JdbcTransactionManager::class.java, Supplier { JdbcTransactionManager(writer) })
            .withPropertyValues("pawbridge.animal-images.enabled=true")

        // when
        runner.run { context ->
            // then
            assertThat(context).hasFailed()
            assertThat(context.startupFailure)
                .hasRootCauseMessage("Animal image actors must use the writer transaction DataSource")
            confirmVerified(writer, other, storage)
        }
    }

    @Test
    fun `기존 저장소 없음__이미지를 활성화하면__대체 경로나 외부 접근 없이 실패한다`() {
        // given
        val writer = mockk<DataSource>()
        excludeRecords {
            writer.hashCode()
            writer.toString()
            writer.equals(any())
        }
        val runner = runner()
            .withBean(PostWriteSettings::class.java, Supplier { F3TestInputs.settings() })
            .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { JdbcTemplate(writer) })
            .withBean("writeTransactionManager", JdbcTransactionManager::class.java, Supplier { JdbcTransactionManager(writer) })
            .withPropertyValues("pawbridge.animal-images.enabled=true")

        // when
        runner.run { context ->
            // then
            assertThat(context).hasFailed()
            assertThat(context.startupFailure)
                .hasRootCauseInstanceOf(NoSuchBeanDefinitionException::class.java)
                .hasStackTraceContaining("postS3Client")
            confirmVerified(writer)
        }
    }

    companion object {
        private fun runner(): ApplicationContextRunner {
            return ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration::class.java))
                .withUserConfiguration(AnimalImageConfiguration::class.java)
        }

        private fun inputs(writer: DataSource, storage: S3Client, settings: PostWriteSettings): ApplicationContextRunner {
            return runner()
                .withBean(
                    "postS3Client",
                    S3Client::class.java,
                    Supplier { storage },
                    BeanDefinitionCustomizer { definition -> definition.setDestroyMethodName("close") },
                )
                .withBean(PostWriteSettings::class.java, Supplier { settings })
                .withBean("writeDataSource", DataSource::class.java, Supplier { writer })
                .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { JdbcTemplate(writer) })
                .withBean("writeTransactionManager", JdbcTransactionManager::class.java, Supplier { JdbcTransactionManager(writer) })
        }
    }
}
