package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads
import com.pawbridge.backend.animal.internal.shelter.infrastructure.MembershipLockedAnimalShelterReads
import com.pawbridge.backend.boot.configuration.ShelterMembershipConfiguration
import com.pawbridge.backend.support.anyNonNull
import com.pawbridge.backend.support.anyVarargs
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.ZoneId
import java.util.function.Supplier
import javax.sql.DataSource

class ShelterMembershipConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = ["absent", "false"])
    fun given__no_activation__when__assembling__then__create_no_feature_or_transaction_beans(flag: String) {
        var runner = ApplicationContextRunner().withUserConfiguration(ShelterMembershipConfiguration::class.java)
        if (flag != "absent") {
            runner = runner.withPropertyValues("pawbridge.shelter-membership.enabled=$flag")
        }

        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(ShelterMembership::class.java)
        }
    }

    @Test
    fun given__existing_writer_resources__when__assembling__then__reuse_jdbc_tm_clock_with_readonly_and_repeatable_read_without_new_resources() {
        val jdbc = mock(JdbcTemplate::class.java)
        val manager = mock(PlatformTransactionManager::class.java)

        withReaderInputs(runner(jdbc, manager)).run { context ->
            assertThat(context).hasNotFailed()
            val memberships = context.getBean(ShelterMembership::class.java)
            assertThat(ReflectionTestUtils.getField(memberships, "jdbc")).isSameAs(jdbc)
            val write = ReflectionTestUtils.getField(memberships, "write") as TransactionTemplate
            val read = ReflectionTestUtils.getField(memberships, "read") as TransactionTemplate
            val statistics = ReflectionTestUtils.getField(memberships, "statistics") as TransactionTemplate
            assertThat(write.transactionManager).isSameAs(manager)
            assertThat(write.isReadOnly).isFalse()
            assertThat(write.timeout).isEqualTo(-1)
            assertThat(read.transactionManager).isSameAs(manager)
            assertThat(read.isReadOnly).isTrue()
            assertThat(statistics.isolationLevel).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ)
            val clock = ReflectionTestUtils.getField(memberships, "clock") as Clock
            assertThat(clock.zone).isEqualTo(ZoneId.of("Asia/Seoul"))
            assertThat(context.getBeansOfType(DataSource::class.java)).hasSize(1)
            verify(jdbc).afterPropertiesSet()
            verifyNoMoreInteractions(jdbc)
            verifyNoInteractions(manager)
        }
    }

    @Test
    fun given__matching_writer_source__when__shelter_commands_are_on__then__wrap_only_the_module_lookup_without_sql_or_new_transaction() {
        val writer = mock(DataSource::class.java)
        val jdbc = mock(JdbcTemplate::class.java)
        whenever(jdbc.dataSource).thenReturn(writer)

        lockingRunner(jdbc, writer).run { context ->
            assertThat(context).hasNotFailed()
            val lookup = context.getBean(ShelterMembership.ShelterLookup::class.java)
            val locked = ReflectionTestUtils.getField(lookup, "shelters")
            assertThat(locked).isInstanceOf(MembershipLockedAnimalShelterReads::class.java)
            assertThat(ReflectionTestUtils.getField(locked!!, "delegate")).isSameAs(context.getBean("animalModuleReads"))
            assertThat(ReflectionTestUtils.getField(locked, "jdbc")).isSameAs(jdbc)
            verifyNoSqlOn(jdbc)
            verifyNoInteractions(writer)
        }
    }

    @Test
    fun given__different_writer_source__when__shelter_commands_are_on__then__fail_closed_before_sql() {
        val writer = mock(DataSource::class.java)
        val jdbc = mock(JdbcTemplate::class.java)
        whenever(jdbc.dataSource).thenReturn(mock(DataSource::class.java))

        lockingRunner(jdbc, writer).run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure)
                .hasRootCauseMessage("Shelter membership module requires the shared writer data source")
            verifyNoSqlOn(jdbc)
            verifyNoInteractions(writer)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["absent", "false", "on", "yes", "1", "unknown"])
    fun given__shelter_commands_not_explicitly_true__when__assembling__then__retain_original_delegate_without_writer_locking(
        flag: String,
    ) {
        val jdbc = mock(JdbcTemplate::class.java)
        var runner = withReaderInputs(runner(jdbc, mock(PlatformTransactionManager::class.java)))
        if (flag != "absent") {
            runner = runner.withPropertyValues("pawbridge.shelter-commands.enabled=$flag")
        }

        runner.run { context ->
            assertThat(context).hasNotFailed()
            val actual = ReflectionTestUtils.getField(context.getBean(ShelterMembership.ShelterLookup::class.java), "shelters")
            assertThat(actual).isSameAs(context.getBean("animalModuleReads"))
            verify(jdbc, never()).dataSource
            verifyNoSqlOn(jdbc)
        }
    }

    companion object {
        private fun verifyNoSqlOn(jdbc: JdbcTemplate) {
            verify(jdbc, never()).query(anyString(), anyNonNull<RowMapper<Any>>(), *anyVarargs())
            verify(jdbc, never()).update(anyString(), *anyVarargs())
        }

        private fun lockingRunner(jdbc: JdbcTemplate, writer: DataSource): ApplicationContextRunner {
            return withReaderInputs(runner(jdbc, mock(PlatformTransactionManager::class.java)))
                .withBean("writeDataSource", DataSource::class.java, Supplier { writer })
                .withPropertyValues("pawbridge.shelter-commands.enabled=true")
        }

        /** Inputs of the in-process Animal shelter reader that the membership lookup is built on. */
        private fun withReaderInputs(runner: ApplicationContextRunner): ApplicationContextRunner {
            return runner
                .withBean("dataSource", DataSource::class.java, Supplier { mock(DataSource::class.java) })
                .withBean(
                    "transactionManager",
                    PlatformTransactionManager::class.java,
                    Supplier { mock(PlatformTransactionManager::class.java) },
                )
                .withBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
                .withBean(ShelterReads::class.java, Supplier { mock(ShelterReads::class.java) })
        }

        private fun runner(jdbc: JdbcTemplate, manager: PlatformTransactionManager): ApplicationContextRunner {
            return ApplicationContextRunner()
                .withUserConfiguration(ShelterMembershipConfiguration::class.java)
                .withPropertyValues("pawbridge.shelter-membership.enabled=true")
                .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { jdbc })
                .withBean("writeTransactionManager", PlatformTransactionManager::class.java, Supplier { manager })
                .withBean("readClock", Clock::class.java, Supplier { Clock.systemUTC() })
        }
    }
}
