package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.api.AnimalMemberReads;
import com.pawbridge.backend.boot.configuration.UserAnimalActivitiesConfiguration;
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class UserAnimalActivitiesConfigurationTest {

    @ParameterizedTest
    @ValueSource(strings = {"absent", "false"})
    void given__no_activation__when__assembling__then__create_no_activity_beans(String flag) {
        var runner = new ApplicationContextRunner().withUserConfiguration(UserAnimalActivitiesConfiguration.class);
        if (!flag.equals("absent")) {
            runner = runner.withPropertyValues("pawbridge.user-animal-activities.enabled=false");
        }

        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(UserAnimalActivities.class);
            assertThat(context).doesNotHaveBean(AnimalMemberReads.class);
        });
    }

    @Test
    void given__existing_writer__when__assembling__then__reuse_transactions_and_avoid_new_data_resources() {
        var jdbc = mock(JdbcTemplate.class);
        var writerManager = mock(PlatformTransactionManager.class);

        runner(jdbc, writerManager).run(context -> {
            assertThat(context).hasNotFailed();
            var activities = context.getBean(UserAnimalActivities.class);
            assertThat(ReflectionTestUtils.getField(activities, "jdbc")).isSameAs(jdbc);
            var write = (TransactionTemplate) ReflectionTestUtils.getField(activities, "write");
            var read = (TransactionTemplate) ReflectionTestUtils.getField(activities, "read");
            assertThat(write.getTransactionManager()).isSameAs(writerManager);
            assertThat(write.isReadOnly()).isFalse();
            assertThat(write.getTimeout()).isEqualTo(-1);
            assertThat(read.getTransactionManager()).isSameAs(writerManager);
            assertThat(read.isReadOnly()).isTrue();
            assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
            verify(jdbc).afterPropertiesSet();
            verifyNoMoreInteractions(jdbc);
            verifyNoInteractions(writerManager);
        });
    }

    private static ApplicationContextRunner runner(JdbcTemplate jdbc, PlatformTransactionManager writerManager) {
        return new ApplicationContextRunner()
                .withUserConfiguration(UserAnimalActivitiesConfiguration.class)
                .withPropertyValues("pawbridge.user-animal-activities.enabled=true")
                .withBean("writeJdbc", JdbcTemplate.class, () -> jdbc)
                .withBean("writeTransactionManager", PlatformTransactionManager.class, () -> writerManager)
                .withBean("dataSource", DataSource.class, () -> mock(DataSource.class))
                .withBean("transactionManager", PlatformTransactionManager.class,
                        () -> mock(PlatformTransactionManager.class))
                .withBean(ObjectMapper.class, ObjectMapper::new);
    }
}
