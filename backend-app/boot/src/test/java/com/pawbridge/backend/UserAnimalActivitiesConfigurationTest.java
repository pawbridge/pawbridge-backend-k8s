package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.pawbridge.backend.boot.configuration.UserAnimalActivitiesConfiguration;
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class UserAnimalActivitiesConfigurationTest {
    @ParameterizedTest @ValueSource(strings={"absent","false"})
    void given__no_activation__when__assembling__then__create_no_activity_or_external_client_beans(String flag){var runner=new ApplicationContextRunner().withUserConfiguration(UserAnimalActivitiesConfiguration.class);
        if(!flag.equals("absent"))runner=runner.withPropertyValues("pawbridge.user-animal-activities.enabled=false");runner.run(context->{assertThat(context).hasNotFailed();assertThat(context).doesNotHaveBean(UserAnimalActivities.class);assertThat(context).doesNotHaveBean(java.net.http.HttpClient.class);});}
    @Test void given__activation_without_explicit_animal_address__when__assembling__then__fail_before_sql(){var jdbc=mock(JdbcTemplate.class);runner(jdbc,mock(PlatformTransactionManager.class)).run(context->{assertThat(context).hasFailed();verify(jdbc).afterPropertiesSet();verifyNoMoreInteractions(jdbc);});}
    @Test void given__existing_writer_and_explicit_address__when__assembling__then__reuse_transactions_and_avoid_new_data_resources(){var jdbc=mock(JdbcTemplate.class);var manager=mock(PlatformTransactionManager.class);
        runner(jdbc,manager).withPropertyValues("service.animal.url=http://127.0.0.1:1").run(context->{assertThat(context).hasNotFailed();var activities=context.getBean(UserAnimalActivities.class);assertThat(ReflectionTestUtils.getField(activities,"jdbc")).isSameAs(jdbc);
            var write=(TransactionTemplate)ReflectionTestUtils.getField(activities,"write");var read=(TransactionTemplate)ReflectionTestUtils.getField(activities,"read");assertThat(write.getTransactionManager()).isSameAs(manager);assertThat(write.isReadOnly()).isFalse();assertThat(write.getTimeout()).isEqualTo(-1);assertThat(read.getTransactionManager()).isSameAs(manager);assertThat(read.isReadOnly()).isTrue();
            assertThat(context.getBeansOfType(javax.sql.DataSource.class)).isEmpty();verify(jdbc).afterPropertiesSet();verifyNoMoreInteractions(jdbc);verifyNoInteractions(manager);
        });}
    private static ApplicationContextRunner runner(JdbcTemplate jdbc,PlatformTransactionManager manager){return new ApplicationContextRunner().withUserConfiguration(UserAnimalActivitiesConfiguration.class).withPropertyValues("pawbridge.user-animal-activities.enabled=true")
        .withBean("writeJdbc",JdbcTemplate.class,()->jdbc).withBean("writeTransactionManager",PlatformTransactionManager.class,()->manager);}
}
