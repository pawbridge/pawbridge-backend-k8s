package com.pawbridge.backend.boot.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.internal.apms.infrastructure.*;
import com.pawbridge.backend.animal.internal.apms.infrastructure.client.ApmsApiClient;
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.*;
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Animal;
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import java.io.InputStream;
import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.*;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Fresh P18 only. Ordered contracts retain their synthetic data and Batch history; no reset/delete. */
@Tag("owned-postgresql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(90)
class OwnedPostgresqlApmsCollectionTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final Clock METADATA_CLOCK = Clock.fixed(CLOCK.instant(), ZoneId.systemDefault());
    private static final String KEY = "owned-apms-synthetic-key-only-20261021";
    private static final String JOB = "apmsAnimalSyncJob";
    private static final String FIXTURE_SHA = "55dbd18513400e3037f025f352550c1c10dff2bf2a8b110095f601276e27f8f5";
    private static final Map<String, Set<String>> ACL = privileges();
    private static final List<String> SEQUENCES = List.of("animals_id_seq", "shelters_id_seq", "outbox_events_id_seq",
            "batch_job_seq", "batch_job_execution_seq", "batch_step_execution_seq");
    private static final Map<String, String> KEYS = Map.ofEntries(
            Map.entry("animals", "id"), Map.entry("shelters", "id"), Map.entry("outbox_events", "id"),
            Map.entry("animal_search_documents", "animal_id"), Map.entry("shelter_search_documents", "shelter_id"),
            Map.entry("batch_job_instance", "job_instance_id"), Map.entry("batch_job_execution", "job_execution_id"),
            Map.entry("batch_job_execution_params", "job_execution_id,parameter_name"),
            Map.entry("batch_step_execution", "step_execution_id"), Map.entry("batch_step_execution_context", "step_execution_id"),
            Map.entry("batch_job_execution_context", "job_execution_id"));
    private final Map<ch.qos.logback.classic.Logger, ch.qos.logback.classic.Level> logLevels = new LinkedHashMap<>();
    private final List<FetchObservation> fetches = new CopyOnWriteArrayList<>();
    private final ApmsApiClient fixtureClient = this::fetch;
    private Settings settings;
    private ObservedPool pool;
    private DriverManagerDataSource direct;
    private JdbcTemplate jdbc;
    private AnnotationConfigApplicationContext context;
    private ApmsBatchRunner runner;
    private JobRepository repository;
    private JobExplorer explorer;
    private ApmsBatchExecutionRecovery recovery;
    private boolean nextAllowed, bodyCompleted, unsettled;
    private int nextOrder = 1;
    private volatile int scenario;
    private volatile CountDownLatch fetchEntered, fetchRelease;
    private final AtomicBoolean latchUsed = new AtomicBoolean();
    private String outboxBefore;
    private LocalDate lastSuccessfulEnd;

    @BeforeAll void givenExactFreshP18__whenAssemblingExistingWriter__thenVerifyOwnershipBeforeAnyBusinessWrite() {
        observed("P18 preparation", () -> {
            quiet(); settings = Settings.load();
            try (InputStream input = getClass().getResourceAsStream("/owned-postgresql/apms-collection-owned-schema.sql")) {
                check(input != null && HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())).equals(FIXTURE_SHA),
                        "Only the reviewed frozen P18 fixture SQL may be paired with this class");
            }
            direct = new DriverManagerDataSource(settings.url(), settings.username(), settings.password());
            Properties timeouts = new Properties(); timeouts.setProperty("connectTimeout", "3"); timeouts.setProperty("socketTimeout", "5");
            direct.setConnectionProperties(timeouts);
            var config = new HikariConfig(); config.setJdbcUrl(settings.url()); config.setUsername(settings.username()); config.setPassword(settings.password());
            config.setPoolName("owned-p18-apms-writer"); config.setMaximumPoolSize(3); config.setMinimumIdle(0);
            config.setInitializationFailTimeout(-1); config.setConnectionTimeout(2000); config.setValidationTimeout(1000);
            config.addDataSourceProperty("connectTimeout", "3"); config.addDataSourceProperty("socketTimeout", "5");
            pool = new ObservedPool(config); jdbc = new JdbcTemplate(pool); jdbc.setQueryTimeout(5); verifyTarget();
            for (String table : ACL.keySet()) check(count(table) == 0, "A P18 trial requires empty application and Batch tables; prior history is never adopted");
            outboxBefore = fingerprint("outbox_events"); released();
            context = new AnnotationConfigApplicationContext();
            TestPropertyValues.of("apms.batch.schedule-enabled=true", "apms.batch.workers=1", "apms.batch.step-timeout-seconds=15",
                    "apms.batch.collection-cron=0 0 0 1 1 *", "apms.api.service-key=" + KEY,
                    "apms.api.base-url=https://synthetic.apms.invalid",
                    "apms.sync.max-requests=30", "apms.sync.max-animals=10").applyTo(context);
            context.registerBean("writeDataSource", HikariDataSource.class, () -> pool, bean -> bean.setDestroyMethodName(""));
            context.registerBean("writeJdbc", JdbcTemplate.class, () -> jdbc);
            context.registerBean("writeTransactionManager", JdbcTransactionManager.class, () -> new JdbcTransactionManager(pool));
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules());
            context.addBeanFactoryPostProcessor(factory -> {
                ((org.springframework.beans.factory.support.AbstractBeanDefinition) factory.getBeanDefinition("apmsApiClient")).setDestroyMethodName("");
                factory.registerSingleton("apmsApiClient", fixtureClient);
                factory.registerSingleton("apmsClock", CLOCK);
                factory.registerSingleton("apmsMetadataClock", METADATA_CLOCK);
            });
            context.register(PropertyPlaceholderAutoConfiguration.class, ConfigurationPropertiesAutoConfiguration.class,
                    AnimalApmsCollectionConfiguration.class);
            context.refresh();
            check(context.getBean(ApmsApiClient.class) == fixtureClient,
                    "Only the synthetic provider instance may be used; actual HTTP is forbidden");
            check(context.getBean("apmsClock") == CLOCK && context.getBean("apmsMetadataClock") == METADATA_CLOCK,
                    "The actual plan and metadata seams must use their separate fixed clock zones");
            runner = context.getBean(ApmsBatchRunner.class); repository = context.getBean("apmsJobRepository", JobRepository.class);
            explorer = context.getBean("apmsJobExplorer", JobExplorer.class); recovery = context.getBean(ApmsBatchExecutionRecovery.class);
            check(context.getBean("animalCommandSearch", com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter.class).dataSource() == pool,
                    "The actual shared Animal search writer must use the same test-owned writer");
            released(); nextAllowed = true;
        });
    }

    @BeforeEach void givenPreviousContractSucceeded__whenNextContractStarts__thenFenceOnAnyPriorFailure(TestInfo info) {
        boolean prior = nextAllowed; nextAllowed = false; bodyCompleted = false;
        observed("before contract", () -> {
            check(prior && !unsettled && info.getTestMethod().orElseThrow().getAnnotation(Order.class).value() == nextOrder,
                    "Prior failure, unsettled workers or partial class selection forbids later owned writes"); released();
        });
    }
    @AfterEach void givenContractEnded__whenCheckingResources__thenOnlySuccessfulBodyAndLifecycleOpenNextContract() {
        nextAllowed = false;
        observed("after contract", () -> {
            check(!unsettled, "Unsettled APMS work forbids later observation and pool closure"); released();
            check(settings.marker().equals(jdbc.queryForObject("SELECT marker FROM public.pb_apms_collection_fixture", String.class))
                    && outboxBefore.equals(fingerprint("outbox_events")), "Marker and original Outbox must remain unchanged");
            released(); check(bodyCompleted, "Failed owned contracts retain their history and fence the next write");
            nextOrder++; nextAllowed = true;
        });
    }
    @AfterAll void givenNoUnsettledWorkers__whenClosing__thenCloseContextBeforeOwnedPoolAndRetainHistory() {
        try {
            observed("final close", () -> {
                check(!unsettled, "Keep the owned context/pool while worker settlement is unknown");
                if (context != null && context.isActive()) released();
                if (context != null) context.close();
                if (pool != null) {
                    released(); pool.close(); check(pool.isClosed(), "The explicit test-owned pool must close");
                    System.out.println("APMS_COLLECTION_OWNED callbacks=" + fetches.size() + " callbackActive="
                            + fetches.stream().map(FetchObservation::active).distinct().sorted().toList()
                            + " callbackTX=" + fetches.stream().map(FetchObservation::transaction).distinct().toList()
                            + " peakBorrows=" + pool.peak.get() + " sourceWrites=" + pool.sourceWrites.get()
                            + " searchWrites=" + pool.searchWrites.get() + " rollbackCalls=" + pool.rollbacks.get()
                            + " commitCalls=" + pool.commits.get() + " historyRetained=true");
                }
            });
        } finally { if (!unsettled) logLevels.forEach(ch.qos.logback.classic.Logger::setLevel); }
    }

    @Test @Order(1)
    void givenFreshAndThenExistingApmsAnimals__whenActualRunnerCompletes__thenUpsertAndSearchShareWriterTransactionsWithoutOutbox() {
        contract("normal APMS upsert", () -> {
            scenario = 1; requireCompleted(runner.run());
            long firstId = jdbc.queryForObject("SELECT id FROM pawbridge_animal.animals WHERE apms_desertion_no=?", Long.class, number(1));
            scenario = 2; JobExecution completed = requireCompleted(runner.run());
            ApmsSyncPlan completedPlan = ApmsSyncPlan.from(completed.getJobParameters());
            lastSuccessfulEnd = completedPlan.end();
            ApmsSyncPlan next = futureHistoryProbe();
            check(next.updatedStart().equals(completedPlan.end()) && next.updatedStart().isAfter(completedPlan.updatedStart()),
                    "Persisted PostgreSQL three-step evidence must advance recovery to its completed end outside the rolling overlap");
            check(jdbc.queryForObject("SELECT count(*) FROM pawbridge_animal.animals", Long.class) == 2
                    && jdbc.queryForObject("SELECT id FROM pawbridge_animal.animals WHERE apms_desertion_no=?", Long.class, number(1)) == firstId,
                    "The same APMS identity must update in place while the new identity is inserted once");
            check("Updated owned breed".equals(jdbc.queryForObject("SELECT breed FROM pawbridge_animal.animals WHERE id=?", String.class, firstId))
                    && count("shelters") == 2 && count("animal_search_documents") == 2 && count("shelter_search_documents") == 2,
                    "Actual source upserts and prepared Shelter/search rows must be committed");
            check(jdbc.queryForObject("SELECT count(*) FROM pawbridge_animal.animals a JOIN pawbridge_animal.animal_search_documents d ON d.animal_id=a.id WHERE NOT a.search_dirty AND a.search_revision=d.source_revision", Long.class) == 2,
                    "Every committed Animal search document must match its source revision");
            check(!fetches.isEmpty() && pool.sourceWrites.get() > 0 && pool.searchWrites.get() > 0
                    && pool.samples.stream().anyMatch(s -> s.table().equals("animals"))
                    && pool.samples.stream().anyMatch(s -> s.table().equals("animal_search_documents"))
                    && pool.samples.stream().allMatch(s -> s.transaction() && s.bound() != null && s.manualCommit()),
                    "Actual source and search writes must run under the same bound writer transaction contract");
            check(pool.samples.stream().filter(s -> s.table().equals("animal_search_documents")).allMatch(doc ->
                    pool.samples.stream().anyMatch(source -> source.table().equals("animals") && source.pid() == doc.pid() && source.bound() == doc.bound())),
                    "Actual Animal and search SQL must share a physical PID and the same JDBC transaction holder");
            requireBusinessAndMetadataRollback(completed, firstId);
        });
    }

    @Test @Order(2)
    void givenTwoRowsInOneChunkAndNaturalBreedOverflow__whenRecoveryFails__thenKeepValidItemCommitAndRejectBadItem() {
        contract("natural rollback and fault-tolerant partial recovery", () -> {
            String originalAnimals = "SELECT count(*)::text||':'||md5(COALESCE(string_agg(row_to_json(t)::text,',' ORDER BY id),'')) "
                    + "FROM pawbridge_animal.animals t WHERE apms_desertion_no IN (?,?)";
            String originalDocuments = "SELECT count(*)::text||':'||md5(COALESCE(string_agg(row_to_json(t)::text,',' ORDER BY animal_id),'')) "
                    + "FROM pawbridge_animal.animal_search_documents t WHERE animal_id IN "
                    + "(SELECT id FROM pawbridge_animal.animals WHERE apms_desertion_no IN (?,?))";
            String animalsBefore = jdbc.queryForObject(originalAnimals, String.class, number(1), number(2));
            String documentsBefore = jdbc.queryForObject(originalDocuments, String.class, number(1), number(2));
            check(animalsBefore.startsWith("2:") && documentsBefore.startsWith("2:"),
                    "The two original owned Animals and their projections must exist before the recovery probe");
            int inserted = pool.animalInserts.get(), rolledBack = pool.rollbacks.get();
            scenario = 3; JobExecution failed = runner.run();
            check(failed.getStatus() == BatchStatus.FAILED && failed.getStepExecutions().stream().anyMatch(s -> s.getStepName().equals("shelterPrepStep") && s.getStatus() == BatchStatus.COMPLETED)
                    && failed.getStepExecutions().stream().anyMatch(s -> s.getStepName().equals("apmsAnimalSyncStep") && s.getStatus() == BatchStatus.FAILED),
                    "Preparation commits independently before the natural Animal column constraint failure");
            check(pool.animalInserts.get() > inserted && pool.rollbacks.get() > rolledBack,
                    "A real first Animal insert must execute before the writer transaction rolls back");
            check(jdbc.queryForObject("SELECT count(*) FROM pawbridge_animal.animals a JOIN pawbridge_animal.animal_search_documents d ON d.animal_id=a.id "
                    + "WHERE a.apms_desertion_no=? AND NOT a.search_dirty AND a.search_revision=d.source_revision", Long.class, number(3)) == 1
                    && count("animals") == 3 && count("animal_search_documents") == 3,
                    "Fault-tolerant recovery may commit the valid item and its matching projection after the first chunk rollback");
            check(jdbc.queryForObject("SELECT count(*) FROM pawbridge_animal.animals WHERE apms_desertion_no=?", Long.class, number(4)) == 0
                    && jdbc.queryForObject("SELECT count(*) FROM pawbridge_animal.animal_search_documents d LEFT JOIN pawbridge_animal.animals a ON a.id=d.animal_id "
                    + "WHERE a.id IS NULL OR a.apms_desertion_no=?", Long.class, number(4)) == 0,
                    "The rejected item must leave neither an Animal nor a projection or orphan document");
            check(count("shelters") == 3 && count("shelter_search_documents") == 3
                    && animalsBefore.equals(jdbc.queryForObject(originalAnimals, String.class, number(1), number(2)))
                    && documentsBefore.equals(jdbc.queryForObject(originalDocuments, String.class, number(1), number(2))),
                    "The prepared Shelter and partial valid-item commit must preserve both earlier Animal/search snapshots");
            check(ApmsQueryProgress.verifiedResults(failed, ApmsSyncPlan.from(failed.getJobParameters())).isEmpty(),
                    "Failed data steps must not become a verified successful query checkpoint");
            ApmsSyncPlan failedPlan = ApmsSyncPlan.from(failed.getJobParameters());
            ApmsSyncPlan gap = futureHistoryProbe();
            check(gap.updatedStart().equals(failedPlan.updatedStart()) && gap.updatedStart().isBefore(lastSuccessfulEnd)
                    && !gap.recentStart().isAfter(failedPlan.recentStart()),
                    "An unverified failed interval must retain its old update and intake gaps despite earlier completed metadata");
        });
    }

    @Test @Order(3)
    void givenTwoPhysicalSessionsAndSlotKeyOne__whenRunnerIsBusy__thenNoFetchOrMetadataWriteAndPhysicalCloseReleasesSlot() {
        contract("physical APMS slot", () -> {
            Map<String,String> before = allFingerprint(); int calls = fetches.size();
            try (Connection owner = direct.getConnection(); Connection contender = direct.getConnection()) {
                check(pid(owner) != pid(contender) && lock(owner, "pg_try_advisory_lock"), "Two distinct PostgreSQL PIDs must contend for APMS key1");
                owner.setAutoCommit(false); pid(owner); owner.rollback();
                check(owns(owner) && !lock(contender, "pg_try_advisory_lock"), "Rollback must retain the session slot"); owner.setAutoCommit(true);
                expect(ApmsBatchRunner.AlreadyRunningException.class, runner::run);
                check(fetches.size() == calls && before.equals(allFingerprint()), "BUSY must leave all eleven tables and Batch metadata unchanged");
                owner.close(); check(lock(contender, "pg_try_advisory_lock") && lock(contender, "pg_advisory_unlock"),
                        "Physical owner close must transfer the actual slot to the second PID");
            }
        });
    }

    @Test @Order(4)
    void givenRecentJobAndNewerStepHeartbeat__whenRecoveryCrossesExactTenMinuteCutoff__thenRejectRecentAndFailOnlyStaleMetadata() {
        contract("recent and stale metadata", () -> {
            JobExecution execution = repository.createJobExecution(JOB, new JobParametersBuilder().addString("ownedRecovery", UUID.randomUUID().toString()).toJobParameters());
            execution.setStatus(BatchStatus.STARTED); repository.update(execution);
            StepExecution step = new StepExecution("ownedRecoveryStep", execution); step.setStatus(BatchStatus.STARTED); repository.add(step);
            LocalDateTime now = LocalDateTime.now(METADATA_CLOCK), cutoff = now.minusMinutes(10);
            timestamp(execution.getId(), step.getId(), now.minusMinutes(1), now.minusMinutes(1));
            Map<String,String> recent = allFingerprint(); int calls = fetches.size();
            expect(ApmsBatchRunner.UnavailableException.class, runner::run);
            check(calls == fetches.size() && recent.equals(allFingerprint()), "Recent metadata rejection must create no Job/provider/business writes"); requireSlotAvailable();
            timestamp(execution.getId(), step.getId(), cutoff.minusMinutes(1), now.minusMinutes(1));
            Map<String,String> newerStep = allFingerprint(); expect(ApmsBatchRunner.UnavailableException.class, runner::run);
            check(newerStep.equals(allFingerprint()) && calls == fetches.size(), "A newer Step heartbeat must protect an old-looking Job");
            timestamp(execution.getId(), step.getId(), cutoff.minusMinutes(1), cutoff);
            Map<String,String> data = businessFingerprint();
            try (Connection slot = direct.getConnection()) {
                check(lock(slot, "pg_try_advisory_lock"), "Recovery may run only while this test owns the same APMS slot");
                try { check(recovery.recoverOrReject(JOB) == 1, "The exact ten-minute cutoff is stale and must close one owned execution"); }
                finally { check(lock(slot, "pg_advisory_unlock"), "The explicit recovery slot must release"); }
            }
            JobExecution recovered = explorer.getJobExecution(execution.getId());
            check(recovered != null && recovered.getStatus() == BatchStatus.FAILED && recovered.getEndTime() != null
                    && recovered.getStepExecutions().stream().allMatch(s -> s.getStatus() == BatchStatus.FAILED && s.getEndTime() != null)
                    && recovered.getExitStatus().getExitDescription().contains("Execution process disappeared and the APMS named lock was released"),
                    "Only fixed FAILED/end metadata may replace the stale STARTED execution");
            check(data.equals(businessFingerprint()) && calls == fetches.size(), "Metadata recovery must neither refetch nor change committed source/search/Outbox");
        });
    }

    @Test @Order(5)
    void givenInflightActualSchedule__whenNormalStopIsRequested__thenWaitForSynchronousJobAndSlotReleaseBeforeClosing() {
        contract("actual schedule normal stop", () -> {
            scenario = 4; fetchEntered = new CountDownLatch(1); fetchRelease = new CountDownLatch(1); latchUsed.set(false);
            ApmsCollectionSchedule schedule = new ApmsCollectionSchedule(runner, "* * * * * *");
            CountDownLatch stopped = new CountDownLatch(1); AtomicReference<Throwable> problem = new AtomicReference<>();
            Thread closer = new Thread(() -> { try { schedule.stop(); } catch (Throwable failure) { problem.set(failure); } finally { stopped.countDown(); } }, "owned-apms-stop");
            closer.setDaemon(true); unsettled = true; ThreadPoolTaskScheduler scheduler = null;
            try {
                schedule.start(); scheduler = (ThreadPoolTaskScheduler) ReflectionTestUtils.getField(schedule, "scheduler");
                check(scheduler != null && fetchEntered.await(8, TimeUnit.SECONDS), "The real scheduled callback must enter the synthetic provider");
                closer.start(); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!scheduler.getScheduledThreadPoolExecutor().isShutdown() && System.nanoTime() < deadline) stopped.await(10, TimeUnit.MILLISECONDS);
                check(scheduler.getScheduledThreadPoolExecutor().isShutdown() && !stopped.await(150, TimeUnit.MILLISECONDS),
                        "Normal stop must forbid future ticks and wait while the synchronous job holds its slot");
            } finally {
                fetchRelease.countDown(); if (closer.getState() == Thread.State.NEW) closer.start();
                boolean finished = awaitCloser(closer, 8);
                unsettled = !(finished && stopped.getCount() == 0 && scheduler != null && scheduler.getScheduledThreadPoolExecutor().isTerminated());
                check(!unsettled, "Unconfirmed scheduler/closer settlement fences later database access and pool closure");
            }
            check(problem.get() == null && !schedule.isRunning(), "Normal stop must finish without an execution failure");
            released(); requireSlotAvailable();
            check(jdbc.queryForObject("SELECT count(*) FROM pawbridge_animal.animals WHERE apms_desertion_no=?", Long.class, number(5)) == 1,
                    "The in-flight scheduled job must finish its actual Animal commit before stop returns");
            check(explorer.getJobExecutions(explorer.getJobInstances(JOB, 0, 1).getFirst()).stream().allMatch(e -> e.getStatus() == BatchStatus.COMPLETED),
                    "The scheduled run must complete rather than merely terminate its worker");
        });
    }

    private ApmsRootResponse<ApmsAnimal> fetch(String key, int page, int size, String from, String through, String kind,
            String state, String type, String updatedFrom, String updatedThrough) {
        try {
            boolean tx = TransactionSynchronizationManager.isActualTransactionActive(); int active = pool.getHikariPoolMXBean().getActiveConnections();
            fetches.add(new FetchObservation(active, tx));
            check(KEY.equals(key) && !tx && active == 1 && pool.connections.get() == 1 && page == 1 && size == 1000,
                    "Only the raw APMS slot may remain borrowed during bounded synthetic fetches");
            try (Connection probe = direct.getConnection()) { check(!lock(probe, "pg_try_advisory_lock"), "The actual runner must retain APMS key1 through HTTP"); }
            if (scenario == 4 && latchUsed.compareAndSet(false, true)) {
                fetchEntered.countDown(); check(fetchRelease.await(8, TimeUnit.SECONDS), "The owned provider latch must release within its bound");
            }
            List<ApmsAnimal> rows = switch (scenario) {
                case 1 -> List.of(animal(1, 1, "Initial owned breed"));
                case 2 -> List.of(animal(1, 1, "Updated owned breed"), animal(2, 2, "New owned breed"));
                case 3 -> List.of(animal(3, 3, "First rollback breed"), animal(4, 3, "x".repeat(101)));
                case 4 -> List.of(animal(5, 4, "Scheduled owned breed"));
                default -> throw new AssertionError("No synthetic provider scenario is active");
            };
            rows = rows.stream().filter(a -> inside(a.getHappenDt(), from, through)
                    && (updatedFrom == null || inside("20261021", updatedFrom, updatedThrough))).toList();
            return new ApmsRootResponse<>(new ApmsResponse<>(new ApmsHeader(null, "00", "owned", null),
                    new ApmsBody<>(new ApmsItems<>(rows), Integer.toString(size), Integer.toString(page), Integer.toString(rows.size()))));
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError("Owned provider wait was interrupted"); }
        catch (SQLException failure) { throw new AssertionError("Owned provider slot observation failed"); }
    }
    private static ApmsAnimal animal(int id, int care, String breed) {
        return ApmsAnimal.builder().desertionNo(number(id)).noticeNo("OWNED-P18-" + id).upKindCd("417000")
                .kindNm(breed).age("2023(년생)").weight("5(Kg)").colorCd("brown").sexCd("M").neuterYn("Y")
                .specialMark("Owned APMS fixture " + id).processState("보호중").noticeSdt("20261020").noticeEdt("20261030")
                .updTm("2026-10-21 00:00:00").happenDt("20261020").happenPlace("Owned place")
                .popfile1("https://fixture.invalid/p18/one.jpg").careRegNo("OWNED-P18-CARE-" + care).careNm("Owned shelter " + care)
                .careTel("02-000-0000").careAddr("Owned synthetic address").orgNm("Owned agency").chargeNm("Owned contact").build();
    }
    private static String number(int id) { return "99000000000000" + id; }
    private static boolean inside(String value, String from, String to) { return value.compareTo(from) >= 0 && value.compareTo(to) <= 0; }
    private JobExecution requireCompleted(JobExecution returned) {
        JobExecution execution = explorer.getJobExecution(returned.getId());
        check(execution != null && execution.getStatus() == BatchStatus.COMPLETED && execution.getStepExecutions().size() == 3
                && execution.getStepExecutions().stream().map(StepExecution::getStepName).collect(java.util.stream.Collectors.toSet())
                    .equals(Set.of("shelterPrepStep", "apmsAnimalSyncStep", "apmsCollectionVerificationStep"))
                && execution.getStepExecutions().stream().allMatch(s -> s.getStatus() == BatchStatus.COMPLETED && s.getSkipCount() == 0)
                && "postgresql".equals(execution.getExecutionContext().getString(ApmsQueryProgress.BACKEND_KEY, ""))
                && execution.getExecutionContext().getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT, -1) == 0,
                "The actual three-step job must complete without skipped or incomplete collection");
        var results = ApmsQueryProgress.verifiedResults(execution, ApmsSyncPlan.from(execution.getJobParameters()));
        check(!results.isEmpty() && results.stream().allMatch(ApmsQueryProgress.Result::complete),
                "Completed data and verification steps must provide durable per-query evidence");
        return execution;
    }
    private void requireBusinessAndMetadataRollback(JobExecution completed, long animalId) {
        long stepId = completed.getStepExecutions().stream().filter(s -> s.getStepName().equals("apmsAnimalSyncStep"))
                .findFirst().orElseThrow().getId();
        StepExecution step = explorer.getStepExecution(completed.getId(), stepId);
        check(step != null, "The completed owned Step must be reloaded before its rollback probe");
        var manager = context.getBean("animalCommandJpaTransactionManager", JpaTransactionManager.class);
        var entityManager = context.getBean("animalCommandEntityManager", EntityManager.class);
        var search = context.getBean("animalCommandSearch", SearchDocumentWriter.class);
        Map<String,String> before = allFingerprint();
        int committed = pool.commits.get(), rolledBack = pool.rollbacks.get();
        int contexts = pool.stepContextUpdates.get(), steps = pool.stepUpdates.get();
        RuntimeException forced = new IllegalStateException("Owned APMS metadata atomicity rollback");
        AtomicBoolean reached = new AtomicBoolean();
        try {
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                check(manager.getDataSource() == pool && TransactionSynchronizationManager.isActualTransactionActive()
                        && !TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                        "The atomicity probe must use the actual Animal JPA writer transaction");
                Animal animal = entityManager.find(Animal.class, animalId);
                check(animal != null && !"Owned P18 metadata rollback".equals(animal.getDescription()),
                        "Only an existing owned Animal may receive the temporary rollback mutation");
                animal.updateDescription("Owned P18 metadata rollback");
                entityManager.flush();
                search.animal(animalId);
                step.getExecutionContext().putString("owned.p18.atomicity", "rollback-only");
                repository.updateExecutionContext(step);
                step.setWriteCount(step.getWriteCount() + 1);
                repository.update(step);
                check(pool.stepContextUpdates.get() > contexts && pool.stepUpdates.get() > steps,
                        "Both actual JobRepository execution-context and Step updates must reach PostgreSQL");
                for (String table : List.of("animals", "animal_search_documents", "batch_step_execution_context", "batch_step_execution"))
                    check(!before.get(table).equals(fingerprint(table)),
                            "Business, projection and both metadata mutations must be visible before the forced rollback");
                reached.set(true);
                throw forced;
            });
            throw new AssertionError("The atomicity probe must end in its exact forced rollback");
        } catch (RuntimeException failure) {
            check(reached.get() && failure == forced,
                    "Only the designated RuntimeException after both real metadata updates is the rollback evidence");
        }
        check(pool.commits.get() == committed && pool.rollbacks.get() > rolledBack,
                "Joining Batch metadata must add no physical commit and the outer JPA transaction must roll back");
        check(before.equals(allFingerprint()),
                "The forced JPA rollback must preserve all eleven business, projection and Batch table snapshots");
        released();
    }
    private ApmsSyncPlan futureHistoryProbe() {
        // The source intentionally rechecks thirty days. Move only this read-only observation beyond
        // that overlap; do not alter the real runner's Clock or launch another collection.
        Clock later = Clock.fixed(CLOCK.instant().plus(Duration.ofDays(45)), CLOCK.getZone());
        return new ApmsSyncPlanFactory(context.getBean("animalCommandAnimals",
                com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository.class), explorer, later).create(JOB);
    }
    private void timestamp(long execution, long step, LocalDateTime jobTime, LocalDateTime stepTime) {
        new TransactionTemplate(new JdbcTransactionManager(pool)).executeWithoutResult(s -> {
            check(jdbc.update("UPDATE pawbridge_animal.batch_job_execution SET last_updated=?,start_time=? WHERE job_execution_id=?", Timestamp.valueOf(jobTime), Timestamp.valueOf(jobTime), execution) == 1,
                    "Only the preregistered recovery execution may receive a synthetic heartbeat");
            check(jdbc.update("UPDATE pawbridge_animal.batch_step_execution SET last_updated=?,start_time=? WHERE step_execution_id=? AND job_execution_id=?", Timestamp.valueOf(stepTime), Timestamp.valueOf(stepTime), step, execution) == 1,
                    "Only its exact owned Step may receive a synthetic heartbeat");
        });
    }
    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM pawbridge_animal." + table, Long.class); }
    private String fingerprint(String table) { return jdbc.queryForObject("SELECT count(*)::text||':'||md5(COALESCE(string_agg(row_to_json(t)::text,',' ORDER BY " + KEYS.get(table) + "),'')) FROM pawbridge_animal." + table + " t", String.class); }
    private Map<String,String> allFingerprint() { Map<String,String> rows = new LinkedHashMap<>(); KEYS.keySet().stream().sorted().forEach(t -> rows.put(t, fingerprint(t))); return rows; }
    private Map<String,String> businessFingerprint() { Map<String,String> rows = new LinkedHashMap<>(); for (String t : List.of("animals", "shelters", "outbox_events", "animal_search_documents", "shelter_search_documents")) rows.put(t, fingerprint(t)); return rows; }
    private void released() {
        check(!unsettled, "Unconfirmed APMS work forbids subsequent access or shutdown");
        if (context != null && context.isActive()) {
            var executor = context.getBean("apmsBatchTaskExecutor", AnimalApmsCollectionConfiguration.ApmsChunkExecutor.class);
            if (executor.getActiveCount() != 0 || !executor.getThreadPoolExecutor().getQueue().isEmpty()) { unsettled = true; throw new AssertionError("APMS chunks remain unsettled; preserve context and pool"); }
        }
        boolean returned = !TransactionSynchronizationManager.isActualTransactionActive() && !TransactionSynchronizationManager.hasResource(pool)
                && (pool == null || (pool.connections.get() == 0 && pool.statements.get() == 0 && pool.results.get() == 0
                && pool.getHikariPoolMXBean().getActiveConnections() == 0 && pool.getHikariPoolMXBean().getThreadsAwaitingConnection() == 0));
        if (!returned) unsettled = true;
        check(returned, "Unreturned JDBC resources fence later work and preserve the owned context/pool");
    }
    private static int pid(Connection connection) throws SQLException { try (Statement s = connection.createStatement()) { s.setQueryTimeout(3); try (ResultSet r = s.executeQuery("SELECT pg_backend_pid()")) { check(r.next(), "A physical PID is required"); return r.getInt(1); } } }
    private static boolean lock(Connection connection, String function) throws SQLException {
        check(Set.of("pg_try_advisory_lock", "pg_advisory_unlock").contains(function), "Only exact APMS slot operations are permitted");
        try (PreparedStatement s = connection.prepareStatement("SELECT " + function + "(?,?)")) { s.setQueryTimeout(3); s.setInt(1, 0x50415742); s.setInt(2, 1); try (ResultSet r = s.executeQuery()) { check(r.next(), "The APMS slot result is required"); return r.getBoolean(1); } }
    }
    private static boolean owns(Connection c) throws SQLException { try (PreparedStatement s = c.prepareStatement("SELECT EXISTS(SELECT 1 FROM pg_locks WHERE locktype='advisory' AND pid=pg_backend_pid() AND classid=? AND objid=1 AND objsubid=2 AND granted)")) { s.setInt(1, 0x50415742); s.setQueryTimeout(3); try (ResultSet r = s.executeQuery()) { check(r.next(), "Slot ownership is observable"); return r.getBoolean(1); } } }
    private void requireSlotAvailable() throws Exception { released(); try (Connection c = direct.getConnection()) { check(lock(c, "pg_try_advisory_lock") && lock(c, "pg_advisory_unlock"), "A fresh physical session must acquire and release APMS key1"); } }
    private static boolean awaitCloser(Thread thread, int seconds) {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds); boolean interrupted = false;
        try { while (thread.isAlive()) { long left = until - System.nanoTime(); if (left <= 0) return false; try { TimeUnit.NANOSECONDS.timedJoin(thread, left); } catch (InterruptedException e) { interrupted = true; } } return thread.getState() == Thread.State.TERMINATED; }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    private void contract(String phase, Checked body) { observed(phase, () -> { body.run(); bodyCompleted = true; }); }
    private static void observed(String phase, Checked body) { try { body.run(); } catch (AssertionError e) { throw e; } catch (Throwable e) { throw new AssertionError("Owned APMS observation failed: " + phase + "; type=" + e.getClass().getName()); } }
    private static void expect(Class<? extends Throwable> type, Checked body) { try { body.run(); } catch (Throwable e) { check(type.isInstance(e), "The exact owned refusal type is required"); return; } throw new AssertionError("Expected APMS refusal was absent"); }
    private static void check(boolean value, String fixed) { if (!value) throw new AssertionError(fixed); }
    private interface Checked { void run() throws Exception; }
    private record FetchObservation(int active, boolean transaction) { }
    private record WriteSample(String table, int pid, Object bound, boolean transaction, boolean manualCommit) { }

    private void verifyTarget() {
        Map<String,Object> id = jdbc.queryForMap("SELECT current_database() db,current_user actor,session_user session,current_setting('server_version_num') version,(SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()) owner");
        check(settings.database().equals(id.get("db")) && settings.username().equals(id.get("actor")) && settings.username().equals(id.get("session"))
                && settings.owner().equals(id.get("owner")) && "170011".equals(id.get("version")), "Only the new owned PostgreSQL17.11 target and distinct owner/writer are accepted");
        check(settings.marker().equals(jdbc.queryForObject("SELECT marker FROM public.pb_apms_collection_fixture", String.class)), "The exact P18 marker is mandatory");
        check(Boolean.TRUE.equals(jdbc.queryForObject("SELECT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls AND NOT rolinherit AND NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=pg_roles.oid) FROM pg_roles WHERE rolname=current_user", Boolean.class)), "The writer must not inherit administrative or owner capabilities");
        check(Boolean.TRUE.equals(jdbc.queryForObject("SELECT NOT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls AND NOT rolinherit FROM pg_roles WHERE rolname=?", Boolean.class, settings.owner()))
                && Boolean.FALSE.equals(jdbc.queryForObject("SELECT pg_has_role(current_user,?,'MEMBER')", Boolean.class, settings.owner())), "The separate owner must remain NOLOGIN and inaccessible");
        check(Boolean.FALSE.equals(jdbc.queryForObject("SELECT has_database_privilege(current_user,current_database(),'CREATE,TEMP')", Boolean.class)), "Runtime database/TEMP DDL is forbidden");
        for (String schema : List.of("public", "pawbridge_animal")) check(Boolean.TRUE.equals(jdbc.queryForObject("SELECT has_schema_privilege(current_user,?,'USAGE') AND NOT has_schema_privilege(current_user,?,'CREATE')", Boolean.class, schema, schema)), "Runtime schema creation is forbidden");
        check(jdbc.query("SELECT tablename FROM pg_tables WHERE schemaname='pawbridge_animal' ORDER BY tablename", (r,n) -> r.getString(1)).equals(ACL.keySet().stream().sorted().toList()), "Only the exact five business and six Batch tables are accepted");
        check(jdbc.queryForObject("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname !~ '^pg_' AND n.nspname<>'information_schema' AND c.relkind IN('r','p','v','m','f')", Long.class) == 12, "Only eleven application tables plus the ownership marker may exist");
        for (var table : ACL.entrySet()) for (String right : List.of("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER")) check(Boolean.valueOf(table.getValue().contains(right)).equals(jdbc.queryForObject("SELECT has_table_privilege(current_user,?,?)", Boolean.class, "pawbridge_animal." + table.getKey(), right)), "Every runtime table ACL must match the reviewed fixture");
        for (var table : ACL.entrySet()) for (String right : List.of("INSERT", "UPDATE", "REFERENCES")) if (!table.getValue().contains(right))
            check(Boolean.FALSE.equals(jdbc.queryForObject("SELECT has_any_column_privilege(current_user,?,?)", Boolean.class, "pawbridge_animal." + table.getKey(), right)),
                    "Column grants must not bypass denied table privileges");
        check(Boolean.TRUE.equals(jdbc.queryForObject("SELECT has_table_privilege(current_user,'public.pb_apms_collection_fixture','SELECT') AND NOT has_table_privilege(current_user,'public.pb_apms_collection_fixture','INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')", Boolean.class)), "Marker must remain SELECT-only");
        check(jdbc.query("SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='pawbridge_animal' AND c.relkind='S' ORDER BY c.relname", (r,n) -> r.getString(1)).equals(SEQUENCES.stream().sorted().toList()), "Only three identity and three Batch sequences may exist");
        for (String sequence : SEQUENCES) check(Boolean.valueOf(!sequence.equals("outbox_events_id_seq")).equals(jdbc.queryForObject("SELECT has_sequence_privilege(current_user,?,'USAGE')", Boolean.class, "pawbridge_animal." + sequence))
                && Boolean.FALSE.equals(jdbc.queryForObject("SELECT has_sequence_privilege(current_user,?,'SELECT,UPDATE')", Boolean.class, "pawbridge_animal." + sequence)), "Only necessary nextval USAGE is allowed; Outbox has no write sequence privilege");
        check(jdbc.queryForObject("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN('public','pawbridge_animal') AND c.relkind IN('r','S') AND pg_get_userbyid(c.relowner)<>?", Long.class, settings.owner()) == 0, "All fixture objects must retain the separate owner");
    }
    private static Map<String,Set<String>> privileges() {
        Map<String,Set<String>> v = new LinkedHashMap<>();
        for (String t : List.of("animals", "shelters", "animal_search_documents", "shelter_search_documents")) v.put(t, Set.of("SELECT", "INSERT", "UPDATE"));
        v.put("outbox_events", Set.of("SELECT"));
        for (String t : List.of("batch_job_instance", "batch_job_execution_params")) v.put(t, Set.of("SELECT", "INSERT"));
        for (String t : List.of("batch_job_execution", "batch_step_execution", "batch_job_execution_context", "batch_step_execution_context")) v.put(t, Set.of("SELECT", "INSERT", "UPDATE"));
        return Map.copyOf(v);
    }
    private void quiet() { for (String name : List.of("com.zaxxer.hikari", "org.springframework.jdbc", "org.springframework.batch", "org.springframework.context", "org.springframework.orm.jpa", "org.hibernate", "com.pawbridge.backend.animal.internal.apms")) { var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(name); logLevels.put(logger, logger.getLevel()); logger.setLevel(ch.qos.logback.classic.Level.OFF); } }

    /** Actual wrappers count borrows, not physical connection creations; no SQL or values are emitted. */
    private static final class ObservedPool extends HikariDataSource {
        final AtomicInteger connections = new AtomicInteger(), statements = new AtomicInteger(), results = new AtomicInteger(), peak = new AtomicInteger();
        final AtomicInteger commits = new AtomicInteger(), rollbacks = new AtomicInteger(), animalInserts = new AtomicInteger(), sourceWrites = new AtomicInteger(), searchWrites = new AtomicInteger();
        final AtomicInteger stepContextUpdates = new AtomicInteger(), stepUpdates = new AtomicInteger();
        final List<WriteSample> samples = new CopyOnWriteArrayList<>();
        ObservedPool(HikariConfig config) { super(config); }
        @Override public Connection getConnection() throws SQLException {
            Connection c = super.getConnection(); int backend; try { backend = pid(c); } catch (SQLException | RuntimeException | Error e) { c.close(); throw e; }
            int active = connections.incrementAndGet(); peak.accumulateAndGet(active, Math::max); AtomicBoolean closed = new AtomicBoolean(); List<Tracked> children = new CopyOnWriteArrayList<>();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (p,m,a) -> {
                if (m.getName().equals("close")) { if (closed.compareAndSet(false,true)) try { return call(c,m,a); } finally { children.forEach(Tracked::closed); connections.decrementAndGet(); } return null; }
                if (m.getName().equals("rollback")) rollbacks.incrementAndGet();
                Object v = call(c,m,a);
                if (m.getName().equals("commit")) commits.incrementAndGet();
                if (v instanceof Statement s) { var t = new Tracked(s, a != null && a.length > 0 && a[0] instanceof String sql ? sql.toLowerCase(Locale.ROOT) : "", backend, c); children.add(t); return t.proxy(); } return v;
            });
        }
        private final class Tracked {
            final Statement statement; final String sql; final int backend; final Connection connection;
            final AtomicBoolean closed = new AtomicBoolean(); final List<AtomicBoolean> cursors = new CopyOnWriteArrayList<>();
            Tracked(Statement s,String sql,int backend,Connection c) { statement=s;this.sql=sql;this.backend=backend;connection=c;statements.incrementAndGet(); }
            Object proxy() { return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class}, (p,m,a) -> {
                if (m.getName().equals("close")) try { return call(statement,m,a); } finally { closed(); }
                if (m.getName().startsWith("execute") && (sql.startsWith("insert ") || sql.startsWith("update "))) {
                    for (String table : List.of("animal_search_documents", "shelter_search_documents", "animals", "shelters")) if (sql.contains("pawbridge_animal." + table)) {
                        samples.add(new WriteSample(table,backend,TransactionSynchronizationManager.getResource(ObservedPool.this),TransactionSynchronizationManager.isActualTransactionActive(),!connection.getAutoCommit()));
                        if (table.endsWith("_documents")) searchWrites.incrementAndGet(); else sourceWrites.incrementAndGet(); break;
                    }
                }
                Object v = call(statement,m,a);
                if (m.getName().startsWith("execute") && sql.startsWith("insert into pawbridge_animal.animals")) animalInserts.incrementAndGet();
                if (m.getName().startsWith("execute") && sql.startsWith("update pawbridge_animal.batch_step_execution_context\nset ")) stepContextUpdates.incrementAndGet();
                if (m.getName().startsWith("execute") && sql.startsWith("update pawbridge_animal.batch_step_execution\nset ")) stepUpdates.incrementAndGet();
                if (!(v instanceof ResultSet row)) return v;
                AtomicBoolean done = new AtomicBoolean(); cursors.add(done); results.incrementAndGet();
                return Proxy.newProxyInstance(ResultSet.class.getClassLoader(),new Class<?>[]{ResultSet.class},(rp,rm,ra)-> { if (rm.getName().equals("close")) try { return call(row,rm,ra); } finally { closeCursor(done); } return call(row,rm,ra); });
            }); }
            void closeCursor(AtomicBoolean c) { if (c.compareAndSet(false,true)) results.decrementAndGet(); }
            void closed() { if (closed.compareAndSet(false,true)) { cursors.forEach(this::closeCursor); statements.decrementAndGet(); } }
        }
    }
    private static Object call(Object target, Method method, Object[] args) throws Throwable { try { return method.invoke(target,args); } catch (InvocationTargetException e) { throw e.getCause(); } }
    private record Settings(String url,String database,String username,String password,String marker) {
        String owner() { return database.replace("pb_apms_collection_owned_", "pb_apms_collection_owner_"); }
        static Settings load() throws Exception {
            String path = System.getenv("P18_PG_SETTINGS"); check(path != null && !path.isBlank(), "P18_PG_SETTINGS is required without fallback or skip");
            Path file = Path.of(path), expected = Path.of("/private/tmp/pawbridge-owned-pg-night-20261020/apms-collection-recovery-2/apms-collection.properties");
            check(file.isAbsolute() && file.normalize().equals(file) && file.equals(expected) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file), "Only the exact new P18 regular settings file is accepted");
            for (Path parent=file.getParent();parent!=null;parent=parent.getParent()) check(!Files.isSymbolicLink(parent), "Settings ancestors may not be symlinks");
            check(Files.getPosixFilePermissions(file,LinkOption.NOFOLLOW_LINKS).equals(Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE))
                    && ((Number)Files.getAttribute(file,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).intValue()==1 && System.getProperty("user.name").equals(Files.getOwner(file).getName())
                    && Files.getOwner(file).equals(Files.getOwner(file.getParent())), "P18 settings must be current-owner single-link0600");
            check(Files.getPosixFilePermissions(file.getParent()).equals(Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE,PosixFilePermission.OWNER_EXECUTE)), "The new P18 private directory must remain0700");
            Properties v = new Properties(); try(InputStream in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) { v.load(in); }
            check(v.stringPropertyNames().equals(Set.of("jdbcUrl","database","username","password","marker")), "Exactly five private P18 settings are accepted");
            String db=required(v,"database"),url=required(v,"jdbcUrl"),user=required(v,"username"),marker=required(v,"marker");
            check(db.matches("pb_apms_collection_owned_[0-9a-f]{12}") && url.equals("jdbc:postgresql://127.0.0.1:52620/"+db)
                    && user.equals(db.replace("pb_apms_collection_owned_","pb_apms_collection_writer_")) && marker.matches("[0-9a-f]{32}"), "P18 target/role/marker must identify only its new loopback fixture");
            return new Settings(url,db,user,required(v,"password"),marker);
        }
        private static String required(Properties v,String key) { String value=v.getProperty(key);check(value!=null&&!value.isBlank(),"Every P18 setting is mandatory");return value; }
        @Override public String toString() { return "OwnedApmsSettings[redacted]"; }
    }
}
