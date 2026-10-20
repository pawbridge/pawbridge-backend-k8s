package com.pawbridge.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer;
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads;
import com.pawbridge.backend.animal.internal.shelter.web.ShelterDiscoveryReadController;
import com.pawbridge.backend.animal.internal.shelter.web.ShelterReadController;
import com.pawbridge.backend.animal.internal.shelter.web.ShelterReadExceptionHandler;
import com.pawbridge.backend.boot.configuration.AnimalShelterConfiguration;
import javax.sql.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnimalShelterConfigurationTest {
    @ParameterizedTest @ValueSource(strings = {"absent", "false"})
    void given__shelters_disabled_even_with_postgresql_backend__when__assembling_without_database_inputs__then__create_no_shelter_beans(String flag) {
        var runner = context().withPropertyValues("pawbridge.animal-query.backend=postgresql");
        if (flag.equals("false")) runner = runner.withPropertyValues("pawbridge.animal-shelters.enabled=false");
        runner.run(result -> assertThat(result).hasNotFailed().doesNotHaveBean(ShelterReads.class)
                .doesNotHaveBean(ShelterReadController.class).doesNotHaveBean(ShelterDiscoveryReadController.class)
                .doesNotHaveBean(ShelterReadExceptionHandler.class).doesNotHaveBean(DataSource.class)
                .doesNotHaveBean(PlatformTransactionManager.class).doesNotHaveBean(KoreanSearchAnalyzer.class));
    }

    @ParameterizedTest @ValueSource(strings = {"absent", "elasticsearch", "postgresql", "POSTGRESQL"})
    void given__enabled_shelters_and_selected_backend__when__assembling_and_closing__then__reuse_named_reader_inputs_and_register_only_source_selected_controllers(String backend) {
        DataSource source = mock(DataSource.class), writer = mock(DataSource.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class), writerManager = mock(PlatformTransactionManager.class);
        ObjectMapper mapper = new ObjectMapper();
        var runner = context().withPropertyValues("pawbridge.animal-shelters.enabled=true")
                .withBean("dataSource", DataSource.class, () -> source)
                .withBean("writerDataSource", DataSource.class, () -> writer, definition -> definition.setPrimary(true))
                .withBean("transactionManager", PlatformTransactionManager.class, () -> manager)
                .withBean("writerTransactionManager", PlatformTransactionManager.class, () -> writerManager, definition -> definition.setPrimary(true))
                .withBean(ObjectMapper.class, () -> mapper);
        if (!backend.equals("absent")) runner = runner.withPropertyValues("pawbridge.animal-query.backend=" + backend);
        runner.run(result -> {
            assertThat(result).hasNotFailed().hasSingleBean(ShelterReads.class).hasSingleBean(ShelterReadController.class)
                    .hasSingleBean(ShelterReadExceptionHandler.class).doesNotHaveBean(KoreanSearchAnalyzer.class);
            assertThat(result.getBeansOfType(ShelterDiscoveryReadController.class)).hasSize("postgresql".equalsIgnoreCase(backend) ? 1 : 0);
            assertThat(result.getBeansOfType(DataSource.class)).containsOnlyKeys("dataSource", "writerDataSource");
            ShelterReads reads = result.getBean(ShelterReads.class);
            assertThat(ReflectionTestUtils.getField(reads, "mapper")).isSameAs(mapper);
            for (String field : new String[]{"jdbc", "discoveryJdbc"}) {
                var jdbc = (JdbcTemplate) ((NamedParameterJdbcTemplate) ReflectionTestUtils.getField(reads, field)).getJdbcTemplate();
                assertThat(jdbc.getDataSource()).isSameAs(source);
                assertThat(jdbc.getQueryTimeout()).isEqualTo(field.equals("discoveryJdbc") ? 5 : -1);
            }
            for (String field : new String[]{"reads", "discoveryReads"}) {
                var transaction = (TransactionTemplate) ReflectionTestUtils.getField(reads, field);
                assertThat(transaction.getTransactionManager()).isSameAs(manager);
                assertThat(transaction.isReadOnly()).isTrue();
                assertThat(transaction.getIsolationLevel()).isEqualTo(field.equals("discoveryReads")
                        ? TransactionDefinition.ISOLATION_REPEATABLE_READ : TransactionDefinition.ISOLATION_DEFAULT);
                assertThat(transaction.getTimeout()).isEqualTo(field.equals("discoveryReads") ? 5 : TransactionDefinition.TIMEOUT_DEFAULT);
            }
            assertThat(AopUtils.isAopProxy(reads)).isFalse();
            assertThat(AopUtils.isAopProxy(result.getBean(ShelterReadController.class))).isFalse();
            assertThat(PawBridgeApplication.class.getAnnotation(Import.class).value()).contains(AnimalShelterConfiguration.class);
        });
        verifyNoInteractions(source, writer, manager, writerManager);
    }

    private static ApplicationContextRunner context() {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(AnimalShelterConfiguration.class);
    }
}
