package com.pawbridge.userservice.contact;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.pawbridge.userservice.repository.UserRepository;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.web.server.ResponseStatusException;

@Tag("postgresql")
@EnabledIfEnvironmentVariable(named="PRIVATE_NOTES_PG_TEST_PORT",matches="[0-9]{1,5}")
class ContactMemberDeletionPostgresqlTest {
    static JdbcTemplate jdbc;
    static DataSourceTransactionManager manager;
    UserRepository users;
    CommunityContactClient community;
    ContactMemberDeletion deletion;
    @BeforeAll static void guardedDatabase() {
        var source=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:"+System.getenv("PRIVATE_NOTES_PG_TEST_PORT")+"/pawbridge","postgres","local_pg_test_only");
        jdbc=new JdbcTemplate(source);
        assertThat(jdbc.queryForList("SELECT marker FROM migration_test_guard.guard",String.class)).containsExactly("services-pg-disposable");
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS pawbridge_user");
        Flyway.configure().dataSource(source).defaultSchema("pawbridge_user").locations("classpath:db/postgresql").cleanDisabled(true).loggers(new String[0]).load().migrate();
        manager=new DataSourceTransactionManager(source);
    }
    @BeforeEach void prepare() {
        jdbc.update("DELETE FROM pawbridge_user.contact_deletions WHERE user_id=501");
        jdbc.update("DELETE FROM pawbridge_user.favorites WHERE user_id=501");
        jdbc.update("DELETE FROM pawbridge_user.refresh_tokens WHERE user_id=501");
        jdbc.update("DELETE FROM pawbridge_user.users WHERE user_id=501");
        jdbc.update("INSERT INTO pawbridge_user.users(user_id,email,name,nickname,provider,role) VALUES (501,'notes-fixture@example.invalid','테스트','탈퇴검증','LOCAL','ROLE_USER')");
        users=mock(UserRepository.class);community=mock(CommunityContactClient.class);
        doAnswer(call -> {jdbc.update("DELETE FROM pawbridge_user.users WHERE user_id=501");return null;}).when(users).deleteById(501L);
        deletion=new ContactMemberDeletion(jdbc,community,users,manager);
    }
    @Test void givenExistingMember_whenDelete_thenPendingStateIsCommittedBeforeRemotePurge() {
        doAnswer(call -> {
            // A different thread/connection sees the durable flag, proving this is not an uncommitted local value.
            var pool=Executors.newSingleThreadExecutor();
            try {
                var observed=pool.submit(() -> new ContactMemberQuery(jdbc).get(501));
                var member=observed.get(5,TimeUnit.SECONDS);
                assertThat(member.active()).isFalse();assertThat(member.deletionPending()).isTrue();
            } finally {pool.shutdownNow();}
            return null;
        }).when(community).removeMailboxes(501);
        deletion.delete(501);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pawbridge_user.users WHERE user_id=501",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pawbridge_user.contact_deletions WHERE user_id=501",Long.class)).isZero();
    }
    @Test void givenRemotePurgeFails_whenDeleteThenRetry_thenBlockNewContactUntilCompletion() {
        doThrow(new IllegalStateException("synthetic outage")).doNothing().when(community).removeMailboxes(501);
        assertThatThrownBy(() -> deletion.delete(501)).isInstanceOf(ResponseStatusException.class);
        var pending=new ContactMemberQuery(jdbc).get(501);
        assertThat(pending.active()).isFalse();assertThat(pending.deletionPending()).isTrue();
        deletion.delete(501);
        assertThat(new ContactMemberQuery(jdbc).get(501).active()).isFalse();
        assertThat(new ContactMemberQuery(jdbc).get(501).deletionPending()).isFalse();
    }

    @Test void givenActivePendingAndMissingIds_whenBatchQueried_thenReturnMinimalStatusInRequestedOrder() {
        var query = new ContactMemberQuery(jdbc);
        assertThat(query.getAll(java.util.List.of(501L, 999999L, 501L)))
                .containsExactly(new ContactMember(501L, "탈퇴검증", true, false), ContactMember.missing(999999));

        jdbc.update("INSERT INTO pawbridge_user.contact_deletions(user_id) VALUES (501)");
        assertThat(query.getAll(java.util.List.of(501L)))
                .containsExactly(new ContactMember(501L, "탈퇴한 회원", false, true));
    }
}
