package com.pawbridge.communityservice.visits;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class VisitPostgresqlTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>(DockerImageName.parse(
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193"))
            .withDatabaseName("community_application_test").withUsername("isolated_test").withPassword("isolated_test_only")
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withMemory(384 * 1024L * 1024)
                    .withPortBindings(new com.github.dockerjava.api.model.PortBinding(
                            com.github.dockerjava.api.model.Ports.Binding.bindIpAndPort("127.0.0.1",0),
                            new com.github.dockerjava.api.model.ExposedPort(5432))))
            .withInitScript("db/community-test-guard.sql");
    static JdbcTemplate jdbc;
    static VisitRepository repository;
    static DataSourceTransactionManager manager;
    static final Instant NOW=Instant.parse("2026-10-12T01:00:00Z");
    static final VisitCookie COOKIES=new VisitCookie(VisitCookieTest.KEY);
    static VisitService service;
    @BeforeAll static void migrate() {
        var ds=new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());
        jdbc=new JdbcTemplate(ds);
        assertThat(jdbc.queryForList("SELECT marker FROM migration_test_guard.guard",String.class))
                .containsExactly("community-application-disposable");
        var flyway=Flyway.configure().dataSource(ds).defaultSchema("pawbridge_community")
                .locations("classpath:db/postgresql").cleanDisabled(true).load();
        flyway.migrate();flyway.validate();
        repository=new VisitRepository(jdbc);manager=new DataSourceTransactionManager(ds);
        service=new VisitService(repository,COOKIES,Clock.fixed(NOW,ZoneOffset.UTC),manager);
    }
    @BeforeEach void resetOwnedTables() {
        jdbc.execute("DELETE FROM pawbridge_community.daily_visitor");
        jdbc.execute("DELETE FROM pawbridge_community.daily_visit_count");
        jdbc.execute("DELETE FROM pawbridge_community.visit_collection");
        service.start();
    }
    @Test void givenSameCookieAcrossConcurrentRequests_whenCount_thenOneIncrement() throws Exception {
        String cookie=COOKIES.initialize(null,NOW).getValue();
        var pool=Executors.newFixedThreadPool(8);var gate=new CountDownLatch(1);
        try {
            var calls=new ArrayList<Future<?>>();
            for(int i=0;i<24;i++) calls.add(pool.submit(() -> {gate.await();return service.count(cookie);}));
            gate.countDown();
            for(var call:calls) call.get(20,TimeUnit.SECONDS);
            assertThat(service.today().count()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pawbridge_community.daily_visitor",Long.class)).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }
    @Test void givenDifferentCookies_whenCountConcurrently_thenNoLostIncrements() throws Exception {
        var pool=Executors.newFixedThreadPool(8);
        try {
            var calls=new ArrayList<Future<?>>();
            for(int i=0;i<30;i++) {
                String cookie=COOKIES.initialize(null,NOW).getValue();
                calls.add(pool.submit(() -> service.count(cookie)));
            }
            for(var call:calls) call.get(20,TimeUnit.SECONDS);
            assertThat(service.today().count()).isEqualTo(30);
        } finally {pool.shutdownNow();}
    }
    @Test void givenCleanupDayLockAndLateRequest_whenMidnightPasses_thenNoRecreatedOldIdentifiers() throws Exception {
        var instant=new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-10-12T14:59:59Z"));
        Clock changing=mock(Clock.class);when(changing.instant()).thenAnswer(call -> instant.get());
        var waiting=spy(repository);var started=new CountDownLatch(1);
        doAnswer(call -> {started.countDown();return call.callRealMethod();}).when(waiting).lockDay(any(),eq(false));
        var writer=new VisitService(waiting,COOKIES,changing,manager);
        String cookie=COOKIES.initialize(null,instant.get()).getValue();
        var pool=Executors.newSingleThreadExecutor();
        var request=new java.util.concurrent.atomic.AtomicReference<Future<?>>();
        try {
            new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(status -> {
                repository.lockDay(LocalDate.of(2026,10,12),true);
                request.set(pool.submit(() -> writer.count(cookie)));
                try {assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();}
                catch(InterruptedException failure) {Thread.currentThread().interrupt();throw new IllegalStateException(failure);}
                instant.set(instant.get().plusSeconds(2));
                repository.deleteDay(LocalDate.of(2026,10,12));
                // Future is verified after the exclusive transaction releases its lock.
            });
            assertThatThrownBy(() -> request.get().get(10,TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pawbridge_community.daily_visitor",Long.class)).isZero();
        } finally {pool.shutdownNow();}
    }
    @Test void givenAggregateConstraintFailure_whenCount_thenRollbackIdentityAndRetryOnce() {
        jdbc.execute("ALTER TABLE pawbridge_community.daily_visit_count ADD CONSTRAINT synthetic_fail CHECK(unique_visitors<1)");
        String cookie=COOKIES.initialize(null,NOW).getValue();
        try {
            assertThatThrownBy(() -> service.count(cookie)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pawbridge_community.daily_visitor",Long.class)).isZero();
        } finally {jdbc.execute("ALTER TABLE pawbridge_community.daily_visit_count DROP CONSTRAINT synthetic_fail");}
        assertThat(service.count(cookie).count()).isEqualTo(1);
    }
    @Test void givenNextKoreanDay_whenCleanupAndCount_thenDeleteOnlyIdentifiersAndKeepHistory() {
        service.count(COOKIES.initialize(null,NOW).getValue());
        var next=NOW.plus(Duration.ofDays(1));
        var tomorrow=new VisitService(repository,COOKIES,Clock.fixed(next,ZoneOffset.UTC),manager);
        tomorrow.cleanup();tomorrow.cleanup();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pawbridge_community.daily_visitor",Long.class)).isZero();
        assertThat(repository.today(LocalDate.of(2026,10,12))).isEqualTo(1);
        assertThat(tomorrow.count(COOKIES.initialize(null,next).getValue()).count()).isEqualTo(1);
        assertThat(tomorrow.period(LocalDate.of(2026,10,12),LocalDate.of(2026,10,13)).daily())
                .extracting(VisitModels.Day::count).containsExactly(1L,1L);
    }
}
