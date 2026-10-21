package com.pawbridge.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.api.AnimalCommandActors;
import com.pawbridge.backend.animal.internal.command.infrastructure.service.AnimalCommandService;
import com.pawbridge.backend.animal.internal.image.infrastructure.AnimalImageService;
import com.pawbridge.backend.animal.internal.image.infrastructure.S3AnimalImages;
import com.pawbridge.backend.animal.internal.image.web.AnimalImageController;
import com.pawbridge.backend.animal.internal.image.web.AnimalImageExceptionHandler;
import com.pawbridge.backend.boot.configuration.AnimalCommandConfiguration;
import com.pawbridge.backend.boot.configuration.AnimalImageConfiguration;
import com.pawbridge.backend.boot.configuration.PostWriteSettings;
import com.pawbridge.backend.user.internal.animalcommand.infrastructure.JdbcAnimalCommandActors;
import java.util.Arrays;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** No real DB/storage client: assembly identity, fail-closed opt-in and borrowed-close contracts only. */
class AnimalImageConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings={"absent","false","yes","on","1","unknown"})
    void given__images_not_explicitly_true__when__assembling_without_inputs__then__create_no_image_route_or_storage(String flag) {
        var runner=runner();if(!flag.equals("absent"))runner=runner.withPropertyValues("pawbridge.animal-images.enabled="+flag);
        runner.run(context->assertThat(context).hasNotFailed().doesNotHaveBean(S3AnimalImages.class).doesNotHaveBean(AnimalImageService.class)
                .doesNotHaveBean(AnimalImageController.class).doesNotHaveBean(AnimalImageExceptionHandler.class)
                .doesNotHaveBean(AnimalCommandActors.class).doesNotHaveBean(S3Client.class));
    }

    @Test
    void given__images_enabled__when__assembling__then__borrow_storage_and_writer_without_exposing_a_second_actor_bean() {
        DataSource writer=mock(DataSource.class);S3Client storage=mock(S3Client.class);PostWriteSettings settings=F3TestInputs.settings();
        inputs(writer,storage,settings).withPropertyValues("pawbridge.animal-images.enabled=TrUe").run(context->{
            assertThat(context).hasNotFailed().hasSingleBean(S3AnimalImages.class).hasSingleBean(AnimalImageService.class)
                    .hasSingleBean(AnimalImageController.class).hasSingleBean(AnimalImageExceptionHandler.class)
                    .doesNotHaveBean(AnimalCommandActors.class).doesNotHaveBean(AnimalCommandService.class);
            S3AnimalImages images=context.getBean(S3AnimalImages.class);
            assertThat(ReflectionTestUtils.getField(images,"s3Client")).isSameAs(storage);
            assertThat(ReflectionTestUtils.getField(images,"bucketName")).isEqualTo(settings.bucket());
            assertThat(ReflectionTestUtils.getField(images,"publicBaseUrl")).isEqualTo(settings.publicBaseUrl());
            var service=context.getBean(AnimalImageService.class);
            assertThat(ReflectionTestUtils.getField(service,"images")).isSameAs(images);
            var actors=ReflectionTestUtils.getField(service,"actors");
            assertThat(actors).isInstanceOf(JdbcAnimalCommandActors.class);
            assertThat(ReflectionTestUtils.getField(actors,"jdbc")).isSameAs(context.getBean("writeJdbc"));
            var writes=(TransactionTemplate)ReflectionTestUtils.getField(service,"writes");
            assertThat(writes.getTransactionManager()).isSameAs(context.getBean("writeTransactionManager"));
            assertThat(writes.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
            assertThat(writes.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
            assertThat(writes.getTimeout()).isEqualTo(10);assertThat(writes.isReadOnly()).isFalse();
            assertThat(ReflectionTestUtils.getField(context.getBean(AnimalImageController.class),"images")).isSameAs(service);
            verifyNoInteractions(writer,storage);
        });
        // The Post configuration owns the client; the image feature must not close it a second time.
        verify(storage,times(1)).close();verifyNoMoreInteractions(storage);verifyNoInteractions(writer);
    }

    @Test
    void given__images_and_animal_commands_enabled__when__assembling__then__keep_one_injectable_actor_bean() {
        DataSource writer=mock(DataSource.class);S3Client storage=mock(S3Client.class);
        inputs(writer,storage,F3TestInputs.settings()).withUserConfiguration(AnimalCommandConfiguration.class).withBean(ObjectMapper.class,ObjectMapper::new)
                .withPropertyValues("pawbridge.animal-images.enabled=true","pawbridge.animal-commands.enabled=true").run(context->{
                    assertThat(context).hasNotFailed().hasSingleBean(AnimalCommandActors.class).hasSingleBean(AnimalCommandService.class)
                            .hasSingleBean(AnimalImageService.class);
                    verifyNoInteractions(writer,storage);
                });
    }

    @Test
    void given__actor_jdbc_uses_another_source__when__images_enabled__then__fail_before_opening_a_database_connection() {
        DataSource writer=mock(DataSource.class);DataSource other=mock(DataSource.class);S3Client storage=mock(S3Client.class);
        runner().withBean("postS3Client",S3Client.class,()->storage,definition->definition.setDestroyMethodName(""))
                .withBean(PostWriteSettings.class,F3TestInputs::settings)
                .withBean("writeJdbc",JdbcTemplate.class,()->new JdbcTemplate(other))
                .withBean("writeTransactionManager",JdbcTransactionManager.class,()->new JdbcTransactionManager(writer))
                .withPropertyValues("pawbridge.animal-images.enabled=true").run(context->{
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage("Animal image actors must use the writer transaction DataSource");
                    verifyNoInteractions(writer,other,storage);
                });
    }

    @Test
    void given__missing_existing_storage__when__images_enabled__then__fail_without_fallback_or_external_access() {
        DataSource writer=mock(DataSource.class);
        runner().withBean(PostWriteSettings.class,F3TestInputs::settings)
                .withBean("writeJdbc",JdbcTemplate.class,()->new JdbcTemplate(writer))
                .withBean("writeTransactionManager",JdbcTransactionManager.class,()->new JdbcTransactionManager(writer))
                .withPropertyValues("pawbridge.animal-images.enabled=true").run(context->{
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(org.springframework.beans.factory.NoSuchBeanDefinitionException.class)
                            .hasStackTraceContaining("postS3Client");
                    verifyNoInteractions(writer);
                });
    }

    @Test
    void given__application_imports__when__inspected__then__register_the_image_configuration_once() {
        assertThat(Arrays.stream(PawBridgeApplication.class.getAnnotation(Import.class).value())
                .filter(type->type==AnimalImageConfiguration.class).count()).isEqualTo(1);
    }

    private static ApplicationContextRunner runner(){
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(AnimalImageConfiguration.class);
    }
    private static ApplicationContextRunner inputs(DataSource writer,S3Client storage,PostWriteSettings settings){
        return runner().withBean("postS3Client",S3Client.class,()->storage,definition->definition.setDestroyMethodName("close"))
                .withBean(PostWriteSettings.class,()->settings)
                .withBean("writeDataSource",DataSource.class,()->writer)
                .withBean("writeJdbc",JdbcTemplate.class,()->new JdbcTemplate(writer))
                .withBean("writeTransactionManager",JdbcTransactionManager.class,()->new JdbcTransactionManager(writer));
    }
}
