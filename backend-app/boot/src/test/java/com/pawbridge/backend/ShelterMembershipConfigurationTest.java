package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads;
import com.pawbridge.backend.animal.internal.shelter.infrastructure.MembershipLockedAnimalShelterReads;
import com.pawbridge.backend.boot.configuration.ShelterMembershipConfiguration;
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership;
import java.time.Clock;
import java.time.ZoneId;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class ShelterMembershipConfigurationTest {

    @ParameterizedTest
    @ValueSource(strings = {"absent", "false"})
    void given__no_activation__when__assembling__then__create_no_feature_or_transaction_beans(String flag) {
        var runner = new ApplicationContextRunner().withUserConfiguration(ShelterMembershipConfiguration.class);
        if (!flag.equals("absent")) {
            runner = runner.withPropertyValues("pawbridge.shelter-membership.enabled=" + flag);
        }

        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ShelterMembership.class);
        });
    }

    @Test
    void given__existing_writer_resources__when__assembling__then__reuse_jdbc_tm_clock_with_readonly_and_repeatable_read_without_new_resources() {
        var jdbc = mock(JdbcTemplate.class);
        var manager = mock(PlatformTransactionManager.class);

        withReaderInputs(runner(jdbc, manager)).run(context -> {
            assertThat(context).hasNotFailed();
            var memberships = context.getBean(ShelterMembership.class);
            assertThat(ReflectionTestUtils.getField(memberships, "jdbc")).isSameAs(jdbc);
            var write = (TransactionTemplate) ReflectionTestUtils.getField(memberships, "write");
            var read = (TransactionTemplate) ReflectionTestUtils.getField(memberships, "read");
            var statistics = (TransactionTemplate) ReflectionTestUtils.getField(memberships, "statistics");
            assertThat(write.getTransactionManager()).isSameAs(manager);
            assertThat(write.isReadOnly()).isFalse();
            assertThat(write.getTimeout()).isEqualTo(-1);
            assertThat(read.getTransactionManager()).isSameAs(manager);
            assertThat(read.isReadOnly()).isTrue();
            assertThat(statistics.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            var clock = (Clock) ReflectionTestUtils.getField(memberships, "clock");
            assertThat(clock.getZone()).isEqualTo(ZoneId.of("Asia/Seoul"));
            assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
            verify(jdbc).afterPropertiesSet();
            verifyNoMoreInteractions(jdbc);
            verifyNoInteractions(manager);
        });
    }

    @Test
    void given__matching_writer_source__when__shelter_commands_are_on__then__wrap_only_the_module_lookup_without_sql_or_new_transaction() {
        var writer = mock(DataSource.class);
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.getDataSource()).thenReturn(writer);

        lockingRunner(jdbc, writer).run(context -> {
            assertThat(context).hasNotFailed();
            var lookup = context.getBean(ShelterMembership.ShelterLookup.class);
            Object locked = ReflectionTestUtils.getField(lookup, "shelters");
            assertThat(locked).isInstanceOf(MembershipLockedAnimalShelterReads.class);
            assertThat(ReflectionTestUtils.getField(locked, "delegate")).isSameAs(context.getBean("animalModuleReads"));
            assertThat(ReflectionTestUtils.getField(locked, "jdbc")).isSameAs(jdbc);
            verifyNoSqlOn(jdbc);
            verifyNoInteractions(writer);
        });
    }

    @Test
    void given__different_writer_source__when__shelter_commands_are_on__then__fail_closed_before_sql() {
        var writer = mock(DataSource.class);
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.getDataSource()).thenReturn(mock(DataSource.class));

        lockingRunner(jdbc, writer).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseMessage("Shelter membership module requires the shared writer data source");
            verifyNoSqlOn(jdbc);
            verifyNoInteractions(writer);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "false", "on", "yes", "1", "unknown"})
    void given__shelter_commands_not_explicitly_true__when__assembling__then__retain_original_delegate_without_writer_locking(
            String flag) {
        var jdbc = mock(JdbcTemplate.class);
        var runner = withReaderInputs(runner(jdbc, mock(PlatformTransactionManager.class)));
        if (!flag.equals("absent")) {
            runner = runner.withPropertyValues("pawbridge.shelter-commands.enabled=" + flag);
        }

        runner.run(context -> {
            assertThat(context).hasNotFailed();
            Object actual = ReflectionTestUtils.getField(context.getBean(ShelterMembership.ShelterLookup.class), "shelters");
            assertThat(actual).isSameAs(context.getBean("animalModuleReads"));
            verify(jdbc, never()).getDataSource();
            verifyNoSqlOn(jdbc);
        });
    }

    private static void verifyNoSqlOn(JdbcTemplate jdbc) {
        verify(jdbc, never()).query(anyString(), any(RowMapper.class), any());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    private static ApplicationContextRunner lockingRunner(JdbcTemplate jdbc, DataSource writer) {
        return withReaderInputs(runner(jdbc, mock(PlatformTransactionManager.class)))
                .withBean("writeDataSource", DataSource.class, () -> writer)
                .withPropertyValues("pawbridge.shelter-commands.enabled=true");
    }

    /** Inputs of the in-process Animal shelter reader that the membership lookup is built on. */
    private static ApplicationContextRunner withReaderInputs(ApplicationContextRunner runner) {
        return runner
                .withBean("dataSource", DataSource.class, () -> mock(DataSource.class))
                .withBean("transactionManager", PlatformTransactionManager.class,
                        () -> mock(PlatformTransactionManager.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(ShelterReads.class, () -> mock(ShelterReads.class));
    }

    private static ApplicationContextRunner runner(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new ApplicationContextRunner()
                .withUserConfiguration(ShelterMembershipConfiguration.class)
                .withPropertyValues("pawbridge.shelter-membership.enabled=true")
                .withBean("writeJdbc", JdbcTemplate.class, () -> jdbc)
                .withBean("writeTransactionManager", PlatformTransactionManager.class, () -> manager)
                .withBean("readClock", Clock.class, Clock::systemUTC);
    }
}
