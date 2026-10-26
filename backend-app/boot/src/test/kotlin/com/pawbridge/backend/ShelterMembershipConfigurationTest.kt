package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads
import com.pawbridge.backend.animal.internal.shelter.infrastructure.MembershipLockedAnimalShelterReads
import com.pawbridge.backend.boot.configuration.ShelterMembershipConfiguration
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.excludeRecords
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
    fun `활성화가 없을 때__구성하면__기능과 트랜잭션 빈을 만들지 않는다`(flag: String) {
        // given
        var runner = ApplicationContextRunner().withUserConfiguration(ShelterMembershipConfiguration::class.java)
        if (flag != "absent") {
            runner = runner.withPropertyValues("pawbridge.shelter-membership.enabled=$flag")
        }

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(ShelterMembership::class.java)
        }
    }

    @Test
    fun `기존 writer 자원이 있을 때__구성하면__새 자원 없이 JDBC와 매니저와 시계를 재사용한다`() {
        // given
        val jdbc = mockk<JdbcTemplate>()
        val manager = mockk<PlatformTransactionManager>()

        excludeRecords { manager.hashCode() }
        justRun { jdbc.afterPropertiesSet() }

        // when
        withReaderInputs(runner(jdbc, manager)).run { context ->
            // then
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
            verify(exactly = 1) { jdbc.afterPropertiesSet() }
            confirmVerified(jdbc)
            confirmVerified(manager)
        }
    }

    @Test
    fun `writer 소스가 일치할 때__보호소 명령이 켜지면__SQL과 새 트랜잭션 없이 모듈 조회만 감싼다`() {
        // given
        val writer = mockk<DataSource>()
        val jdbc = mockk<JdbcTemplate>()

        excludeRecords { writer.hashCode() }
        justRun { jdbc.afterPropertiesSet() }
        every { jdbc.dataSource } returns writer

        // when
        lockingRunner(jdbc, writer).run { context ->
            // then
            assertThat(context).hasNotFailed()
            val lookup = context.getBean(ShelterMembership.ShelterLookup::class.java)
            val locked = ReflectionTestUtils.getField(lookup, "shelters")
            assertThat(locked).isInstanceOf(MembershipLockedAnimalShelterReads::class.java)
            assertThat(ReflectionTestUtils.getField(locked!!, "delegate")).isSameAs(context.getBean("animalModuleReads"))
            assertThat(ReflectionTestUtils.getField(locked, "jdbc")).isSameAs(jdbc)
            verifyNoSqlOn(jdbc)
            confirmVerified(writer)
        }
    }

    @Test
    fun `writer 소스가 다를 때__보호소 명령이 켜지면__SQL 전에 닫힌 채로 실패한다`() {
        // given
        val writer = mockk<DataSource>()
        val jdbc = mockk<JdbcTemplate>()

        excludeRecords { writer.hashCode() }
        justRun { jdbc.afterPropertiesSet() }
        every { jdbc.dataSource } returns mockk<DataSource>()

        // when
        lockingRunner(jdbc, writer).run { context ->
            // then
            assertThat(context).hasFailed()
            assertThat(context.startupFailure)
                .hasRootCauseMessage("Shelter membership module requires the shared writer data source")
            verifyNoSqlOn(jdbc)
            confirmVerified(writer)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["absent", "false", "on", "yes", "1", "unknown"])
    fun `보호소 명령이 true로 명시되지 않았을 때__구성하면__writer 잠금 없이 원래 위임을 유지한다`(
        flag: String,
    ) {
        // given
        val jdbc = mockk<JdbcTemplate>()
        var runner = withReaderInputs(runner(jdbc, mockk<PlatformTransactionManager>()))
        if (flag != "absent") {
            runner = runner.withPropertyValues("pawbridge.shelter-commands.enabled=$flag")
        }

        justRun { jdbc.afterPropertiesSet() }

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
            val actual = ReflectionTestUtils.getField(context.getBean(ShelterMembership.ShelterLookup::class.java), "shelters")
            assertThat(actual).isSameAs(context.getBean("animalModuleReads"))
            verify(exactly = 0) { jdbc.dataSource }
            verifyNoSqlOn(jdbc)
        }
    }

    companion object {
        private fun verifyNoSqlOn(jdbc: JdbcTemplate) {
            verify(exactly = 0) { jdbc.query(any<String>(), any<RowMapper<Any>>(), *anyVararg<Any>()) }
            verify(exactly = 0) { jdbc.update(any<String>(), *anyVararg<Any>()) }
        }

        private fun lockingRunner(jdbc: JdbcTemplate, writer: DataSource): ApplicationContextRunner {
            return withReaderInputs(runner(jdbc, mockk<PlatformTransactionManager>()))
                .withBean("writeDataSource", DataSource::class.java, Supplier { writer })
                .withPropertyValues("pawbridge.shelter-commands.enabled=true")
        }

        /** Inputs of the in-process Animal shelter reader that the membership lookup is built on. */
        private fun withReaderInputs(runner: ApplicationContextRunner): ApplicationContextRunner {
            return runner
                .withBean("dataSource", DataSource::class.java, Supplier { mockk<DataSource>() })
                .withBean(
                    "transactionManager",
                    PlatformTransactionManager::class.java,
                    Supplier { mockk<PlatformTransactionManager>() },
                )
                .withBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
                .withBean(ShelterReads::class.java, Supplier { mockk<ShelterReads>() })
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
