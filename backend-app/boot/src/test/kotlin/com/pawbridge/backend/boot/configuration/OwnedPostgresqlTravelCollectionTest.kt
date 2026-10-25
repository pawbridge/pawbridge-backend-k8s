package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.travel.application.PetTravel
import com.pawbridge.backend.animal.internal.travel.application.PetTravelException
import com.pawbridge.backend.animal.internal.travel.infrastructure.PostgresqlPetTravel
import com.pawbridge.backend.animal.internal.travel.infrastructure.collection.PetTravelCollector
import com.pawbridge.backend.animal.internal.travel.infrastructure.collection.PostgresqlTravelCollectionCatalog
import com.pawbridge.backend.animal.internal.travel.infrastructure.collection.TourApiClient
import com.pawbridge.backend.animal.internal.travel.infrastructure.collection.TourApiProperties
import com.pawbridge.backend.support.TestFaker
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher
import org.springframework.batch.core.repository.JobRepository
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.transaction.TransactionDefinition
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
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * One fresh synthetic database, one explicit writer, original Root job/scheduler methods.
 * Ordered contracts deliberately retain owned history and a final RUNNING orphan for inspection.
 * This is explicit component assembly, not a full Boot context or live provider test.
 */
@Tag("owned-postgresql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Timeout(45)
class OwnedPostgresqlTravelCollectionTest {
    private val previousLevels: MutableMap<ch.qos.logback.classic.Logger, ch.qos.logback.classic.Level?> = LinkedHashMap()
    private val clientObservations: MutableList<ClientObservation> = CopyOnWriteArrayList()
    private val configuration = AnimalTravelCollectionConfiguration()
    private lateinit var settings: Settings
    private lateinit var direct: DriverManagerDataSource
    private var pool: ObservedPool? = null
    private lateinit var jdbc: JdbcTemplate
    private lateinit var manager: JdbcTransactionManager
    private lateinit var catalog: PostgresqlTravelCollectionCatalog
    private lateinit var repository: JobRepository
    private lateinit var launcher: TaskExecutorJobLauncher
    private lateinit var reader: PetTravel
    private lateinit var clock: Clock
    private var nextContractAllowed = false
    private var bodyCompleted = false
    private var nextOrder = 1

    @Volatile
    private var unsettledWorkers = false

    private fun pool(): ObservedPool = pool!!

    @BeforeAll
    fun givenNewPrivateOwnedDatabase__whenPreparingComponents__thenRequireExactIdentitySchemaAndLeastPrivileges() {
        observed("identity and explicit assembly") {
            quietLogs()
            settings = Settings.load()
            direct = DriverManagerDataSource(settings.url, settings.username, settings.password)
            val timeouts = Properties()
            timeouts.setProperty("connectTimeout", "3")
            timeouts.setProperty("socketTimeout", "10")
            direct.setConnectionProperties(timeouts)
            val probe = JdbcTemplate(direct)
            probe.queryTimeout = 5
            verifyServer(probe)
            val config = HikariConfig()
            config.jdbcUrl = settings.url
            config.username = settings.username
            config.password = settings.password
            config.poolName = "owned-travel-collection-writer"
            config.maximumPoolSize = 4
            config.minimumIdle = 0
            config.connectionTimeout = 2000
            config.validationTimeout = 1000
            config.initializationFailTimeout = -1
            config.addDataSourceProperty("connectTimeout", "3")
            config.addDataSourceProperty("socketTimeout", "10")
            val owned = ObservedPool(config)
            pool = owned
            jdbc = JdbcTemplate(owned)
            jdbc.queryTimeout = 5
            manager = JdbcTransactionManager(owned)
            catalog = configuration.travelCollectionCatalog(owned, jdbc, manager, JSON)
            repository = configuration.travelCollectionJobRepository(owned, manager, catalog)
            launcher = TaskExecutorJobLauncher()
            launcher.setJobRepository(repository)
            launcher.setTaskExecutor(SyncTaskExecutor())
            launcher.afterPropertiesSet()
            clock = Clock.fixed(Instant.now().truncatedTo(ChronoUnit.SECONDS), ZoneOffset.UTC)
            val reads = TransactionTemplate(manager)
            reads.isReadOnly = true
            val repeatable = TransactionTemplate(manager, reads)
            repeatable.isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
            reader = PostgresqlPetTravel(jdbc, JSON, reads, repeatable)
            for (table in PRIVILEGES.keys) {
                val expected: Long =
                    if (table == "pet_travel_collection_state" || table == "pet_travel_pet_collection_state") 1 else 0
                check(count(table) == expected, "A new fixture must contain only the two original singleton checkpoints")
            }
            check(
                catalog.collectionState().phase == "HIDDEN" && catalog.collectionState().nextPage == 1 &&
                    catalog.petCollectionState().nextPage == 1 && catalog.petCollectionState().expectedTotal == null,
                "Original empty checkpoints are mandatory; a partially used fixture must not be automatically replayed",
            )
            released()
            nextContractAllowed = true
        }
    }

    @BeforeEach
    fun requireAllPreviousResourcesReturned(test: TestInfo) {
        val predecessorSucceeded = nextContractAllowed
        nextContractAllowed = false
        bodyCompleted = false
        observed("before contract") {
            check(
                predecessorSucceeded && !unsettledWorkers,
                "A previous contract or lifecycle check failed; further owned writes are fenced",
            )
            check(
                test.testMethod.orElseThrow().getAnnotation(Order::class.java).value == nextOrder,
                "Owned contracts must run only in their complete approved order",
            )
            released()
        }
    }

    @AfterEach
    fun requirePrivateMarkerAndAllResourcesPreserved() {
        nextContractAllowed = false
        observed("after contract") {
            check(!unsettledWorkers, "Unsettled owned scheduler work fences further database access and pool shutdown")
            released()
            check(
                settings.marker == jdbc.queryForObject(
                    "SELECT marker FROM public.pb_travel_collection_fixture",
                    String::class.java,
                ),
                "The private ownership marker must remain unchanged",
            )
            released()
            check(bodyCompleted, "The failed owned contract must remain frozen before any later write")
            nextOrder++
            nextContractAllowed = true
        }
    }

    @AfterAll
    fun closeOnlyTheTestOwnedPoolAndRetainSyntheticHistory() {
        try {
            observed("final writer shutdown") {
                val owned = pool
                if (owned != null) {
                    check(!unsettledWorkers, "Preserve the owned pool while worker or closer settlement is unconfirmed")
                    released()
                    owned.close()
                    check(owned.isClosed, "The explicit test-owned writer pool must close")
                    println(
                        "TRAVEL_COLLECTION_OWNED clientCalls=" + clientObservations.size +
                            " callbackWriterActive=" + clientObservations.map { it.active }.distinct().sorted() +
                            " callbackTransactions=" + clientObservations.map { it.transaction }.distinct() +
                            " peakWriterBorrows=" + owned.peak.get() + " rollbackCalls=" + owned.rollbacks.get() +
                            " fixtureHistoryRetained=true",
                    )
                }
            }
        } finally {
            // Unsettled workers may still log; retain suppression until Root handles the failed process.
            if (!unsettledWorkers) {
                previousLevels.forEach { (logger, level) -> logger.level = level }
            }
        }
    }

    @Test
    @Order(1)
    fun `잘못된 둘째 신원의 대량 행 둘이 주어졌을 때__한 페이지를 저장하면__첫 삽입을 롤백하고 체크포인트를 유지한다`() {
        contract("bulk page and checkpoint rollback") {
            val before = catalog.petCollectionState()
            val inserts = pool().petInserts.get()
            val rollbacks = pool().rollbacks.get()
            val page = TourApiClient.Page(
                listOf(
                    mapOf("contentid" to "998101", "acmpyNeedMtr" to "synthetic leash"),
                    mapOf("contentid" to "invalid-owned-identity"),
                ),
                2,
            )
            expect(
                IllegalArgumentException::class.java,
                "Invalid pet identity",
                Checked {
                    catalog.savePetPage(before, page, clock.instant(), clock.instant().plusSeconds(86400))
                },
            )
            check(
                pool().petInserts.get() == inserts + 1 && pool().rollbacks.get() == rollbacks + 1,
                "The first actual PostgreSQL INSERT must execute before the enclosing page transaction rolls back",
            )
            check(
                count("pet_travel_pet_details") == 0L && catalog.petCollectionState() == before,
                "Neither page data nor its singleton checkpoint may survive a failed page",
            )
        }
    }

    @Test
    @Order(2)
    fun `합성 지역과 동기화 행이 주어졌을 때__부분 작업 뒤 새 호출을 실행하면__예산을 저장하고 이어서 기존 리더를 제공한다`() {
        contract("actual Batch partial and catalog resume") {
            val client = syntheticClient(true, Checked { })
            val properties = properties(true)
            val observationsBefore = clientObservations.size
            val first = run(client, properties)
            requireExecution(first, BatchStatus.COMPLETED, "PARTIAL")
            val run = jdbc.queryForMap(
                "SELECT status,error_code,requests,discovered,published FROM pawbridge_animal.pet_travel_collection_runs",
            )
            check(
                run["status"] == "PARTIAL" && run["error_code"] == "VISIT_DETAIL_FAILED" &&
                    number(run, "requests") == 8 && number(run, "discovered") == 1 && number(run, "published") == 1,
                "An INTRO failure must retain the fixed partial result and exact charged request counts",
            )
            check(
                catalog.collectionState().phase == "HIDDEN" && catalog.collectionState().nextPage == 1 &&
                    catalog.collectionState().completedAt == clock.instant() &&
                    catalog.petCollectionState().nextPage == 1 &&
                    catalog.petCollectionState().completedAt == clock.instant(),
                "Completed discovery and bulk pages must retain their original next-cycle checkpoints",
            )
            check(
                reader.regions().items.size == 1 && reader.regions().items.first().code == AREA &&
                    reader.places(AREA, 0).totalElements == 1L &&
                    reader.places(AREA, 0).items.first().contentId == CONTENT,
                "The existing public reader must observe the collected region and type-filtered inventory",
            )
            val detail = reader.detail(CONTENT)
            check(
                detail.place!!.title == "Owned travel place" && detail.overview == "Owned overview" &&
                    detail.conditions!!.requirements == "synthetic leash" && detail.petInformationAvailable &&
                    detail.petInformationStatus == "READY" && detail.visitInformation!!.status == "PARTIAL" &&
                    detail.visitInformation!!.additionalItems.size == 1 && detail.images.size == 1 &&
                    detail.imagesStatus == "READY",
                "Independent bulk, common, INFO and licensed image snapshots must remain readable after an INTRO failure",
            )
            check(clientObservations.size - observationsBefore == 8, "Only the eight bounded synthetic operations may run")
            val second = run(client, properties)
            requireExecution(second, BatchStatus.COMPLETED, "COMPLETED")
            check(
                first.jobInstance.id != second.jobInstance.id &&
                    count("batch_job_instance") == 2L && count("pet_travel_collection_runs") == 2L,
                "A new requestId creates a new Batch instance; it is not a Batch restart of the partial catalog run",
            )
            check(
                jdbc.queryForObject(
                    "SELECT count(*) FROM pawbridge_animal.pet_travel_collection_runs WHERE status='COMPLETED' AND requests=4 AND discovered=1 AND published=0",
                    Long::class.javaObjectType,
                ) == 1L,
                "The fresh invocation must skip fresh bulk/common/INFO/images and resume only the failed INTRO work",
            )
            val budgets: MutableMap<String, Int> = LinkedHashMap()
            jdbc.query(
                "SELECT operation,used FROM pawbridge_animal.pet_travel_request_budgets ORDER BY operation",
                RowCallbackHandler { row ->
                    budgets[row.getString(1)] = row.getInt(2)
                },
            )
            check(
                budgets == mapOf(
                    "PET_BULK" to 1,
                    "REGIONS" to 2,
                    "SYNC" to 4,
                    "COMMON" to 1,
                    "INTRO" to 2,
                    "INFO" to 1,
                    "IMAGES" to 1,
                ),
                "The Seoul-day operation budgets must charge the failed attempt and avoid successful-resource refetches",
            )
            check(
                jdbc.queryForObject(
                    "SELECT count(*) FROM pawbridge_animal.pet_travel_request_budgets WHERE request_day=?",
                    Long::class.javaObjectType,
                    java.sql.Date.valueOf(java.time.LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Seoul")))),
                ) == 7L,
                "Every budget row must use the collector clock's Seoul calendar day",
            )
            check(
                reader.detail(CONTENT).visitInformation!!.status == "READY" && !catalog.hasPendingVisitDetails(),
                "A successful INTRO retry must complete the existing reader's visit snapshot",
            )
        }
    }

    @Test
    @Order(3)
    fun `PostgreSQL 물리 세션 둘이 주어졌을 때__잠금 소유자가 롤백하고 수집기가 바쁘면__물리 종료까지 세션 잠금을 유지한다`() {
        contract("two physical lock sessions and busy collector") {
            val before = fingerprint()
            val client = noCallsClient()
            direct.connection.use { first ->
                direct.connection.use { second ->
                    check(pid(first) != pid(second), "Lock contenders must be different actual PostgreSQL backend PIDs")
                    check(
                        lock(first, "pg_try_advisory_lock"),
                        "The first physical session must acquire the original namespace and key2",
                    )
                    first.autoCommit = false
                    pid(first)
                    first.rollback()
                    check(
                        owns(first) && !lock(second, "pg_try_advisory_lock"),
                        "Rollback must retain the session lock and the other physical session must remain busy",
                    )
                    first.autoCommit = true
                    expect(
                        IllegalStateException::class.java,
                        "TRAVEL_COLLECTION_BUSY",
                        Checked { collector(client, properties(false)).collect() },
                    )
                    verify { client wasNot Called }
                    check(
                        fingerprint() == before,
                        "A busy collector must not start a catalog run, charge budget or write Batch metadata",
                    )
                    first.close()
                    second.prepareStatement("SELECT pg_advisory_lock(?,?)").use { statement ->
                        statement.queryTimeout = 3
                        statement.setInt(1, LOCK_NAMESPACE)
                        statement.setInt(2, LOCK_KEY)
                        statement.executeQuery().use { row ->
                            check(row.next(), "Physical owner close must release the lock to the waiting session")
                        }
                    }
                    check(
                        owns(second) && lock(second, "pg_advisory_unlock"),
                        "The second session must actually own and explicitly release the transferred lock",
                    )
                }
            }
        }
    }

    @Test
    @Order(4)
    fun `진행 중 스케줄러와 제공자 실패가 주어졌을 때__종료와 실패가 끝나면__기다리고 쓰기 연결과 세션 잠금을 해제한다`() {
        contract("normal scheduler shutdown and failed run release") {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val workDone = CountDownLatch(1)
            val closeStarted = CountDownLatch(1)
            val closeDone = CountDownLatch(1)
            val failure = AtomicReference<Throwable>()
            val client = syntheticClient(
                false,
                Checked {
                    entered.countDown()
                    check(release.await(8, TimeUnit.SECONDS), "Synthetic provider must be released within its test bound")
                },
            )
            val job: Job = configuration.petTravelCollectionJob(repository, manager, collector(client, properties(false)))
            val schedule = AnimalTravelCollectionSchedule(repository, job)
            val scheduler: ThreadPoolTaskScheduler = AnimalTravelCollectionSchedule.tourApiCollectionScheduler()
            scheduler.initialize()
            val closing = Thread(
                Runnable {
                    closeStarted.countDown()
                    try {
                        scheduler.shutdown()
                    } catch (problem: Throwable) {
                        failure.compareAndSet(null, problem)
                    } finally {
                        closeDone.countDown()
                    }
                },
                "owned-travel-shutdown-observer",
            )
            closing.isDaemon = true
            var submitted = false
            unsettledWorkers = true
            try {
                scheduler.execute(
                    Runnable {
                        try {
                            schedule.collect()
                        } catch (problem: Throwable) {
                            failure.set(problem)
                        } finally {
                            workDone.countDown()
                        }
                    },
                )
                submitted = true
                check(
                    entered.await(8, TimeUnit.SECONDS),
                    "The actual schedule method must reach its synthetic provider callback",
                )
                closing.start()
                check(closeStarted.await(2, TimeUnit.SECONDS), "The scheduler close observer must start")
                check(
                    awaitShutdown(scheduler, closeDone),
                    "The scheduler must actually reach shutdown within the observation bound",
                )
                check(
                    !closeDone.await(150, TimeUnit.MILLISECONDS) &&
                        pool().hikariPoolMXBean.activeConnections == 1,
                    "Normal named-scheduler shutdown must wait while the collector retains only its lock connection",
                )
            } finally {
                release.countDown()
                if (!submitted) {
                    workDone.countDown()
                }
                if (closing.state == Thread.State.NEW) {
                    closing.start()
                }
                val workSettled = awaitWork(workDone, 6)
                val closerSettled = awaitCloser(closing, 6)
                unsettledWorkers = !(
                    workSettled && closerSettled && closeDone.count == 0L &&
                        scheduler.scheduledExecutor.isTerminated
                    )
                check(
                    !unsettledWorkers,
                    "Worker and closer must each settle within their own bound; preserve pool and fence later writes otherwise",
                )
            }
            check(failure.get() == null, "The released bounded collection and closer must finish without an execution failure")
            released()
            requireLockAvailable()
            val hiddenDetail = "synthetic-provider-detail-must-not-persist"
            val unavailable = mockk<TourApiClient>()
            every { unavailable.fetch(TourApiClient.Operation.REGIONS, "") } answers {
                observeClient("REGIONS_FAILURE")
                throw IllegalStateException(hiddenDetail)
            }
            val failed = run(unavailable, properties(false))
            requireExecution(failed, BatchStatus.FAILED, "FAILED")
            check(
                jdbc.queryForObject(
                    "SELECT count(*) FROM pawbridge_animal.pet_travel_collection_runs WHERE status='FAILED' AND error_code='COLLECTION_FAILED' AND requests=1",
                    Long::class.javaObjectType,
                ) == 1L,
                "A failed upstream attempt must consume one request and persist only the fixed catalog failure",
            )
            check(
                jdbc.queryForObject(
                    "SELECT count(*) FROM pawbridge_animal.batch_step_execution WHERE exit_message LIKE ?",
                    Long::class.javaObjectType,
                    "%" + hiddenDetail + "%",
                ) == 0L,
                "Upstream exception detail must not be retained in Batch metadata",
            )
            released()
            requireLockAvailable()
        }
    }

    @Test
    @Order(5)
    fun `소진된 예산과 소유 RUNNING 고아가 주어졌을 때__다시 호출하면__쿼터를 구분하고 자동 고아 복구를 거절한다`() {
        contract("quota distinction and retained RUNNING orphan") {
            val client = noCallsClient()
            val properties = properties(false)
            properties.dailyRequestLimit = 1
            val budgets = budgetFingerprint()
            val quota = run(client, properties)
            requireExecution(quota, BatchStatus.COMPLETED, "QUOTA")
            verify { client wasNot Called }
            check(
                budgetFingerprint() == budgets &&
                    jdbc.queryForObject(
                        "SELECT count(*) FROM pawbridge_animal.pet_travel_collection_runs WHERE status='QUOTA' AND error_code='REQUEST_BUDGET' AND requests=0",
                        Long::class.javaObjectType,
                    ) == 1L,
                "An exhausted operation must return catalog QUOTA without a provider call or another budget increment",
            )
            check(
                reader.detail(CONTENT).conditions!!.requirements == "synthetic leash" &&
                    reader.places(AREA, 0).totalElements == 1L,
                "Quota must preserve the previously published detail and inventory",
            )
            val orphan = TestFaker.uuid().toString()
            catalog.startRun(orphan, clock.instant())
            val runs = count("pet_travel_collection_runs")
            expect(
                IllegalStateException::class.java,
                "TRAVEL_ORPHAN_RUN",
                Checked { collector(client, properties).collect() },
            )
            verify { client wasNot Called }
            check(
                count("pet_travel_collection_runs") == runs && budgetFingerprint() == budgets &&
                    jdbc.queryForObject(
                        "SELECT count(*) FROM pawbridge_animal.pet_travel_collection_runs WHERE id=? AND status='RUNNING' AND finished_at IS NULL",
                        Long::class.javaObjectType,
                        orphan,
                    ) == 1L,
                "An existing owned RUNNING row must be refused and retained without deletion, completion or automatic recovery",
            )
            released()
            requireLockAvailable()
        }
    }

    private fun properties(bulk: Boolean): TourApiProperties {
        val value = TourApiProperties()
        value.isEnabled = true
        value.isBulkPetEnabled = bulk
        value.maxPagesPerRun = 2
        value.maxBulkPagesPerRun = 1
        value.maxDetailsPerRun = 1
        value.maxVisitDetailsPerRun = 1
        return value
    }

    private fun contract(phase: String, action: Checked) {
        observed(phase) {
            action.run()
            bodyCompleted = true
        }
    }

    private fun collector(client: TourApiClient, properties: TourApiProperties): PetTravelCollector {
        return configuration.petTravelCollector(pool(), catalog, client, properties, clock)
    }

    private fun run(client: TourApiClient, properties: TourApiProperties): JobExecution {
        return launcher.run(
            configuration.petTravelCollectionJob(repository, manager, collector(client, properties)),
            JobParametersBuilder().addString("requestId", TestFaker.uuid().toString()).toJobParameters(),
        )
    }

    private fun requireExecution(execution: JobExecution, status: BatchStatus, exit: String) {
        check(
            execution.status == status && execution.exitStatus.exitCode == exit &&
                execution.stepExecutions.size == 1 &&
                execution.stepExecutions.iterator().next().status == status &&
                execution.stepExecutions.iterator().next().exitStatus.exitCode == exit,
            "BatchStatus and the tasklet's catalog ExitStatus must be checked separately",
        )
        check(
            jdbc.queryForObject(
                "SELECT count(*) FROM pawbridge_animal.batch_job_execution WHERE job_execution_id=? AND status=? AND exit_code=?",
                Long::class.javaObjectType,
                execution.id,
                status.name,
                exit,
            ) == 1L,
            "The actual qualified PostgreSQL JobRepository must persist both status values",
        )
        released()
    }

    private fun noCallsClient(): TourApiClient = mockk<TourApiClient>()

    private fun syntheticClient(failFirstIntro: Boolean, beforeRegions: Checked): TourApiClient {
        val client = mockk<TourApiClient>()
        val intros = AtomicInteger()
        every { client.fetch(TourApiClient.Operation.REGIONS, "") } answers {
            observeClient("REGIONS")
            beforeRegions.run()
            listOf(mapOf("code" to AREA, "name" to "Owned region"))
        }
        every { client.fetchPage(TourApiClient.Operation.PET_BULK, "", 1) } answers {
            observeClient("PET_BULK")
            TourApiClient.Page(
                listOf(mapOf("contentid" to CONTENT, "acmpyNeedMtr" to "synthetic leash", "acmpyTypeCd" to "DOGS")),
                1,
            )
        }
        every { client.fetchPage(TourApiClient.Operation.SYNC, "0", 1) } answers {
            observeClient("SYNC_HIDDEN")
            TourApiClient.Page(listOf(), 0)
        }
        every { client.fetchPage(TourApiClient.Operation.SYNC, "1", 1) } answers {
            observeClient("SYNC_SHOWN")
            TourApiClient.Page(
                listOf(
                    mapOf(
                        "contentid" to CONTENT,
                        "contenttypeid" to "12",
                        "lDongRegnCd" to AREA,
                        "modifiedtime" to REVISION,
                        "showflag" to "1",
                        "title" to "Owned travel place",
                        "addr1" to "Owned address",
                        "firstimage" to "https://tong.visitkorea.or.kr/cms/resource/77/OWNED.jpg",
                        "cpyrhtDivCd" to "Type1",
                    ),
                ),
                1,
            )
        }
        every { client.fetch(TourApiClient.Operation.COMMON, CONTENT) } answers {
            observeClient("COMMON")
            listOf(
                mapOf(
                    "contentid" to CONTENT,
                    "title" to "Owned travel place",
                    "overview" to "Owned overview",
                    "contenttypeid" to "12",
                ),
            )
        }
        every { client.fetchDetail(TourApiClient.Operation.INTRO, CONTENT, "12") } answers {
            observeClient("INTRO")
            if (failFirstIntro && intros.getAndIncrement() == 0) {
                throw PetTravelException.unavailable()
            }
            listOf(
                mapOf(
                    "contentid" to CONTENT,
                    "contenttypeid" to "12",
                    "infocenter" to "Owned center",
                    "usetime" to "Owned hours",
                ),
            )
        }
        every { client.fetchDetail(TourApiClient.Operation.INFO, CONTENT, "12") } answers {
            observeClient("INFO")
            listOf(
                mapOf(
                    "contentid" to CONTENT,
                    "contenttypeid" to "12",
                    "infoname" to "Owned information",
                    "infotext" to "Owned information text",
                ),
            )
        }
        every { client.fetchDetail(TourApiClient.Operation.IMAGES, CONTENT, "12") } answers {
            observeClient("IMAGES")
            listOf(
                mapOf(
                    "contentid" to CONTENT,
                    "serialnum" to "1",
                    "imgname" to "Owned image",
                    "originimgurl" to "https://tong.visitkorea.or.kr/cms/resource/77/OWNED.jpg",
                    "cpyrhtDivCd" to "Type1",
                ),
            )
        }
        return client
    }

    private fun observeClient(operation: String) {
        val transaction = TransactionSynchronizationManager.isActualTransactionActive()
        val active = pool().hikariPoolMXBean.activeConnections
        val owner = pool().lastLockPid.get()
        clientObservations.add(ClientObservation(operation, owner, active, transaction))
        check(
            !transaction && active == 1 &&
                pool().connections.get() == 1 && owner > 0,
            "The actual provider seam must have no writer transaction or extra borrowed connection; only the raw session lock may remain borrowed",
        )
        val ownerConnection = pool().lockConnection.get()
        check(
            ownerConnection != null && ownerConnection.autoCommit && pid(ownerConnection) == owner && owns(ownerConnection),
            "Metadata inspected on the actual retained connection must confirm the same PID still owns namespace/key2 outside a transaction",
        )
    }

    private fun released() {
        val owned = pool
        check(
            !TransactionSynchronizationManager.isActualTransactionActive() &&
                (owned == null || !TransactionSynchronizationManager.hasResource(owned)),
            "No writer transaction or resource binding may escape a phase",
        )
        if (owned != null) {
            check(
                owned.connections.get() == 0 && owned.statements.get() == 0 && owned.results.get() == 0 &&
                    owned.hikariPoolMXBean.activeConnections == 0 && owned.hikariPoolMXBean.threadsAwaitingConnection == 0,
                "All actual writer connections, statements and result cursors must return without a waiting borrower",
            )
        }
    }

    private fun requireLockAvailable() {
        direct.connection.use { connection ->
            check(
                lock(connection, "pg_try_advisory_lock") && lock(connection, "pg_advisory_unlock"),
                "A fresh physical session must acquire and release the original collector lock after every finish/failure",
            )
        }
    }

    private fun count(table: String): Long {
        check(PRIVILEGES.containsKey(table), "Only the exact owned fixture tables may be counted")
        return jdbc.queryForObject("SELECT count(*) FROM pawbridge_animal." + table, Long::class.javaObjectType)!!
    }

    private fun fingerprint(): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (table in PRIVILEGES.keys) {
            result[table] = jdbc.queryForObject(
                "SELECT md5(COALESCE(string_agg(value,',' ORDER BY value),'')) FROM (SELECT row_to_json(t)::text AS value FROM pawbridge_animal." + table + " t) rows",
                String::class.java,
            )!!
        }
        return result
    }

    private fun budgetFingerprint(): Map<String, String> {
        return mapOf(
            "budgets" to jdbc.queryForObject(
                "SELECT md5(COALESCE(string_agg(row_to_json(t)::text,',' ORDER BY request_day,operation),'')) FROM pawbridge_animal.pet_travel_request_budgets t",
                String::class.java,
            )!!,
        )
    }

    private fun verifyServer(probe: JdbcTemplate) {
        val identity = probe.queryForMap(
            "SELECT current_database() AS db,current_user AS actor,session_user AS session,current_setting('server_version_num') AS version,(SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()) AS owner",
        )
        check(
            settings.database == identity["db"] && settings.username == identity["actor"] &&
                settings.username == identity["session"] && settings.owner() == identity["owner"] &&
                "170011" == identity["version"],
            "Only the new owned PostgreSQL17.11 database, dedicated NOLOGIN owner and dedicated login writer are accepted",
        )
        check(
            settings.marker == probe.queryForObject("SELECT marker FROM public.pb_travel_collection_fixture", String::class.java),
            "The single private ownership marker must match",
        )
        check(
            true == probe.queryForObject(
                "SELECT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls AND NOT rolinherit AND NOT EXISTS(SELECT 1 FROM pg_auth_members m WHERE m.member=pg_roles.oid) FROM pg_roles WHERE rolname=current_user",
                Boolean::class.javaObjectType,
            ),
            "The writer must have no administrator or inherited role membership",
        )
        check(
            true == probe.queryForObject(
                "SELECT NOT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls AND NOT rolinherit FROM pg_roles WHERE rolname=?",
                Boolean::class.javaObjectType,
                settings.owner(),
            ) &&
                false == probe.queryForObject(
                    "SELECT pg_has_role(current_user,?,'MEMBER')",
                    Boolean::class.javaObjectType,
                    settings.owner(),
                ),
            "The separate fixture owner must remain NOLOGIN and inaccessible to the writer",
        )
        check(
            false == probe.queryForObject(
                "SELECT has_database_privilege(current_user,current_database(),'CREATE,TEMP')",
                Boolean::class.javaObjectType,
            ),
            "The writer must have no database or temporary-object DDL privilege",
        )
        for (schema in listOf("public", "pawbridge_animal")) {
            check(
                true == probe.queryForObject(
                    "SELECT has_schema_privilege(current_user,?,'USAGE') AND NOT has_schema_privilege(current_user,?,'CREATE')",
                    Boolean::class.javaObjectType,
                    schema,
                    schema,
                ),
                "The writer must have schema USAGE without CREATE",
            )
        }
        val tables = probe.query(
            "SELECT tablename FROM pg_tables WHERE schemaname='pawbridge_animal' ORDER BY tablename",
            RowMapper { r, _ -> r.getString(1) },
        )
        check(
            tables == PRIVILEGES.keys.sorted(),
            "Only ten Travel and six original Batch tables may exist in the application schema",
        )
        check(
            probe.queryForObject(
                "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname !~ '^pg_' AND n.nspname<>'information_schema' AND c.relkind IN('r','p','v','m','f')",
                Long::class.javaObjectType,
            ) == 17L,
            "Exactly the sixteen application tables and one marker may exist; P14 source tables must not be copied",
        )
        for (table in PRIVILEGES.entries) {
            for (permission in listOf("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER")) {
                check(
                    table.value.contains(permission) == probe.queryForObject(
                        "SELECT has_table_privilege(current_user,?,?)",
                        Boolean::class.javaObjectType,
                        "pawbridge_animal." + table.key,
                        permission,
                    ),
                    "Every runtime table privilege must exactly match the required collection/Batch SQL",
                )
            }
        }
        check(
            true == probe.queryForObject(
                "SELECT has_table_privilege(current_user,'public.pb_travel_collection_fixture','SELECT') AND NOT has_table_privilege(current_user,'public.pb_travel_collection_fixture','INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')",
                Boolean::class.javaObjectType,
            ),
            "The ownership marker must remain SELECT-only",
        )
        val sequences = probe.query(
            "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='pawbridge_animal' AND c.relkind='S' ORDER BY c.relname",
            RowMapper { r, _ -> r.getString(1) },
        )
        check(
            sequences == listOf("batch_job_execution_seq", "batch_job_seq", "batch_step_execution_seq"),
            "Only the three official PostgreSQL Batch sequences may exist",
        )
        for (sequence in sequences) {
            check(
                true == probe.queryForObject(
                    "SELECT has_sequence_privilege(current_user,?,'USAGE') AND NOT has_sequence_privilege(current_user,?,'SELECT,UPDATE')",
                    Boolean::class.javaObjectType,
                    "pawbridge_animal." + sequence,
                    "pawbridge_animal." + sequence,
                ),
                "Batch sequences need nextval USAGE without direct SELECT or UPDATE privilege",
            )
        }
        check(
            probe.queryForObject(
                "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN('public','pawbridge_animal') AND c.relkind IN('r','S') AND pg_get_userbyid(c.relowner)<>?",
                Long::class.javaObjectType,
                settings.owner(),
            ) == 0L,
            "Every new fixture table and sequence must belong to the separate NOLOGIN owner",
        )
    }

    private fun quietLogs() {
        for (name in listOf("com.zaxxer.hikari", "org.springframework.jdbc", "org.springframework.batch")) {
            val logger = LoggerFactory.getLogger(name) as ch.qos.logback.classic.Logger
            previousLevels[logger] = logger.level
            logger.level = ch.qos.logback.classic.Level.OFF
        }
    }

    /** Observes actual JDBC resources and PIDs without retaining SQL, parameters or payload. */
    private class ObservedPool(configuration: HikariConfig) : HikariDataSource(configuration) {
        val connections = AtomicInteger()
        val statements = AtomicInteger()
        val results = AtomicInteger()
        val peak = AtomicInteger()
        val rollbacks = AtomicInteger()
        val petInserts = AtomicInteger()
        val lastLockPid = AtomicInteger()
        val lockConnection = AtomicReference<Connection>()

        override fun getConnection(): Connection {
            val actual = super.getConnection()
            val backend: Int
            try {
                backend = pid(actual)
            } catch (failure: Throwable) {
                actual.close()
                throw failure
            }
            val active = connections.incrementAndGet()
            peak.accumulateAndGet(active) { a, b -> Math.max(a, b) }
            val closed = AtomicBoolean()
            val children: MutableList<TrackedStatement> = CopyOnWriteArrayList()
            return Proxy.newProxyInstance(
                Connection::class.java.classLoader,
                arrayOf<Class<*>>(Connection::class.java),
                InvocationHandler { _, method, args ->
                    if (method.name == "close") {
                        if (closed.compareAndSet(false, true)) {
                            try {
                                call(actual, method, args)
                            } finally {
                                children.forEach { it.closed() }
                                connections.decrementAndGet()
                            }
                        }
                        return@InvocationHandler null
                    }
                    if (method.name == "rollback") {
                        rollbacks.incrementAndGet()
                    }
                    val result = call(actual, method, args)
                    if (result is Statement) {
                        val sql = if (args != null && args.size > 0 && args[0] is String) args[0] as String else ""
                        val tracked = TrackedStatement(result, sql, backend, actual)
                        children.add(tracked)
                        return@InvocationHandler tracked.proxy()
                    }
                    result
                },
            ) as Connection
        }

        private inner class TrackedStatement(
            val delegate: Statement,
            val sql: String,
            val backend: Int,
            val connection: Connection,
        ) {
            val isClosed = AtomicBoolean()
            val cursors: MutableList<AtomicBoolean> = CopyOnWriteArrayList()

            init {
                statements.incrementAndGet()
            }

            fun proxy(): Any {
                return Proxy.newProxyInstance(
                    Statement::class.java.classLoader,
                    arrayOf<Class<*>>(if (delegate is PreparedStatement) PreparedStatement::class.java else Statement::class.java),
                    InvocationHandler { _, method, args ->
                        if (method.name == "close") {
                            try {
                                return@InvocationHandler call(delegate, method, args)
                            } finally {
                                closed()
                            }
                        }
                        if (method.name.startsWith("execute")) {
                            if (sql.contains("pg_try_advisory_lock")) {
                                lastLockPid.set(backend)
                                lockConnection.set(connection)
                            }
                            if (sql.startsWith("INSERT INTO pawbridge_animal.pet_travel_pet_details")) {
                                petInserts.incrementAndGet()
                                check(
                                    !connection.autoCommit && TransactionSynchronizationManager.isActualTransactionActive(),
                                    "Bulk page SQL must execute inside its real narrow writer transaction",
                                )
                            }
                        }
                        val value = call(delegate, method, args)
                        if (value !is ResultSet) {
                            return@InvocationHandler value
                        }
                        val cursorClosed = AtomicBoolean()
                        cursors.add(cursorClosed)
                        results.incrementAndGet()
                        Proxy.newProxyInstance(
                            ResultSet::class.java.classLoader,
                            arrayOf<Class<*>>(ResultSet::class.java),
                            InvocationHandler { _, cursorMethod, cursorArgs ->
                                if (cursorMethod.name == "close") {
                                    try {
                                        return@InvocationHandler call(value, cursorMethod, cursorArgs)
                                    } finally {
                                        closeCursor(cursorClosed)
                                    }
                                }
                                call(value, cursorMethod, cursorArgs)
                            },
                        )
                    },
                )
            }

            fun closed() {
                if (isClosed.compareAndSet(false, true)) {
                    cursors.forEach { closeCursor(it) }
                    statements.decrementAndGet()
                }
            }

            fun closeCursor(cursor: AtomicBoolean) {
                if (cursor.compareAndSet(false, true)) {
                    results.decrementAndGet()
                }
            }
        }
    }

    private fun interface Checked {
        fun run()
    }

    private data class ClientObservation(val operation: String, val lockPid: Int, val active: Int, val transaction: Boolean)

    private class Settings(
        val url: String,
        val database: String,
        val username: String,
        val password: String,
        val marker: String,
    ) {
        fun owner(): String = database.replace("pb_travel_collection_owned_", "pb_travel_collection_owner_")

        override fun toString(): String = "OwnedTravelCollectionSettings[redacted]"

        companion object {
            fun load(): Settings {
                val value = System.getenv("P17_PG_SETTINGS")
                check(value != null && value.isNotBlank(), "P17_PG_SETTINGS is mandatory and never causes a skip")
                val file = Path.of(value)
                val expected = Path.of(
                    "/private/tmp/pawbridge-owned-pg-night-20261020/travel-collection-recovery-1/travel-collection.properties",
                )
                check(
                    file.isAbsolute && file.normalize() == file && file == expected &&
                        Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file),
                    "Only the exact new owned travel settings path is accepted",
                )
                var parent: Path? = file.parent
                while (parent != null) {
                    check(!Files.isSymbolicLink(parent), "Settings ancestors may not be symbolic links")
                    parent = parent.parent
                }
                check(
                    Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS) ==
                        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE) &&
                        (Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toInt() == 1 &&
                        System.getProperty("user.name") == Files.getOwner(file).name &&
                        Files.getOwner(file) == Files.getOwner(file.parent),
                    "Settings must be single-link0600 under their current owner",
                )
                check(
                    Files.getPosixFilePermissions(file.parent, LinkOption.NOFOLLOW_LINKS) ==
                        setOf(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE,
                        ),
                    "New settings directory must retain exact0700 permissions",
                )
                val values = Properties()
                Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { input: InputStream -> values.load(input) }
                check(
                    values.stringPropertyNames() == setOf("jdbcUrl", "username", "password", "database", "marker"),
                    "Exactly five private settings fields are required",
                )
                val database = required(values, "database")
                val url = required(values, "jdbcUrl")
                val username = required(values, "username")
                val marker = required(values, "marker")
                check(
                    database.matches(Regex("pb_travel_collection_owned_[0-9a-f]{12}")) &&
                        url == "jdbc:postgresql://127.0.0.1:52620/" + database &&
                        username == database.replace("pb_travel_collection_owned_", "pb_travel_collection_writer_") &&
                        marker.matches(Regex("[0-9a-f]{32}")),
                    "Only one new isolated loopback database and its dedicated writer/marker are accepted",
                )
                return Settings(url, database, username, required(values, "password"), marker)
            }

            private fun required(values: Properties, key: String): String {
                val value = values.getProperty(key)
                check(value != null && value.isNotBlank(), "Every private owned setting is required")
                return value
            }
        }
    }

    companion object {
        private val JSON: ObjectMapper = ObjectMapper().findAndRegisterModules()
        private const val PROVIDER = "KOREA_TOURISM_ORGANIZATION"
        private const val CONTENT = "998201"
        private const val AREA = "77"
        private const val REVISION = "20261021010101"
        private const val LOCK_NAMESPACE = 0x50415742
        private const val LOCK_KEY = 2
        private val PRIVILEGES: Map<String, Set<String>> = privileges()

        private fun awaitShutdown(scheduler: ThreadPoolTaskScheduler, closeDone: CountDownLatch): Boolean {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!scheduler.scheduledExecutor.isShutdown) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) {
                    return false
                }
                if (closeDone.await(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(10)), TimeUnit.NANOSECONDS)) {
                    return scheduler.scheduledExecutor.isShutdown
                }
            }
            return true
        }

        private fun awaitWork(done: CountDownLatch, seconds: Int): Boolean {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds.toLong())
            var interrupted = false
            try {
                while (done.count != 0L) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) {
                        return false
                    }
                    try {
                        if (done.await(remaining, TimeUnit.NANOSECONDS)) {
                            return true
                        }
                    } catch (failure: InterruptedException) {
                        interrupted = true
                    }
                }
                return true
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        private fun awaitCloser(closer: Thread, seconds: Int): Boolean {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds.toLong())
            var interrupted = false
            try {
                while (closer.isAlive) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) {
                        return false
                    }
                    try {
                        TimeUnit.NANOSECONDS.timedJoin(closer, remaining)
                    } catch (failure: InterruptedException) {
                        interrupted = true
                    }
                }
                return closer.state == Thread.State.TERMINATED
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        private fun pid(connection: Connection): Int {
            connection.prepareStatement("SELECT pg_backend_pid()").use { statement ->
                statement.queryTimeout = 3
                statement.executeQuery().use { row ->
                    check(row.next(), "The actual PostgreSQL PID is required")
                    return row.getInt(1)
                }
            }
        }

        private fun lock(connection: Connection, function: String): Boolean {
            check(
                setOf("pg_try_advisory_lock", "pg_advisory_unlock").contains(function),
                "Only the fixed advisory lock operations are permitted",
            )
            connection.prepareStatement("SELECT " + function + "(?,?)").use { statement ->
                statement.queryTimeout = 3
                statement.setInt(1, LOCK_NAMESPACE)
                statement.setInt(2, LOCK_KEY)
                statement.executeQuery().use { row ->
                    check(row.next(), "The advisory result is required")
                    return row.getBoolean(1)
                }
            }
        }

        private fun owns(connection: Connection): Boolean {
            connection.prepareStatement(
                "SELECT EXISTS(SELECT 1 FROM pg_locks WHERE locktype='advisory' AND pid=pg_backend_pid() AND database=(SELECT oid FROM pg_database WHERE datname=current_database()) AND classid=? AND objid=? AND objsubid=2 AND granted)",
            ).use { statement ->
                statement.queryTimeout = 3
                statement.setInt(1, LOCK_NAMESPACE)
                statement.setInt(2, LOCK_KEY)
                statement.executeQuery().use { row ->
                    check(row.next(), "Actual session ownership is required")
                    return row.getBoolean(1)
                }
            }
        }

        private fun number(row: Map<String, Any?>, key: String): Int {
            return (row[key] as Number).toInt()
        }

        private fun privileges(): Map<String, Set<String>> {
            val values = LinkedHashMap<String, Set<String>>()
            for (table in listOf("pet_travel_collection_state", "pet_travel_pet_collection_state")) {
                values[table] = setOf("SELECT", "UPDATE")
            }
            for (table in listOf(
                "pet_travel_regions",
                "pet_travel_collection_runs",
                "pet_travel_request_budgets",
                "pet_travel_targets",
                "pet_travel_places",
                "pet_travel_pet_details",
                "pet_travel_visit_details",
            )) {
                values[table] = setOf("SELECT", "INSERT", "UPDATE")
            }
            values["pet_travel_images"] = setOf("SELECT", "INSERT", "DELETE")
            for (table in listOf("batch_job_instance", "batch_job_execution_params")) {
                values[table] = setOf("SELECT", "INSERT")
            }
            for (table in listOf(
                "batch_job_execution",
                "batch_step_execution",
                "batch_job_execution_context",
                "batch_step_execution_context",
            )) {
                values[table] = setOf("SELECT", "INSERT", "UPDATE")
            }
            return values.toMap()
        }

        private fun call(target: Any, method: Method, args: Array<Any?>?): Any? {
            try {
                return method.invoke(target, *(args ?: arrayOf()))
            } catch (failure: InvocationTargetException) {
                throw failure.cause!!
            }
        }

        private fun observed(phase: String, action: Checked) {
            try {
                action.run()
            } catch (failure: AssertionError) {
                throw failure
            } catch (failure: Throwable) {
                throw AssertionError(
                    "Owned travel collection observation failed: " + phase + "; type=" + failure.javaClass.name,
                )
            }
        }

        private fun expect(type: Class<out Throwable>, message: String, action: Checked) {
            try {
                action.run()
            } catch (failure: Throwable) {
                check(
                    type.isInstance(failure) && message == failure.message,
                    "The exact fixed owned failure contract is required",
                )
                return
            }
            throw AssertionError("Expected owned synthetic failure was absent")
        }

        private fun check(value: Boolean, fixed: String) {
            if (!value) {
                throw AssertionError(fixed)
            }
        }
    }
}
