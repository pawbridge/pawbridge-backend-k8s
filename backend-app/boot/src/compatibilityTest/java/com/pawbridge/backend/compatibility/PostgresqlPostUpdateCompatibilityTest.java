package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.community.internal.post.application.*;
import com.pawbridge.backend.community.internal.post.domain.*;
import com.pawbridge.backend.community.internal.post.infrastructure.*;
import com.pawbridge.backend.community.internal.post.infrastructure.search.KoreanSearchTerms;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresqlPostUpdateCompatibilityTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private final Clock createdClock=Clock.fixed(Instant.parse("2026-01-01T00:00:00.123456Z"),ZoneId.systemDefault());
    private final Clock updateClock=Clock.offset(createdClock,Duration.ofHours(1));
    private F3PostgresqlFixture fixture;
    private HikariDataSource reader,writer;
    private JdbcTemplate readJdbc,writeJdbc;
    private JdbcPostReader reads;
    private JdbcPostWriter creates,updates;
    private F5LegacyPostOracle oracle;
    @BeforeAll void openOnlyOwnedHostedF5FixtureAndUnchangedPools() throws Exception {
        fixture=F3PostgresqlFixture.open();
        try(Connection conn=fixture.admin();var statement=conn.createStatement();var row=statement.executeQuery("SELECT value,owner FROM pawbridge_f5_fixture.marker")){
            assertThat(row.next()).isTrue();assertThat(row.getString(1)).isEqualTo("pawbridge-f5-ci-only-v1");
            assertThat(row.getString(2)).isEqualTo(F3PostgresqlFixture.required("F5_CI_OWNER"))
                    .isEqualTo(F3PostgresqlFixture.required("GITHUB_RUN_ID")+"-"+F3PostgresqlFixture.required("GITHUB_RUN_ATTEMPT"));
            assertThat(row.next()).isFalse();
        }
        reader=pool(fixture.reads().applicationUser(),fixture.reads().applicationPassword(),2,"f5-reader");
        writer=pool(F3PostgresqlFixture.WRITER,fixture.writerPassword(),1,"f5-writer");
        readJdbc=new JdbcTemplate(reader);writeJdbc=new JdbcTemplate(writer);
        var manager=new DataSourceTransactionManager(reader);manager.setEnforceReadOnly(true);
        var readTx=new TransactionTemplate(manager);readTx.setReadOnly(true);readTx.setTimeout(5);
        reads=new JdbcPostReader(readJdbc,readTx,mapper);
        var writeTx=new TransactionTemplate(new DataSourceTransactionManager(writer));writeTx.setTimeout(10);
        creates=new JdbcPostWriter(writeJdbc,writeTx,mapper,createdClock);updates=new JdbcPostWriter(writeJdbc,writeTx,mapper,updateClock);
        oracle=new F5LegacyPostOracle(fixture,updateClock);
    }
    @AfterEach void clearOnlyFixtureAuthorAndCheckConnections() throws Exception {if(fixture!=null)fixture.close();returned();}
    @AfterAll void closeOracleAnalyzersAndPools() throws Exception {
        try{if(oracle!=null)oracle.close();if(creates!=null)creates.close();if(updates!=null)updates.close();}
        finally{if(reader!=null)reader.close();if(writer!=null)writer.close();if(fixture!=null)fixture.close();}
    }

    @ParameterizedTest @ValueSource(strings={"omitted","same-title","same-images","changed-title","changed-content","empty-title","changed-images"})
    void given__same_legacy_and_candidate_posts__when__partial_update__then__match_response_fields_and_committed_auditing_in_each_zone(String mode) throws Exception {
        var legacy=creates.create(input(List.of("https://fixture.invalid/f5/original")));
        var candidate=creates.create(input(legacy.imageUrls()));
        String title=mode.equals("same-title")?legacy.title():mode.equals("changed-title")?"수정 제목":mode.equals("empty-title")?"":null;
        String content=mode.equals("changed-content")?"수정 본문":null;
        List<String> images=mode.equals("same-images")?List.copyOf(legacy.imageUrls()):mode.equals("changed-images")?List.of():null;
        var expected=oracle.update(legacy.postId(),title,content,images);
        var actual=updates.update(candidate.postId(),F3PostgresqlFixture.AUTHOR,title,content,images);
        assertThat(actual.title()).isEqualTo(expected.title());assertThat(actual.content()).isEqualTo(expected.content());
        assertThat(actual.imageUrls()).isEqualTo(expected.imageUrls());assertThat(actual.boardType()).isEqualTo(expected.boardType());
        assertThat(actual.createdAt()).isEqualTo(expected.createdAt());assertThat(actual.updatedAt()).isEqualTo(expected.updatedAt());
        assertThat(reads.findById(candidate.postId()).orElseThrow().updatedAt()).isEqualTo(oracle.read(legacy.postId()).updatedAt());
        assertThat(updatedEvents(candidate.postId())).isOne();assertPayload(candidate.postId(),actual);
        assertThat(writeJdbc.queryForObject("SELECT title_hash=md5(?) AND content_hash=md5(?) AND analyzer_version=? FROM pawbridge_community.post_search_documents WHERE post_id=?",
                Boolean.class,actual.title(),actual.content(),KoreanSearchTerms.VERSION,candidate.postId())).isTrue();returned();
    }

    @ParameterizedTest @ValueSource(strings={"sql-null","json-null","empty-array"})
    void given__legacy_nullable_image_storage__when__updating_text__then__preserve_success_or_failure_and_original_json_shape(String mode) throws Exception {
        var legacy=creates.create(input(List.of()));var candidate=creates.create(input(List.of()));
        String json=mode.equals("sql-null")?"NULL":mode.equals("json-null")?"'null'::json":"'[]'::json";
        fixture.execute("UPDATE pawbridge_community.posts SET image_urls="+json+" WHERE post_id IN ("+legacy.postId()+","+candidate.postId()+")");
        if(mode.equals("json-null")){
            assertThatThrownBy(()->oracle.update(legacy.postId(),"수정",null,null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(()->updates.update(candidate.postId(),F3PostgresqlFixture.AUTHOR,"수정",null,null)).isInstanceOf(NullPointerException.class);
            assertThat(reads.findById(candidate.postId()).orElseThrow().title()).isEqualTo(candidate.title());assertThat(updatedEvents(candidate.postId())).isZero();
            assertThat(writeJdbc.queryForObject("SELECT image_urls::text FROM pawbridge_community.posts WHERE post_id=?",String.class,candidate.postId())).isEqualTo("null");
            updates.update(candidate.postId(),F3PostgresqlFixture.AUTHOR,"수정",null,List.of());
            assertThat(reads.findById(candidate.postId()).orElseThrow().imageUrls()).isEmpty();assertThat(updatedEvents(candidate.postId())).isOne();
        }else{
            var expected=oracle.update(legacy.postId(),"수정",null,null);var actual=updates.update(candidate.postId(),F3PostgresqlFixture.AUTHOR,"수정",null,null);
            assertThat(actual.imageUrls()).isEqualTo(expected.imageUrls()).isEmpty();assertThat(actual.updatedAt()).isEqualTo(expected.updatedAt());
            assertThat(writeJdbc.queryForObject("SELECT image_urls::text FROM pawbridge_community.posts WHERE post_id=?",String.class,candidate.postId()))
                    .isEqualTo(writeJdbc.queryForObject("SELECT image_urls::text FROM pawbridge_community.posts WHERE post_id=?",String.class,legacy.postId()));
        }returned();
    }

    @ParameterizedTest @ValueSource(strings={"outbox_events","post_search_documents"})
    void given__later_sql_failure_after_media__when__updating__then__rollback_post_event_and_projection_and_next_request_recovers(String table) throws Exception {
        var post=creates.create(input(List.of("https://fixture.invalid/old")));String before=business(post.postId());
        var io=new java.util.concurrent.atomic.AtomicInteger();
        var application=new UpdatePost(reads,urls->io.incrementAndGet(),files->List.of("https://fixture.invalid/new"),id->Optional.of("작성자"),updates);
        String privilege=table.equals("outbox_events")?"INSERT":"UPDATE";
        fixture.execute("REVOKE "+privilege+" ON pawbridge_community."+table+" FROM "+F3PostgresqlFixture.WRITER);
        try{
            assertThatThrownBy(()->application.update(post.postId(),F3PostgresqlFixture.AUTHOR,"수정 제목",null,List.of(upload())))
                    .isInstanceOf(DataAccessException.class);
            assertThat(io.get()).isOne();assertThat(business(post.postId())).isEqualTo(before);assertThat(updatedEvents(post.postId())).isZero();returned();
        }finally{fixture.execute("GRANT "+privilege+" ON pawbridge_community."+table+" TO "+F3PostgresqlFixture.WRITER);}
        application.update(post.postId(),F3PostgresqlFixture.AUTHOR,"수정 제목",null,List.of(upload()));
        assertThat(io.get()).isEqualTo(2);assertThat(updatedEvents(post.postId())).isOne();assertThat(reads.findById(post.postId()).orElseThrow().title()).isEqualTo("수정 제목");returned();
    }

    @Test void given__media_and_nickname_callbacks__when__waiting__then__neither_reader_nor_writer_connection_is_held() throws Exception {
        var post=creates.create(input(List.of("https://fixture.invalid/old")));
        var stages=new LinkedBlockingQueue<String>();var release=new Semaphore(0);
        java.util.function.Consumer<String> pause=stage->{
            returned();stages.add(stage);
            try{if(!release.tryAcquire(10,TimeUnit.SECONDS))throw new IllegalStateException("Synthetic I/O wait timed out");}
            catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}
            returned();
        };
        var application=new UpdatePost(reads,urls->pause.accept("remove"),files->{pause.accept("upload");return List.of("https://fixture.invalid/new");},
                id->{pause.accept("nickname");return Optional.of("작성자");},updates);
        var executor=Executors.newSingleThreadExecutor();
        try{
            var future=executor.submit(()->application.update(post.postId(),F3PostgresqlFixture.AUTHOR,"수정",null,List.of(upload())));
            for(String expected:List.of("remove","upload","nickname")){
                assertThat(stages.poll(5,TimeUnit.SECONDS)).isEqualTo(expected);returned();assertThat(future.isDone()).isFalse();release.release();
            }
            assertThat(future.get(10,TimeUnit.SECONDS).title()).isEqualTo("수정");assertThat(updatedEvents(post.postId())).isOne();returned();
        }finally{release.release(3);executor.shutdownNow();}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void given__independent_connections__when__two_puts_wait_on_same_row__then__observe_pg_lock_and_merge_current_omitted_fields(boolean sameField) throws Exception {
        var post=creates.create(input(List.of()));var executor=Executors.newSingleThreadExecutor();
        try(Connection first=writerConnection();Connection second=writerConnection();var one=directWriter(first);var two=directWriter(second)){
            first.setAutoCommit(false);lock(first,post.postId());int holder=pid(first),waiter=pid(second);
            var started=new CountDownLatch(1);
            var blocked=executor.submit(()->{started.countDown();return two.update(post.postId(),F3PostgresqlFixture.AUTHOR,sameField?"두번째 제목":null,sameField?null:"두번째 본문",null);});
            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();assertThat(awaitBlocked(holder,waiter)).isTrue();assertThat(blocked.isDone()).isFalse();
            one.update(post.postId(),F3PostgresqlFixture.AUTHOR,"첫번째 제목",null,null);
            var finalResponse=blocked.get(10,TimeUnit.SECONDS);
            assertThat(finalResponse.title()).isEqualTo(sameField?"두번째 제목":"첫번째 제목");assertThat(finalResponse.content()).isEqualTo(sameField?post.content():"두번째 본문");
            assertThat(reads.findById(post.postId()).orElseThrow().title()).isEqualTo(finalResponse.title());assertThat(updatedEvents(post.postId())).isEqualTo(2);assertPayload(post.postId(),finalResponse);
        }finally{executor.shutdownNow();}returned();
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void given__independent_put_and_delete__when__row_lock_orders_commits__then__never_resurrect_deleted_post(boolean deleteFirst) throws Exception {
        var post=creates.create(input(List.of()));var executor=Executors.newSingleThreadExecutor();
        try(Connection first=writerConnection();Connection second=writerConnection();var one=directWriter(first);var two=directWriter(second)){
            first.setAutoCommit(false);lock(first,post.postId());int holder=pid(first),waiter=pid(second);
            var started=new CountDownLatch(1);
            var blocked=executor.submit(()->{started.countDown();if(deleteFirst)two.update(post.postId(),F3PostgresqlFixture.AUTHOR,"수정",null,null);else directDeletion(second).delete(post.postId(),F3PostgresqlFixture.AUTHOR);});
            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();assertThat(awaitBlocked(holder,waiter)).isTrue();
            if(deleteFirst){directDeletion(first).delete(post.postId(),F3PostgresqlFixture.AUTHOR);
                assertThatThrownBy(()->blocked.get(10,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(PostNotFoundException.class);
            }else{one.update(post.postId(),F3PostgresqlFixture.AUTHOR,"수정",null,null);blocked.get(10,TimeUnit.SECONDS);}
            assertThat(reads.findById(post.postId())).isEmpty();assertThat(updatedEvents(post.postId())).isEqualTo(deleteFirst?0:1);
            assertThat(writeJdbc.queryForObject("SELECT count(*) FROM pawbridge_community.post_search_documents WHERE post_id=?",Long.class,post.postId())).isZero();
        }finally{executor.shutdownNow();}returned();
    }

    @Test void given__ownership_change_during_upload__when__writer_rechecks__then__403_without_update_event_and_without_compensation() throws Exception {
        var post=creates.create(input(List.of("https://fixture.invalid/old")));
        var application=new UpdatePost(reads,urls->{},files->{
            try{fixture.execute("UPDATE pawbridge_community.posts SET author_id="+(F3PostgresqlFixture.AUTHOR+1)+" WHERE post_id="+post.postId());}
            catch(SQLException failure){throw new IllegalStateException(failure);}return List.of("https://fixture.invalid/new");
        },id->Optional.of("작성자"),updates);
        try{
            assertThatThrownBy(()->application.update(post.postId(),F3PostgresqlFixture.AUTHOR,"수정",null,List.of(upload()))).isInstanceOf(PostNotOwnedException.class);
            assertThat(updatedEvents(post.postId())).isZero();assertThat(reads.findById(post.postId()).orElseThrow().title()).isEqualTo(post.title());
        }finally{fixture.execute("UPDATE pawbridge_community.posts SET author_id="+F3PostgresqlFixture.AUTHOR+" WHERE post_id="+post.postId());}returned();
    }

    @Test void given__database_character_or_length_constraint__when__updating__then__preserve_narrow_charset_mapping_and_atomic_rollback() {
        var post=creates.create(input(List.of()));String before=business(post.postId());
        assertThatThrownBy(()->updates.update(post.postId(),F3PostgresqlFixture.AUTHOR,"제목 😀",null,null)).isInstanceOf(UnsupportedPostCharactersException.class);
        assertThat(business(post.postId())).isEqualTo(before);
        assertThatThrownBy(()->updates.update(post.postId(),F3PostgresqlFixture.AUTHOR,"x".repeat(201),null,null)).isInstanceOf(DataAccessException.class);
        assertThat(business(post.postId())).isEqualTo(before);assertThat(updatedEvents(post.postId())).isZero();returned();
    }

    @Test void given__expanded_writer_capability__when__changing_immutable_identity_or_other_responsibility__then__database_denies_it() {
        for(String column:List.of("author_id","board_type","created_at"))assertDenied(()->writeJdbc.update("UPDATE pawbridge_community.posts SET "+column+"="+column));
        assertDenied(()->writeJdbc.update("DELETE FROM pawbridge_community.posts"));assertDenied(()->writeJdbc.queryForList("SELECT * FROM pawbridge_user.users"));
        assertThat(writer.getMaximumPoolSize()).isOne();assertThat(reader.getMaximumPoolSize()).isEqualTo(2);returned();
    }

    private PostToCreate input(List<String> images){return new PostToCreate(F3PostgresqlFixture.AUTHOR,"기존 제목","기존 본문",PostBoardType.COMMUNICATION,images);}
    private PostUpload upload(){return new PostUpload("one.png","image/png",1,()->new java.io.ByteArrayInputStream(new byte[]{1}));}
    private long updatedEvents(long id){return writeJdbc.queryForObject("SELECT count(*) FROM pawbridge_community.outbox_events WHERE aggregate_id=? AND type='POST_UPDATED'",Long.class,Long.toString(id));}
    private void assertPayload(long id,PostSnapshot post)throws Exception{
        String text=writeJdbc.queryForObject("SELECT payload::text FROM pawbridge_community.outbox_events WHERE aggregate_id=? AND type='POST_UPDATED' ORDER BY outbox_id DESC LIMIT 1",String.class,Long.toString(id));
        assertThat(mapper.readTree(text)).isEqualTo(mapper.valueToTree(Map.of("postId",id,"authorId",post.authorId(),"title",post.title(),"content",post.content(),"boardType",post.boardType(),"imageUrls",post.imageUrls())));
    }
    private String business(long id){return writeJdbc.queryForObject("SELECT to_jsonb(p)::text||COALESCE((SELECT json_agg(d)::text FROM pawbridge_community.post_search_documents d WHERE d.post_id=p.post_id),'') FROM pawbridge_community.posts p WHERE post_id=?",String.class,id);}
    private HikariDataSource pool(String user,String password,int max,String name){var c=new HikariConfig();c.setJdbcUrl(fixture.reads().jdbcUrl());c.setUsername(user);c.setPassword(password);c.setMaximumPoolSize(max);c.setMinimumIdle(0);c.setConnectionTimeout(2000);c.setPoolName(name);return new HikariDataSource(c);}
    private Connection writerConnection()throws SQLException{return DriverManager.getConnection(fixture.reads().jdbcUrl(),F3PostgresqlFixture.WRITER,fixture.writerPassword());}
    private JdbcPostWriter directWriter(Connection c){var ds=new SingleConnectionDataSource(c,true);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));tx.setTimeout(10);return new JdbcPostWriter(new JdbcTemplate(ds),tx,mapper,updateClock);}
    private JdbcPostDeletion directDeletion(Connection c){var ds=new SingleConnectionDataSource(c,true);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));tx.setTimeout(10);return new JdbcPostDeletion(new JdbcTemplate(ds),tx,mapper,updateClock);}
    private void lock(Connection c,long id)throws SQLException{try(var q=c.prepareStatement("SELECT post_id FROM pawbridge_community.posts WHERE post_id=? FOR UPDATE")){q.setLong(1,id);try(var row=q.executeQuery()){assertThat(row.next()).isTrue();}}}
    private int pid(Connection c)throws SQLException{try(var q=c.createStatement();var row=q.executeQuery("SELECT pg_backend_pid()")){row.next();return row.getInt(1);}}
    private boolean awaitBlocked(int holder,int waiter)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);try(Connection c=fixture.admin();var q=c.prepareStatement("SELECT ? = ANY(pg_blocking_pids(?))")){q.setInt(1,holder);q.setInt(2,waiter);do{try(var row=q.executeQuery()){row.next();if(row.getBoolean(1))return true;}Thread.sleep(10);}while(System.nanoTime()<end);}return false;}
    private void returned(){assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();if(reader!=null)assertThat(reader.getHikariPoolMXBean().getActiveConnections()).isZero();if(writer!=null)assertThat(writer.getHikariPoolMXBean().getActiveConnections()).isZero();}
    private static void assertDenied(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation){assertThatThrownBy(operation).isInstanceOf(DataAccessException.class).satisfies(f->{Throwable c=f;while(c!=null&&!(c instanceof SQLException))c=c.getCause();assertThat(c).isInstanceOf(SQLException.class);assertThat(((SQLException)c).getSQLState()).isEqualTo("42501");});}
}
