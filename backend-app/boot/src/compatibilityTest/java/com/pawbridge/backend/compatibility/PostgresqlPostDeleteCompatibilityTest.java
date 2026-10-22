package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pawbridge.backend.community.internal.post.application.DeletePost;
import com.pawbridge.backend.community.internal.post.application.PostNotFoundException;
import com.pawbridge.backend.community.internal.post.domain.PostBoardType;
import com.pawbridge.backend.community.internal.post.domain.PostToCreate;
import com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostDeletion;
import com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostReader;
import com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostWriter;
import com.pawbridge.communityservice.domain.entity.Post;
import com.pawbridge.communityservice.domain.entity.StringListConverter;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.data.mapping.context.PersistentEntities;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Same guarded PG fixture; independent row-lock connections are test-only, never a larger production pool. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class PostgresqlPostDeleteCompatibilityTest {
    private final ObjectMapper mapper=JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    private final Clock clock=Clock.fixed(Instant.parse("2026-01-01T00:00:00.123456Z"),ZoneId.systemDefault());
    private final Clock deletionClock=Clock.offset(clock,java.time.Duration.ofHours(1));
    private F3PostgresqlFixture fixture;
    private HikariDataSource reader;
    private HikariDataSource writer;
    private JdbcTemplate readJdbc;
    private JdbcTemplate writeJdbc;
    private JdbcPostReader reads;
    private JdbcPostWriter creates;
    private JdbcPostDeletion deletes;
    private SessionFactory legacy;

    @BeforeAll void openOnlyOwnedF4FixtureAndExistingBoundedPools() throws Exception {
        fixture=F3PostgresqlFixture.open();
        try(Connection connection=fixture.admin();var statement=connection.createStatement();
            var marker=statement.executeQuery("SELECT value,owner FROM pawbridge_f4_fixture.marker")) {
            assertThat(marker.next()).isTrue();
            assertThat(marker.getString(1)).isEqualTo("pawbridge-f4-ci-only-v1");
            assertThat(marker.getString(2)).isEqualTo(F3PostgresqlFixture.required("F4_CI_OWNER"))
                    .isEqualTo(F3PostgresqlFixture.required("GITHUB_RUN_ID")+"-"+F3PostgresqlFixture.required("GITHUB_RUN_ATTEMPT"));
            assertThat(marker.next()).isFalse();
        }
        reader=pool(fixture.reads().applicationUser(),fixture.reads().applicationPassword(),2,"f4-reader");
        writer=pool(F3PostgresqlFixture.WRITER,fixture.writerPassword(),1,"f4-writer");
        readJdbc=new JdbcTemplate(reader); writeJdbc=new JdbcTemplate(writer);
        var manager=new DataSourceTransactionManager(reader);manager.setEnforceReadOnly(true);
        var readTx=new TransactionTemplate(manager);readTx.setReadOnly(true);readTx.setTimeout(5);
        reads=new JdbcPostReader(readJdbc,readTx,mapper);
        var writeTx=new TransactionTemplate(new DataSourceTransactionManager(writer));writeTx.setTimeout(10);
        creates=new JdbcPostWriter(writeJdbc,writeTx,mapper,clock);
        deletes=new JdbcPostDeletion(writeJdbc,writeTx,deletionClock);
        // Legacy Hibernate emits a full UPDATE. Only its synthetic oracle uses admin; candidate retains column grants.
        var admin=new DriverManagerDataSource(fixture.reads().jdbcUrl(),
                F3PostgresqlFixture.required("F2_TEST_ADMIN_USER"),F3PostgresqlFixture.required("F2_TEST_ADMIN_PASSWORD"));
        var config=new Configuration().addAnnotatedClass(Post.class).addAttributeConverter(StringListConverter.class)
                .setProperty(AvailableSettings.DEFAULT_SCHEMA,"pawbridge_community")
                .setProperty(AvailableSettings.HBM2DDL_AUTO,"none")
                .setProperty(AvailableSettings.JDBC_TIME_ZONE,"UTC").setProperty(AvailableSettings.SHOW_SQL,"false");
        config.getProperties().put(AvailableSettings.JAKARTA_NON_JTA_DATASOURCE,admin);
        legacy=config.buildSessionFactory();
        assertThat(reader.getMaximumPoolSize()).isEqualTo(2);
        assertThat(writer.getMaximumPoolSize()).isOne();
        assertThat(reader.getMinimumIdle()).isZero();assertThat(writer.getMinimumIdle()).isZero();
    }

    @AfterEach void removeOnlyFixtureAuthorRowsAndCheckConnections() throws Exception {
        if(fixture!=null) fixture.close();
        returned();
    }

    @AfterAll void closeOracleNoriAndBothPools() throws Exception {
        try { if(legacy!=null)legacy.close();if(creates!=null)creates.close(); }
        finally { if(reader!=null)reader.close();if(writer!=null)writer.close();if(fixture!=null)fixture.close(); }
    }

    @Test void given__live_post__when__deleting__then__retain_body_write_no_outbox_remove_search_and_read_404() throws Exception {
        var post=creates.create(input(List.of("https://fixture.invalid/f4/original")));
        String body=unchangedBody(post.postId());
        deletes.delete(post.postId(),F3PostgresqlFixture.AUTHOR);
        assertThat(unchangedBody(post.postId())).isEqualTo(body);
        assertThat(writeJdbc.queryForObject("SELECT deleted_at IS NOT NULL AND updated_at IS NOT NULL FROM pawbridge_community.posts WHERE post_id=?",Boolean.class,post.postId())).isTrue();
        assertThat(reads.findById(post.postId())).isEmpty();
        assertThat(searchCount(post.postId())).isZero();
        assertThat(deletedEvents(post.postId())).isZero();
        assertThatThrownBy(()->deletes.delete(post.postId(),F3PostgresqlFixture.AUTHOR)).isInstanceOf(PostNotFoundException.class);
        assertThat(deletedEvents(post.postId())).isZero();returned();
    }

    @ParameterizedTest @ValueSource(strings={"post_search_documents"})
    void given__later_sql_failure_after_media_attempt__when__deleting__then__rollback_every_db_change_and_next_request_recovers(String table) throws Exception {
        var post=creates.create(input(List.of("https://fixture.invalid/f4/original")));
        AtomicInteger mediaAttempts=new AtomicInteger();
        var request=new DeletePost(reads,urls->mediaAttempts.incrementAndGet(),deletes);
        String permission="DELETE";
        fixture.execute("REVOKE "+permission+" ON pawbridge_community."+table+" FROM "+F3PostgresqlFixture.WRITER);
        try {
            assertThatThrownBy(()->request.delete(post.postId(),F3PostgresqlFixture.AUTHOR)).isInstanceOf(DataAccessException.class);
            assertThat(mediaAttempts.get()).isOne();
            assertThat(reads.findById(post.postId())).contains(post);
            assertThat(deletedEvents(post.postId())).isZero();assertThat(searchCount(post.postId())).isOne();returned();
        } finally { fixture.execute("GRANT "+permission+" ON pawbridge_community."+table+" TO "+F3PostgresqlFixture.WRITER); }
        request.delete(post.postId(),F3PostgresqlFixture.AUTHOR);
        assertThat(mediaAttempts.get()).isEqualTo(2);assertThat(deletedEvents(post.postId())).isZero();
        assertThat(reads.findById(post.postId())).isEmpty();returned();
    }

    @Test void given__single_request_waiting_for_media__when__observing_pools__then__neither_connection_is_occupied() throws Exception {
        var post=creates.create(input(List.of("https://fixture.invalid/f4/original")));
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var request=new DeletePost(reads,urls->{
            returned();entered.countDown();
            try { if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("fixture media timeout"); }
            catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}
            returned();
        },deletes);
        var executor=Executors.newSingleThreadExecutor();
        try {
            var future=executor.submit(()->request.delete(post.postId(),F3PostgresqlFixture.AUTHOR));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();returned();
            assertThat(future.isDone()).isFalse();release.countDown();future.get(10,TimeUnit.SECONDS);
            assertThat(deletedEvents(post.postId())).isZero();returned();
        } finally { release.countDown();executor.shutdownNow(); }
    }

    @Test void given__two_independent_writer_connections__when__same_row_is_deleted__then__observe_pg_blocking_and_one_event() throws Exception {
        var post=creates.create(input(List.of()));
        var executor=Executors.newSingleThreadExecutor();
        try(Connection first=writerConnection();Connection second=writerConnection()) {
            first.setAutoCommit(false);
            int holder=backendPid(first);int waiter=backendPid(second);
            try(var lock=first.prepareStatement("SELECT post_id FROM pawbridge_community.posts WHERE post_id=? FOR UPDATE")) {
                lock.setLong(1,post.postId());try(var row=lock.executeQuery()){assertThat(row.next()).isTrue();}
            }
            var started=new CountDownLatch(1);
            var blocked=executor.submit(()->{started.countDown();directDeletion(second).delete(post.postId(),F3PostgresqlFixture.AUTHOR);});
            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(awaitBlocked(holder,waiter)).as("PostgreSQL must report this row-lock waiter, independently of the max1 pool").isTrue();
            assertThat(blocked.isDone()).isFalse();assertThat(deletedEvents(post.postId())).isZero();
            directDeletion(first).delete(post.postId(),F3PostgresqlFixture.AUTHOR);
            assertThatThrownBy(()->blocked.get(10,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(PostNotFoundException.class);
            assertThat(deletedEvents(post.postId())).isZero();assertThat(searchCount(post.postId())).isZero();
            assertThat(reads.findById(post.postId())).isEmpty();
        } finally { executor.shutdownNow(); }
        assertThat(writer.getMaximumPoolSize()).isOne();returned();
    }

    @Test void given__legacy_delete_and_jpa_auditing__when__candidate_deletes__then__timestamp_and_json_observation_match_in_each_jvm_zone() throws Exception {
        var oracle=creates.create(input(List.of("https://fixture.invalid/한글.jpg")));
        var candidate=creates.create(input(oracle.imageUrls()));
        LocalDateTime observed;
        try(var session=legacy.openSession()) {
            var tx=session.beginTransaction();var entity=session.find(Post.class,oracle.postId());
            entity.delete();observed=entity.getDeletedAt();
            // The real legacy @LastModifiedDate is updated by @EnableJpaAuditing in JpaConfig.
            var auditing=new AuditingHandler(new PersistentEntities(List.of()));
            LocalDateTime auditingTime=observed;
            auditing.setDateTimeProvider(()->Optional.of(auditingTime));auditing.markModified(entity);
            assertThat(entity.getUpdatedAt()).isEqualTo(observed);tx.commit();
        }
        Clock sameTime=Clock.fixed(observed.atZone(ZoneId.systemDefault()).toInstant(),ZoneId.systemDefault());
        var tx=new TransactionTemplate(new DataSourceTransactionManager(writer));tx.setTimeout(10);
        new JdbcPostDeletion(writeJdbc,tx,sameTime).delete(candidate.postId(),F3PostgresqlFixture.AUTHOR);
        try(var session=legacy.openSession()) {
            var expected=session.find(Post.class,oracle.postId());var actual=session.find(Post.class,candidate.postId());
            assertThat(actual.getDeletedAt()).isEqualTo(expected.getDeletedAt());
            assertThat(actual.getUpdatedAt()).isEqualTo(expected.getUpdatedAt());
            assertThat(actual.getCreatedAt()).isEqualTo(expected.getCreatedAt());
            JsonNode actualJson=mapper.valueToTree(Map.of("deletedAt",actual.getDeletedAt(),"updatedAt",actual.getUpdatedAt(),"imageUrls",actual.getImageUrls()));
            JsonNode expectedJson=mapper.valueToTree(Map.of("deletedAt",expected.getDeletedAt(),"updatedAt",expected.getUpdatedAt(),"imageUrls",expected.getImageUrls()));
            assertThat(actualJson).isEqualTo(expectedJson);
        }
        returned();
    }

    @Test void given__ownership_changed_during_media__when__writer_rechecks__then__deny_without_delete_event_or_search_change() throws Exception {
        var post=creates.create(input(List.of("https://fixture.invalid/f4/original")));
        var attempts=new AtomicInteger();
        var request=new DeletePost(reads,urls->{
            attempts.incrementAndGet();
            try { fixture.execute("UPDATE pawbridge_community.posts SET author_id="+(F3PostgresqlFixture.AUTHOR+1)+" WHERE post_id="+post.postId()); }
            catch(SQLException failure){throw new IllegalStateException("Synthetic fixture mutation failed",failure);}
        },deletes);
        try {
            assertThatThrownBy(()->request.delete(post.postId(),F3PostgresqlFixture.AUTHOR))
                    .isInstanceOf(com.pawbridge.backend.community.internal.post.domain.PostNotOwnedException.class);
            assertThat(attempts.get()).isOne();
            assertThat(reads.findById(post.postId()).orElseThrow().authorId()).isEqualTo(F3PostgresqlFixture.AUTHOR+1);
            assertThat(deletedEvents(post.postId())).isZero();assertThat(searchCount(post.postId())).isOne();returned();
        } finally { fixture.execute("UPDATE pawbridge_community.posts SET author_id="+F3PostgresqlFixture.AUTHOR+" WHERE post_id="+post.postId()); }
    }

    @Test void given__minimal_writer_and_read_only_reader__when__crossing_permissions__then__deny_unrelated_mutations() {
        assertDenied(()->writeJdbc.update("UPDATE pawbridge_community.posts SET author_id=author_id"));
        assertDenied(()->writeJdbc.update("UPDATE pawbridge_community.posts SET board_type=board_type"));
        assertDenied(()->writeJdbc.update("DELETE FROM pawbridge_community.posts"));
        assertDenied(()->writeJdbc.queryForList("SELECT * FROM pawbridge_user.users"));
        assertDenied(()->writeJdbc.execute("CREATE TABLE pawbridge_community.f4_forbidden(id BIGINT)"));
        assertDenied(()->readJdbc.update("UPDATE pawbridge_community.posts SET deleted_at=now()"));
        assertThat(writeJdbc.queryForObject("SELECT has_column_privilege(current_user,'pawbridge_community.posts','deleted_at','UPDATE') AND has_column_privilege(current_user,'pawbridge_community.posts','updated_at','UPDATE')",Boolean.class)).isTrue();
        assertThat(writeJdbc.queryForObject("SELECT has_column_privilege(current_user,'pawbridge_community.posts','author_id','UPDATE')",Boolean.class)).isFalse();
        returned();
    }

    private PostToCreate input(List<String> images){return new PostToCreate(F3PostgresqlFixture.AUTHOR,"삭제 원본 제목","삭제 원본 본문",PostBoardType.COMMUNICATION,images);}
    /** Explicit difference from the legacy service: the candidate never writes POST_DELETED outbox rows, so this is always zero. */
    private long deletedEvents(long id){return writeJdbc.queryForObject("SELECT count(*) FROM pawbridge_community.outbox_events WHERE aggregate_id=? AND type='POST_DELETED'",Long.class,Long.toString(id));}
    private long searchCount(long id){return writeJdbc.queryForObject("SELECT count(*) FROM pawbridge_community.post_search_documents WHERE post_id=?",Long.class,id);}
    private String unchangedBody(long id){return writeJdbc.queryForObject("SELECT (to_jsonb(p)-'deleted_at'-'updated_at')::text FROM pawbridge_community.posts p WHERE post_id=?",String.class,id);}
    private Connection writerConnection() throws SQLException {return DriverManager.getConnection(fixture.reads().jdbcUrl(),F3PostgresqlFixture.WRITER,fixture.writerPassword());}
    private JdbcPostDeletion directDeletion(Connection connection){
        var dataSource=new SingleConnectionDataSource(connection,true);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(dataSource));tx.setTimeout(10);
        return new JdbcPostDeletion(new JdbcTemplate(dataSource),tx,deletionClock);
    }
    private int backendPid(Connection connection)throws SQLException{
        try(var statement=connection.createStatement();var row=statement.executeQuery("SELECT pg_backend_pid()")){row.next();return row.getInt(1);}
    }
    private boolean awaitBlocked(int holder,int waiter)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        try(Connection connection=fixture.admin();var statement=connection.prepareStatement("SELECT ? = ANY(pg_blocking_pids(?))")){
            statement.setInt(1,holder);statement.setInt(2,waiter);
            do {try(var row=statement.executeQuery()){row.next();if(row.getBoolean(1))return true;}Thread.sleep(10);}
            while(System.nanoTime()<end);
        }
        return false;
    }
    private HikariDataSource pool(String user,String password,int max,String name){
        var config=new HikariConfig();config.setJdbcUrl(fixture.reads().jdbcUrl());config.setUsername(user);config.setPassword(password);
        config.setMaximumPoolSize(max);config.setMinimumIdle(0);config.setConnectionTimeout(2000);config.setValidationTimeout(1000);
        config.setPoolName(name);config.setConnectionInitSql("SET search_path TO public");return new HikariDataSource(config);
    }
    private void returned(){
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        if(reader!=null){assertThat(TransactionSynchronizationManager.hasResource(reader)).isFalse();assertThat(reader.getHikariPoolMXBean().getActiveConnections()).isZero();}
        if(writer!=null){assertThat(TransactionSynchronizationManager.hasResource(writer)).isFalse();assertThat(writer.getHikariPoolMXBean().getActiveConnections()).isZero();}
    }
    private static void assertDenied(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation){
        assertThatThrownBy(operation).isInstanceOf(DataAccessException.class).satisfies(failure->{
            Throwable cause=failure;while(cause!=null&&!(cause instanceof SQLException))cause=cause.getCause();
            assertThat(cause).isInstanceOf(SQLException.class);assertThat(((SQLException)cause).getSQLState()).isEqualTo("42501");
        });
    }
}
