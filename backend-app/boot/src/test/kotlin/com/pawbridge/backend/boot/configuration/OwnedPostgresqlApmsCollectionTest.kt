package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsAnimalSnapshot
import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsBatchExecutionRecovery
import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsBatchRunner
import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsQueryProgress
import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsSyncPlan
import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsSyncPlanFactory
import com.pawbridge.backend.animal.internal.apms.infrastructure.client.ApmsApiClient
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsAnimal
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsBody
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsHeader
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsItems
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsResponse
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsRootResponse
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Animal
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter
import com.pawbridge.backend.support.TestFaker
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.slf4j.LoggerFactory
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.StepExecution
import org.springframework.batch.core.explore.JobExplorer
import org.springframework.batch.core.repository.JobRepository
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.beans.factory.support.AbstractBeanDefinition
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.test.util.TestPropertyValues
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.io.InputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.HexFormat
import java.util.Locale
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier

/** Fresh P18 only. Ordered contracts retain their synthetic data and Batch history; no reset/delete. */
@Tag("owned-postgresql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(90)
class OwnedPostgresqlApmsCollectionTest {
    private val logLevels: MutableMap<ch.qos.logback.classic.Logger, ch.qos.logback.classic.Level?> = LinkedHashMap()
    private val fetches: MutableList<FetchObservation> = CopyOnWriteArrayList()
    private val fixtureClient: ApmsApiClient = ApmsApiClient { key, page, size, from, through, kind, state, type, updatedFrom, updatedThrough ->
        fetch(key, page, size, from, through, kind, state, type, updatedFrom, updatedThrough)
    }
    private lateinit var settings: Settings
    private var pool: ObservedPool? = null
    private lateinit var direct: DriverManagerDataSource
    private lateinit var jdbc: JdbcTemplate
    private var context: AnnotationConfigApplicationContext? = null
    private lateinit var runner: ApmsBatchRunner
    private lateinit var repository: JobRepository
    private lateinit var explorer: JobExplorer
    private lateinit var recovery: ApmsBatchExecutionRecovery
    private var nextAllowed = false
    private var bodyCompleted = false
    private var unsettled = false
    private var nextOrder = 1

    @Volatile
    private var scenario = 0

    @Volatile
    private lateinit var fetchEntered: CountDownLatch

    @Volatile
    private lateinit var fetchRelease: CountDownLatch
    private val latchUsed = AtomicBoolean()
    private lateinit var outboxBefore: String
    private var lastSuccessfulEnd: LocalDate? = null

    private fun pool(): ObservedPool = pool!!

    private fun context(): AnnotationConfigApplicationContext = context!!

    @BeforeAll
    fun givenExactFreshP18__whenAssemblingExistingWriter__thenVerifyOwnershipBeforeAnyBusinessWrite() {
        observed("P18 preparation") {
            quiet()
            settings = Settings.load()
            javaClass.getResourceAsStream("/owned-postgresql/apms-collection-owned-schema.sql").use { input ->
                check(
                    input != null &&
                        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())) == FIXTURE_SHA,
                    "Only the reviewed frozen P18 fixture SQL may be paired with this class",
                )
            }
            direct = DriverManagerDataSource(settings.url, settings.username, settings.password)
            val timeouts = Properties()
            timeouts.setProperty("connectTimeout", "3")
            timeouts.setProperty("socketTimeout", "5")
            direct.setConnectionProperties(timeouts)
            val config = HikariConfig()
            config.jdbcUrl = settings.url
            config.username = settings.username
            config.password = settings.password
            config.poolName = "owned-p18-apms-writer"
            config.maximumPoolSize = 3
            config.minimumIdle = 0
            config.initializationFailTimeout = -1
            config.connectionTimeout = 2000
            config.validationTimeout = 1000
            config.addDataSourceProperty("connectTimeout", "3")
            config.addDataSourceProperty("socketTimeout", "5")
            pool = ObservedPool(config)
            jdbc = JdbcTemplate(pool())
            jdbc.queryTimeout = 5
            verifyTarget()
            for (table in ACL.keys) {
                check(count(table) == 0L, "A P18 trial requires empty application and Batch tables; prior history is never adopted")
            }
            outboxBefore = fingerprint("outbox_events")
            released()
            val created = AnnotationConfigApplicationContext()
            context = created
            TestPropertyValues.of(
                "apms.batch.schedule-enabled=true",
                "apms.batch.workers=1",
                "apms.batch.step-timeout-seconds=15",
                "apms.batch.collection-cron=0 0 0 1 1 *",
                "apms.api.service-key=" + KEY,
                "apms.api.base-url=https://synthetic.apms.invalid",
                "apms.sync.max-requests=30",
                "apms.sync.max-animals=10",
            ).applyTo(created)
            created.registerBean(
                "writeDataSource",
                HikariDataSource::class.java,
                Supplier { pool() },
                BeanDefinitionCustomizer { bean -> bean.setDestroyMethodName("") },
            )
            created.registerBean("writeJdbc", JdbcTemplate::class.java, Supplier { jdbc })
            created.registerBean(
                "writeTransactionManager",
                JdbcTransactionManager::class.java,
                Supplier { JdbcTransactionManager(pool()) },
            )
            created.registerBean(ObjectMapper::class.java, Supplier { ObjectMapper().findAndRegisterModules() })
            created.addBeanFactoryPostProcessor(
                BeanFactoryPostProcessor { factory ->
                    (factory.getBeanDefinition("apmsApiClient") as AbstractBeanDefinition).setDestroyMethodName("")
                    factory.registerSingleton("apmsApiClient", fixtureClient)
                    factory.registerSingleton("apmsClock", CLOCK)
                    factory.registerSingleton("apmsMetadataClock", METADATA_CLOCK)
                },
            )
            created.register(
                PropertyPlaceholderAutoConfiguration::class.java,
                ConfigurationPropertiesAutoConfiguration::class.java,
                AnimalApmsCollectionConfiguration::class.java,
            )
            created.refresh()
            check(
                created.getBean(ApmsApiClient::class.java) === fixtureClient,
                "Only the synthetic provider instance may be used; actual HTTP is forbidden",
            )
            check(
                created.getBean("apmsClock") === CLOCK && created.getBean("apmsMetadataClock") === METADATA_CLOCK,
                "The actual plan and metadata seams must use their separate fixed clock zones",
            )
            runner = created.getBean(ApmsBatchRunner::class.java)
            repository = created.getBean("apmsJobRepository", JobRepository::class.java)
            explorer = created.getBean("apmsJobExplorer", JobExplorer::class.java)
            recovery = created.getBean(ApmsBatchExecutionRecovery::class.java)
            check(
                created.getBean("animalCommandSearch", SearchDocumentWriter::class.java).dataSource() === pool(),
                "The actual shared Animal search writer must use the same test-owned writer",
            )
            released()
            nextAllowed = true
        }
    }

    @BeforeEach
    fun givenPreviousContractSucceeded__whenNextContractStarts__thenFenceOnAnyPriorFailure(info: TestInfo) {
        val prior = nextAllowed
        nextAllowed = false
        bodyCompleted = false
        observed("before contract") {
            check(
                prior && !unsettled && info.testMethod.orElseThrow().getAnnotation(Order::class.java).value == nextOrder,
                "Prior failure, unsettled workers or partial class selection forbids later owned writes",
            )
            released()
        }
    }

    @AfterEach
    fun givenContractEnded__whenCheckingResources__thenOnlySuccessfulBodyAndLifecycleOpenNextContract() {
        nextAllowed = false
        observed("after contract") {
            check(!unsettled, "Unsettled APMS work forbids later observation and pool closure")
            released()
            check(
                settings.marker == jdbc.queryForObject("SELECT marker FROM public.pb_apms_collection_fixture", String::class.java) &&
                    outboxBefore == fingerprint("outbox_events"),
                "Marker and original Outbox must remain unchanged",
            )
            released()
            check(bodyCompleted, "Failed owned contracts retain their history and fence the next write")
            nextOrder++
            nextAllowed = true
        }
    }

    @AfterAll
    fun givenNoUnsettledWorkers__whenClosing__thenCloseContextBeforeOwnedPoolAndRetainHistory() {
        try {
            observed("final close") {
                check(!unsettled, "Keep the owned context/pool while worker settlement is unknown")
                val closing = context
                if (closing != null && closing.isActive) {
                    released()
                }
                if (closing != null) {
                    closing.close()
                }
                val owned = pool
                if (owned != null) {
                    released()
                    owned.close()
                    check(owned.isClosed, "The explicit test-owned pool must close")
                    println(
                        "APMS_COLLECTION_OWNED callbacks=" + fetches.size + " callbackActive=" +
                            fetches.map { it.active }.distinct().sorted() +
                            " callbackTX=" + fetches.map { it.transaction }.distinct() +
                            " peakBorrows=" + owned.peak.get() + " sourceWrites=" + owned.sourceWrites.get() +
                            " searchWrites=" + owned.searchWrites.get() + " rollbackCalls=" + owned.rollbacks.get() +
                            " commitCalls=" + owned.commits.get() + " historyRetained=true",
                    )
                }
            }
        } finally {
            if (!unsettled) {
                logLevels.forEach { (logger, level) -> logger.level = level }
            }
        }
    }

    @Test
    @Order(1)
    fun `새 동물과 기존 동물이 주어졌을 때__실제 러너가 끝나면__업서트와 검색이 쓰기 트랜잭션을 공유한다`() {
        contract("normal APMS upsert") {
            scenario = 1
            requireCompleted(runner.run())
            val firstId: Long = jdbc.queryForObject(
                "SELECT id FROM pawbridge_animal.animals WHERE apms_desertion_no=?",
                Long::class.javaObjectType,
                number(1),
            )!!
            scenario = 2
            val completed = requireCompleted(runner.run())
            val completedPlan = ApmsSyncPlan.from(completed.jobParameters)
            lastSuccessfulEnd = completedPlan.end
            val next = futureHistoryProbe()
            check(
                next.updatedStart == completedPlan.end && next.updatedStart.isAfter(completedPlan.updatedStart),
                "Persisted PostgreSQL three-step evidence must advance recovery to its completed end outside the rolling overlap",
            )
            check(
                jdbc.queryForObject("SELECT count(*) FROM pawbridge_animal.animals", Long::class.javaObjectType) == 2L &&
                    jdbc.queryForObject(
                        "SELECT id FROM pawbridge_animal.animals WHERE apms_desertion_no=?",
                        Long::class.javaObjectType,
                        number(1),
                    ) == firstId,
                "The same APMS identity must update in place while the new identity is inserted once",
            )
            check(
                "Updated owned breed" == jdbc.queryForObject(
                    "SELECT breed FROM pawbridge_animal.animals WHERE id=?",
                    String::class.java,
                    firstId,
                ) &&
                    count("shelters") == 2L && count("animal_search_documents") == 2L &&
                    count("shelter_search_documents") == 2L,
                "Actual source upserts and prepared Shelter/search rows must be committed",
            )
            check(
                jdbc.queryForObject(
                    "SELECT count(*) FROM pawbridge_animal.animals a JOIN pawbridge_animal.animal_search_documents d ON d.animal_id=a.id WHERE NOT a.search_dirty AND a.search_revision=d.source_revision",
                    Long::class.javaObjectType,
                ) == 2L,
                "Every committed Animal search document must match its source revision",
            )
            check(
                !fetches.isEmpty() && pool().sourceWrites.get() > 0 && pool().searchWrites.get() > 0 &&
                    pool().samples.any { s -> s.table == "animals" } &&
                    pool().samples.any { s -> s.table == "animal_search_documents" } &&
                    pool().samples.all { s -> s.transaction && s.bound != null && s.manualCommit },
                "Actual source and search writes must run under the same bound writer transaction contract",
            )
            check(
                pool().samples.filter { s -> s.table == "animal_search_documents" }.all { doc ->
                    pool().samples.any { source ->
                        source.table == "animals" && source.pid == doc.pid && source.bound === doc.bound
                    }
                },
                "Actual Animal and search SQL must share a physical PID and the same JDBC transaction holder",
            )
            requireBusinessAndMetadataRollback(completed, firstId)
        }
    }

    @Test
    @Order(2)
    fun `한 청크의 두 행과 품종 넘침이 주어졌을 때__복구가 실패하면__유효 항목만 커밋하고 나쁜 항목을 거절한다`() {
        contract("natural rollback and fault-tolerant partial recovery") {
            val originalAnimals = "SELECT count(*)::text||':'||md5(COALESCE(string_agg(row_to_json(t)::text,',' ORDER BY id),'')) " +
                "FROM pawbridge_animal.animals t WHERE apms_desertion_no IN (?,?)"
            val originalDocuments = "SELECT count(*)::text||':'||md5(COALESCE(string_agg(row_to_json(t)::text,',' ORDER BY animal_id),'')) " +
                "FROM pawbridge_animal.animal_search_documents t WHERE animal_id IN " +
                "(SELECT id FROM pawbridge_animal.animals WHERE apms_desertion_no IN (?,?))"
            val animalsBefore = jdbc.queryForObject(originalAnimals, String::class.java, number(1), number(2))!!
            val documentsBefore = jdbc.queryForObject(originalDocuments, String::class.java, number(1), number(2))!!
            check(
                animalsBefore.startsWith("2:") && documentsBefore.startsWith("2:"),
                "The two original owned Animals and their projections must exist before the recovery probe",
            )
            val inserted = pool().animalInserts.get()
            val rolledBack = pool().rollbacks.get()
            scenario = 3
            val failed = runner.run()
            check(
                failed.status == BatchStatus.FAILED &&
                    failed.stepExecutions.any { s -> s.stepName == "shelterPrepStep" && s.status == BatchStatus.COMPLETED } &&
                    failed.stepExecutions.any { s -> s.stepName == "apmsAnimalSyncStep" && s.status == BatchStatus.FAILED },
                "Preparation commits independently before the natural Animal column constraint failure",
            )
            check(
                pool().animalInserts.get() > inserted && pool().rollbacks.get() > rolledBack,
                "A real first Animal insert must execute before the writer transaction rolls back",
            )
            check(
                jdbc.queryForObject(
                    "SELECT count(*) FROM pawbridge_animal.animals a JOIN pawbridge_animal.animal_search_documents d ON d.animal_id=a.id " +
                        "WHERE a.apms_desertion_no=? AND NOT a.search_dirty AND a.search_revision=d.source_revision",
                    Long::class.javaObjectType,
                    number(3),
                ) == 1L &&
                    count("animals") == 3L && count("animal_search_documents") == 3L,
                "Fault-tolerant recovery may commit the valid item and its matching projection after the first chunk rollback",
            )
            check(
                jdbc.queryForObject(
                    "SELECT count(*) FROM pawbridge_animal.animals WHERE apms_desertion_no=?",
                    Long::class.javaObjectType,
                    number(4),
                ) == 0L &&
                    jdbc.queryForObject(
                        "SELECT count(*) FROM pawbridge_animal.animal_search_documents d LEFT JOIN pawbridge_animal.animals a ON a.id=d.animal_id " +
                            "WHERE a.id IS NULL OR a.apms_desertion_no=?",
                        Long::class.javaObjectType,
                        number(4),
                    ) == 0L,
                "The rejected item must leave neither an Animal nor a projection or orphan document",
            )
            check(
                count("shelters") == 3L && count("shelter_search_documents") == 3L &&
                    animalsBefore == jdbc.queryForObject(originalAnimals, String::class.java, number(1), number(2)) &&
                    documentsBefore == jdbc.queryForObject(originalDocuments, String::class.java, number(1), number(2)),
                "The prepared Shelter and partial valid-item commit must preserve both earlier Animal/search snapshots",
            )
            check(
                ApmsQueryProgress.verifiedResults(failed, ApmsSyncPlan.from(failed.jobParameters)).isEmpty(),
                "Failed data steps must not become a verified successful query checkpoint",
            )
            val failedPlan = ApmsSyncPlan.from(failed.jobParameters)
            val gap = futureHistoryProbe()
            check(
                gap.updatedStart == failedPlan.updatedStart && gap.updatedStart.isBefore(lastSuccessfulEnd) &&
                    !gap.recentStart.isAfter(failedPlan.recentStart),
                "An unverified failed interval must retain its old update and intake gaps despite earlier completed metadata",
            )
        }
    }

    @Test
    @Order(3)
    fun `물리 세션 둘과 슬롯 키 1이 주어졌을 때__러너가 바쁘면__조회·메타 쓰기 없이 세션이 닫히면 슬롯이 풀린다`() {
        contract("physical APMS slot") {
            val before = allFingerprint()
            val calls = fetches.size
            direct.connection.use { owner ->
                direct.connection.use { contender ->
                    check(
                        pid(owner) != pid(contender) && lock(owner, "pg_try_advisory_lock"),
                        "Two distinct PostgreSQL PIDs must contend for APMS key1",
                    )
                    owner.autoCommit = false
                    pid(owner)
                    owner.rollback()
                    check(
                        owns(owner) && !lock(contender, "pg_try_advisory_lock"),
                        "Rollback must retain the session slot",
                    )
                    owner.autoCommit = true
                    expect(ApmsBatchRunner.AlreadyRunningException::class.java, Checked { runner.run() })
                    check(
                        fetches.size == calls && before == allFingerprint(),
                        "BUSY must leave all eleven tables and Batch metadata unchanged",
                    )
                    owner.close()
                    check(
                        lock(contender, "pg_try_advisory_lock") && lock(contender, "pg_advisory_unlock"),
                        "Physical owner close must transfer the actual slot to the second PID",
                    )
                }
            }
        }
    }

    @Test
    @Order(4)
    fun `최근 작업과 더 새로운 하트비트가 주어졌을 때__10분 경계를 지나 복구하면__최근은 거절하고 오래된 메타만 실패시킨다`() {
        contract("recent and stale metadata") {
            val execution = repository.createJobExecution(
                JOB,
                JobParametersBuilder().addString("ownedRecovery", TestFaker.uuid().toString()).toJobParameters(),
            )
            execution.status = BatchStatus.STARTED
            repository.update(execution)
            val step = StepExecution("ownedRecoveryStep", execution)
            step.status = BatchStatus.STARTED
            repository.add(step)
            val now = LocalDateTime.now(METADATA_CLOCK)
            val cutoff = now.minusMinutes(10)
            timestamp(execution.id, step.id, now.minusMinutes(1), now.minusMinutes(1))
            val recent = allFingerprint()
            val calls = fetches.size
            expect(ApmsBatchRunner.UnavailableException::class.java, Checked { runner.run() })
            check(
                calls == fetches.size && recent == allFingerprint(),
                "Recent metadata rejection must create no Job/provider/business writes",
            )
            requireSlotAvailable()
            timestamp(execution.id, step.id, cutoff.minusMinutes(1), now.minusMinutes(1))
            val newerStep = allFingerprint()
            expect(ApmsBatchRunner.UnavailableException::class.java, Checked { runner.run() })
            check(
                newerStep == allFingerprint() && calls == fetches.size,
                "A newer Step heartbeat must protect an old-looking Job",
            )
            timestamp(execution.id, step.id, cutoff.minusMinutes(1), cutoff)
            val data = businessFingerprint()
            direct.connection.use { slot ->
                check(lock(slot, "pg_try_advisory_lock"), "Recovery may run only while this test owns the same APMS slot")
                try {
                    check(
                        recovery.recoverOrReject(JOB) == 1,
                        "The exact ten-minute cutoff is stale and must close one owned execution",
                    )
                } finally {
                    check(lock(slot, "pg_advisory_unlock"), "The explicit recovery slot must release")
                }
            }
            val recovered = explorer.getJobExecution(execution.id)
            check(
                recovered !== null && recovered.status == BatchStatus.FAILED && recovered.endTime != null &&
                    recovered.stepExecutions.all { s -> s.status == BatchStatus.FAILED && s.endTime != null } &&
                    recovered.exitStatus.exitDescription.contains(
                        "Execution process disappeared and the APMS named lock was released",
                    ),
                "Only fixed FAILED/end metadata may replace the stale STARTED execution",
            )
            check(
                data == businessFingerprint() && calls == fetches.size,
                "Metadata recovery must neither refetch nor change committed source/search/Outbox",
            )
        }
    }

    @Test
    @Order(5)
    fun `실행 중인 실제 스케줄이 주어졌을 때__정상 중지를 요청하면__동기 작업과 슬롯 해제를 기다린 뒤 닫는다`() {
        contract("actual schedule normal stop") {
            scenario = 4
            fetchEntered = CountDownLatch(1)
            fetchRelease = CountDownLatch(1)
            latchUsed.set(false)
            val schedule = ApmsCollectionSchedule(runner, "* * * * * *")
            val stopped = CountDownLatch(1)
            val problem = AtomicReference<Throwable>()
            val closer = Thread(
                Runnable {
                    try {
                        schedule.stop()
                    } catch (failure: Throwable) {
                        problem.set(failure)
                    } finally {
                        stopped.countDown()
                    }
                },
                "owned-apms-stop",
            )
            closer.isDaemon = true
            unsettled = true
            var scheduler: ThreadPoolTaskScheduler? = null
            try {
                schedule.start()
                scheduler = ReflectionTestUtils.getField(schedule, "scheduler") as ThreadPoolTaskScheduler?
                check(
                    scheduler != null && fetchEntered.await(8, TimeUnit.SECONDS),
                    "The real scheduled callback must enter the synthetic provider",
                )
                closer.start()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (!scheduler!!.scheduledThreadPoolExecutor.isShutdown && System.nanoTime() < deadline) {
                    stopped.await(10, TimeUnit.MILLISECONDS)
                }
                check(
                    scheduler.scheduledThreadPoolExecutor.isShutdown && !stopped.await(150, TimeUnit.MILLISECONDS),
                    "Normal stop must forbid future ticks and wait while the synchronous job holds its slot",
                )
            } finally {
                fetchRelease.countDown()
                if (closer.state == Thread.State.NEW) {
                    closer.start()
                }
                val finished = awaitCloser(closer, 8)
                unsettled = !(
                    finished && stopped.count == 0L && scheduler != null &&
                        scheduler.scheduledThreadPoolExecutor.isTerminated
                    )
                check(!unsettled, "Unconfirmed scheduler/closer settlement fences later database access and pool closure")
            }
            check(problem.get() == null && !schedule.isRunning, "Normal stop must finish without an execution failure")
            released()
            requireSlotAvailable()
            check(
                jdbc.queryForObject(
                    "SELECT count(*) FROM pawbridge_animal.animals WHERE apms_desertion_no=?",
                    Long::class.javaObjectType,
                    number(5),
                ) == 1L,
                "The in-flight scheduled job must finish its actual Animal commit before stop returns",
            )
            check(
                explorer.getJobExecutions(explorer.getJobInstances(JOB, 0, 1).first())
                    .all { e -> e.status == BatchStatus.COMPLETED },
                "The scheduled run must complete rather than merely terminate its worker",
            )
        }
    }

    private fun fetch(
        key: String?,
        page: Int,
        size: Int,
        from: String?,
        through: String?,
        kind: String?,
        state: String?,
        type: String?,
        updatedFrom: String?,
        updatedThrough: String?,
    ): ApmsRootResponse<ApmsAnimal> {
        try {
            val tx = TransactionSynchronizationManager.isActualTransactionActive()
            val active = pool().hikariPoolMXBean.activeConnections
            fetches.add(FetchObservation(active, tx))
            check(
                KEY == key && !tx && active == 1 && pool().connections.get() == 1 && page == 1 && size == 1000,
                "Only the raw APMS slot may remain borrowed during bounded synthetic fetches",
            )
            direct.connection.use { probe ->
                check(!lock(probe, "pg_try_advisory_lock"), "The actual runner must retain APMS key1 through HTTP")
            }
            if (scenario == 4 && latchUsed.compareAndSet(false, true)) {
                fetchEntered.countDown()
                check(fetchRelease.await(8, TimeUnit.SECONDS), "The owned provider latch must release within its bound")
            }
            var rows: List<ApmsAnimal> = when (scenario) {
                1 -> listOf(animal(1, 1, "Initial owned breed"))
                2 -> listOf(animal(1, 1, "Updated owned breed"), animal(2, 2, "New owned breed"))
                3 -> listOf(animal(3, 3, "First rollback breed"), animal(4, 3, "x".repeat(101)))
                4 -> listOf(animal(5, 4, "Scheduled owned breed"))
                else -> throw AssertionError("No synthetic provider scenario is active")
            }
            rows = rows.filter { a ->
                inside(a.happenDt, from, through) &&
                    (updatedFrom == null || inside("20261021", updatedFrom, updatedThrough))
            }
            return ApmsRootResponse<ApmsAnimal>(
                ApmsResponse<ApmsAnimal>(
                    ApmsHeader(null, "00", "owned", null),
                    ApmsBody<ApmsAnimal>(
                        ApmsItems<ApmsAnimal>(rows),
                        size.toString(),
                        page.toString(),
                        rows.size.toString(),
                    ),
                ),
            )
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AssertionError("Owned provider wait was interrupted")
        } catch (failure: SQLException) {
            throw AssertionError("Owned provider slot observation failed")
        }
    }

    private fun requireCompleted(returned: JobExecution): JobExecution {
        val execution = explorer.getJobExecution(returned.id)
        check(
            execution !== null && execution.status == BatchStatus.COMPLETED && execution.stepExecutions.size == 3 &&
                execution.stepExecutions.map { it.stepName }.toSet() ==
                setOf("shelterPrepStep", "apmsAnimalSyncStep", "apmsCollectionVerificationStep") &&
                execution.stepExecutions.all { s -> s.status == BatchStatus.COMPLETED && s.skipCount == 0L } &&
                "postgresql" == execution.executionContext.getString(ApmsQueryProgress.BACKEND_KEY, "") &&
                execution.executionContext.getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT, -1) == 0,
            "The actual three-step job must complete without skipped or incomplete collection",
        )
        val results = ApmsQueryProgress.verifiedResults(execution!!, ApmsSyncPlan.from(execution.jobParameters))
        check(
            !results.isEmpty() && results.all { it.complete },
            "Completed data and verification steps must provide durable per-query evidence",
        )
        return execution
    }

    private fun requireBusinessAndMetadataRollback(completed: JobExecution, animalId: Long) {
        val stepId = completed.stepExecutions.filter { s -> s.stepName == "apmsAnimalSyncStep" }
            .first().id
        val step = explorer.getStepExecution(completed.id, stepId)
        check(step !== null, "The completed owned Step must be reloaded before its rollback probe")
        val reloaded: StepExecution = step!!
        val manager = context().getBean("animalCommandJpaTransactionManager", JpaTransactionManager::class.java)
        val entityManager = context().getBean("animalCommandEntityManager", EntityManager::class.java)
        val search = context().getBean("animalCommandSearch", SearchDocumentWriter::class.java)
        val before = allFingerprint()
        val committed = pool().commits.get()
        val rolledBack = pool().rollbacks.get()
        val contexts = pool().stepContextUpdates.get()
        val steps = pool().stepUpdates.get()
        val forced: RuntimeException = IllegalStateException("Owned APMS metadata atomicity rollback")
        val reached = AtomicBoolean()
        try {
            TransactionTemplate(manager).executeWithoutResult {
                check(
                    manager.dataSource === pool() && TransactionSynchronizationManager.isActualTransactionActive() &&
                        !TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                    "The atomicity probe must use the actual Animal JPA writer transaction",
                )
                val animal = entityManager.find(Animal::class.java, animalId)
                check(
                    animal != null && "Owned P18 metadata rollback" != animal.description,
                    "Only an existing owned Animal may receive the temporary rollback mutation",
                )
                animal.updateDescription("Owned P18 metadata rollback")
                entityManager.flush()
                search.animal(animalId)
                reloaded.executionContext.putString("owned.p18.atomicity", "rollback-only")
                repository.updateExecutionContext(reloaded)
                reloaded.writeCount = reloaded.writeCount + 1
                repository.update(reloaded)
                check(
                    pool().stepContextUpdates.get() > contexts && pool().stepUpdates.get() > steps,
                    "Both actual JobRepository execution-context and Step updates must reach PostgreSQL",
                )
                for (table in listOf(
                    "animals",
                    "animal_search_documents",
                    "batch_step_execution_context",
                    "batch_step_execution",
                )) {
                    check(
                        before.getValue(table) != fingerprint(table),
                        "Business, projection and both metadata mutations must be visible before the forced rollback",
                    )
                }
                reached.set(true)
                throw forced
            }
            throw AssertionError("The atomicity probe must end in its exact forced rollback")
        } catch (failure: RuntimeException) {
            check(
                reached.get() && failure === forced,
                "Only the designated RuntimeException after both real metadata updates is the rollback evidence",
            )
        }
        check(
            pool().commits.get() == committed && pool().rollbacks.get() > rolledBack,
            "Joining Batch metadata must add no physical commit and the outer JPA transaction must roll back",
        )
        check(
            before == allFingerprint(),
            "The forced JPA rollback must preserve all eleven business, projection and Batch table snapshots",
        )
        released()
    }

    private fun futureHistoryProbe(): ApmsSyncPlan {
        // The source intentionally rechecks thirty days. Move only this read-only observation beyond
        // that overlap; do not alter the real runner's Clock or launch another collection.
        val later = Clock.fixed(CLOCK.instant().plus(Duration.ofDays(45)), CLOCK.zone)
        return ApmsSyncPlanFactory(
            context().getBean("animalCommandAnimals", AnimalRepository::class.java),
            explorer,
            later,
        ).create(JOB)
    }

    private fun timestamp(execution: Long, step: Long, jobTime: LocalDateTime, stepTime: LocalDateTime) {
        TransactionTemplate(JdbcTransactionManager(pool())).executeWithoutResult {
            check(
                jdbc.update(
                    "UPDATE pawbridge_animal.batch_job_execution SET last_updated=?,start_time=? WHERE job_execution_id=?",
                    Timestamp.valueOf(jobTime),
                    Timestamp.valueOf(jobTime),
                    execution,
                ) == 1,
                "Only the preregistered recovery execution may receive a synthetic heartbeat",
            )
            check(
                jdbc.update(
                    "UPDATE pawbridge_animal.batch_step_execution SET last_updated=?,start_time=? WHERE step_execution_id=? AND job_execution_id=?",
                    Timestamp.valueOf(stepTime),
                    Timestamp.valueOf(stepTime),
                    step,
                    execution,
                ) == 1,
                "Only its exact owned Step may receive a synthetic heartbeat",
            )
        }
    }

    private fun count(table: String): Long {
        return jdbc.queryForObject("SELECT count(*) FROM pawbridge_animal." + table, Long::class.javaObjectType)!!
    }

    private fun fingerprint(table: String): String {
        return jdbc.queryForObject(
            "SELECT count(*)::text||':'||md5(COALESCE(string_agg(row_to_json(t)::text,',' ORDER BY " + KEYS[table] +
                "),'')) FROM pawbridge_animal." + table + " t",
            String::class.java,
        )!!
    }

    private fun allFingerprint(): Map<String, String> {
        val rows = LinkedHashMap<String, String>()
        for (t in KEYS.keys.sorted()) {
            rows[t] = fingerprint(t)
        }
        return rows
    }

    private fun businessFingerprint(): Map<String, String> {
        val rows = LinkedHashMap<String, String>()
        for (t in listOf("animals", "shelters", "outbox_events", "animal_search_documents", "shelter_search_documents")) {
            rows[t] = fingerprint(t)
        }
        return rows
    }

    private fun released() {
        check(!unsettled, "Unconfirmed APMS work forbids subsequent access or shutdown")
        val active = context
        if (active != null && active.isActive) {
            val executor = active.getBean(
                "apmsBatchTaskExecutor",
                AnimalApmsCollectionConfiguration.ApmsChunkExecutor::class.java,
            )
            if (executor.activeCount != 0 || !executor.threadPoolExecutor.queue.isEmpty()) {
                unsettled = true
                throw AssertionError("APMS chunks remain unsettled; preserve context and pool")
            }
        }
        val owned = pool
        val returned = !TransactionSynchronizationManager.isActualTransactionActive() &&
            !TransactionSynchronizationManager.hasResource(owned!!) &&
            (
                owned == null ||
                    (
                        owned.connections.get() == 0 && owned.statements.get() == 0 && owned.results.get() == 0 &&
                            owned.hikariPoolMXBean.activeConnections == 0 &&
                            owned.hikariPoolMXBean.threadsAwaitingConnection == 0
                        )
                )
        if (!returned) {
            unsettled = true
        }
        check(returned, "Unreturned JDBC resources fence later work and preserve the owned context/pool")
    }

    private fun requireSlotAvailable() {
        released()
        direct.connection.use { c ->
            check(
                lock(c, "pg_try_advisory_lock") && lock(c, "pg_advisory_unlock"),
                "A fresh physical session must acquire and release APMS key1",
            )
        }
    }

    private fun contract(phase: String, body: Checked) {
        observed(phase) {
            body.run()
            bodyCompleted = true
        }
    }

    private fun interface Checked {
        fun run()
    }

    private data class FetchObservation(val active: Int, val transaction: Boolean)

    private data class WriteSample(
        val table: String,
        val pid: Int,
        val bound: Any?,
        val transaction: Boolean,
        val manualCommit: Boolean,
    )

    private fun verifyTarget() {
        val id = jdbc.queryForMap(
            "SELECT current_database() db,current_user actor,session_user session,current_setting('server_version_num') version,(SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()) owner",
        )
        check(
            settings.database == id["db"] && settings.username == id["actor"] && settings.username == id["session"] &&
                settings.owner() == id["owner"] && "170011" == id["version"],
            "Only the new owned PostgreSQL17.11 target and distinct owner/writer are accepted",
        )
        check(
            settings.marker == jdbc.queryForObject("SELECT marker FROM public.pb_apms_collection_fixture", String::class.java),
            "The exact P18 marker is mandatory",
        )
        check(
            true == jdbc.queryForObject(
                "SELECT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls AND NOT rolinherit AND NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=pg_roles.oid) FROM pg_roles WHERE rolname=current_user",
                Boolean::class.javaObjectType,
            ),
            "The writer must not inherit administrative or owner capabilities",
        )
        check(
            true == jdbc.queryForObject(
                "SELECT NOT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls AND NOT rolinherit FROM pg_roles WHERE rolname=?",
                Boolean::class.javaObjectType,
                settings.owner(),
            ) &&
                false == jdbc.queryForObject(
                    "SELECT pg_has_role(current_user,?,'MEMBER')",
                    Boolean::class.javaObjectType,
                    settings.owner(),
                ),
            "The separate owner must remain NOLOGIN and inaccessible",
        )
        check(
            false == jdbc.queryForObject(
                "SELECT has_database_privilege(current_user,current_database(),'CREATE,TEMP')",
                Boolean::class.javaObjectType,
            ),
            "Runtime database/TEMP DDL is forbidden",
        )
        for (schema in listOf("public", "pawbridge_animal")) {
            check(
                true == jdbc.queryForObject(
                    "SELECT has_schema_privilege(current_user,?,'USAGE') AND NOT has_schema_privilege(current_user,?,'CREATE')",
                    Boolean::class.javaObjectType,
                    schema,
                    schema,
                ),
                "Runtime schema creation is forbidden",
            )
        }
        check(
            jdbc.query(
                "SELECT tablename FROM pg_tables WHERE schemaname='pawbridge_animal' ORDER BY tablename",
                RowMapper { r, _ -> r.getString(1) },
            ) == ACL.keys.sorted(),
            "Only the exact five business and six Batch tables are accepted",
        )
        check(
            jdbc.queryForObject(
                "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname !~ '^pg_' AND n.nspname<>'information_schema' AND c.relkind IN('r','p','v','m','f')",
                Long::class.javaObjectType,
            ) == 12L,
            "Only eleven application tables plus the ownership marker may exist",
        )
        for (table in ACL.entries) {
            for (right in listOf("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER")) {
                check(
                    table.value.contains(right) == jdbc.queryForObject(
                        "SELECT has_table_privilege(current_user,?,?)",
                        Boolean::class.javaObjectType,
                        "pawbridge_animal." + table.key,
                        right,
                    ),
                    "Every runtime table ACL must match the reviewed fixture",
                )
            }
        }
        for (table in ACL.entries) {
            for (right in listOf("INSERT", "UPDATE", "REFERENCES")) {
                if (!table.value.contains(right)) {
                    check(
                        false == jdbc.queryForObject(
                            "SELECT has_any_column_privilege(current_user,?,?)",
                            Boolean::class.javaObjectType,
                            "pawbridge_animal." + table.key,
                            right,
                        ),
                        "Column grants must not bypass denied table privileges",
                    )
                }
            }
        }
        check(
            true == jdbc.queryForObject(
                "SELECT has_table_privilege(current_user,'public.pb_apms_collection_fixture','SELECT') AND NOT has_table_privilege(current_user,'public.pb_apms_collection_fixture','INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')",
                Boolean::class.javaObjectType,
            ),
            "Marker must remain SELECT-only",
        )
        check(
            jdbc.query(
                "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='pawbridge_animal' AND c.relkind='S' ORDER BY c.relname",
                RowMapper { r, _ -> r.getString(1) },
            ) == SEQUENCES.sorted(),
            "Only three identity and three Batch sequences may exist",
        )
        for (sequence in SEQUENCES) {
            check(
                (sequence != "outbox_events_id_seq") == jdbc.queryForObject(
                    "SELECT has_sequence_privilege(current_user,?,'USAGE')",
                    Boolean::class.javaObjectType,
                    "pawbridge_animal." + sequence,
                ) &&
                    false == jdbc.queryForObject(
                        "SELECT has_sequence_privilege(current_user,?,'SELECT,UPDATE')",
                        Boolean::class.javaObjectType,
                        "pawbridge_animal." + sequence,
                    ),
                "Only necessary nextval USAGE is allowed; Outbox has no write sequence privilege",
            )
        }
        check(
            jdbc.queryForObject(
                "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN('public','pawbridge_animal') AND c.relkind IN('r','S') AND pg_get_userbyid(c.relowner)<>?",
                Long::class.javaObjectType,
                settings.owner(),
            ) == 0L,
            "All fixture objects must retain the separate owner",
        )
    }

    private fun quiet() {
        for (name in listOf(
            "com.zaxxer.hikari",
            "org.springframework.jdbc",
            "org.springframework.batch",
            "org.springframework.context",
            "org.springframework.orm.jpa",
            "org.hibernate",
            "com.pawbridge.backend.animal.internal.apms",
        )) {
            val logger = LoggerFactory.getLogger(name) as ch.qos.logback.classic.Logger
            logLevels[logger] = logger.level
            logger.level = ch.qos.logback.classic.Level.OFF
        }
    }

    /** Actual wrappers count borrows, not physical connection creations; no SQL or values are emitted. */
    private class ObservedPool(config: HikariConfig) : HikariDataSource(config) {
        val connections = AtomicInteger()
        val statements = AtomicInteger()
        val results = AtomicInteger()
        val peak = AtomicInteger()
        val commits = AtomicInteger()
        val rollbacks = AtomicInteger()
        val animalInserts = AtomicInteger()
        val sourceWrites = AtomicInteger()
        val searchWrites = AtomicInteger()
        val stepContextUpdates = AtomicInteger()
        val stepUpdates = AtomicInteger()
        val samples: MutableList<WriteSample> = CopyOnWriteArrayList()

        override fun getConnection(): Connection {
            val c = super.getConnection()
            val backend: Int
            try {
                backend = pid(c)
            } catch (e: Throwable) {
                c.close()
                throw e
            }
            val active = connections.incrementAndGet()
            peak.accumulateAndGet(active) { a, b -> Math.max(a, b) }
            val closed = AtomicBoolean()
            val children: MutableList<Tracked> = CopyOnWriteArrayList()
            return Proxy.newProxyInstance(
                Connection::class.java.classLoader,
                arrayOf<Class<*>>(Connection::class.java),
                InvocationHandler { _, m, a ->
                    if (m.name == "close") {
                        if (closed.compareAndSet(false, true)) {
                            try {
                                return@InvocationHandler call(c, m, a)
                            } finally {
                                children.forEach { it.closed() }
                                connections.decrementAndGet()
                            }
                        }
                        return@InvocationHandler null
                    }
                    if (m.name == "rollback") {
                        rollbacks.incrementAndGet()
                    }
                    val v = call(c, m, a)
                    if (m.name == "commit") {
                        commits.incrementAndGet()
                    }
                    if (v is Statement) {
                        val t = Tracked(
                            v,
                            if (a != null && a.size > 0 && a[0] is String) (a[0] as String).lowercase(Locale.ROOT) else "",
                            backend,
                            c,
                        )
                        children.add(t)
                        return@InvocationHandler t.proxy()
                    }
                    v
                },
            ) as Connection
        }

        private inner class Tracked(
            val statement: Statement,
            val sql: String,
            val backend: Int,
            val connection: Connection,
        ) {
            val closed = AtomicBoolean()
            val cursors: MutableList<AtomicBoolean> = CopyOnWriteArrayList()

            init {
                statements.incrementAndGet()
            }

            fun proxy(): Any {
                return Proxy.newProxyInstance(
                    Statement::class.java.classLoader,
                    arrayOf<Class<*>>(if (statement is PreparedStatement) PreparedStatement::class.java else Statement::class.java),
                    InvocationHandler { _, m, a ->
                        if (m.name == "close") {
                            try {
                                return@InvocationHandler call(statement, m, a)
                            } finally {
                                closed()
                            }
                        }
                        if (m.name.startsWith("execute") && (sql.startsWith("insert ") || sql.startsWith("update "))) {
                            for (table in listOf("animal_search_documents", "shelter_search_documents", "animals", "shelters")) {
                                if (sql.contains("pawbridge_animal." + table)) {
                                    samples.add(
                                        WriteSample(
                                            table,
                                            backend,
                                            TransactionSynchronizationManager.getResource(this@ObservedPool),
                                            TransactionSynchronizationManager.isActualTransactionActive(),
                                            !connection.autoCommit,
                                        ),
                                    )
                                    if (table.endsWith("_documents")) {
                                        searchWrites.incrementAndGet()
                                    } else {
                                        sourceWrites.incrementAndGet()
                                    }
                                    break
                                }
                            }
                        }
                        val v = call(statement, m, a)
                        if (m.name.startsWith("execute") && sql.startsWith("insert into pawbridge_animal.animals")) {
                            animalInserts.incrementAndGet()
                        }
                        if (m.name.startsWith("execute") &&
                            sql.startsWith("update pawbridge_animal.batch_step_execution_context\nset ")
                        ) {
                            stepContextUpdates.incrementAndGet()
                        }
                        if (m.name.startsWith("execute") &&
                            sql.startsWith("update pawbridge_animal.batch_step_execution\nset ")
                        ) {
                            stepUpdates.incrementAndGet()
                        }
                        if (v !is ResultSet) {
                            return@InvocationHandler v
                        }
                        val done = AtomicBoolean()
                        cursors.add(done)
                        results.incrementAndGet()
                        Proxy.newProxyInstance(
                            ResultSet::class.java.classLoader,
                            arrayOf<Class<*>>(ResultSet::class.java),
                            InvocationHandler { _, rm, ra ->
                                if (rm.name == "close") {
                                    try {
                                        return@InvocationHandler call(v, rm, ra)
                                    } finally {
                                        closeCursor(done)
                                    }
                                }
                                call(v, rm, ra)
                            },
                        )
                    },
                )
            }

            fun closeCursor(c: AtomicBoolean) {
                if (c.compareAndSet(false, true)) {
                    results.decrementAndGet()
                }
            }

            fun closed() {
                if (closed.compareAndSet(false, true)) {
                    cursors.forEach { closeCursor(it) }
                    statements.decrementAndGet()
                }
            }
        }
    }

    private class Settings(
        val url: String,
        val database: String,
        val username: String,
        val password: String,
        val marker: String,
    ) {
        fun owner(): String = database.replace("pb_apms_collection_owned_", "pb_apms_collection_owner_")

        override fun toString(): String = "OwnedApmsSettings[redacted]"

        companion object {
            fun load(): Settings {
                val path = System.getenv("P18_PG_SETTINGS")
                check(path != null && path.isNotBlank(), "P18_PG_SETTINGS is required without fallback or skip")
                val file = Path.of(path)
                val expected = Path.of(
                    "/private/tmp/pawbridge-owned-pg-night-20261020/apms-collection-recovery-2/apms-collection.properties",
                )
                check(
                    file.isAbsolute && file.normalize() == file && file == expected &&
                        Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file),
                    "Only the exact new P18 regular settings file is accepted",
                )
                var parent: Path? = file.parent
                while (parent != null) {
                    check(!Files.isSymbolicLink(parent), "Settings ancestors may not be symlinks")
                    parent = parent.parent
                }
                check(
                    Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS) ==
                        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE) &&
                        (Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toInt() == 1 &&
                        System.getProperty("user.name") == Files.getOwner(file).name &&
                        Files.getOwner(file) == Files.getOwner(file.parent),
                    "P18 settings must be current-owner single-link0600",
                )
                check(
                    Files.getPosixFilePermissions(file.parent) ==
                        setOf(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE,
                        ),
                    "The new P18 private directory must remain0700",
                )
                val v = Properties()
                Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { input: InputStream -> v.load(input) }
                check(
                    v.stringPropertyNames() == setOf("jdbcUrl", "database", "username", "password", "marker"),
                    "Exactly five private P18 settings are accepted",
                )
                val db = required(v, "database")
                val url = required(v, "jdbcUrl")
                val user = required(v, "username")
                val marker = required(v, "marker")
                check(
                    db.matches(Regex("pb_apms_collection_owned_[0-9a-f]{12}")) &&
                        url == "jdbc:postgresql://127.0.0.1:52620/" + db &&
                        user == db.replace("pb_apms_collection_owned_", "pb_apms_collection_writer_") &&
                        marker.matches(Regex("[0-9a-f]{32}")),
                    "P18 target/role/marker must identify only its new loopback fixture",
                )
                return Settings(url, db, user, required(v, "password"), marker)
            }

            private fun required(v: Properties, key: String): String {
                val value = v.getProperty(key)
                check(value != null && value.isNotBlank(), "Every P18 setting is mandatory")
                return value
            }
        }
    }

    companion object {
        private val CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"), ZoneId.of("Asia/Seoul"))
        private val METADATA_CLOCK: Clock = Clock.fixed(CLOCK.instant(), ZoneId.systemDefault())
        private const val KEY = "owned-apms-synthetic-key-only-20261021"
        private const val JOB = "apmsAnimalSyncJob"
        private const val FIXTURE_SHA = "55dbd18513400e3037f025f352550c1c10dff2bf2a8b110095f601276e27f8f5"
        private val ACL: Map<String, Set<String>> = privileges()
        private val SEQUENCES: List<String> = listOf(
            "animals_id_seq",
            "shelters_id_seq",
            "outbox_events_id_seq",
            "batch_job_seq",
            "batch_job_execution_seq",
            "batch_step_execution_seq",
        )
        private val KEYS: Map<String, String> = mapOf(
            "animals" to "id",
            "shelters" to "id",
            "outbox_events" to "id",
            "animal_search_documents" to "animal_id",
            "shelter_search_documents" to "shelter_id",
            "batch_job_instance" to "job_instance_id",
            "batch_job_execution" to "job_execution_id",
            "batch_job_execution_params" to "job_execution_id,parameter_name",
            "batch_step_execution" to "step_execution_id",
            "batch_step_execution_context" to "step_execution_id",
            "batch_job_execution_context" to "job_execution_id",
        )

        private fun animal(id: Int, care: Int, breed: String): ApmsAnimal {
            return ApmsAnimal.builder()
                .desertionNo(number(id))
                .noticeNo("OWNED-P18-" + id)
                .upKindCd("417000")
                .kindNm(breed)
                .age("2023(년생)")
                .weight("5(Kg)")
                .colorCd("brown")
                .sexCd("M")
                .neuterYn("Y")
                .specialMark("Owned APMS fixture " + id)
                .processState("보호중")
                .noticeSdt("20261020")
                .noticeEdt("20261030")
                .updTm("2026-10-21 00:00:00")
                .happenDt("20261020")
                .happenPlace("Owned place")
                .popfile1("https://fixture.invalid/p18/one.jpg")
                .careRegNo("OWNED-P18-CARE-" + care)
                .careNm("Owned shelter " + care)
                .careTel("02-000-0000")
                .careAddr("Owned synthetic address")
                .orgNm("Owned agency")
                .chargeNm("Owned contact")
                .build()
        }

        private fun number(id: Int): String = "99000000000000" + id

        private fun inside(value: String?, from: String?, to: String?): Boolean {
            return value!!.compareTo(from!!) >= 0 && value.compareTo(to!!) <= 0
        }

        private fun pid(connection: Connection): Int {
            connection.createStatement().use { s ->
                s.queryTimeout = 3
                s.executeQuery("SELECT pg_backend_pid()").use { r ->
                    check(r.next(), "A physical PID is required")
                    return r.getInt(1)
                }
            }
        }

        private fun lock(connection: Connection, function: String): Boolean {
            check(
                setOf("pg_try_advisory_lock", "pg_advisory_unlock").contains(function),
                "Only exact APMS slot operations are permitted",
            )
            connection.prepareStatement("SELECT " + function + "(?,?)").use { s ->
                s.queryTimeout = 3
                s.setInt(1, 0x50415742)
                s.setInt(2, 1)
                s.executeQuery().use { r ->
                    check(r.next(), "The APMS slot result is required")
                    return r.getBoolean(1)
                }
            }
        }

        private fun owns(c: Connection): Boolean {
            c.prepareStatement(
                "SELECT EXISTS(SELECT 1 FROM pg_locks WHERE locktype='advisory' AND pid=pg_backend_pid() AND classid=? AND objid=1 AND objsubid=2 AND granted)",
            ).use { s ->
                s.setInt(1, 0x50415742)
                s.queryTimeout = 3
                s.executeQuery().use { r ->
                    check(r.next(), "Slot ownership is observable")
                    return r.getBoolean(1)
                }
            }
        }

        private fun awaitCloser(thread: Thread, seconds: Int): Boolean {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds.toLong())
            var interrupted = false
            try {
                while (thread.isAlive) {
                    val left = until - System.nanoTime()
                    if (left <= 0) {
                        return false
                    }
                    try {
                        TimeUnit.NANOSECONDS.timedJoin(thread, left)
                    } catch (e: InterruptedException) {
                        interrupted = true
                    }
                }
                return thread.state == Thread.State.TERMINATED
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        private fun observed(phase: String, body: Checked) {
            try {
                body.run()
            } catch (e: AssertionError) {
                throw e
            } catch (e: Throwable) {
                throw AssertionError("Owned APMS observation failed: " + phase + "; type=" + e.javaClass.name)
            }
        }

        private fun expect(type: Class<out Throwable>, body: Checked) {
            try {
                body.run()
            } catch (e: Throwable) {
                check(type.isInstance(e), "The exact owned refusal type is required")
                return
            }
            throw AssertionError("Expected APMS refusal was absent")
        }

        private fun check(value: Boolean, fixed: String) {
            if (!value) {
                throw AssertionError(fixed)
            }
        }

        private fun call(target: Any, method: Method, args: Array<Any?>?): Any? {
            try {
                return method.invoke(target, *(args ?: arrayOf()))
            } catch (e: InvocationTargetException) {
                throw e.cause!!
            }
        }

        private fun privileges(): Map<String, Set<String>> {
            val v = LinkedHashMap<String, Set<String>>()
            for (t in listOf("animals", "shelters", "animal_search_documents", "shelter_search_documents")) {
                v[t] = setOf("SELECT", "INSERT", "UPDATE")
            }
            v["outbox_events"] = setOf("SELECT")
            for (t in listOf("batch_job_instance", "batch_job_execution_params")) {
                v[t] = setOf("SELECT", "INSERT")
            }
            for (t in listOf(
                "batch_job_execution",
                "batch_step_execution",
                "batch_job_execution_context",
                "batch_step_execution_context",
            )) {
                v[t] = setOf("SELECT", "INSERT", "UPDATE")
            }
            return v.toMap()
        }
    }
}
