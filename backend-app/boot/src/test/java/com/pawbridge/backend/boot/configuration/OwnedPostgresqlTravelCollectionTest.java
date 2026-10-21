package com.pawbridge.backend.boot.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.internal.travel.application.PetTravel;
import com.pawbridge.backend.animal.internal.travel.application.PetTravelException;
import com.pawbridge.backend.animal.internal.travel.infrastructure.PostgresqlPetTravel;
import com.pawbridge.backend.animal.internal.travel.infrastructure.collection.PetTravelCollector;
import com.pawbridge.backend.animal.internal.travel.infrastructure.collection.PostgresqlTravelCollectionCatalog;
import com.pawbridge.backend.animal.internal.travel.infrastructure.collection.TourApiClient;
import com.pawbridge.backend.animal.internal.travel.infrastructure.collection.TourApiProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * One fresh synthetic database, one explicit writer, original Root job/scheduler methods.
 * Ordered contracts deliberately retain owned history and a final RUNNING orphan for inspection.
 * This is explicit component assembly, not a full Boot context or live provider test.
 */
@Tag("owned-postgresql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(45)
class OwnedPostgresqlTravelCollectionTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final String PROVIDER = "KOREA_TOURISM_ORGANIZATION", CONTENT = "998201";
    private static final String AREA = "77", REVISION = "20261021010101";
    private static final int LOCK_NAMESPACE = 0x50415742, LOCK_KEY = 2;
    private static final Map<String,Set<String>> PRIVILEGES = privileges();
    private final Map<ch.qos.logback.classic.Logger,ch.qos.logback.classic.Level> previousLevels = new LinkedHashMap<>();
    private final List<ClientObservation> clientObservations = new CopyOnWriteArrayList<>();
    private final AnimalTravelCollectionConfiguration configuration = new AnimalTravelCollectionConfiguration();
    private Settings settings;
    private DriverManagerDataSource direct;
    private ObservedPool pool;
    private JdbcTemplate jdbc;
    private JdbcTransactionManager manager;
    private PostgresqlTravelCollectionCatalog catalog;
    private JobRepository repository;
    private TaskExecutorJobLauncher launcher;
    private PetTravel reader;
    private Clock clock;
    private boolean nextContractAllowed;
    private boolean bodyCompleted;
    private int nextOrder = 1;
    private volatile boolean unsettledWorkers;

    @BeforeAll void givenNewPrivateOwnedDatabase__whenPreparingComponents__thenRequireExactIdentitySchemaAndLeastPrivileges() {
        observed("identity and explicit assembly", () -> {
            quietLogs(); settings = Settings.load();
            direct = new DriverManagerDataSource(settings.url(), settings.username(), settings.password());
            Properties timeouts = new Properties(); timeouts.setProperty("connectTimeout", "3"); timeouts.setProperty("socketTimeout", "10");
            direct.setConnectionProperties(timeouts);
            var probe = new JdbcTemplate(direct); probe.setQueryTimeout(5); verifyServer(probe);
            HikariConfig config = new HikariConfig(); config.setJdbcUrl(settings.url());
            config.setUsername(settings.username()); config.setPassword(settings.password());
            config.setPoolName("owned-travel-collection-writer"); config.setMaximumPoolSize(4); config.setMinimumIdle(0);
            config.setConnectionTimeout(2000); config.setValidationTimeout(1000); config.setInitializationFailTimeout(-1);
            config.addDataSourceProperty("connectTimeout", "3"); config.addDataSourceProperty("socketTimeout", "10");
            pool = new ObservedPool(config); jdbc = new JdbcTemplate(pool); jdbc.setQueryTimeout(5);
            manager = new JdbcTransactionManager(pool);
            catalog = configuration.travelCollectionCatalog(pool, jdbc, manager, JSON);
            repository = configuration.travelCollectionJobRepository(pool, manager, catalog);
            launcher = new TaskExecutorJobLauncher(); launcher.setJobRepository(repository);
            launcher.setTaskExecutor(new SyncTaskExecutor()); launcher.afterPropertiesSet();
            clock = Clock.fixed(Instant.now().truncatedTo(ChronoUnit.SECONDS), ZoneOffset.UTC);
            var reads = new TransactionTemplate(manager); reads.setReadOnly(true);
            var repeatable = new TransactionTemplate(manager, reads);
            repeatable.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            reader = new PostgresqlPetTravel(jdbc, JSON, reads, repeatable);
            for (String table : PRIVILEGES.keySet()) {
                long expected = table.equals("pet_travel_collection_state") || table.equals("pet_travel_pet_collection_state") ? 1 : 0;
                check(count(table) == expected, "A new fixture must contain only the two original singleton checkpoints");
            }
            check(catalog.collectionState().phase().equals("HIDDEN") && catalog.collectionState().nextPage() == 1
                    && catalog.petCollectionState().nextPage() == 1 && catalog.petCollectionState().expectedTotal() == null,
                    "Original empty checkpoints are mandatory; a partially used fixture must not be automatically replayed");
            released();
            nextContractAllowed = true;
        });
    }

    @BeforeEach void requireAllPreviousResourcesReturned(TestInfo test) {
        boolean predecessorSucceeded = nextContractAllowed;
        nextContractAllowed = false; bodyCompleted = false;
        observed("before contract", () -> {
            check(predecessorSucceeded && !unsettledWorkers,
                    "A previous contract or lifecycle check failed; further owned writes are fenced");
            check(test.getTestMethod().orElseThrow().getAnnotation(Order.class).value() == nextOrder,
                    "Owned contracts must run only in their complete approved order");
            released();
        });
    }
    @AfterEach void requirePrivateMarkerAndAllResourcesPreserved() {
        nextContractAllowed = false;
        observed("after contract", () -> {
            check(!unsettledWorkers, "Unsettled owned scheduler work fences further database access and pool shutdown");
            released(); check(settings.marker().equals(jdbc.queryForObject(
                    "SELECT marker FROM public.pb_travel_collection_fixture", String.class)), "The private ownership marker must remain unchanged");
            released(); check(bodyCompleted, "The failed owned contract must remain frozen before any later write");
            nextOrder++; nextContractAllowed = true;
        });
    }
    @AfterAll void closeOnlyTheTestOwnedPoolAndRetainSyntheticHistory() {
        try {
            observed("final writer shutdown", () -> {
                if (pool != null) {
                    check(!unsettledWorkers, "Preserve the owned pool while worker or closer settlement is unconfirmed");
                    released(); pool.close(); check(pool.isClosed(), "The explicit test-owned writer pool must close");
                    System.out.println("TRAVEL_COLLECTION_OWNED clientCalls=" + clientObservations.size()
                            + " callbackWriterActive=" + clientObservations.stream().map(ClientObservation::active).distinct().sorted().toList()
                            + " callbackTransactions=" + clientObservations.stream().map(ClientObservation::transaction).distinct().toList()
                            + " peakWriterBorrows=" + pool.peak.get() + " rollbackCalls=" + pool.rollbacks.get()
                            + " fixtureHistoryRetained=true");
                }
            });
        } finally {
            // Unsettled workers may still log; retain suppression until Root handles the failed process.
            if (!unsettledWorkers) previousLevels.forEach(ch.qos.logback.classic.Logger::setLevel);
        }
    }

    @Test @Order(1)
    void givenTwoBulkRowsWithInvalidSecondIdentity__whenSavingOnePage__thenRollbackFirstInsertAndKeepCheckpoint() {
        contract("bulk page and checkpoint rollback", () -> {
            var before = catalog.petCollectionState(); int inserts = pool.petInserts.get(), rollbacks = pool.rollbacks.get();
            var page = new TourApiClient.Page(List.of(Map.of("contentid", "998101", "acmpyNeedMtr", "synthetic leash"),
                    Map.of("contentid", "invalid-owned-identity")), 2);
            expect(IllegalArgumentException.class, "Invalid pet identity", () -> catalog.savePetPage(before, page,
                    clock.instant(), clock.instant().plusSeconds(86400)));
            check(pool.petInserts.get() == inserts + 1 && pool.rollbacks.get() == rollbacks + 1,
                    "The first actual PostgreSQL INSERT must execute before the enclosing page transaction rolls back");
            check(count("pet_travel_pet_details") == 0 && catalog.petCollectionState().equals(before),
                    "Neither page data nor its singleton checkpoint may survive a failed page");
        });
    }

    @Test @Order(2)
    void givenSyntheticBulkRegionsAndSyncRows__whenPartialJobAndFreshInvocationRun__thenPersistBudgetResumeAndServeExistingReader() {
        contract("actual Batch partial and catalog resume", () -> {
            var client = syntheticClient(true, () -> { }); var properties = properties(true);
            int observationsBefore = clientObservations.size();
            var first = run(client, properties);
            requireExecution(first, BatchStatus.COMPLETED, "PARTIAL");
            var run = jdbc.queryForMap("SELECT status,error_code,requests,discovered,published FROM pawbridge_animal.pet_travel_collection_runs");
            check(run.get("status").equals("PARTIAL") && run.get("error_code").equals("VISIT_DETAIL_FAILED")
                    && number(run, "requests") == 8 && number(run, "discovered") == 1 && number(run, "published") == 1,
                    "An INTRO failure must retain the fixed partial result and exact charged request counts");
            check(catalog.collectionState().phase().equals("HIDDEN") && catalog.collectionState().nextPage() == 1
                    && catalog.collectionState().completedAt().equals(clock.instant())
                    && catalog.petCollectionState().nextPage() == 1 && catalog.petCollectionState().completedAt().equals(clock.instant()),
                    "Completed discovery and bulk pages must retain their original next-cycle checkpoints");
            check(reader.regions().items().size() == 1 && reader.regions().items().getFirst().code().equals(AREA)
                    && reader.places(AREA, 0).totalElements() == 1 && reader.places(AREA, 0).items().getFirst().contentId().equals(CONTENT),
                    "The existing public reader must observe the collected region and type-filtered inventory");
            var detail = reader.detail(CONTENT);
            check(detail.place().title().equals("Owned travel place") && detail.overview().equals("Owned overview")
                    && detail.conditions().requirements().equals("synthetic leash") && detail.petInformationAvailable()
                    && detail.petInformationStatus().equals("READY") && detail.visitInformation().status().equals("PARTIAL")
                    && detail.visitInformation().additionalItems().size() == 1 && detail.images().size() == 1
                    && detail.imagesStatus().equals("READY"),
                    "Independent bulk, common, INFO and licensed image snapshots must remain readable after an INTRO failure");
            check(clientObservations.size() - observationsBefore == 8, "Only the eight bounded synthetic operations may run");
            var second = run(client, properties);
            requireExecution(second, BatchStatus.COMPLETED, "COMPLETED");
            check(!first.getJobInstance().getId().equals(second.getJobInstance().getId())
                    && count("batch_job_instance") == 2 && count("pet_travel_collection_runs") == 2,
                    "A new requestId creates a new Batch instance; it is not a Batch restart of the partial catalog run");
            check(jdbc.queryForObject("SELECT count(*) FROM pawbridge_animal.pet_travel_collection_runs WHERE status='COMPLETED' AND requests=4 AND discovered=1 AND published=0", Long.class) == 1,
                    "The fresh invocation must skip fresh bulk/common/INFO/images and resume only the failed INTRO work");
            Map<String,Integer> budgets = new LinkedHashMap<>();
            jdbc.query("SELECT operation,used FROM pawbridge_animal.pet_travel_request_budgets ORDER BY operation", row -> {
                budgets.put(row.getString(1), row.getInt(2));
            });
            check(budgets.equals(Map.of("PET_BULK", 1, "REGIONS", 2, "SYNC", 4, "COMMON", 1, "INTRO", 2, "INFO", 1, "IMAGES", 1)),
                    "The Seoul-day operation budgets must charge the failed attempt and avoid successful-resource refetches");
            check(jdbc.queryForObject("SELECT count(*) FROM pawbridge_animal.pet_travel_request_budgets WHERE request_day=?", Long.class,
                    java.sql.Date.valueOf(java.time.LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Seoul"))))) == 7,
                    "Every budget row must use the collector clock's Seoul calendar day");
            check(reader.detail(CONTENT).visitInformation().status().equals("READY") && !catalog.hasPendingVisitDetails(),
                    "A successful INTRO retry must complete the existing reader's visit snapshot");
        });
    }

    @Test @Order(3)
    void givenTwoPhysicalPostgresqlSessions__whenLockOwnerRollsBackAndCollectorIsBusy__thenRetainSessionLockUntilPhysicalClose() {
        contract("two physical lock sessions and busy collector", () -> {
            Map<String,String> before = fingerprint(); var client = noCallsClient();
            try (var first = direct.getConnection(); var second = direct.getConnection()) {
                check(pid(first) != pid(second), "Lock contenders must be different actual PostgreSQL backend PIDs");
                check(lock(first, "pg_try_advisory_lock"), "The first physical session must acquire the original namespace and key2");
                first.setAutoCommit(false); pid(first); first.rollback();
                check(owns(first) && !lock(second, "pg_try_advisory_lock"), "Rollback must retain the session lock and the other physical session must remain busy");
                first.setAutoCommit(true);
                expect(IllegalStateException.class, "TRAVEL_COLLECTION_BUSY", () -> collector(client, properties(false)).collect());
                verifyNoInteractions(client);
                check(fingerprint().equals(before), "A busy collector must not start a catalog run, charge budget or write Batch metadata");
                first.close();
                try (var statement = second.prepareStatement("SELECT pg_advisory_lock(?,?)")) {
                    statement.setQueryTimeout(3); statement.setInt(1, LOCK_NAMESPACE); statement.setInt(2, LOCK_KEY);
                    try (var row = statement.executeQuery()) { check(row.next(), "Physical owner close must release the lock to the waiting session"); }
                }
                check(owns(second) && lock(second, "pg_advisory_unlock"), "The second session must actually own and explicitly release the transferred lock");
            }
        });
    }

    @Test @Order(4)
    void givenAnInflightNamedSchedulerAndThenProviderFailure__whenShutdownAndFailureFinish__thenWaitAndReleaseWriterAndSessionLock() {
        contract("normal scheduler shutdown and failed run release", () -> {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), workDone = new CountDownLatch(1);
            CountDownLatch closeStarted = new CountDownLatch(1), closeDone = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            var client = syntheticClient(false, () -> { entered.countDown(); check(release.await(8, TimeUnit.SECONDS), "Synthetic provider must be released within its test bound"); });
            Job job = configuration.petTravelCollectionJob(repository, manager, collector(client, properties(false)));
            var schedule = new AnimalTravelCollectionSchedule(repository, job);
            ThreadPoolTaskScheduler scheduler = AnimalTravelCollectionSchedule.tourApiCollectionScheduler();
            scheduler.initialize();
            Thread closing = new Thread(() -> { closeStarted.countDown(); try { scheduler.shutdown(); }
                catch (Throwable problem) { failure.compareAndSet(null, problem); }
                finally { closeDone.countDown(); } }, "owned-travel-shutdown-observer");
            closing.setDaemon(true);
            boolean submitted = false;
            unsettledWorkers = true;
            try {
                scheduler.execute(() -> { try { schedule.collect(); } catch (Throwable problem) { failure.set(problem); } finally { workDone.countDown(); } });
                submitted = true;
                check(entered.await(8, TimeUnit.SECONDS), "The actual schedule method must reach its synthetic provider callback");
                closing.start(); check(closeStarted.await(2, TimeUnit.SECONDS), "The scheduler close observer must start");
                check(awaitShutdown(scheduler, closeDone), "The scheduler must actually reach shutdown within the observation bound");
                check(!closeDone.await(150, TimeUnit.MILLISECONDS)
                        && pool.getHikariPoolMXBean().getActiveConnections() == 1,
                        "Normal named-scheduler shutdown must wait while the collector retains only its lock connection");
            } finally {
                release.countDown();
                if (!submitted) workDone.countDown();
                if (closing.getState() == Thread.State.NEW) closing.start();
                boolean workSettled = awaitWork(workDone, 6);
                boolean closerSettled = awaitCloser(closing, 6);
                unsettledWorkers = !(workSettled && closerSettled && closeDone.getCount() == 0
                        && scheduler.getScheduledExecutor().isTerminated());
                check(!unsettledWorkers, "Worker and closer must each settle within their own bound; preserve pool and fence later writes otherwise");
            }
            check(failure.get() == null, "The released bounded collection and closer must finish without an execution failure");
            released(); requireLockAvailable();
            String hiddenDetail = "synthetic-provider-detail-must-not-persist";
            var unavailable = mock(TourApiClient.class);
            doAnswer(invocation -> { observeClient("REGIONS_FAILURE"); throw new IllegalStateException(hiddenDetail); })
                    .when(unavailable).fetch(TourApiClient.Operation.REGIONS, "");
            var failed = run(unavailable, properties(false)); requireExecution(failed, BatchStatus.FAILED, "FAILED");
            check(jdbc.queryForObject("SELECT count(*) FROM pawbridge_animal.pet_travel_collection_runs WHERE status='FAILED' AND error_code='COLLECTION_FAILED' AND requests=1", Long.class) == 1,
                    "A failed upstream attempt must consume one request and persist only the fixed catalog failure");
            check(jdbc.queryForObject("SELECT count(*) FROM pawbridge_animal.batch_step_execution WHERE exit_message LIKE ?", Long.class, "%" + hiddenDetail + "%") == 0,
                    "Upstream exception detail must not be retained in Batch metadata");
            released(); requireLockAvailable();
        });
    }

    @Test @Order(5)
    void givenExhaustedBudgetAndThenOwnedRunningOrphan__whenInvokedAgain__thenKeepQuotaDistinctAndRefuseAutomaticOrphanRecovery() {
        contract("quota distinction and retained RUNNING orphan", () -> {
            var client = noCallsClient(); var properties = properties(false); properties.setDailyRequestLimit(1);
            Map<String,String> budgets = budgetFingerprint();
            var quota = run(client, properties); requireExecution(quota, BatchStatus.COMPLETED, "QUOTA");
            verifyNoInteractions(client);
            check(budgetFingerprint().equals(budgets)
                    && jdbc.queryForObject("SELECT count(*) FROM pawbridge_animal.pet_travel_collection_runs WHERE status='QUOTA' AND error_code='REQUEST_BUDGET' AND requests=0", Long.class) == 1,
                    "An exhausted operation must return catalog QUOTA without a provider call or another budget increment");
            check(reader.detail(CONTENT).conditions().requirements().equals("synthetic leash") && reader.places(AREA, 0).totalElements() == 1,
                    "Quota must preserve the previously published detail and inventory");
            String orphan = UUID.randomUUID().toString(); catalog.startRun(orphan, clock.instant());
            long runs = count("pet_travel_collection_runs");
            expect(IllegalStateException.class, "TRAVEL_ORPHAN_RUN", () -> collector(client, properties).collect());
            verifyNoInteractions(client);
            check(count("pet_travel_collection_runs") == runs && budgetFingerprint().equals(budgets)
                    && jdbc.queryForObject("SELECT count(*) FROM pawbridge_animal.pet_travel_collection_runs WHERE id=? AND status='RUNNING' AND finished_at IS NULL", Long.class, orphan) == 1,
                    "An existing owned RUNNING row must be refused and retained without deletion, completion or automatic recovery");
            released(); requireLockAvailable();
        });
    }

    private TourApiProperties properties(boolean bulk) {
        var value = new TourApiProperties(); value.setEnabled(true); value.setBulkPetEnabled(bulk);
        value.setMaxPagesPerRun(2); value.setMaxBulkPagesPerRun(1); value.setMaxDetailsPerRun(1); value.setMaxVisitDetailsPerRun(1);
        return value;
    }
    private void contract(String phase, Checked action) {
        observed(phase, () -> { action.run(); bodyCompleted = true; });
    }
    private static boolean awaitShutdown(ThreadPoolTaskScheduler scheduler, CountDownLatch closeDone) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!scheduler.getScheduledExecutor().isShutdown()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return false;
            if (closeDone.await(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(10)), TimeUnit.NANOSECONDS))
                return scheduler.getScheduledExecutor().isShutdown();
        }
        return true;
    }
    private static boolean awaitWork(CountDownLatch done, int seconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        boolean interrupted = false;
        try {
            while (done.getCount() != 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return false;
                try { if (done.await(remaining, TimeUnit.NANOSECONDS)) return true; }
                catch (InterruptedException failure) { interrupted = true; }
            }
            return true;
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    private static boolean awaitCloser(Thread closer, int seconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        boolean interrupted = false;
        try {
            while (closer.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return false;
                try { TimeUnit.NANOSECONDS.timedJoin(closer, remaining); }
                catch (InterruptedException failure) { interrupted = true; }
            }
            return closer.getState() == Thread.State.TERMINATED;
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    private PetTravelCollector collector(TourApiClient client, TourApiProperties properties) {
        return configuration.petTravelCollector(pool, catalog, client, properties, clock);
    }
    private JobExecution run(TourApiClient client, TourApiProperties properties) throws Exception {
        return launcher.run(configuration.petTravelCollectionJob(repository, manager, collector(client, properties)),
                new JobParametersBuilder().addString("requestId", UUID.randomUUID().toString()).toJobParameters());
    }
    private void requireExecution(JobExecution execution, BatchStatus status, String exit) {
        check(execution.getStatus() == status && execution.getExitStatus().getExitCode().equals(exit)
                && execution.getStepExecutions().size() == 1
                && execution.getStepExecutions().iterator().next().getStatus() == status
                && execution.getStepExecutions().iterator().next().getExitStatus().getExitCode().equals(exit),
                "BatchStatus and the tasklet's catalog ExitStatus must be checked separately");
        check(jdbc.queryForObject("SELECT count(*) FROM pawbridge_animal.batch_job_execution WHERE job_execution_id=? AND status=? AND exit_code=?", Long.class,
                execution.getId(), status.name(), exit) == 1, "The actual qualified PostgreSQL JobRepository must persist both status values");
        released();
    }
    private TourApiClient noCallsClient() { return mock(TourApiClient.class); }
    private TourApiClient syntheticClient(boolean failFirstIntro, Checked beforeRegions) {
        var client = mock(TourApiClient.class); var intros = new AtomicInteger();
        doAnswer(call -> { observeClient("REGIONS"); beforeRegions.run(); return List.of(Map.of("code", AREA, "name", "Owned region")); })
                .when(client).fetch(TourApiClient.Operation.REGIONS, "");
        doAnswer(call -> { observeClient("PET_BULK"); return new TourApiClient.Page(List.of(Map.of("contentid", CONTENT, "acmpyNeedMtr", "synthetic leash", "acmpyTypeCd", "DOGS")), 1); })
                .when(client).fetchPage(TourApiClient.Operation.PET_BULK, "", 1);
        doAnswer(call -> { observeClient("SYNC_HIDDEN"); return new TourApiClient.Page(List.of(), 0); })
                .when(client).fetchPage(TourApiClient.Operation.SYNC, "0", 1);
        doAnswer(call -> { observeClient("SYNC_SHOWN"); return new TourApiClient.Page(List.of(Map.of(
                "contentid", CONTENT, "contenttypeid", "12", "lDongRegnCd", AREA, "modifiedtime", REVISION, "showflag", "1",
                "title", "Owned travel place", "addr1", "Owned address", "firstimage", "https://tong.visitkorea.or.kr/cms/resource/77/OWNED.jpg", "cpyrhtDivCd", "Type1")), 1); })
                .when(client).fetchPage(TourApiClient.Operation.SYNC, "1", 1);
        doAnswer(call -> { observeClient("COMMON"); return List.of(Map.of("contentid", CONTENT, "title", "Owned travel place", "overview", "Owned overview", "contenttypeid", "12")); })
                .when(client).fetch(TourApiClient.Operation.COMMON, CONTENT);
        doAnswer(call -> { observeClient("INTRO"); if (failFirstIntro && intros.getAndIncrement() == 0) throw PetTravelException.unavailable();
            return List.of(Map.of("contentid", CONTENT, "contenttypeid", "12", "infocenter", "Owned center", "usetime", "Owned hours")); })
                .when(client).fetchDetail(TourApiClient.Operation.INTRO, CONTENT, "12");
        doAnswer(call -> { observeClient("INFO"); return List.of(Map.of("contentid", CONTENT, "contenttypeid", "12", "infoname", "Owned information", "infotext", "Owned information text")); })
                .when(client).fetchDetail(TourApiClient.Operation.INFO, CONTENT, "12");
        doAnswer(call -> { observeClient("IMAGES"); return List.of(Map.of("contentid", CONTENT, "serialnum", "1", "imgname", "Owned image",
                "originimgurl", "https://tong.visitkorea.or.kr/cms/resource/77/OWNED.jpg", "cpyrhtDivCd", "Type1")); })
                .when(client).fetchDetail(TourApiClient.Operation.IMAGES, CONTENT, "12");
        return client;
    }
    private void observeClient(String operation) throws SQLException {
        boolean transaction = TransactionSynchronizationManager.isActualTransactionActive();
        int active = pool.getHikariPoolMXBean().getActiveConnections(), owner = pool.lastLockPid.get();
        clientObservations.add(new ClientObservation(operation, owner, active, transaction));
        check(!transaction && active == 1
                && pool.connections.get() == 1 && owner > 0,
                "The actual provider seam must have no writer transaction or extra borrowed connection; only the raw session lock may remain borrowed");
        Connection ownerConnection = pool.lockConnection.get();
        check(ownerConnection != null && ownerConnection.getAutoCommit() && pid(ownerConnection) == owner && owns(ownerConnection),
                "Metadata inspected on the actual retained connection must confirm the same PID still owns namespace/key2 outside a transaction");
    }
    private void released() {
        check(!TransactionSynchronizationManager.isActualTransactionActive()
                && (pool == null || !TransactionSynchronizationManager.hasResource(pool)), "No writer transaction or resource binding may escape a phase");
        if (pool != null) check(pool.connections.get() == 0 && pool.statements.get() == 0 && pool.results.get() == 0
                && pool.getHikariPoolMXBean().getActiveConnections() == 0 && pool.getHikariPoolMXBean().getThreadsAwaitingConnection() == 0,
                "All actual writer connections, statements and result cursors must return without a waiting borrower");
    }
    private void requireLockAvailable() throws Exception {
        try (var connection = direct.getConnection()) {
            check(lock(connection, "pg_try_advisory_lock") && lock(connection, "pg_advisory_unlock"), "A fresh physical session must acquire and release the original collector lock after every finish/failure");
        }
    }
    private static int pid(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT pg_backend_pid()")) {
            statement.setQueryTimeout(3); try (var row = statement.executeQuery()) { check(row.next(), "The actual PostgreSQL PID is required"); return row.getInt(1); }
        }
    }
    private static boolean lock(Connection connection, String function) throws SQLException {
        check(Set.of("pg_try_advisory_lock", "pg_advisory_unlock").contains(function), "Only the fixed advisory lock operations are permitted");
        try (var statement = connection.prepareStatement("SELECT " + function + "(?,?)")) {
            statement.setQueryTimeout(3); statement.setInt(1, LOCK_NAMESPACE); statement.setInt(2, LOCK_KEY);
            try (var row = statement.executeQuery()) { check(row.next(), "The advisory result is required"); return row.getBoolean(1); }
        }
    }
    private static boolean owns(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT EXISTS(SELECT 1 FROM pg_locks WHERE locktype='advisory' AND pid=pg_backend_pid() AND database=(SELECT oid FROM pg_database WHERE datname=current_database()) AND classid=? AND objid=? AND objsubid=2 AND granted)")) {
            statement.setQueryTimeout(3); statement.setInt(1, LOCK_NAMESPACE); statement.setInt(2, LOCK_KEY);
            try (var row = statement.executeQuery()) { check(row.next(), "Actual session ownership is required"); return row.getBoolean(1); }
        }
    }
    private long count(String table) { check(PRIVILEGES.containsKey(table), "Only the exact owned fixture tables may be counted"); return jdbc.queryForObject("SELECT count(*) FROM pawbridge_animal." + table, Long.class); }
    private Map<String,String> fingerprint() {
        Map<String,String> result = new LinkedHashMap<>();
        PRIVILEGES.keySet().forEach(table -> result.put(table, jdbc.queryForObject("SELECT md5(COALESCE(string_agg(value,',' ORDER BY value),'')) FROM (SELECT row_to_json(t)::text AS value FROM pawbridge_animal." + table + " t) rows", String.class)));
        return result;
    }
    private Map<String,String> budgetFingerprint() { return Map.of("budgets", jdbc.queryForObject("SELECT md5(COALESCE(string_agg(row_to_json(t)::text,',' ORDER BY request_day,operation),'')) FROM pawbridge_animal.pet_travel_request_budgets t", String.class)); }
    private static int number(Map<String,Object> row, String key) { return ((Number) row.get(key)).intValue(); }

    private void verifyServer(JdbcTemplate probe) {
        var identity = probe.queryForMap("SELECT current_database() AS db,current_user AS actor,session_user AS session,current_setting('server_version_num') AS version,(SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()) AS owner");
        check(settings.database().equals(identity.get("db")) && settings.username().equals(identity.get("actor"))
                && settings.username().equals(identity.get("session")) && settings.owner().equals(identity.get("owner")) && "170011".equals(identity.get("version")),
                "Only the new owned PostgreSQL17.11 database, dedicated NOLOGIN owner and dedicated login writer are accepted");
        check(settings.marker().equals(probe.queryForObject("SELECT marker FROM public.pb_travel_collection_fixture", String.class)), "The single private ownership marker must match");
        check(Boolean.TRUE.equals(probe.queryForObject("SELECT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls AND NOT rolinherit AND NOT EXISTS(SELECT 1 FROM pg_auth_members m WHERE m.member=pg_roles.oid) FROM pg_roles WHERE rolname=current_user", Boolean.class)), "The writer must have no administrator or inherited role membership");
        check(Boolean.TRUE.equals(probe.queryForObject("SELECT NOT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls AND NOT rolinherit FROM pg_roles WHERE rolname=?", Boolean.class, settings.owner()))
                && Boolean.FALSE.equals(probe.queryForObject("SELECT pg_has_role(current_user,?,'MEMBER')", Boolean.class, settings.owner())), "The separate fixture owner must remain NOLOGIN and inaccessible to the writer");
        check(Boolean.FALSE.equals(probe.queryForObject("SELECT has_database_privilege(current_user,current_database(),'CREATE,TEMP')", Boolean.class)), "The writer must have no database or temporary-object DDL privilege");
        for (String schema : List.of("public", "pawbridge_animal")) check(Boolean.TRUE.equals(probe.queryForObject("SELECT has_schema_privilege(current_user,?,'USAGE') AND NOT has_schema_privilege(current_user,?,'CREATE')", Boolean.class, schema, schema)), "The writer must have schema USAGE without CREATE");
        List<String> tables = probe.query("SELECT tablename FROM pg_tables WHERE schemaname='pawbridge_animal' ORDER BY tablename", (r, n) -> r.getString(1));
        check(tables.equals(PRIVILEGES.keySet().stream().sorted().toList()), "Only ten Travel and six original Batch tables may exist in the application schema");
        check(probe.queryForObject("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname !~ '^pg_' AND n.nspname<>'information_schema' AND c.relkind IN('r','p','v','m','f')", Long.class) == 17,
                "Exactly the sixteen application tables and one marker may exist; P14 source tables must not be copied");
        for (var table : PRIVILEGES.entrySet()) for (String permission : List.of("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER"))
            check(Boolean.valueOf(table.getValue().contains(permission)).equals(probe.queryForObject("SELECT has_table_privilege(current_user,?,?)", Boolean.class, "pawbridge_animal." + table.getKey(), permission)), "Every runtime table privilege must exactly match the required collection/Batch SQL");
        check(Boolean.TRUE.equals(probe.queryForObject("SELECT has_table_privilege(current_user,'public.pb_travel_collection_fixture','SELECT') AND NOT has_table_privilege(current_user,'public.pb_travel_collection_fixture','INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')", Boolean.class)), "The ownership marker must remain SELECT-only");
        List<String> sequences = probe.query("SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='pawbridge_animal' AND c.relkind='S' ORDER BY c.relname", (r, n) -> r.getString(1));
        check(sequences.equals(List.of("batch_job_execution_seq", "batch_job_seq", "batch_step_execution_seq")), "Only the three official PostgreSQL Batch sequences may exist");
        for (String sequence : sequences) check(Boolean.TRUE.equals(probe.queryForObject("SELECT has_sequence_privilege(current_user,?,'USAGE') AND NOT has_sequence_privilege(current_user,?,'SELECT,UPDATE')", Boolean.class, "pawbridge_animal." + sequence, "pawbridge_animal." + sequence)), "Batch sequences need nextval USAGE without direct SELECT or UPDATE privilege");
        check(probe.queryForObject("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN('public','pawbridge_animal') AND c.relkind IN('r','S') AND pg_get_userbyid(c.relowner)<>?", Long.class, settings.owner()) == 0,
                "Every new fixture table and sequence must belong to the separate NOLOGIN owner");
    }
    private static Map<String,Set<String>> privileges() {
        Map<String,Set<String>> values = new LinkedHashMap<>();
        for (String table : List.of("pet_travel_collection_state", "pet_travel_pet_collection_state")) values.put(table, Set.of("SELECT", "UPDATE"));
        for (String table : List.of("pet_travel_regions", "pet_travel_collection_runs", "pet_travel_request_budgets", "pet_travel_targets", "pet_travel_places", "pet_travel_pet_details", "pet_travel_visit_details")) values.put(table, Set.of("SELECT", "INSERT", "UPDATE"));
        values.put("pet_travel_images", Set.of("SELECT", "INSERT", "DELETE"));
        for (String table : List.of("batch_job_instance", "batch_job_execution_params")) values.put(table, Set.of("SELECT", "INSERT"));
        for (String table : List.of("batch_job_execution", "batch_step_execution", "batch_job_execution_context", "batch_step_execution_context")) values.put(table, Set.of("SELECT", "INSERT", "UPDATE"));
        return Map.copyOf(values);
    }
    private void quietLogs() {
        for (String name : List.of("com.zaxxer.hikari", "org.springframework.jdbc", "org.springframework.batch")) {
            var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(name);
            previousLevels.put(logger, logger.getLevel()); logger.setLevel(ch.qos.logback.classic.Level.OFF);
        }
    }

    /** Observes actual JDBC resources and PIDs without retaining SQL, parameters or payload. */
    private static final class ObservedPool extends HikariDataSource {
        final AtomicInteger connections = new AtomicInteger(), statements = new AtomicInteger(), results = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger(), rollbacks = new AtomicInteger(), petInserts = new AtomicInteger(), lastLockPid = new AtomicInteger();
        final AtomicReference<Connection> lockConnection = new AtomicReference<>();
        ObservedPool(HikariConfig configuration) { super(configuration); }
        @Override public Connection getConnection() throws SQLException {
            Connection actual = super.getConnection(); int backend;
            try { backend = pid(actual); } catch (SQLException | RuntimeException | Error failure) { actual.close(); throw failure; }
            int active = connections.incrementAndGet(); peak.accumulateAndGet(active, Math::max);
            AtomicBoolean closed = new AtomicBoolean(); List<TrackedStatement> children = new CopyOnWriteArrayList<>();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                if (method.getName().equals("close")) {
                    if (closed.compareAndSet(false, true)) { try { call(actual, method, args); } finally { children.forEach(TrackedStatement::closed); connections.decrementAndGet(); } } return null;
                }
                if (method.getName().equals("rollback")) rollbacks.incrementAndGet();
                Object result = call(actual, method, args);
                if (result instanceof Statement statement) {
                    String sql = args != null && args.length > 0 && args[0] instanceof String text ? text : "";
                    var tracked = new TrackedStatement(statement, sql, backend, actual); children.add(tracked); return tracked.proxy();
                }
                return result;
            });
        }
        private final class TrackedStatement {
            final Statement delegate; final String sql; final int backend; final Connection connection;
            final AtomicBoolean isClosed = new AtomicBoolean(); final List<AtomicBoolean> cursors = new CopyOnWriteArrayList<>();
            TrackedStatement(Statement delegate, String sql, int backend, Connection connection) { this.delegate=delegate;this.sql=sql;this.backend=backend;this.connection=connection;statements.incrementAndGet(); }
            Object proxy() {
                return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{delegate instanceof PreparedStatement ? PreparedStatement.class : Statement.class}, (proxy, method, args) -> {
                    if (method.getName().equals("close")) { try { return call(delegate, method, args); } finally { closed(); } }
                    if (method.getName().startsWith("execute")) {
                        if (sql.contains("pg_try_advisory_lock")) { lastLockPid.set(backend); lockConnection.set(connection); }
                        if (sql.startsWith("INSERT INTO pawbridge_animal.pet_travel_pet_details")) {
                            petInserts.incrementAndGet(); check(!connection.getAutoCommit() && TransactionSynchronizationManager.isActualTransactionActive(), "Bulk page SQL must execute inside its real narrow writer transaction");
                        }
                    }
                    Object value = call(delegate, method, args);
                    if (!(value instanceof ResultSet row)) return value;
                    AtomicBoolean cursorClosed = new AtomicBoolean(); cursors.add(cursorClosed); results.incrementAndGet();
                    return Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class}, (cursorProxy, cursorMethod, cursorArgs) -> {
                        if (cursorMethod.getName().equals("close")) { try { return call(row, cursorMethod, cursorArgs); } finally { closeCursor(cursorClosed); } }
                        return call(row, cursorMethod, cursorArgs);
                    });
                });
            }
            void closed() { if (isClosed.compareAndSet(false, true)) { cursors.forEach(this::closeCursor); statements.decrementAndGet(); } }
            void closeCursor(AtomicBoolean cursor) { if (cursor.compareAndSet(false, true)) results.decrementAndGet(); }
        }
    }

    private static Object call(Object target, Method method, Object[] args) throws Throwable { try { return method.invoke(target, args); } catch (InvocationTargetException failure) { throw failure.getCause(); } }
    private static void observed(String phase, Checked action) { try { action.run(); } catch (AssertionError failure) { throw failure; } catch (Throwable failure) { throw new AssertionError("Owned travel collection observation failed: " + phase + "; type=" + failure.getClass().getName()); } }
    private static void expect(Class<? extends Throwable> type, String message, Checked action) { try { action.run(); } catch (Throwable failure) { check(type.isInstance(failure) && message.equals(failure.getMessage()), "The exact fixed owned failure contract is required"); return; } throw new AssertionError("Expected owned synthetic failure was absent"); }
    private static void check(boolean value, String fixed) { if (!value) throw new AssertionError(fixed); }
    private interface Checked { void run() throws Exception; }
    private record ClientObservation(String operation, int lockPid, int active, boolean transaction) { }

    private record Settings(String url, String database, String username, String password, String marker) {
        String owner() { return database.replace("pb_travel_collection_owned_", "pb_travel_collection_owner_"); }
        static Settings load() throws Exception {
            String value = System.getenv("P17_PG_SETTINGS");
            check(value != null && !value.isBlank(), "P17_PG_SETTINGS is mandatory and never causes a skip");
            Path file = Path.of(value), expected = Path.of("/private/tmp/pawbridge-owned-pg-night-20261020/travel-collection-recovery-1/travel-collection.properties");
            check(file.isAbsolute() && file.normalize().equals(file) && file.equals(expected)
                    && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file), "Only the exact new owned travel settings path is accepted");
            for (Path parent = file.getParent(); parent != null; parent = parent.getParent()) check(!Files.isSymbolicLink(parent), "Settings ancestors may not be symbolic links");
            check(Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS).equals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
                    && ((Number) Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).intValue() == 1
                    && System.getProperty("user.name").equals(Files.getOwner(file).getName())
                    && Files.getOwner(file).equals(Files.getOwner(file.getParent())), "Settings must be single-link0600 under their current owner");
            check(Files.getPosixFilePermissions(file.getParent(), LinkOption.NOFOLLOW_LINKS).equals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)), "New settings directory must retain exact0700 permissions");
            Properties values = new Properties(); try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { values.load(input); }
            check(values.stringPropertyNames().equals(Set.of("jdbcUrl", "username", "password", "database", "marker")), "Exactly five private settings fields are required");
            String database = required(values, "database"), url = required(values, "jdbcUrl"), username = required(values, "username"), marker = required(values, "marker");
            check(database.matches("pb_travel_collection_owned_[0-9a-f]{12}") && url.equals("jdbc:postgresql://127.0.0.1:52620/" + database)
                    && username.equals(database.replace("pb_travel_collection_owned_", "pb_travel_collection_writer_")) && marker.matches("[0-9a-f]{32}"), "Only one new isolated loopback database and its dedicated writer/marker are accepted");
            return new Settings(url, database, username, required(values, "password"), marker);
        }
        private static String required(Properties values, String key) { String value = values.getProperty(key); check(value != null && !value.isBlank(), "Every private owned setting is required"); return value; }
        @Override public String toString() { return "OwnedTravelCollectionSettings[redacted]"; }
    }
}
