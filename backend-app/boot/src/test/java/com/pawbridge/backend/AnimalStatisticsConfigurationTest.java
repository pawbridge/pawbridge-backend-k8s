package com.pawbridge.backend;

import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics;
import com.pawbridge.backend.animal.internal.statistics.web.AdminAnimalStatisticsController;
import com.pawbridge.backend.animal.internal.statistics.web.AnimalStatisticsController;
import com.pawbridge.backend.animal.internal.statistics.web.AnimalStatisticsExceptionHandler;
import com.pawbridge.backend.boot.configuration.AnimalShelterConfiguration;
import com.pawbridge.backend.boot.configuration.AnimalStatisticsConfiguration;
import java.time.Clock;
import java.time.ZoneId;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
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

class AnimalStatisticsConfigurationTest {
    @ParameterizedTest @ValueSource(strings = {"absent", "false"})
    void given__statistics_not_enabled__when__assembling_without_reader_inputs__then__register_no_statistics_beans(String flag) {
        var runner = context();
        if (flag.equals("false")) runner = runner.withPropertyValues("pawbridge.animal-statistics.enabled=false");
        runner.run(result -> assertThat(result).hasNotFailed().doesNotHaveBean(AnimalStatistics.class)
                .doesNotHaveBean(AnimalStatisticsController.class).doesNotHaveBean(AdminAnimalStatisticsController.class)
                .doesNotHaveBean(AnimalStatisticsExceptionHandler.class).doesNotHaveBean(DataSource.class)
                .doesNotHaveBean(PlatformTransactionManager.class));
    }

    @Test
    void given__statistics_enabled_with_named_reader_and_primary_writer__when__assembling_and_closing__then__keep_reader_transaction_and_seoul_clock_without_outer_transaction() {
        DataSource source = mock(DataSource.class), writer = mock(DataSource.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class), writerManager = mock(PlatformTransactionManager.class);
        context().withPropertyValues("pawbridge.animal-statistics.enabled=true")
                .withBean("dataSource", DataSource.class, () -> source)
                .withBean("writerDataSource", DataSource.class, () -> writer, definition -> definition.setPrimary(true))
                .withBean("transactionManager", PlatformTransactionManager.class, () -> manager)
                .withBean("writerTransactionManager", PlatformTransactionManager.class, () -> writerManager, definition -> definition.setPrimary(true))
                .run(result -> {
                    assertThat(result).hasNotFailed().hasSingleBean(AnimalStatistics.class).hasSingleBean(AnimalStatisticsController.class)
                            .hasSingleBean(AdminAnimalStatisticsController.class).hasSingleBean(AnimalStatisticsExceptionHandler.class);
                    AnimalStatistics statistics = result.getBean(AnimalStatistics.class);
                    var jdbc = (JdbcTemplate) ((NamedParameterJdbcTemplate) ReflectionTestUtils.getField(statistics, "jdbc")).getJdbcTemplate();
                    assertThat(jdbc.getDataSource()).isSameAs(source);
                    assertThat(jdbc.getQueryTimeout()).isEqualTo(-1);
                    var reads = (TransactionTemplate) ReflectionTestUtils.getField(statistics, "reads");
                    assertThat(reads.getTransactionManager()).isSameAs(manager);
                    assertThat(reads.isReadOnly()).isTrue();
                    assertThat(reads.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT);
                    assertThat(reads.getTimeout()).isEqualTo(TransactionDefinition.TIMEOUT_DEFAULT);
                    assertThat(((Clock) ReflectionTestUtils.getField(statistics, "clock")).getZone()).isEqualTo(ZoneId.of("Asia/Seoul"));
                    assertThat(AopUtils.isAopProxy(statistics)).isFalse();
                    assertThat(AopUtils.isAopProxy(result.getBean(AdminAnimalStatisticsController.class))).isFalse();
                    assertThat(PawBridgeApplication.class.getAnnotation(Import.class).value())
                            .contains(AnimalShelterConfiguration.class, AnimalStatisticsConfiguration.class);
                });
        verifyNoInteractions(source, writer, manager, writerManager);
    }

    private static ApplicationContextRunner context() {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(AnimalStatisticsConfiguration.class);
    }
}
