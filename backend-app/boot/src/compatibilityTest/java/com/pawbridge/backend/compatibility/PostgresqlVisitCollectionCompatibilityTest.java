package com.pawbridge.backend.compatibility;

import com.pawbridge.backend.community.internal.visit.infrastructure.JdbcVisitCollection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.community.internal.visit.application.VisitCollection;
import com.pawbridge.backend.community.internal.visit.application.VisitCollectionRejectedException;
import com.pawbridge.backend.community.internal.visit.application.VisitIdentity;
import com.pawbridge.backend.community.internal.visit.infrastructure.JdbcVisitCollectionStore;
import com.pawbridge.communityservice.visits.VisitCookie;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Date;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Raw F16 repository/service versus the candidate; HTTP identity/auth/Origin/bot remain in closest MVC tests. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresqlVisitCollectionCompatibilityTest {
    private static final LocalDate DAY = LocalDate.of(2044,3,1);
    private static final Instant NOW = Instant.parse("2044-02-29T15:00:00.123456Z");
    private static final Instant BEGAN = Instant.parse("2044-02-27T15:00:00.123456Z");
    private static final List<LocalDate> RESERVED_DAYS = List.of(DAY.minusDays(3),DAY.minusDays(2),DAY.minusDays(1),DAY,DAY.plusDays(1));
    private static final String SOURCE_RECEIPT_SHA = "abb6051b4a30d00250b4a1fe34c27acfaaef9ec8b19384a6c17d53102e35b916";
    private static final String GRANTS = "GRANT SELECT(visit_date),INSERT(visit_date,visitor_hash),DELETE ON pawbridge_community.daily_visitor TO pawbridge_f3_writer;\n"
            + "GRANT SELECT(visit_date,unique_visitors),INSERT(visit_date,unique_visitors),UPDATE(unique_visitors) ON pawbridge_community.daily_visit_count TO pawbridge_f3_writer;\n"
            + "GRANT SELECT(singleton,started_at),INSERT(singleton,started_at) ON pawbridge_community.visit_collection TO pawbridge_f3_writer;\n";
    private static final String MEMBER_PROOF="source_sha=f06a72a06bd26662c647bde780dfc7a27bf7349c\nfixture=F29 plus User deletion and installed-chat cleanup\nnew_business_ddl=0\nsynthetic_tables_added=6\nsynthetic_identity_sequences_added=2\nnew_schema=0\nnew_marker=0\nsnapshot_tables=24\nreader_delta=0\nnew_sequence_acl=0\ncf93ed908b18f63f59832cd0fe5bcf500ccf9e4c39972deeda5de446c4dd953d  member-ddl-fragment-0\neb3fe612fe9d4aff8372539b0d59e1da4c34266036ce88f1ed0ba51b88f5e139  member-ddl-fragment-1\nb705222e7a9c9a6964628c937cff63470e1dd30103b27111ae3c5d94d284e1ec  member-ddl-fragment-2\n7afb8a3d992eba1258d61a610a418133f56766e9def3eb2e105c584bedcd2fdb  member-ddl-fragment-3\n5604c03964f051343785c2d30f9bf20022d6a0c867152b6175168c4f5ea81754  member-exact-grants\n";
    private final Set<VisitorKey> visitors = new LinkedHashSet<>();
    private final Set<LocalDate> counts = new LinkedHashSet<>();
    private final Set<Instant> starts = new LinkedHashSet<>();
    private final VisitCookie cookies = new VisitCookie(F16VisitCollectionOracle.KEY);
    private F3PostgresqlFixture owner;
    private HikariDataSource writer;
    private ObjectMapper mapper;
    private String baseline;

    @BeforeAll void openOnlyTheExplicitOwnedTwentySixTableFixture() throws Exception {
        assertThat(ZoneId.systemDefault().getId()).isIn("UTC","Asia/Seoul");
        owner=F3PostgresqlFixture.open();verifyOwnership();
        assertThat(scalar("SELECT count(*) FROM pawbridge_community.daily_visitor")).isEqualTo("0");
        assertThat(scalar("SELECT count(*) FROM pawbridge_community.visit_collection")).isEqualTo("0");
        for(LocalDate date:RESERVED_DAYS)assertThat(scalar("SELECT count(*) FROM pawbridge_community.daily_visit_count WHERE visit_date=DATE '"+date+"'")).isEqualTo("0");
        writer=writer("f16-visit-collection-writer");
        try(var c=new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)){mapper=c.getBean(ObjectMapper.class);}
        baseline=snapshot();
    }
    @AfterEach void restoreOnlyTheReservedObservedRowsAndReturnEveryConnection()throws Exception{
        cleanupOwned();returned();if(baseline!=null)assertThat(snapshot().equals(baseline)).as("All twenty-six tables restore exactly").isTrue();
    }
    @AfterAll void closeOnlyOwnWriter(){if(writer!=null)writer.close();}

    @Test
    void givenAMicrosecondCollectionStartAndSparseLeapDays__whenStartingTwiceAndReadingPeriodsInEitherZone__thenKeepTheFirstInstantAndZeroFillOnlyCollectedDays()throws Exception{
        JsonNode expected=null;
        for(boolean source:new boolean[]{true,false}){
            var clock=new MutableClock(BEGAN);
            try(var r=new Round(source,writer,clock,exclusive->{})){
                start(r,BEGAN);clock.set(BEGAN.plusSeconds(1));start(r,BEGAN.plusSeconds(1));
                seedCount(DAY.minusDays(1),7);clock.set(NOW);String before=snapshot();
                JsonNode actual=wire(r.period(DAY.minusDays(3),DAY));
                assertThat(actual.size()).isEqualTo(5);assertThat(actual.get("collectionStartedAt").asText()).isEqualTo(BEGAN.toString());
                assertThat(actual.get("today").asText()).isEqualTo(DAY.toString());
                assertThat(actual.get("daily").size()).isEqualTo(3);
                for(int i=0;i<3;i++){assertThat(actual.get("daily").get(i).get("date").asText()).isEqualTo(DAY.minusDays(2-i).toString());assertThat(actual.get("daily").get(i).get("count").asLong()).isEqualTo(i==1?7:0);}
                if(expected==null)expected=actual;else assertThat(actual).isEqualTo(expected);
                assertThat(snapshot().equals(before)).isTrue();returned();
            }finally{cleanupOwned();}
        }
    }

    @Test
    void givenNewAndRepeatedDayIdentities__whenCountingThenARealSqlFailureOccursAfterTheCte__thenDedupeAndRollbackBothPhysicalWritesWithEitherManager()throws Exception{
        for(boolean source:new boolean[]{true,false})try(var r=new Round(source,writer,new MutableClock(NOW),exclusive->{})){
            String a=cookie(DAY),b=cookie(DAY),c=cookie(DAY);
            assertToday(r.count(a),DAY,1);assertToday(r.count(a),DAY,1);assertToday(r.count(b),DAY,2);
            String before=snapshot();r.jdbc.failAfterCount=true;
            Throwable failure=catchThrowable(()->r.count(c));assertThat(failure).isInstanceOf(DataAccessException.class);
            assertThat(sqlState(failure)).isEqualTo("22012");assertThat(snapshot().equals(before)).isTrue();returned();
            assertToday(r.count(c),DAY,3);assertThat(scalar("SELECT count(*) FROM pawbridge_community.daily_visitor WHERE visit_date=DATE '"+DAY+"'")).isEqualTo("3");
            cleanupOwned();
        }
    }

    @ParameterizedTest(name="givenSharedDayLocksAndConcurrentIdentities__whenBothCount__thenSameIdentityDedupesAndDifferentIdentitiesIncrementWithoutLostUpdates[{index}]")
    @ValueSource(booleans={true,false})
    void givenSharedDayLocksAndConcurrentIdentities__whenBothCount__thenSameIdentityDedupesAndDifferentIdentitiesIncrementWithoutLostUpdates(boolean sameIdentity)throws Exception{
        for(boolean source:new boolean[]{true,false}){
            String a=cookie(DAY),b=sameIdentity?a:cookie(DAY);var barrier=new CyclicBarrier(2);
            Consumer<Boolean> meet=exclusive->{assertThat(exclusive).isFalse();try{barrier.await(7,TimeUnit.SECONDS);}catch(Exception e){throw new IllegalStateException("Owned shared locks did not meet");}};
            var workers=Executors.newFixedThreadPool(2);
            try(var other=writer("f16-visit-collection-other");var first=new Round(source,writer,new MutableClock(NOW),meet);var second=new Round(source,other,new MutableClock(NOW),meet)){
                try{
                    var x=workers.submit(()->wire(first.count(a)).get("count").asLong());var y=workers.submit(()->wire(second.count(b)).get("count").asLong());
                    var values=new ArrayList<Long>();values.add(x.get(15,TimeUnit.SECONDS));values.add(y.get(15,TimeUnit.SECONDS));values.sort(Long::compareTo);
                    assertThat(values).containsExactly(1L,sameIdentity?1L:2L);
                    assertThat(scalar("SELECT unique_visitors FROM pawbridge_community.daily_visit_count WHERE visit_date=DATE '"+DAY+"'")).isEqualTo(sameIdentity?"1":"2");
                    assertThat(scalar("SELECT count(*) FROM pawbridge_community.daily_visitor WHERE visit_date=DATE '"+DAY+"'")).isEqualTo(sameIdentity?"1":"2");
                }finally{workers.shutdownNow();assertThat(workers.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
            }finally{workers.shutdownNow();cleanupOwned();}
        }
    }

    @Test
    void givenOldAndCurrentVisitorRows__whenCleanupCrossesTheSeoulFiveMinuteGrace__thenCommitEachOldDayAndPreserveAllAggregatesAndStartMetadata()throws Exception{
        for(boolean source:new boolean[]{true,false}){
            var clock=new MutableClock(DAY.atStartOfDay(VisitCookie.ZONE).toInstant().plusSeconds(299).plusNanos(999_999_000));
            try(var r=new Round(source,writer,clock,exclusive->assertThat(exclusive).isTrue())){
                start(r,clock.instant());for(LocalDate day:List.of(DAY.minusDays(2),DAY.minusDays(1),DAY)){seedVisitor(day,cookie(day));seedCount(day,11);}
                String before=snapshot();r.cleanup();assertThat(snapshot().equals(before)).isTrue();assertThat(r.jdbc.deletedDays).isEmpty();
                clock.set(DAY.atStartOfDay(VisitCookie.ZONE).toInstant().plusSeconds(300));r.cleanup();
                assertThat(r.jdbc.committedDeleteDays).containsExactly(DAY.minusDays(2),DAY.minusDays(1));
                assertThat(scalar("SELECT count(*) FROM pawbridge_community.daily_visitor")).isEqualTo("1");
                for(LocalDate day:List.of(DAY.minusDays(2),DAY.minusDays(1),DAY))assertThat(scalar("SELECT unique_visitors FROM pawbridge_community.daily_visit_count WHERE visit_date=DATE '"+day+"'")).isEqualTo("11");
                assertThat(scalar("SELECT count(*) FROM pawbridge_community.visit_collection")).isEqualTo("1");returned();
            }finally{cleanupOwned();}
        }
    }

    @Test
    void givenCleanupHoldsTheExclusiveOldDayLock__whenCountWaitsAcrossSeoulMidnight__thenRecheckTheDayAfterWaitingAndNeverRecreateTheCleanedIdentity()throws Exception{
        for(boolean source:new boolean[]{true,false}){
            LocalDate old=DAY.minusDays(1);String value=cookie(old);seedVisitor(old,value);seedCount(old,1);
            var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
            Consumer<Boolean> pause=exclusive->{assertThat(exclusive).isTrue();locked.countDown();await(release);};
            Clock countClock=new TwoSampleClock(DAY.atStartOfDay(VisitCookie.ZONE).toInstant().minusNanos(1),DAY.atStartOfDay(VisitCookie.ZONE).toInstant().plusSeconds(300));
            var workers=Executors.newFixedThreadPool(2);
            try(var other=writer("f16-visit-collection-waiter");var cleaner=new Round(source,writer,Clock.fixed(DAY.atStartOfDay(VisitCookie.ZONE).toInstant().plusSeconds(300),ZoneOffset.UTC),pause);var counter=new Round(source,other,countClock,exclusive->assertThat(exclusive).isFalse())){
                try{
                    var cleaning=workers.submit(cleaner::cleanup);assertThat(locked.await(7,TimeUnit.SECONDS)).isTrue();
                    var counting=workers.submit(()->catchThrowable(()->counter.count(value)));
                    try{awaitBlocked(source?"f16-visit-collection-original":"f16-visit-collection-waiter");}finally{release.countDown();}
                    cleaning.get(12,TimeUnit.SECONDS);Throwable failure=counting.get(12,TimeUnit.SECONDS);
                    if(source){assertThat(failure).isInstanceOf(ResponseStatusException.class);assertThat(((ResponseStatusException)failure).getStatusCode().value()).isEqualTo(409);}
                    else assertThat(failure).isInstanceOf(VisitCollectionRejectedException.class);
                    assertThat(scalar("SELECT count(*) FROM pawbridge_community.daily_visitor")).isEqualTo("0");assertThat(scalar("SELECT unique_visitors FROM pawbridge_community.daily_visit_count WHERE visit_date=DATE '"+old+"'")).isEqualTo("1");
                }finally{release.countDown();workers.shutdownNow();assertThat(workers.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
            }finally{release.countDown();workers.shutdownNow();cleanupOwned();}
        }
    }

    @ParameterizedTest(name="givenAnExactWriterPrivilegeRevoked__whenCountingOrCleaning__thenKeepTheWholePriorStateAndRestoreOnlyThatPrivilegeBeforeRecovery[{index}]")
    @ValueSource(strings={"count-update","visitor-delete"})
    void givenAnExactWriterPrivilegeRevoked__whenCountingOrCleaning__thenKeepTheWholePriorStateAndRestoreOnlyThatPrivilegeBeforeRecovery(String denied)throws Exception{
        String clause=denied.equals("count-update")?"UPDATE(unique_visitors) ON pawbridge_community.daily_visit_count":"DELETE ON pawbridge_community.daily_visitor";
        String a=cookie(DAY.minusDays(1));seedVisitor(DAY.minusDays(1),a);seedCount(DAY.minusDays(1),1);
        String b=cookie(DAY);seedCount(DAY,3);
        try(var r=new Round(false,writer,Clock.fixed(DAY.atStartOfDay(VisitCookie.ZONE).toInstant().plusSeconds(300),ZoneOffset.UTC),exclusive->{})){
            String before=snapshot();execute("REVOKE "+clause+" FROM "+F3PostgresqlFixture.WRITER);
            try{
                Throwable failure=catchThrowable(()->{if(denied.equals("count-update"))r.count(b);else r.cleanup();});
                assertThat(failure).isInstanceOf(DataAccessException.class);assertThat(sqlState(failure)).isEqualTo("42501");assertThat(snapshot().equals(before)).isTrue();returned();
            }finally{verifyOwnership();execute("GRANT "+clause+" TO "+F3PostgresqlFixture.WRITER);}
            if(denied.equals("count-update"))assertToday(r.count(b),DAY,4);else{r.cleanup();assertThat(scalar("SELECT count(*) FROM pawbridge_community.daily_visitor")).isEqualTo("0");}
        }
    }

    private final class Round implements AutoCloseable{
        final F16VisitCollectionOracle original;final VisitCollection candidate;final ObservingJdbc jdbc;
        Round(boolean source,HikariDataSource ds,Clock clock,Consumer<Boolean> afterLock){
            if(source){original=new F16VisitCollectionOracle(owner,clock,PostgresqlVisitCollectionCompatibilityTest.this::verifyOwnership,dataSource->new ObservingJdbc(dataSource,afterLock));jdbc=(ObservingJdbc)original.jdbc;candidate=null;}
            else{original=null;jdbc=new ObservingJdbc(ds,afterLock);var tx=new TransactionTemplate(new JdbcTransactionManager(ds));tx.setTimeout(10);candidate=new JdbcVisitCollection(new JdbcVisitCollectionStore(jdbc),new VisitIdentity(F16VisitCollectionOracle.KEY),clock,tx);}
        }
        void start(){if(original!=null)original.service.start();else candidate.start();}
        Object count(String value){if(original!=null)return original.service.count(value);return candidate.count(value);}
        Object period(LocalDate a,LocalDate b){if(original!=null)return original.service.period(a,b);return candidate.period(a,b);}
        void cleanup(){assertForeignVisitorZero();if(original!=null)original.service.cleanup();else candidate.cleanup();}
        @Override public void close(){if(original!=null)original.close();}
    }
    private final class ObservingJdbc extends JdbcTemplate{
        final Consumer<Boolean> afterLock;final List<LocalDate> deletedDays=new ArrayList<>(),committedDeleteDays=new ArrayList<>();boolean failAfterCount;
        ObservingJdbc(DataSource ds,Consumer<Boolean> afterLock){super(ds);setQueryTimeout(7);this.afterLock=afterLock;}
        @Override public void query(String sql,RowCallbackHandler handler,Object... args)throws DataAccessException{
            super.query(sql,handler,args);if(sql.startsWith("SELECT pg_advisory_xact_lock")){assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();assertThat(TransactionSynchronizationManager.hasResource(getDataSource())).isTrue();afterLock.accept(!sql.contains("_shared"));}
        }
        @Override public int update(String sql,Object... args)throws DataAccessException{
            int result=super.update(sql,args);
            if(sql.stripLeading().startsWith("WITH inserted")){assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();if(failAfterCount){failAfterCount=false;super.queryForObject("SELECT 1/0",Integer.class);}}
            if(sql.startsWith("DELETE FROM pawbridge_community.daily_visitor")){
                LocalDate day=((Date)args[0]).toLocalDate();deletedDays.add(day);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){try{assertThat(deletedDays).as("Each old day commits before the next delete is executed").hasSize(committedDeleteDays.size()+1);assertThat(scalar("SELECT count(*) FROM pawbridge_community.daily_visitor WHERE visit_date=DATE '"+day+"'")).isEqualTo("0");committedDeleteDays.add(day);}catch(Exception failure){throw new IllegalStateException("Owned committed day visibility cannot be verified");}}});
            }
            return result;
        }
    }
    private void start(Round r,Instant instant){starts.add(instant);r.start();}
    private String cookie(LocalDate day){assertThat(RESERVED_DAYS).contains(day);String value=cookies.initialize(null,day.atTime(12,0).atZone(VisitCookie.ZONE).toInstant()).getValue();String hash=cookies.hash(day,cookies.identity(value,day));visitors.add(new VisitorKey(day,hash));counts.add(day);return value;}
    private void seedVisitor(LocalDate day,String cookie)throws Exception{String hash=cookies.hash(day,cookies.identity(cookie,day));try(Connection c=admin();var s=c.prepareStatement("INSERT INTO pawbridge_community.daily_visitor(visit_date,visitor_hash) VALUES(?,?)")){s.setDate(1,Date.valueOf(day));s.setString(2,hash);assertThat(s.executeUpdate()).isOne();visitors.add(new VisitorKey(day,hash));}}
    private void seedCount(LocalDate day,long count)throws Exception{assertThat(RESERVED_DAYS).contains(day);try(Connection c=admin();var s=c.prepareStatement("INSERT INTO pawbridge_community.daily_visit_count(visit_date,unique_visitors) VALUES(?,?)")){s.setDate(1,Date.valueOf(day));s.setLong(2,count);assertThat(s.executeUpdate()).isOne();counts.add(day);}}
    private void cleanupOwned()throws Exception{
        if(owner==null)return;assertForeignVisitorZero();
        for(VisitorKey key:visitors)try(Connection c=admin();var s=c.prepareStatement("DELETE FROM pawbridge_community.daily_visitor WHERE visit_date=? AND visitor_hash=?")){s.setDate(1,Date.valueOf(key.day));s.setString(2,key.hash);s.executeUpdate();}
        for(LocalDate day:counts)execute("DELETE FROM pawbridge_community.daily_visit_count WHERE visit_date=DATE '"+day+"'");
        if(!starts.isEmpty())try(Connection c=admin();var s=c.prepareStatement("DELETE FROM pawbridge_community.visit_collection WHERE singleton AND started_at=?")){for(Instant instant:starts){s.setTimestamp(1,java.sql.Timestamp.from(instant));s.executeUpdate();}}
        visitors.clear();counts.clear();starts.clear();
    }
    private void assertForeignVisitorZero(){if(owner==null)return;try(Connection c=admin();var s=c.createStatement();var rows=s.executeQuery("SELECT visit_date,visitor_hash FROM pawbridge_community.daily_visitor")){while(rows.next())assertThat(visitors.contains(new VisitorKey(rows.getDate(1).toLocalDate(),rows.getString(2)))).as("Do not globally clean foreign visitor identities").isTrue();}catch(SQLException failure){throw new IllegalStateException("Owned visitor scope cannot be verified");}}
    private void assertToday(Object value,LocalDate day,long count)throws Exception{JsonNode node=wire(value);assertThat(node.size()).isEqualTo(2);assertThat(node.get("date").asText()).isEqualTo(day.toString());assertThat(node.get("count").asLong()).isEqualTo(count);returned();}
    private JsonNode wire(Object value)throws Exception{return mapper.readTree(mapper.writeValueAsBytes(value));}
    private HikariDataSource writer(String name){var c=new HikariConfig();c.setJdbcUrl(owner.reads().jdbcUrl());c.setUsername(F3PostgresqlFixture.WRITER);c.setPassword(owner.writerPassword());c.setMaximumPoolSize(1);c.setMinimumIdle(0);c.setConnectionTimeout(2000);c.setValidationTimeout(1000);c.setPoolName(name);c.addDataSourceProperty("ApplicationName",name);c.addDataSourceProperty("socketTimeout","12");c.setConnectionInitSql("SET statement_timeout=8000; SET lock_timeout=5000");return new HikariDataSource(c);}
    private void returned(){assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();assertThat(TransactionSynchronizationManager.hasResource(writer)).isFalse();assertThat(writer.getHikariPoolMXBean().getActiveConnections()).isZero();}
    private Connection admin()throws SQLException{verifyOwnership();return owner.admin();}
    private String scalar(String sql)throws SQLException{try(Connection c=admin();var s=c.createStatement();var r=s.executeQuery(sql)){assertThat(r.next()).isTrue();return r.getString(1);}}
    private void execute(String sql)throws SQLException{try(Connection c=admin();var s=c.createStatement()){s.execute(sql);}}
    private void awaitBlocked(String application)throws Exception{long until=System.nanoTime()+Duration.ofSeconds(5).toNanos();while(System.nanoTime()<until){if(!scalar("SELECT count(*) FROM pg_stat_activity WHERE application_name='"+application+"' AND cardinality(pg_blocking_pids(pid))>0").equals("0"))return;Thread.sleep(20);}throw new IllegalStateException("Owned advisory lock waiter was not observed");}
    private static void await(CountDownLatch latch){try{if(!latch.await(8,TimeUnit.SECONDS))throw new IllegalStateException("Owned exclusive lock release timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Owned lock wait interrupted");}}
    private static String sqlState(Throwable failure){var seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable,Boolean>());for(Throwable t=failure;t!=null&&seen.add(t);t=t.getCause())if(t instanceof SQLException sql&&sql.getSQLState()!=null)return sql.getSQLState();return null;}
    private record VisitorKey(LocalDate day,String hash){}
    private static final class MutableClock extends Clock{private volatile Instant now;MutableClock(Instant now){this.now=now;}void set(Instant value){now=value;}@Override public Instant instant(){return now;}@Override public ZoneId getZone(){return ZoneOffset.UTC;}@Override public Clock withZone(ZoneId zone){return this;}}
    private static final class TwoSampleClock extends Clock{private final Instant first,second;private final java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();TwoSampleClock(Instant first,Instant second){this.first=first;this.second=second;}@Override public Instant instant(){return calls.getAndIncrement()==0?first:second;}@Override public ZoneId getZone(){return ZoneOffset.UTC;}@Override public Clock withZone(ZoneId zone){return this;}}

    private void verifyOwnership(){try{
        String run=F3PostgresqlFixture.required("GITHUB_RUN_ID")+"-"+F3PostgresqlFixture.required("GITHUB_RUN_ATTEMPT");
        String mode=System.getProperty("visit.collection.fixture.mode","");assertThat(mode).isIn("member","admin");
        Path fixture=receipt("visit.collection.fixture.receipt","visit-collection-fixture-write.txt"),source=receipt("visit.collection.oracle.receipt","f16-collection-oracle-sha256.txt");assertThat(source.getParent()).isEqualTo(fixture.getParent());
        String parent=mode.equals("member")?"member-cleanup":"admin-user-update";
        String expected="owner="+run+"\nsource_sha=33cad5567767f8730fc2c4389815ecd031f41dc0\nfixture="+parent+" plus visit collection\nnew_business_ddl=0\nsynthetic_tables_added=2\nnew_schema=0\nnew_marker=0\nsnapshot_tables=26\nreader_delta=0\nnew_sequence_acl=0\n"+sha(GRANTS.getBytes(StandardCharsets.UTF_8))+"  visit-collection-exact-grants\n";
        assertThat(Files.readString(fixture).equals(expected)).isTrue();assertThat(sha(Files.readAllBytes(source))).isEqualTo(SOURCE_RECEIPT_SHA);
        Path prior=verified(fixture.resolveSibling("member-cleanup-fixture-write.txt"));String body=Files.readString(prior);assertThat(body.equals("owner="+run+"\n"+MEMBER_PROOF)).isTrue();
        if(mode.equals("admin")){
            Path admin=verified(fixture.resolveSibling("admin-user-update-fixture-write.txt"));
            String grant="GRANT SELECT(role,care_reg_no),UPDATE(role,care_reg_no) ON pawbridge_user.users TO pawbridge_f3_writer;";
            String adminProof="owner="+run+"\nsource_sha=c32ae0d84b69969abd35c541dbdd4e81738c4be5\nfixture=member cleanup plus administrator User update\nnew_business_ddl=0\nnew_marker=0\nsnapshot_tables=24\nreader_delta=0\nnew_sequence_acl=0\nwriter_user_select=user_id,nickname,role,care_reg_no\nwriter_user_update=user_id,nickname,updated_at,role,care_reg_no\n"+sha(grant.getBytes(StandardCharsets.UTF_8))+"  admin-user-exact-grants\n";
            assertThat(Files.readString(admin).equals(adminProof)).isTrue();
        }
        try(Connection c=owner.admin();var s=c.createStatement();var r=s.executeQuery("SELECT value||':'||owner FROM pawbridge_f16_fixture.marker")){assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("pawbridge-f16-ci-only-v1:"+run);}
    }catch(java.io.IOException|java.security.NoSuchAlgorithmException|SQLException failure){throw new IllegalStateException("Owned visit collection evidence cannot be verified");}}
    private static Path receipt(String property,String filename)throws java.io.IOException{Path p=Path.of(System.getProperty(property,"")).toAbsolutePath().normalize();assertThat(p.getFileName().toString()).isEqualTo(filename);return verified(p);}
    private static Path verified(Path path)throws java.io.IOException{assertThat(Files.isSymbolicLink(path)).isFalse();assertThat(Files.isRegularFile(path)).isTrue();assertThat(path.toRealPath()).isEqualTo(path);return path;}
    private static String sha(byte[] bytes)throws java.security.NoSuchAlgorithmException{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
    private String snapshot()throws Exception{
        String[][] tables=TABLES;var sql=new StringBuilder("SELECT json_build_object(");for(int i=0;i<tables.length;i++){if(i>0)sql.append(',');var t=tables[i];sql.append("'").append(t[0]).append("',(SELECT COALESCE(json_agg(r ORDER BY ").append(t[2]).append("),'[]'::json) FROM ").append(t[1]).append(" r)");}sql.append(")::text");String value=scalar(sql.toString());assertThat(mapper.readTree(value).size()).isEqualTo(26);return value;
    }
    private static final String[][] TABLES={
        {"private_notes","pawbridge_community.private_notes","note_id"},{"mailboxes","pawbridge_community.private_note_mailboxes","member_id,note_id"},{"pending","pawbridge_user.contact_deletions","user_id"},{"members","pawbridge_community.private_note_members","member_id"},{"blocks","pawbridge_community.private_note_blocks","member_id,blocked_id"},{"visits","pawbridge_community.daily_visit_count","visit_date"},{"videos","pawbridge_community.home_videos","id"},{"board","pawbridge_community.home_video_board","id"},{"users","pawbridge_user.users","user_id"},{"favorites","pawbridge_user.favorites","favorite_id"},{"posts","pawbridge_community.posts","post_id"},{"comments","pawbridge_community.comments","comment_id"},{"f4comments","pawbridge_f4_fixture.comments","comment_id"},{"notes","pawbridge_f4_fixture.private_notes","note_id"},{"outbox","pawbridge_community.outbox_events","outbox_id"},{"search","pawbridge_community.post_search_documents","post_id"},{"private_note_requests","pawbridge_community.private_note_requests","sender_id,request_id"},{"animal_reports","pawbridge_community.animal_reports","report_id"},{"refresh_tokens","pawbridge_user.refresh_tokens","refresh_token_id"},{"shelter_applications","pawbridge_user.shelter_applications","id"},{"chat_rooms","pawbridge_community.member_chat_rooms","room_id"},{"chat_members","pawbridge_community.member_chat_room_members","room_id,member_id"},{"chat_messages","pawbridge_community.member_chat_messages","room_id,sequence"},{"chat_requests","pawbridge_community.member_chat_requests","sender_id,request_id"},{"daily_visitor","pawbridge_community.daily_visitor","visit_date,visitor_hash"},{"visit_collection","pawbridge_community.visit_collection","singleton"}};
}
