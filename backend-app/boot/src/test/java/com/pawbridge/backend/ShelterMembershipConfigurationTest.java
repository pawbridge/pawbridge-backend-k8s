package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.pawbridge.backend.boot.configuration.ShelterMembershipConfiguration;
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class ShelterMembershipConfigurationTest {
    @ParameterizedTest @ValueSource(strings={"absent","false"})
    void given__no_activation__when__assembling__then__create_no_feature_http_or_transaction_beans(String flag){
        var runner=new ApplicationContextRunner().withUserConfiguration(ShelterMembershipConfiguration.class);
        if(!flag.equals("absent"))runner=runner.withPropertyValues("pawbridge.shelter-membership.enabled="+flag);
        runner.run(context->{assertThat(context).hasNotFailed();assertThat(context).doesNotHaveBean(ShelterMembership.class);assertThat(context).doesNotHaveBean(java.net.http.HttpClient.class);});
    }
    @Test void given__activation_without_animal_origin__when__assembling__then__fail_closed_without_sql(){
        var jdbc=mock(JdbcTemplate.class);runner(jdbc,mock(PlatformTransactionManager.class)).run(context->{assertThat(context).hasFailed();verify(jdbc).afterPropertiesSet();verifyNoMoreInteractions(jdbc);});
    }
    @Test void given__explicit_origin_and_existing_writer_resources__when__assembling__then__reuse_jdbc_tm_clock_with_readonly_and_repeatable_read_without_new_resources(){
        var jdbc=mock(JdbcTemplate.class);var manager=mock(PlatformTransactionManager.class);
        runner(jdbc,manager).withPropertyValues("pawbridge.shelter-membership.animal-base-url=http://127.0.0.1:1").run(context->{
            assertThat(context).hasNotFailed();var memberships=context.getBean(ShelterMembership.class);
            assertThat(ReflectionTestUtils.getField(memberships,"jdbc")).isSameAs(jdbc);
            var write=(TransactionTemplate)ReflectionTestUtils.getField(memberships,"write");var read=(TransactionTemplate)ReflectionTestUtils.getField(memberships,"read");var stats=(TransactionTemplate)ReflectionTestUtils.getField(memberships,"statistics");
            assertThat(write.getTransactionManager()).isSameAs(manager);assertThat(write.isReadOnly()).isFalse();assertThat(write.getTimeout()).isEqualTo(-1);
            assertThat(read.getTransactionManager()).isSameAs(manager);assertThat(read.isReadOnly()).isTrue();assertThat(stats.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            assertThat(((Clock)ReflectionTestUtils.getField(memberships,"clock")).getZone()).isEqualTo(java.time.ZoneId.of("Asia/Seoul"));
            assertThat(context.getBeansOfType(javax.sql.DataSource.class)).isEmpty();verify(jdbc).afterPropertiesSet();verifyNoMoreInteractions(jdbc);verifyNoInteractions(manager);
        });
    }
    @Test void given__module_and_matching_writer__when__assembling__then__wrap_only_module_lookup_without_sql_or_new_transaction(){
        var writer=mock(javax.sql.DataSource.class);var jdbc=mock(JdbcTemplate.class);when(jdbc.getDataSource()).thenReturn(writer);
        moduleRunner(jdbc,writer).withPropertyValues("pawbridge.shelter-commands.enabled=true").run(context->{
            assertThat(context).hasNotFailed();
            var lookup=context.getBean(ShelterMembership.ShelterLookup.class);
            Object locked=ReflectionTestUtils.getField(lookup,"shelters");
            assertThat(locked).isInstanceOf(com.pawbridge.backend.animal.internal.shelter.infrastructure.MembershipLockedAnimalShelterReads.class);
            assertThat(ReflectionTestUtils.getField(locked,"delegate")).isSameAs(context.getBean("animalModuleReads"));
            assertThat(ReflectionTestUtils.getField(locked,"jdbc")).isSameAs(jdbc);
            assertThat(context.getBeansOfType(java.net.http.HttpClient.class)).isEmpty();
            verify(jdbc,never()).query(anyString(),any(org.springframework.jdbc.core.RowMapper.class),any());
            verify(jdbc,never()).update(anyString(),any(Object[].class));
            verifyNoInteractions(writer);
        });
    }
    @Test void given__module_and_different_writer_source__when__assembling__then__fail_closed_before_sql(){
        var writer=mock(javax.sql.DataSource.class);var jdbc=mock(JdbcTemplate.class);when(jdbc.getDataSource()).thenReturn(mock(javax.sql.DataSource.class));
        moduleRunner(jdbc,writer).withPropertyValues("pawbridge.shelter-commands.enabled=true").run(context->{
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage("Shelter membership module requires the shared writer data source");
            verify(jdbc,never()).query(anyString(),any(org.springframework.jdbc.core.RowMapper.class),any());
            verify(jdbc,never()).update(anyString(),any(Object[].class));
            verifyNoInteractions(writer);
        });
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"absent","false","on","yes","1","unknown"})
    void given__shelter_commands_not_explicitly_true__when__module_membership_assembles__then__retain_original_delegate_without_writer_locking(String flag){
        var jdbc=mock(JdbcTemplate.class);
        var selected=runner(jdbc,mock(PlatformTransactionManager.class))
                .withPropertyValues("pawbridge.shelter-membership.animal-backend=module")
                .withBean("dataSource",javax.sql.DataSource.class,()->mock(javax.sql.DataSource.class))
                .withBean("transactionManager",PlatformTransactionManager.class,()->mock(PlatformTransactionManager.class))
                .withBean(com.fasterxml.jackson.databind.ObjectMapper.class,com.fasterxml.jackson.databind.ObjectMapper::new)
                .withBean(com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.class,
                        ()->mock(com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.class));
        if(!flag.equals("absent"))selected=selected.withPropertyValues("pawbridge.shelter-commands.enabled="+flag);
        selected.run(context->{
            assertThat(context).hasNotFailed();
            Object actual=ReflectionTestUtils.getField(context.getBean(ShelterMembership.ShelterLookup.class),"shelters");
            assertThat(actual).isSameAs(context.getBean("animalModuleReads"));
            verify(jdbc,never()).getDataSource();
            verify(jdbc,never()).query(anyString(),any(org.springframework.jdbc.core.RowMapper.class),any());
            verify(jdbc,never()).update(anyString(),any(Object[].class));
        });
    }
    private static ApplicationContextRunner moduleRunner(JdbcTemplate jdbc,javax.sql.DataSource writer){
        return runner(jdbc,mock(PlatformTransactionManager.class))
                .withPropertyValues("pawbridge.shelter-membership.animal-backend=module")
                .withBean("writeDataSource",javax.sql.DataSource.class,()->writer)
                .withBean("dataSource",javax.sql.DataSource.class,()->mock(javax.sql.DataSource.class))
                .withBean("transactionManager",PlatformTransactionManager.class,()->mock(PlatformTransactionManager.class))
                .withBean(com.fasterxml.jackson.databind.ObjectMapper.class,com.fasterxml.jackson.databind.ObjectMapper::new)
                .withBean(com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.class,
                        ()->mock(com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.class));
    }
    private static ApplicationContextRunner runner(JdbcTemplate jdbc,PlatformTransactionManager manager){return new ApplicationContextRunner().withUserConfiguration(ShelterMembershipConfiguration.class)
            .withPropertyValues("pawbridge.shelter-membership.enabled=true").withBean("writeJdbc",JdbcTemplate.class,()->jdbc)
            .withBean("writeTransactionManager",PlatformTransactionManager.class,()->manager).withBean("readClock",Clock.class,Clock::systemUTC);}
}
