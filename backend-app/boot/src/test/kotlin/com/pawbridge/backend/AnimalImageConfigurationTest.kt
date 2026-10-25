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
import com.pawbridge.backend.user.internal.animalcommand.infrastructure.JdbcAnimalCommandActors
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import software.amazon.awssdk.services.s3.S3Client
import java.util.function.Supplier
import javax.sql.DataSource

/** No real DB/storage client: assembly identity, fail-closed opt-in and borrowed-close contracts only. */
class AnimalImageConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = ["absent", "false", "yes", "on", "1", "unknown"])
    fun given__images_not_explicitly_true__when__assembling_without_inputs__then__create_no_image_route_or_storage(
        flag: String,
    ) {
        var runner = runner()
        if (flag != "absent") {
            runner = runner.withPropertyValues("pawbridge.animal-images.enabled=$flag")
        }
        runner.run { context ->
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
    fun given__images_enabled__when__assembling__then__borrow_storage_and_writer_without_exposing_a_second_actor_bean() {
        val writer = mock(DataSource::class.java)
        val storage = mock(S3Client::class.java)
        val settings = F3TestInputs.settings()
        inputs(writer, storage, settings).withPropertyValues("pawbridge.animal-images.enabled=TrUe").run { context ->
            assertThat(context).hasNotFailed()
                .hasSingleBean(S3AnimalImages::class.java)
                .hasSingleBean(AnimalImageService::class.java)
                .hasSingleBean(AnimalImageController::class.java)
                .hasSingleBean(AnimalImageExceptionHandler::class.java)
                .doesNotHaveBean(AnimalCommandActors::class.java)
                .doesNotHaveBean(AnimalCommandService::class.java)
            val images = context.getBean(S3AnimalImages::class.java)
            assertThat(ReflectionTestUtils.getField(images, "s3Client")).isSameAs(storage)
            assertThat(ReflectionTestUtils.getField(images, "bucketName")).isEqualTo(settings.bucket())
            assertThat(ReflectionTestUtils.getField(images, "publicBaseUrl")).isEqualTo(settings.publicBaseUrl())
            val service = context.getBean(AnimalImageService::class.java)
            assertThat(ReflectionTestUtils.getField(service, "images")).isSameAs(images)
            val actors = ReflectionTestUtils.getField(service, "actors")
            assertThat(actors).isInstanceOf(JdbcAnimalCommandActors::class.java)
            assertThat(ReflectionTestUtils.getField(actors!!, "jdbc")).isSameAs(context.getBean("writeJdbc"))
            val writes = ReflectionTestUtils.getField(service, "writes") as TransactionTemplate
            assertThat(writes.transactionManager).isSameAs(context.getBean("writeTransactionManager"))
            assertThat(writes.propagationBehavior).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED)
            assertThat(writes.isolationLevel).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED)
            assertThat(writes.timeout).isEqualTo(10)
            assertThat(writes.isReadOnly).isFalse()
            assertThat(ReflectionTestUtils.getField(context.getBean(AnimalImageController::class.java), "images"))
                .isSameAs(service)
            verifyNoInteractions(writer, storage)
        }
        // The Post configuration owns the client; the image feature must not close it a second time.
        verify(storage, times(1)).close()
        verifyNoMoreInteractions(storage)
        verifyNoInteractions(writer)
    }

    @Test
    fun given__images_and_animal_commands_enabled__when__assembling__then__keep_one_injectable_actor_bean() {
        val writer = mock(DataSource::class.java)
        val storage = mock(S3Client::class.java)
        inputs(writer, storage, F3TestInputs.settings())
            .withUserConfiguration(AnimalCommandConfiguration::class.java)
            .withBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
            .withPropertyValues("pawbridge.animal-images.enabled=true", "pawbridge.animal-commands.enabled=true")
            .run { context ->
                assertThat(context).hasNotFailed()
                    .hasSingleBean(AnimalCommandActors::class.java)
                    .hasSingleBean(AnimalCommandService::class.java)
                    .hasSingleBean(AnimalImageService::class.java)
                verifyNoInteractions(writer, storage)
            }
    }

    @Test
    fun given__actor_jdbc_uses_another_source__when__images_enabled__then__fail_before_opening_a_database_connection() {
        val writer = mock(DataSource::class.java)
        val other = mock(DataSource::class.java)
        val storage = mock(S3Client::class.java)
        runner()
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
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasRootCauseMessage("Animal image actors must use the writer transaction DataSource")
                verifyNoInteractions(writer, other, storage)
            }
    }

    @Test
    fun given__missing_existing_storage__when__images_enabled__then__fail_without_fallback_or_external_access() {
        val writer = mock(DataSource::class.java)
        runner()
            .withBean(PostWriteSettings::class.java, Supplier { F3TestInputs.settings() })
            .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { JdbcTemplate(writer) })
            .withBean("writeTransactionManager", JdbcTransactionManager::class.java, Supplier { JdbcTransactionManager(writer) })
            .withPropertyValues("pawbridge.animal-images.enabled=true")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasRootCauseInstanceOf(NoSuchBeanDefinitionException::class.java)
                    .hasStackTraceContaining("postS3Client")
                verifyNoInteractions(writer)
            }
    }

    @Test
    fun given__application_imports__when__inspected__then__register_the_image_configuration_once() {
        assertThat(
            PawBridgeApplication::class.java.getAnnotation(Import::class.java).value
                .filter { type -> type.java == AnimalImageConfiguration::class.java }
                .size,
        ).isEqualTo(1)
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
