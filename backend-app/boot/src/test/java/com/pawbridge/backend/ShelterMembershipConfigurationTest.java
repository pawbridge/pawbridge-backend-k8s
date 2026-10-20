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
    private static ApplicationContextRunner runner(JdbcTemplate jdbc,PlatformTransactionManager manager){return new ApplicationContextRunner().withUserConfiguration(ShelterMembershipConfiguration.class)
            .withPropertyValues("pawbridge.shelter-membership.enabled=true").withBean("writeJdbc",JdbcTemplate.class,()->jdbc)
            .withBean("writeTransactionManager",PlatformTransactionManager.class,()->manager).withBean("readClock",Clock.class,Clock::systemUTC);}
}
